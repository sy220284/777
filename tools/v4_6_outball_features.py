from __future__ import annotations

import csv
import importlib.util
import io
import json
import urllib.request
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
OUTBALL_SOURCE = "https://raw.githubusercontent.com/fentouxungui/Double-Color-Ball-Data/main/lottery_data.csv"
START = 750
MODEL_START = 500
BLOCK = 250
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}

# 2026092—2026105由多个公开出球顺序页面交叉核对后冻结为研究补丁；
# 每期仍必须与仓库排序红球逐期集合一致，否则实验立即失败。
RECENT_ORDER = {
    2026092: [33, 11, 12, 9, 30, 25],
    2026093: [15, 24, 8, 5, 20, 21],
    2026094: [17, 24, 6, 15, 25, 13],
    2026095: [6, 14, 33, 21, 22, 4],
    2026096: [4, 22, 16, 31, 26, 1],
    2026097: [24, 30, 5, 26, 29, 16],
    2026098: [8, 22, 18, 25, 16, 26],
    2026099: [1, 30, 14, 12, 18, 31],
    2026100: [31, 9, 13, 22, 3, 4],
    2026101: [24, 9, 5, 8, 25, 6],
    2026102: [16, 10, 4, 13, 25, 3],
    2026103: [11, 4, 20, 30, 27, 28],
    2026104: [11, 19, 13, 20, 12, 31],
    2026105: [30, 15, 4, 2, 14, 13],
}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load("v46order_v4", ROOT / "models" / "v4" / "predictor.py")
history_tool = _load("v46order_history", ROOT / "tools" / "load_history.py")


def load_dataframe() -> pd.DataFrame:
    rows = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    records = [{
        "seq": int(r["seq"]),
        **{f"red{i}": int(r["red"][i - 1]) for i in range(1, 7)},
        "blue": int(r["blue"]),
    } for r in rows]
    df = pd.DataFrame(records)
    if int(df.iloc[-1]["seq"]) != 3502:
        raise RuntimeError(f"仓库历史边界异常: {int(df.iloc[-1]['seq'])}")
    return pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)


def _fetch_legacy_orders() -> list[dict]:
    req = urllib.request.Request(OUTBALL_SOURCE, headers={"User-Agent": "777-v46-outball/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        text = response.read().decode("utf-8-sig")
    rows = []
    for row in csv.DictReader(io.StringIO(text)):
        issue = int(row["期号"])
        if issue > 2026091:
            continue
        order = [int(x) for x in row["出球顺序"].split()]
        reds = sorted(int(x) for x in row["排序红球"].split())
        blue = int(row["排序蓝球"])
        if len(order) != 6 or sorted(order) != reds:
            raise RuntimeError(f"外部出球顺序格式异常: issue={issue}")
        rows.append({"issue": issue, "order": order, "red": reds, "blue": blue})
    rows.sort(key=lambda r: r["issue"])
    if not rows or rows[0]["issue"] != 2003001 or rows[-1]["issue"] != 2026091:
        raise RuntimeError("外部出球顺序历史边界异常")
    if len(rows) != 3488:
        raise RuntimeError(f"外部出球顺序期数异常: expected=3488 actual={len(rows)}")
    return rows


def load_order_matrix(df: pd.DataFrame) -> np.ndarray:
    legacy = _fetch_legacy_orders()
    order = np.zeros((3502, 6), dtype=int)
    for seq, row in enumerate(legacy, start=1):
        repo_red = sorted(int(df.iloc[seq - 1][f"red{i}"]) for i in range(1, 7))
        if row["red"] != repo_red or row["blue"] != int(df.iloc[seq - 1]["blue"]):
            raise RuntimeError(f"外部出球历史与仓库不一致: seq={seq} issue={row['issue']}")
        order[seq - 1] = row["order"]

    for offset, issue in enumerate(range(2026092, 2026106), start=3489):
        balls = RECENT_ORDER[issue]
        repo_red = sorted(int(df.iloc[offset - 1][f"red{i}"]) for i in range(1, 7))
        if sorted(balls) != repo_red:
            raise RuntimeError(f"近期出球顺序补丁与仓库不一致: seq={offset} issue={issue}")
        order[offset - 1] = balls
    if (order == 0).any():
        raise RuntimeError("出球顺序矩阵存在未填充记录")
    return order


def build_order_features(order: np.ndarray, occ: np.ndarray, min_history: int = 200) -> dict[int, np.ndarray]:
    n_draws = len(order)
    last_pos = np.zeros(v4.RED_COUNT, dtype=float)
    pos_sum = np.zeros(v4.RED_COUNT, dtype=float)
    pos_count = np.zeros(v4.RED_COUNT, dtype=float)
    first_transition = np.zeros((v4.RED_COUNT, v4.RED_COUNT), dtype=float)
    first_base = np.zeros(v4.RED_COUNT, dtype=float)
    last_transition = np.zeros((v4.RED_COUNT, v4.RED_COUNT), dtype=float)
    last_base = np.zeros(v4.RED_COUNT, dtype=float)
    out: dict[int, np.ndarray] = {}

    # 位置矩阵：0代表该期未出现，1—6代表真实摇出顺序。
    pos_matrix = np.zeros((n_draws, v4.RED_COUNT), dtype=np.int8)
    for t in range(n_draws):
        for p, ball in enumerate(order[t], start=1):
            pos_matrix[t, ball - 1] = p

    for t in range(n_draws):
        if t >= min_history:
            rows = []
            prev = order[t - 1]
            prev_first = int(prev[0] - 1)
            prev_last = int(prev[-1] - 1)
            recent50 = pos_matrix[max(0, t - 50):t]
            recent200 = pos_matrix[max(0, t - 200):t]
            first50 = (recent50 == 1).mean(axis=0)
            last50 = (recent50 == 6).mean(axis=0)

            for n0 in range(v4.RED_COUNT):
                p50 = recent50[:, n0]
                p200 = recent200[:, n0]
                nz50 = p50[p50 > 0]
                nz200 = p200[p200 > 0]
                mean50 = float(nz50.mean()) if len(nz50) else 3.5
                mean200 = float(nz200.mean()) if len(nz200) else 3.5
                mean_all = pos_sum[n0] / pos_count[n0] if pos_count[n0] else 3.5
                prev_pos = float(pos_matrix[t - 1, n0])
                trans_first = (first_transition[prev_first, n0] + 1.0) / (first_base[prev_first] + 5.5)
                trans_last = (last_transition[prev_last, n0] + 1.0) / (last_base[prev_last] + 5.5)
                rows.append([
                    last_pos[n0] / 6.0,
                    mean_all / 6.0,
                    mean50 / 6.0,
                    mean200 / 6.0,
                    float(first50[n0]),
                    float(last50[n0]),
                    prev_pos / 6.0,
                    float(trans_first),
                    float(trans_last),
                    float(abs((n0 + 1) - int(prev[0])) == 1),
                    float(abs((n0 + 1) - int(prev[-1])) == 1),
                ])
            out[t] = np.asarray(rows, dtype=float)

        # 当前期数据只在当前期特征已经构造完之后进入历史状态。
        current_set = order[t] - 1
        for p, n0 in enumerate(current_set, start=1):
            last_pos[n0] = float(p)
            pos_sum[n0] += float(p)
            pos_count[n0] += 1.0

        if t >= 1:
            prev_first = int(order[t - 1, 0] - 1)
            prev_last = int(order[t - 1, -1] - 1)
            first_base[prev_first] += 1.0
            last_base[prev_last] += 1.0
            first_transition[prev_first, current_set] += 1.0
            last_transition[prev_last, current_set] += 1.0
    return out


def build_augmented_features(df: pd.DataFrame, order: np.ndarray):
    base_x, y, t_index, occ = v4.build_red_features(df)
    order_features = build_order_features(order, occ)
    extra = np.stack([order_features[int(t)] for t in t_index])
    return np.concatenate([base_x, extra], axis=-1), y, t_index, occ


def walk_forward_augmented(df: pd.DataFrame, order: np.ndarray):
    x, y, t_index, occ = build_augmented_features(df, order)
    n = len(df)
    model_defs = {"all": None, "w1000": 1000, "w500": 500}
    raw = {name: np.full((n, v4.RED_COUNT), np.nan) for name in model_defs}
    for bstart in range(MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        test = (t_index >= bstart) & (t_index < bend)
        for name, window in model_defs.items():
            train = t_index < bstart
            if window is not None:
                train &= t_index >= max(200, bstart - window)
            model = v4._logistic()
            model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
            p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
            raw[name][t_index[test]] = p.reshape(-1, v4.RED_COUNT)

    ensemble = np.full((n, v4.RED_COUNT), np.nan)
    names = list(model_defs)
    hit_cumsum = {}
    for name in names:
        hits = np.zeros(n, dtype=float)
        for u in range(MODEL_START, n):
            hits[u] = v4._topk_hits(raw[name][u], occ[u], 10)
        hit_cumsum[name] = np.concatenate([[0.0], np.cumsum(hits)])
    for t in range(MODEL_START + BLOCK, n):
        left = max(MODEL_START, t - BLOCK)
        perf = np.asarray([
            (hit_cumsum[name][t] - hit_cumsum[name][left]) / max(1, t - left)
            for name in names
        ])
        baseline = v4.RED_PICK * 10 / v4.RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        weights = np.exp(z - z.max())
        weights /= weights.sum()
        ensemble[t] = sum(weights[i] * v4._rank_score(raw[name][t]) for i, name in enumerate(names))
    return ensemble, raw, occ


def _rank_matrix(pred: np.ndarray) -> np.ndarray:
    out = np.full_like(pred, np.nan, dtype=float)
    for t in range(len(pred)):
        if np.isfinite(pred[t]).all():
            out[t] = v4._rank_score(pred[t])
    return out


def blend(base: np.ndarray, augmented: np.ndarray, weight: float) -> np.ndarray:
    b, a = _rank_matrix(base), _rank_matrix(augmented)
    out = (1.0 - weight) * b + weight * a
    out[~(np.isfinite(b).all(axis=1) & np.isfinite(a).all(axis=1))] = np.nan
    return out


def metrics(pred: np.ndarray, occ: np.ndarray, seqs: np.ndarray, lo: int, hi: int) -> dict:
    idx = np.where((seqs >= lo) & (seqs <= hi))[0]
    values = []
    for t in idx:
        if not np.isfinite(pred[t]).all():
            continue
        order = np.argsort(pred[t])[::-1]
        values.append((int(occ[t, order[:6]].sum()), int(occ[t, order[:12]].sum())))
    a = np.asarray(values, dtype=int)
    return {
        "draws": int(len(a)),
        "top6_mean": float(a[:, 0].mean()),
        "top12_mean": float(a[:, 1].mean()),
        "top12_4plus": int((a[:, 1] >= 4).sum()),
        "top12_5plus": int((a[:, 1] >= 5).sum()),
        "top12_6": int((a[:, 1] >= 6).sum()),
    }


def main() -> None:
    df = load_dataframe()
    order = load_order_matrix(df)
    seqs = df["seq"].to_numpy(int)
    base, _, occ = v4.walk_forward_red(df)
    augmented, _, occ2 = walk_forward_augmented(df, order)
    if not np.array_equal(occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")

    configs = {
        "base": base,
        "outball": augmented,
        "base75_outball25": blend(base, augmented, 0.25),
        "base50_outball50": blend(base, augmented, 0.50),
    }
    report = {
        "experiment": "V4.6历史出球顺序因果特征",
        "source": OUTBALL_SOURCE,
        "legacy_order_draws": 3488,
        "recent_patch_draws": 14,
        "target_live106_is_holdout": True,
        "configs": {},
        "promotion": {},
    }
    for name, pred in configs.items():
        report["configs"][name] = {
            seg: metrics(pred, occ, seqs, *rng) for seg, rng in SEGMENTS.items()
        }

    base_metrics = report["configs"]["base"]
    for name in ("outball", "base75_outball25", "base50_outball50"):
        cur = report["configs"][name]
        checks = {}
        all_nonworse = True
        positive = False
        for seg in ("validation_1", "validation_2", "forward55"):
            d12 = cur[seg]["top12_mean"] - base_metrics[seg]["top12_mean"]
            d6 = cur[seg]["top6_mean"] - base_metrics[seg]["top6_mean"]
            d4 = cur[seg]["top12_4plus"] - base_metrics[seg]["top12_4plus"]
            d5 = cur[seg]["top12_5plus"] - base_metrics[seg]["top12_5plus"]
            nonworse = d12 >= -1e-12 and d5 >= 0
            checks[seg] = {
                "top12_delta": float(d12),
                "top6_delta": float(d6),
                "top12_4plus_delta": int(d4),
                "top12_5plus_delta": int(d5),
                "nonworse": bool(nonworse),
            }
            all_nonworse &= nonworse
            positive |= d12 > 1e-12 or d4 > 0 or d5 > 0
        report["promotion"][name] = {"pass": bool(all_nonworse and positive), "checks": checks}

    out = ROOT / "backtests" / "v4_6" / "outball_features.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
