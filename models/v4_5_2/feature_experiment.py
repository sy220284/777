from __future__ import annotations

import argparse
import importlib.util
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load_module("ssq_v4_feature_exp", ROOT / "models" / "v4" / "predictor.py")
history_tool = _load_module("ssq_history_feature_exp", ROOT / "tools" / "load_history.py")

START = 750
BLOCK = 250
BASE_RATE = 6 / 33
HALF_LIVES = (8, 32, 128)


def load_current_dataframe() -> pd.DataFrame:
    rows = history_tool.validate_current_state()
    records = []
    for row in rows:
        records.append({
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        })
    return pd.DataFrame(records)


def build_extra_features(df: pd.DataFrame, t_index: np.ndarray) -> dict[str, np.ndarray]:
    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    occ = v4._occurrence_matrix(reds, v4.RED_COUNT)
    n = len(df)
    wanted = set(int(t) for t in t_index)

    alphas = [1.0 - math.exp(math.log(0.5) / h) for h in HALF_LIVES]
    ewm = [np.zeros(v4.RED_COUNT, dtype=float) for _ in HALF_LIVES]
    last_seen = np.full(v4.RED_COUNT, -1, dtype=int)
    gap_hist = np.zeros(121, dtype=float)

    ewma_rows = {}
    hazard_rows = {}
    recent_rows = {}

    for t in range(n):
        if t in wanted:
            e = np.stack(ewm, axis=1)
            ewma_rows[t] = np.column_stack([
                e,
                e[:, 0] - e[:, 1],
                e[:, 1] - e[:, 2],
            ])

            hz = np.zeros((v4.RED_COUNT, 2), dtype=float)
            for n0 in range(v4.RED_COUNT):
                gap = t - last_seen[n0] if last_seen[n0] >= 0 else t + 1
                g = min(120, max(1, int(gap)))
                at_g = gap_hist[g]
                survivor = gap_hist[g:].sum()
                hazard = (at_g + 2.0 * BASE_RATE) / (survivor + 2.0)
                hz[n0, 0] = hazard
                hz[n0, 1] = math.log((hazard + 1e-9) / BASE_RATE)
            hazard_rows[t] = hz

            recent2 = occ[max(0, t - 2):t].sum(axis=0).astype(float)
            recent3 = occ[max(0, t - 3):t].sum(axis=0).astype(float)
            neighbor3 = np.zeros(v4.RED_COUNT, dtype=float)
            for n0 in range(v4.RED_COUNT):
                if n0 > 0:
                    neighbor3[n0] += recent3[n0 - 1]
                if n0 + 1 < v4.RED_COUNT:
                    neighbor3[n0] += recent3[n0 + 1]
            recent_rows[t] = np.column_stack([recent2, recent3, neighbor3])

        current = reds[t] - 1
        for n0 in current:
            if last_seen[n0] >= 0:
                gap = min(120, max(1, int(t - last_seen[n0])))
                gap_hist[gap] += 1.0
            last_seen[n0] = t
        for i, alpha in enumerate(alphas):
            ewm[i] = (1.0 - alpha) * ewm[i] + alpha * occ[t]

    def stack(mapping: dict[int, np.ndarray]) -> np.ndarray:
        return np.stack([mapping[int(t)] for t in t_index])

    return {
        "ewma": stack(ewma_rows),
        "hazard": stack(hazard_rows),
        "recent": stack(recent_rows),
    }


def walk_forward_custom(x: np.ndarray, y: np.ndarray, t_index: np.ndarray, occ: np.ndarray) -> np.ndarray:
    n = len(occ)
    model_defs = {
        "all": None,
        "w1000": 1000,
        "w500": 500,
    }
    raw = {name: np.full((n, v4.RED_COUNT), np.nan) for name in model_defs}

    for bstart in range(500, n, BLOCK):
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
        for u in range(500, n):
            hits[u] = v4._topk_hits(raw[name][u], occ[u], 10)
        hit_cumsum[name] = np.concatenate([[0.0], np.cumsum(hits)])

    for t in range(500 + BLOCK, n):
        left = max(500, t - BLOCK)
        perf = np.asarray([
            (hit_cumsum[name][t] - hit_cumsum[name][left]) / max(1, t - left)
            for name in names
        ])
        baseline = v4.RED_PICK * 10 / v4.RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        weights = np.exp(z - z.max())
        weights /= weights.sum()
        ensemble[t] = sum(weights[i] * v4._rank_score(raw[name][t]) for i, name in enumerate(names))
    return ensemble


def segment_metrics(score: np.ndarray, occ: np.ndarray, indices: np.ndarray) -> dict:
    top6, top12 = [], []
    for t in indices:
        order = np.argsort(score[t])[::-1]
        top6.append(int(occ[t, order[:6]].sum()))
        top12.append(int(occ[t, order[:12]].sum()))
    a, b = np.asarray(top6), np.asarray(top12)
    return {
        "draws": int(len(indices)),
        "top6_mean": float(a.mean()),
        "top6_3plus": int((a >= 3).sum()),
        "top6_4plus": int((a >= 4).sum()),
        "top12_mean": float(b.mean()),
        "top12_4plus": int((b >= 4).sum()),
        "top12_5plus": int((b >= 5).sum()),
        "top12_6": int((b >= 6).sum()),
    }


def summarize(score: np.ndarray, occ: np.ndarray, seq: np.ndarray) -> dict:
    valid = np.arange(START, len(seq), dtype=int)
    out = {
        "full": segment_metrics(score, occ, valid),
        "legacy": segment_metrics(score, occ, valid[seq[valid] <= 3446]),
        "forward56": segment_metrics(score, occ, valid[seq[valid] >= 3447]),
    }
    for i, idx in enumerate(np.array_split(valid, 4), start=1):
        out[f"quarter{i}"] = segment_metrics(score, occ, idx)
    return out


def compare(candidate: dict, baseline: dict) -> dict:
    quarter_delta = [
        candidate[f"quarter{i}"]["top12_mean"] - baseline[f"quarter{i}"]["top12_mean"]
        for i in range(1, 5)
    ]
    delta = {
        "full_top12_mean": candidate["full"]["top12_mean"] - baseline["full"]["top12_mean"],
        "full_top12_4plus": candidate["full"]["top12_4plus"] - baseline["full"]["top12_4plus"],
        "full_top12_5plus": candidate["full"]["top12_5plus"] - baseline["full"]["top12_5plus"],
        "full_top6_mean": candidate["full"]["top6_mean"] - baseline["full"]["top6_mean"],
        "forward56_top12_mean": candidate["forward56"]["top12_mean"] - baseline["forward56"]["top12_mean"],
        "forward56_top12_4plus": candidate["forward56"]["top12_4plus"] - baseline["forward56"]["top12_4plus"],
        "forward56_top12_5plus": candidate["forward56"]["top12_5plus"] - baseline["forward56"]["top12_5plus"],
        "quarter_top12_mean": quarter_delta,
    }
    nonnegative = sum(x >= -1e-12 for x in quarter_delta)
    passed = (
        delta["full_top12_mean"] > 0
        and delta["forward56_top12_mean"] > 0
        and delta["full_top12_4plus"] >= 0
        and delta["full_top12_5plus"] >= 0
        and delta["forward56_top12_4plus"] >= 0
        and nonnegative >= 3
    )
    return {"passed": bool(passed), "quarter_nonnegative": int(nonnegative), "delta": delta}


def run() -> dict:
    df = load_current_dataframe()
    x, y, t_index, occ = v4.build_red_features(df)
    extras = build_extra_features(df, t_index)
    seq = df["seq"].to_numpy(int)

    print("运行V4原始基线", flush=True)
    baseline_score, _, _ = v4.walk_forward_red(df)
    baseline = summarize(baseline_score, occ, seq)

    feature_sets = {
        "ewma": [extras["ewma"]],
        "hazard": [extras["hazard"]],
        "recent": [extras["recent"]],
        "ewma_hazard": [extras["ewma"], extras["hazard"]],
        "ewma_recent": [extras["ewma"], extras["recent"]],
        "all_new": [extras["ewma"], extras["hazard"], extras["recent"]],
    }

    summaries = {"v4_baseline": baseline}
    checks = {}
    for name, parts in feature_sets.items():
        print(f"运行新特征方案 {name}", flush=True)
        xx = np.concatenate([x, *parts], axis=2)
        score = walk_forward_custom(xx, y, t_index, occ)
        summary = summarize(score, occ, seq)
        summaries[name] = summary
        checks[name] = compare(summary, baseline)

    return {
        "model": "V4.5.2 独立时序特征实验",
        "features": {
            "ewma": "8/32/128期半衰期指数热度及快慢差",
            "hazard": "基于历史已完成遗漏间隔的全局经验风险率及相对基准对数比",
            "recent": "最近2/3期重复次数及最近3期邻号压力",
        },
        "summaries": summaries,
        "promotion_checks": checks,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="V4.5.2 独立时序特征实验")
    parser.add_argument("--out", default="backtests/v4_5_2/feature_experiment_summary.json")
    args = parser.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
