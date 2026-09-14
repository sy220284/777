from __future__ import annotations

import argparse
import importlib.util
import itertools
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load_module("v46p_v4", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("v46p_v43", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("v46p_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load_module("v46p_history", ROOT / "tools" / "load_history.py")

START = 750
SIGNAL_WINDOW = 250
SIGNAL_MIN_HISTORY = 50
RANDOM_TOP12_MEAN = 6 * 12 / 33
LIVE_106 = {"seq": 3503, "red1": 6, "red2": 11, "red3": 13, "red4": 14, "red5": 22, "red6": 30, "blue": 14}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def load_dataframe(include_live106: bool = True) -> pd.DataFrame:
    rows = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    records = [{
        "seq": int(row["seq"]),
        **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
        "blue": int(row["blue"]),
    } for row in rows]
    df = pd.DataFrame(records)
    if include_live106 and int(df.iloc[-1]["seq"]) == 3502:
        df = pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)
    return df


def _subset_tables(pool_size: int):
    masks = []
    for choice in itertools.combinations(range(pool_size), 6):
        mask = np.zeros(pool_size, dtype=bool)
        mask[list(choice)] = True
        masks.append(mask)
    masks = np.asarray(masks)
    local_pairs = np.asarray(list(itertools.combinations(range(pool_size), 2)), dtype=int)
    within = (masks[:, local_pairs[:, 0]] & masks[:, local_pairs[:, 1]]).astype(np.float32)
    return masks, local_pairs, within


def _zscore(x: np.ndarray) -> np.ndarray:
    return (x - x.mean()) / (x.std() + 1e-9)


def _extended_partition(
    top: np.ndarray,
    red_score: np.ndarray,
    affinity: np.ndarray,
    masks: np.ndarray,
    local_pairs: np.ndarray,
    within: np.ndarray,
):
    pair_values = affinity[top[local_pairs[:, 0]], top[local_pairs[:, 1]]]
    cohesion = within @ pair_values.astype(np.float32)
    marginal = masks @ red_score[top]
    score = v45.RED_PRIMARY_PAIR_WEIGHT * _zscore(cohesion) + v45.RED_MARGINAL_WEIGHT * _zscore(marginal)
    primary_idx = int(np.argmax(score))
    primary_mask = masks[primary_idx]
    overlaps = masks @ primary_mask.astype(float)
    allowed = np.where(overlaps <= v45.RED_MAX_OVERLAP)[0]
    secondary_idx = int(allowed[np.argmax(score[allowed])])
    return top[primary_mask], top[masks[secondary_idx]]


def build_extended_groups(red_pred: np.ndarray, red_occ: np.ndarray, pool_size: int):
    if pool_size <= v45.RED_POOL_SIZE:
        groups, active, _ = v45.build_red_portfolio(red_pred, red_occ, START)
        return groups, active

    baseline_groups, active, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    masks, local_pairs, within = _subset_tables(pool_size)
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    groups = dict(baseline_groups)
    for t in range(START, len(red_pred)):
        if not active[t]:
            continue
        order = np.argsort(red_pred[t])[::-1]
        affinity = v43._pair_affinity(t, pair_cs, marg_cs)
        a, b = _extended_partition(order[:pool_size], red_pred[t], affinity, masks, local_pairs, within)
        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a
        groups[t] = (a, b)
    return groups, active


def _signal_mask(red_pred: np.ndarray, red_occ: np.ndarray):
    hits = np.full(len(red_pred), np.nan)
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1][:12]
        hits[t] = int(red_occ[t, order].sum())
    active = np.zeros(len(red_pred), dtype=bool)
    for t in range(START, len(red_pred)):
        past = hits[max(START, t - SIGNAL_WINDOW):t]
        past = past[np.isfinite(past)]
        active[t] = len(past) >= SIGNAL_MIN_HISTORY and float(past.mean()) >= RANDOM_TOP12_MEAN
    return active


def _best(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def evaluate(df: pd.DataFrame, red_pred: np.ndarray, red_occ: np.ndarray, pool_size: int, blue_models, blue_base):
    v43_groups, _, _ = v45._baseline_red_groups(red_pred, red_occ, START)
    v45_groups, concentration_active = build_extended_groups(red_pred, red_occ, pool_size)
    actual_blue = df["blue"].to_numpy(int)
    blue_v45, _ = v45.fuse_blue_predictions(blue_models, v45_groups, red_occ, actual_blue, START)
    signal_active = _signal_mask(red_pred, red_occ)

    rows = []
    for t in range(START, len(df)):
        use_v45 = bool(signal_active[t])
        a, b = (v45_groups if use_v45 else v43_groups)[t]
        blue_score = blue_v45[t] if use_v45 else blue_base[t]
        order = np.argsort(blue_score)[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "max_red_hit": max(h1, h2),
            "hit1": h1,
            "hit2": h2,
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1, p2),
            "signal_active": use_v45,
            "concentration_active": bool(concentration_active[t]),
            "ticket_overlap": int(len(set(a.tolist()) & set(b.tolist()))),
        })
    return pd.DataFrame(rows)


def summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    if part.empty:
        return {"draws": 0}
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    best_counts = part.best_prize.value_counts().to_dict()
    ticket_counts = tickets.value_counts().to_dict()
    fixed = int(sum(FIXED_PRIZE[p] for p in tickets))
    return {
        "draws": int(len(part)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "red_4plus_draws": int((part.max_red_hit >= 4).sum()),
        "red_5plus_draws": int((part.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "fixed_return_ratio": float(fixed / (len(part) * 4)),
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
        "mean_overlap": float(part.ticket_overlap.mean()),
        "best_prize_counts": {k: int(best_counts.get(k, 0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k: int(ticket_counts.get(k, 0)) for k in PRIZE_ORDER},
    }


def main():
    parser = argparse.ArgumentParser(description="V4.6 扩展候选池高奖组合实验")
    parser.add_argument("--out", default="backtests/v4_6/portfolio_experiment.json")
    parser.add_argument("--no-live106", action="store_true")
    args = parser.parse_args()

    df = load_dataframe(include_live106=not args.no_live106)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)

    report = {
        "experiment": "V4.6 扩展候选池受控重叠组合",
        "history_draws": int(len(df)),
        "live106_holdout_only": not args.no_live106,
        "configs": {},
    }
    for pool_size in (12, 13, 14):
        result = evaluate(df, red_pred, red_occ, pool_size, blue_models, blue_base)
        config = {seg: summary(result, lo, hi) for seg, (lo, hi) in SEGMENTS.items()}
        config["full"] = summary(result, START + 1, int(df.iloc[-1]["seq"]))
        report["configs"][f"pool{pool_size}"] = config

    base = report["configs"]["pool12"]
    promotion = {}
    for name in ("pool13", "pool14"):
        cur = report["configs"][name]
        nonworse = True
        red4_wins = 0
        for seg in ("validation_1", "validation_2", "forward55"):
            nonworse &= cur[seg]["max_red_hit_mean"] >= base[seg]["max_red_hit_mean"] - 1e-12
            nonworse &= cur[seg]["red_5plus_draws"] >= base[seg]["red_5plus_draws"]
            red4_wins += int(cur[seg]["red_4plus_draws"] >= base[seg]["red_4plus_draws"])
        promotion[name] = {
            "stable_red_gate": bool(nonworse and red4_wins >= 2),
            "red4_nonworse_segments": int(red4_wins),
            "full_fixed_return_delta": int(cur["full"]["fixed_return_yuan"] - base["full"]["fixed_return_yuan"]),
            "forward55_fixed_return_delta": int(cur["forward55"]["fixed_return_yuan"] - base["forward55"]["fixed_return_yuan"]),
        }
    report["promotion"] = promotion

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
