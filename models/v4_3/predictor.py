from __future__ import annotations

import argparse
import importlib.util
import itertools
import json
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
V4_PATH = HERE.parent / "v4" / "predictor.py"
_spec = importlib.util.spec_from_file_location("ssq_v4_base", V4_PATH)
if _spec is None or _spec.loader is None:
    raise RuntimeError(f"无法加载V4基线: {V4_PATH}")
v4 = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(v4)

RED_COUNT = v4.RED_COUNT
PAIR_PRIMARY_WINDOW = 500
PAIR_SECONDARY_WINDOW = 600
PAIR_PRIMARY_WEIGHT = 0.80
PAIR_GATE_WINDOW = 600
PAIR_GATE_MIN_HISTORY = 50
PRIZE_RANK = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}


def _pair_cumulative(red_occ: np.ndarray):
    pair_draw = np.einsum("ti,tj->tij", red_occ, red_occ).astype(np.int16)
    for i in range(RED_COUNT):
        pair_draw[:, i, i] = 0
    pair_cs = np.concatenate([
        np.zeros((1, RED_COUNT, RED_COUNT), dtype=np.int32),
        np.cumsum(pair_draw, axis=0, dtype=np.int32),
    ], axis=0)
    marg_cs = np.concatenate([
        np.zeros((1, RED_COUNT), dtype=np.int32),
        np.cumsum(red_occ, axis=0, dtype=np.int32),
    ], axis=0)
    return pair_cs, marg_cs


def _single_pair_affinity(t: int, pair_cs: np.ndarray, marg_cs: np.ndarray, window: int):
    left = max(0, t - window)
    width = t - left
    marginal = (marg_cs[t] - marg_cs[left]).astype(float)
    pair = (pair_cs[t] - pair_cs[left]).astype(float)
    p = marginal / max(1, width)
    # 33选6无放回组合对独立乘积的修正系数。
    correction = 165 / 192
    expected = width * correction * np.outer(p, p)
    z = (pair - expected) / np.sqrt(expected + 2.0)
    np.fill_diagonal(z, 0.0)
    return np.clip(z, -3.0, 3.0)


def _pair_affinity(t: int, pair_cs: np.ndarray, marg_cs: np.ndarray):
    primary = _single_pair_affinity(t, pair_cs, marg_cs, PAIR_PRIMARY_WINDOW)
    secondary = _single_pair_affinity(t, pair_cs, marg_cs, PAIR_SECONDARY_WINDOW)
    return PAIR_PRIMARY_WEIGHT * primary + (1.0 - PAIR_PRIMARY_WEIGHT) * secondary


def _partition_tables():
    parts = []
    for choice in itertools.combinations(range(12), 6):
        if 0 in choice:
            mask = np.zeros(12, dtype=bool)
            mask[list(choice)] = True
            parts.append(mask)
    parts = np.asarray(parts)
    pairs = np.asarray(list(itertools.combinations(range(12), 2)), dtype=int)
    same = (parts[:, pairs[:, 0]] == parts[:, pairs[:, 1]]).astype(float)
    return parts, pairs, same


PARTS, LOCAL_PAIRS, SAME_GROUP = _partition_tables()


def _pair_partition(top12: np.ndarray, affinity: np.ndarray):
    values = affinity[top12[LOCAL_PAIRS[:, 0]], top12[LOCAL_PAIRS[:, 1]]]
    scores = SAME_GROUP @ values
    best = int(np.argmax(scores))
    mask = PARTS[best]
    confidence = float((scores[best] - np.median(scores)) / (np.std(scores) + 1e-9))
    return top12[mask], top12[~mask], confidence


def backtest(df: pd.DataFrame, start: int = 750) -> pd.DataFrame:
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_pred, _ = v4.walk_forward_blue(df)
    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    blues = df["blue"].to_numpy(int)
    pair_cs, marg_cs = _pair_cumulative(red_occ)

    pair_choice = {}
    pair_conf = np.full(len(df), np.nan)
    for t in range(start, len(df)):
        order = np.argsort(red_pred[t])[::-1]
        top12 = order[:12]
        a, b, confidence = _pair_partition(top12, _pair_affinity(t, pair_cs, marg_cs))
        pair_choice[t] = (a, b)
        pair_conf[t] = confidence

    rows = []
    for t in range(start, len(df)):
        order = np.argsort(red_pred[t])[::-1]
        split_a, split_b = order[:6], order[6:12]
        past = pair_conf[max(start, t - PAIR_GATE_WINDOW):t]
        past = past[np.isfinite(past)]
        use_pair = True if len(past) < PAIR_GATE_MIN_HISTORY else pair_conf[t] >= np.median(past)
        a, b = pair_choice[t] if use_pair else (split_a, split_b)

        # 概率更高的一组获得蓝球第一候选。
        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a
        blue_order = np.argsort(blue_pred[t])[::-1]
        blue1, blue2 = int(blue_order[0] + 1), int(blue_order[1] + 1)

        hit1 = int(red_occ[t, a].sum())
        hit2 = int(red_occ[t, b].sum())
        prize1 = v4.prize_level(hit1, blue1 == blues[t])
        prize2 = v4.prize_level(hit2, blue2 == blues[t])
        best = prize1 if PRIZE_RANK[prize1] <= PRIZE_RANK[prize2] else prize2
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "actual_red": " ".join(f"{x:02d}" for x in reds[t]),
            "actual_blue": int(blues[t]),
            "rank_top12": " ".join(f"{x + 1:02d}" for x in order[:12]),
            "ticket1": " ".join(f"{x + 1:02d}" for x in a),
            "blue1": blue1,
            "ticket2": " ".join(f"{x + 1:02d}" for x in b),
            "blue2": blue2,
            "hit1": hit1,
            "hit2": hit2,
            "prize1": prize1,
            "prize2": prize2,
            "best_prize": best,
            "pair_gate": bool(use_pair),
            "pair_confidence": float(pair_conf[t]),
        })
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame) -> dict:
    counts = result["best_prize"].value_counts().to_dict()
    fixed_return = int(sum(FIXED_PRIZE[p] for p in result["best_prize"]))
    draws = len(result)
    return {
        "model": "双色球多尺度联合关系模型 V4.3",
        "tested_draws": draws,
        "tickets_per_draw": 2,
        "total_tickets": draws * 2,
        "ticket_cost_yuan": draws * 4,
        "best_prize_counts": {k: int(counts.get(k, 0)) for k in PRIZE_RANK},
        "winning_draw_rate": float((result["best_prize"] != "未中奖").mean()),
        "fixed_prize_return_yuan": fixed_return,
        "fixed_prize_return_ratio": float(fixed_return / (draws * 4)),
        "pair_gate_active_rate": float(result["pair_gate"].mean()),
        "pair_primary_window": PAIR_PRIMARY_WINDOW,
        "pair_secondary_window": PAIR_SECONDARY_WINDOW,
        "pair_primary_weight": PAIR_PRIMARY_WEIGHT,
        "pair_gate_window": PAIR_GATE_WINDOW,
        "note": "三元联合、固定/动态胆码与边际强聚集均未通过跨段验证；V4.3仅保留多尺度两两关系与长窗门控。",
    }


def main():
    parser = argparse.ArgumentParser(description="双色球 V4.3 多尺度联合关系模型")
    parser.add_argument("--history", default="data/ssq_history_2003_2026-05-03.csv.gz")
    parser.add_argument("--out", default="backtests/v4_3/backtest.csv")
    parser.add_argument("--summary", default="backtests/v4_3/summary.json")
    args = parser.parse_args()

    history = v4.load_history(args.history)
    result = backtest(history)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(out, index=False, encoding="utf-8-sig")
    summary_path = Path(args.summary)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(json.dumps(summarize(result), ensure_ascii=False, indent=2), encoding="utf-8")
    print(result["best_prize"].value_counts().to_string())


if __name__ == "__main__":
    main()
