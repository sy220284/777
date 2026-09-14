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


v4 = _load_module("ssq_v4_blue_exp", ROOT / "models" / "v4" / "predictor.py")
history_tool = _load_module("ssq_history_blue_exp", ROOT / "tools" / "load_history.py")

START = 750
BLOCK = 250
HALF_LIVES = (8, 32, 128)
BLUE_COUNT = 16
BASE_RATE = 1 / BLUE_COUNT


def load_current_dataframe() -> pd.DataFrame:
    rows = history_tool.validate_current_state()
    return pd.DataFrame([
        {
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        }
        for row in rows
    ])


def build_extra_features(df: pd.DataFrame, t_index: np.ndarray) -> dict[str, np.ndarray]:
    blues = df["blue"].to_numpy(int)
    occ = v4._occurrence_matrix(blues, BLUE_COUNT)
    wanted = set(int(t) for t in t_index)
    alphas = [1.0 - math.exp(math.log(0.5) / h) for h in HALF_LIVES]
    ewm = [np.zeros(BLUE_COUNT, dtype=float) for _ in HALF_LIVES]
    trans = np.zeros((BLUE_COUNT, BLUE_COUNT), dtype=float)
    dist_counts = np.zeros(BLUE_COUNT, dtype=float)
    last_seen = np.full(BLUE_COUNT, -1, dtype=int)
    gap_hist = np.zeros(161, dtype=float)

    out_ewma: dict[int, np.ndarray] = {}
    out_transition: dict[int, np.ndarray] = {}
    out_distance: dict[int, np.ndarray] = {}
    out_hazard: dict[int, np.ndarray] = {}

    for t in range(len(df)):
        if t in wanted:
            e = np.stack(ewm, axis=1)
            out_ewma[t] = np.column_stack([e, e[:, 0] - e[:, 1], e[:, 1] - e[:, 2]])

            prev = int(blues[t - 1]) - 1 if t > 0 else 0
            row = trans[prev]
            denom = row.sum() + BLUE_COUNT
            transition_prob = (row + 1.0) / denom
            out_transition[t] = np.column_stack([
                transition_prob,
                np.log((transition_prob + 1e-12) / BASE_RATE),
            ])

            dtotal = dist_counts.sum() + BLUE_COUNT
            distance_prob = np.zeros(BLUE_COUNT, dtype=float)
            for n0 in range(BLUE_COUNT):
                d = abs(n0 - prev)
                distance_prob[n0] = (dist_counts[d] + 1.0) / dtotal
            out_distance[t] = np.column_stack([
                distance_prob,
                np.log((distance_prob + 1e-12) / BASE_RATE),
            ])

            hz = np.zeros((BLUE_COUNT, 2), dtype=float)
            for n0 in range(BLUE_COUNT):
                gap = t - last_seen[n0] if last_seen[n0] >= 0 else t + 1
                g = min(160, max(1, int(gap)))
                at_g = gap_hist[g]
                survivor = gap_hist[g:].sum()
                hazard = (at_g + 2.0 * BASE_RATE) / (survivor + 2.0)
                hz[n0, 0] = hazard
                hz[n0, 1] = math.log((hazard + 1e-12) / BASE_RATE)
            out_hazard[t] = hz

        if t > 0:
            prev = int(blues[t - 1]) - 1
            cur = int(blues[t]) - 1
            trans[prev, cur] += 1.0
            dist_counts[abs(cur - prev)] += 1.0
        cur = int(blues[t]) - 1
        if last_seen[cur] >= 0:
            gap = min(160, max(1, int(t - last_seen[cur])))
            gap_hist[gap] += 1.0
        last_seen[cur] = t
        for i, alpha in enumerate(alphas):
            ewm[i] = (1.0 - alpha) * ewm[i] + alpha * occ[t]

    def stack(mapping: dict[int, np.ndarray]) -> np.ndarray:
        return np.stack([mapping[int(t)] for t in t_index])

    return {
        "ewma": stack(out_ewma),
        "transition": stack(out_transition),
        "distance": stack(out_distance),
        "hazard": stack(out_hazard),
    }


def walk_forward(x: np.ndarray, y: np.ndarray, t_index: np.ndarray, n: int) -> np.ndarray:
    pred = np.full((n, BLUE_COUNT), np.nan)
    for bstart in range(500, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        train = (t_index < bstart) & (t_index >= max(200, bstart - 1000))
        test = (t_index >= bstart) & (t_index < bend)
        model = v4._logistic()
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        pred[t_index[test]] = p.reshape(-1, BLUE_COUNT)
    return pred


def metrics(pred: np.ndarray, actual: np.ndarray, indices: np.ndarray) -> dict:
    top1 = 0
    top2 = 0
    ranks = []
    for t in indices:
        order = np.argsort(pred[t])[::-1]
        true = int(actual[t]) - 1
        if true == int(order[0]):
            top1 += 1
        if true in set(int(x) for x in order[:2]):
            top2 += 1
        rank_pos = int(np.where(order == true)[0][0]) + 1
        ranks.append(rank_pos)
    return {
        "draws": int(len(indices)),
        "top1_hits": int(top1),
        "top1_rate": float(top1 / len(indices)),
        "top2_hits": int(top2),
        "top2_rate": float(top2 / len(indices)),
        "mean_rank": float(np.mean(ranks)),
    }


def summarize(pred: np.ndarray, actual: np.ndarray, seq: np.ndarray) -> dict:
    valid = np.arange(START, len(seq), dtype=int)
    out = {
        "full": metrics(pred, actual, valid),
        "legacy": metrics(pred, actual, valid[seq[valid] <= 3446]),
        "forward56": metrics(pred, actual, valid[seq[valid] >= 3447]),
    }
    for i, idx in enumerate(np.array_split(valid, 4), start=1):
        out[f"quarter{i}"] = metrics(pred, actual, idx)
    return out


def compare(candidate: dict, baseline: dict) -> dict:
    quarter = [candidate[f"quarter{i}"]["top2_hits"] - baseline[f"quarter{i}"]["top2_hits"] for i in range(1, 5)]
    d = {
        "full_top1_hits": candidate["full"]["top1_hits"] - baseline["full"]["top1_hits"],
        "full_top2_hits": candidate["full"]["top2_hits"] - baseline["full"]["top2_hits"],
        "full_mean_rank": candidate["full"]["mean_rank"] - baseline["full"]["mean_rank"],
        "forward56_top1_hits": candidate["forward56"]["top1_hits"] - baseline["forward56"]["top1_hits"],
        "forward56_top2_hits": candidate["forward56"]["top2_hits"] - baseline["forward56"]["top2_hits"],
        "forward56_mean_rank": candidate["forward56"]["mean_rank"] - baseline["forward56"]["mean_rank"],
        "quarter_top2_hits": quarter,
    }
    quarter_nonnegative = sum(x >= 0 for x in quarter)
    passed = (
        d["full_top2_hits"] > 0
        and d["forward56_top2_hits"] >= 0
        and d["full_top1_hits"] >= 0
        and d["forward56_top1_hits"] >= 0
        and d["full_mean_rank"] <= 0
        and quarter_nonnegative >= 3
    )
    return {"passed": bool(passed), "quarter_nonnegative": int(quarter_nonnegative), "delta": d}


def run() -> dict:
    df = load_current_dataframe()
    x, y, t_index, _ = v4.build_blue_features(df)
    actual = df["blue"].to_numpy(int)
    seq = df["seq"].to_numpy(int)
    extras = build_extra_features(df, t_index)

    print("运行蓝球V4基线", flush=True)
    baseline_pred, _ = v4.walk_forward_blue(df)
    baseline = summarize(baseline_pred, actual, seq)

    feature_sets = {
        "ewma": [extras["ewma"]],
        "transition": [extras["transition"]],
        "distance": [extras["distance"]],
        "hazard": [extras["hazard"]],
        "transition_distance": [extras["transition"], extras["distance"]],
        "all_new": [extras["ewma"], extras["transition"], extras["distance"], extras["hazard"]],
    }
    summaries = {"v4_blue_baseline": baseline}
    checks = {}
    for name, parts in feature_sets.items():
        print(f"运行蓝球新特征方案 {name}", flush=True)
        xx = np.concatenate([x, *parts], axis=2)
        pred = walk_forward(xx, y, t_index, len(df))
        s = summarize(pred, actual, seq)
        summaries[name] = s
        checks[name] = compare(s, baseline)

    return {
        "model": "V4.5.2 蓝球条件时序特征实验",
        "features": {
            "ewma": "8/32/128期半衰期指数频率与快慢差",
            "transition": "上一蓝球到候选蓝球的历史条件转移概率",
            "distance": "上一蓝球到当前候选的绝对距离历史分布",
            "hazard": "已完成遗漏间隔的全局经验风险率",
        },
        "summaries": summaries,
        "promotion_checks": checks,
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2 蓝球条件时序特征实验")
    p.add_argument("--out", default="backtests/v4_5_2/blue_feature_experiment_summary.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
