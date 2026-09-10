from __future__ import annotations

import argparse
import json
from pathlib import Path

import numpy as np
from sklearn.metrics import roc_auc_score

RED_COUNT = 33
RED_PICK = 6
BASELINE = RED_PICK / RED_COUNT


def _topk_stats(score: np.ndarray, occ: np.ndarray, start: int, end: int, k: int = 12) -> dict:
    hits = []
    for t in range(start, end):
        order = np.argsort(score[t])[::-1][:k]
        hits.append(int(occ[t, order].sum()))
    a = np.asarray(hits, dtype=int)
    return {
        "mean": float(a.mean()),
        "4plus": int((a >= 4).sum()),
        "5plus": int((a >= 5).sum()),
        "6": int((a >= 6).sum()),
    }


def _brier(prob: np.ndarray, y: np.ndarray) -> float:
    return float(np.mean((prob - y) ** 2))


def causal_rank_position_calibration(
    rank_score: np.ndarray,
    occ: np.ndarray,
    *,
    start: int,
    window: int | None,
    prior_strength: float = 1000.0,
) -> np.ndarray:
    """按模型历史排名位置的真实命中率，逐期因果校准概率。

    当前期只使用此前期次。prior_strength 将每个排名位置向 6/33 随机基线收缩。
    该方法用于验证“排名位置是否具有稳定概率含义”，不是新增预测模型。
    """
    n = len(rank_score)
    rank_pos = np.empty((n, RED_COUNT), dtype=int)
    y_by_rank = np.zeros((n, RED_COUNT), dtype=np.int8)
    for t in range(n):
        order = np.argsort(rank_score[t])[::-1]
        rank_pos[t, order] = np.arange(RED_COUNT)
        y_by_rank[t] = occ[t, order]

    cs = np.vstack([np.zeros((1, RED_COUNT), dtype=int), np.cumsum(y_by_rank, axis=0)])
    calibrated = np.full_like(rank_score, np.nan, dtype=float)
    for t in range(start, n):
        left = 0 if window is None else max(0, t - window)
        width = t - left
        p_by_rank = (cs[t] - cs[left] + prior_strength * BASELINE) / (width + prior_strength)
        calibrated[t] = p_by_rank[rank_pos[t]]
    return calibrated


def main() -> None:
    p = argparse.ArgumentParser(description="评估V4红球概率是否真正优于6/33随机基线")
    p.add_argument("--preds", required=True, help="包含ens/occ/all/w1000/w500的npz")
    p.add_argument("--out", required=True)
    p.add_argument("--start", type=int, default=750)
    p.add_argument("--forward-start", type=int, default=3446)
    args = p.parse_args()

    z = np.load(args.preds)
    occ = z["occ"]
    n = len(occ)
    y = occ[args.start:n].ravel()
    baseline_prob = np.full_like(y, BASELINE, dtype=float)

    raw = {
        "random_constant": {
            "brier": _brier(baseline_prob, y),
            "auc": 0.5,
        }
    }
    for name in ("all", "w1000", "w500"):
        prob = z[name][args.start:n].ravel()
        raw[name] = {
            "brier": _brier(prob, y),
            "auc": float(roc_auc_score(y, prob)),
        }

    ens = z["ens"]
    base_top12 = {
        "full": _topk_stats(ens, occ, args.start, n),
        "forward": _topk_stats(ens, occ, args.forward_start, n),
    }

    rank_calibration = {}
    for window in (100, 250, 750, 1000):
        cal = causal_rank_position_calibration(
            ens, occ, start=args.start, window=window, prior_strength=1000.0
        )
        mask = np.isfinite(cal[args.start:])
        rank_calibration[str(window)] = {
            "brier": _brier(cal[args.start:][mask], occ[args.start:][mask]),
            "full_top12": _topk_stats(cal, occ, args.start, n),
            "forward_top12": _topk_stats(cal, occ, args.forward_start, n),
        }

    result = {
        "baseline_probability": BASELINE,
        "raw_probability_quality": raw,
        "base_rank_top12": base_top12,
        "rank_position_calibration": rank_calibration,
        "interpretation": (
            "若校准仅改善概率误差但不能改善候选池或实际奖级，则只保留为诊断工具；"
            "若改变排序后长期奖级下降，则不得进入生产模型。"
        ),
    }
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")


if __name__ == "__main__":
    main()
