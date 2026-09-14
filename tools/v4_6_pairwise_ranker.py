from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import SGDClassifier
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

ROOT = Path(__file__).resolve().parents[1]
MODEL_START = 500
START = 750
BLOCK = 250
TRAIN_WINDOW = 1000
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load("v46_pair_v4", ROOT / "models" / "v4" / "predictor.py")
v45 = _load("v46_pair_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load("v46_pair_history", ROOT / "tools" / "load_history.py")


def load_dataframe() -> pd.DataFrame:
    rows = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    df = pd.DataFrame([{
        "seq": int(r["seq"]),
        **{f"red{i}": int(r["red"][i - 1]) for i in range(1, 7)},
        "blue": int(r["blue"]),
    } for r in rows])
    if int(df.iloc[-1]["seq"]) == 3502:
        df = pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)
    return df


def _pair_dataset(x: np.ndarray, y: np.ndarray, draw_indices: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    """每期6个中奖号与27个未中奖号逐对比较；方向按固定奇偶翻转以构造严格平衡二分类。"""
    feature_count = x.shape[-1]
    rows = np.empty((len(draw_indices) * 6 * 27, feature_count), dtype=np.float32)
    labels = np.empty(len(rows), dtype=np.int8)
    cursor = 0
    for local_t, d in enumerate(draw_indices):
        pos = np.flatnonzero(y[d] == 1)
        neg = np.flatnonzero(y[d] == 0)
        for p in pos:
            for n in neg:
                if ((int(d) + int(p) + int(n)) & 1) == 0:
                    rows[cursor] = x[d, p] - x[d, n]
                    labels[cursor] = 1
                else:
                    rows[cursor] = x[d, n] - x[d, p]
                    labels[cursor] = 0
                cursor += 1
    if cursor != len(rows):
        raise RuntimeError("成对排序样本计数异常")
    return rows, labels


def walk_forward_pairwise(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    x, y, t_index, occ = v4.build_red_features(df)
    n = len(df)
    pred = np.full((n, v4.RED_COUNT), np.nan, dtype=float)

    for bstart in range(MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        train_mask = (t_index < bstart) & (t_index >= max(200, bstart - TRAIN_WINDOW))
        test_mask = (t_index >= bstart) & (t_index < bend)
        train_rows = np.flatnonzero(train_mask)
        if not len(train_rows) or not test_mask.any():
            continue
        px, py = _pair_dataset(x, y, train_rows)
        model = make_pipeline(
            StandardScaler(),
            SGDClassifier(
                loss="log_loss",
                alpha=1e-4,
                max_iter=30,
                tol=1e-4,
                average=True,
                random_state=20260914,
            ),
        )
        model.fit(px, py)
        test_x = x[test_mask].reshape(-1, x.shape[-1])
        score = model.decision_function(test_x).reshape(-1, v4.RED_COUNT)
        pred[t_index[test_mask]] = score
    return pred, occ


def _rank01(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def blend(base: np.ndarray, pairwise: np.ndarray, weight: float) -> np.ndarray:
    out = np.full_like(base, np.nan, dtype=float)
    for t in range(len(base)):
        if np.isfinite(base[t]).all() and np.isfinite(pairwise[t]).all():
            out[t] = (1.0 - weight) * _rank01(base[t]) + weight * _rank01(pairwise[t])
    return out


def candidate_metrics(pred: np.ndarray, occ: np.ndarray, seqs: np.ndarray, lo: int, hi: int) -> dict:
    rows = []
    for t in np.where((seqs >= lo) & (seqs <= hi))[0]:
        if not np.isfinite(pred[t]).all():
            continue
        order = np.argsort(pred[t])[::-1]
        rows.append((int(occ[t, order[:6]].sum()), int(occ[t, order[:12]].sum())))
    a = np.asarray(rows, dtype=int)
    return {
        "draws": int(len(a)),
        "top6_mean": float(a[:, 0].mean()),
        "top12_mean": float(a[:, 1].mean()),
        "top12_4plus": int((a[:, 1] >= 4).sum()),
        "top12_5plus": int((a[:, 1] >= 5).sum()),
    }


def _signal_mask(pred: np.ndarray, occ: np.ndarray) -> np.ndarray:
    baseline = 6 * 12 / 33
    hits = np.full(len(pred), np.nan)
    for t in range(START, len(pred)):
        if np.isfinite(pred[t]).all():
            hits[t] = int(occ[t, np.argsort(pred[t])[::-1][:12]].sum())
    active = np.zeros(len(pred), dtype=bool)
    for t in range(START, len(pred)):
        past = hits[max(START, t - 250):t]
        past = past[np.isfinite(past)]
        active[t] = len(past) >= 50 and float(past.mean()) >= baseline
    return active


def _best(a: str, b: str) -> str:
    return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def portfolio(df: pd.DataFrame, pred: np.ndarray, occ: np.ndarray, blue_base: np.ndarray, blue_models: dict[str, np.ndarray]) -> pd.DataFrame:
    low, _, _ = v45._baseline_red_groups(pred, occ, START)
    high, _, _ = v45.build_red_portfolio(pred, occ, START)
    signal = _signal_mask(pred, occ)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual_blue, START)
    rows = []
    for t in range(START, len(df)):
        use_high = bool(signal[t])
        a, b = (high if use_high else low)[t]
        bs = blue_high[t] if use_high else blue_base[t]
        bo = np.argsort(bs)[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({"seq": int(df.iloc[t]["seq"]), "max_red_hit": max(h1, h2), "prize1": p1, "prize2": p2, "best_prize": _best(p1, p2)})
    return pd.DataFrame(rows)


def portfolio_summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    return {
        "draws": int(len(part)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "red_4plus": int((part.max_red_hit >= 4).sum()),
        "red_5plus": int((part.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
    }


def main() -> None:
    df = load_dataframe()
    seqs = df["seq"].to_numpy(int)
    base, _, occ = v4.walk_forward_red(df)
    pairwise, occ2 = walk_forward_pairwise(df)
    if not np.array_equal(occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")
    configs = {
        "base": base,
        "pairwise": pairwise,
        "base75_pair25": blend(base, pairwise, 0.25),
        "base50_pair50": blend(base, pairwise, 0.50),
    }
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    report = {"experiment": "V4.6固定1000期成对排序挑战模型", "configs": {}, "promotion": {}}
    portfolios = {}
    for name, pred in configs.items():
        p = portfolio(df, pred, occ, blue_base, blue_models)
        portfolios[name] = p
        report["configs"][name] = {
            seg: {
                "candidate": candidate_metrics(pred, occ, seqs, *rng),
                "portfolio": portfolio_summary(p, *rng),
            } for seg, rng in SEGMENTS.items()
        }

    base_cfg = report["configs"]["base"]
    for name in ("pairwise", "base75_pair25", "base50_pair50"):
        cur = report["configs"][name]
        all_nonworse = True
        positive = 0
        checks = {}
        for seg in ("validation_1", "validation_2", "forward55"):
            bc, cc = base_cfg[seg]["candidate"], cur[seg]["candidate"]
            bp, cp = base_cfg[seg]["portfolio"], cur[seg]["portfolio"]
            d12 = cc["top12_mean"] - bc["top12_mean"]
            d5 = cc["top12_5plus"] - bc["top12_5plus"]
            dret = cp["fixed_return"] - bp["fixed_return"]
            dred5 = cp["red_5plus"] - bp["red_5plus"]
            nonworse = d12 >= -1e-12 and d5 >= 0 and dret >= 0 and dred5 >= 0
            improved = d12 > 1e-12 or d5 > 0 or dret > 0 or dred5 > 0
            checks[seg] = {"top12_mean_delta": float(d12), "top12_5plus_delta": int(d5), "fixed_return_delta": int(dret), "portfolio_red5_delta": int(dred5), "nonworse": bool(nonworse), "improved": bool(improved)}
            all_nonworse &= nonworse
            positive += int(improved)
        report["promotion"][name] = {"pass": bool(all_nonworse and positive >= 2), "positive_segments": int(positive), "checks": checks}

    out = ROOT / "backtests" / "v4_6" / "pairwise_ranker.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
