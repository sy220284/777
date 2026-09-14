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


v4 = _load_module("v46f_v4", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("v46f_v43", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("v46f_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load_module("v46f_history", ROOT / "tools" / "load_history.py")

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
    df = pd.DataFrame([{ "seq": int(r["seq"]), **{f"red{i}": int(r["red"][i-1]) for i in range(1,7)}, "blue": int(r["blue"]) } for r in rows])
    if include_live106 and int(df.iloc[-1]["seq"]) == 3502:
        df = pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)
    return df


def _signal_mask(red_pred: np.ndarray, red_occ: np.ndarray):
    hits = np.full(len(red_pred), np.nan)
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1][:12]
        hits[t] = int(red_occ[t, order].sum())
    active = np.zeros(len(red_pred), dtype=bool)
    means = np.full(len(red_pred), np.nan)
    for t in range(START, len(red_pred)):
        past = hits[max(START, t - SIGNAL_WINDOW):t]
        past = past[np.isfinite(past)]
        if len(past):
            means[t] = float(past.mean())
        active[t] = len(past) >= SIGNAL_MIN_HISTORY and means[t] >= RANDOM_TOP12_MEAN
    return active, means


def _current_v43_groups(red_pred: np.ndarray, red_occ: np.ndarray):
    groups, _, _ = v45._baseline_red_groups(red_pred, red_occ, START)
    return groups


def _always_pair_groups(red_pred: np.ndarray, red_occ: np.ndarray):
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    groups = {}
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1]
        a, b, _ = v43._pair_partition(order[:12], v43._pair_affinity(t, pair_cs, marg_cs))
        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a
        groups[t] = (a, b)
    return groups


def _always_split_groups(red_pred: np.ndarray):
    groups = {}
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1]
        groups[t] = (order[:6], order[6:12])
    return groups


def _zscore(x: np.ndarray) -> np.ndarray:
    return (x - x.mean()) / (x.std() + 1e-9)


def _pool13_omit_groups(red_pred: np.ndarray, red_occ: np.ndarray):
    """从前13中因果排除1个，再把剩余12个按V4.3两两关系拆成两张互斥票。"""
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    groups = {}
    for t in range(START, len(red_pred)):
        order13 = np.argsort(red_pred[t])[::-1][:13]
        affinity = v43._pair_affinity(t, pair_cs, marg_cs)
        partition_quality = []
        marginal_total = []
        choices = []
        for omit in range(13):
            remaining = np.delete(order13, omit)
            values = affinity[remaining[v43.LOCAL_PAIRS[:, 0]], remaining[v43.LOCAL_PAIRS[:, 1]]]
            scores = v43.SAME_GROUP @ values
            best = int(np.argmax(scores))
            mask = v43.PARTS[best]
            a, b = remaining[mask], remaining[~mask]
            partition_quality.append(float(scores[best]))
            marginal_total.append(float(red_pred[t, remaining].sum()))
            choices.append((a, b))
        combined = 0.8 * _zscore(np.asarray(partition_quality)) + 1.2 * _zscore(np.asarray(marginal_total))
        idx = int(np.argmax(combined))
        a, b = choices[idx]
        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a
        groups[t] = (a, b)
    return groups


def _best(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def evaluate(df, red_pred, red_occ, fallback_groups, high_groups, blue_base, blue_high, signal_active):
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal_active[t])
        a, b = (high_groups if high else fallback_groups)[t]
        blue_score = blue_high[t] if high else blue_base[t]
        bo = np.argsort(blue_score)[::-1]
        b1, b2 = int(bo[0]+1), int(bo[1]+1)
        h1, h2 = int(red_occ[t,a].sum()), int(red_occ[t,b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "max_red_hit": max(h1,h2),
            "hit1": h1,
            "hit2": h2,
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1,p2),
        })
    return pd.DataFrame(rows)


def summary(result: pd.DataFrame, lo: int, hi: int):
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    if part.empty:
        return {"draws": 0}
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    best = part.best_prize.value_counts().to_dict()
    ticket_counts = tickets.value_counts().to_dict()
    fixed = int(sum(FIXED_PRIZE[p] for p in tickets))
    low = part[~part.signal_active]
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "low_signal_max_red_hit_mean": float(low.max_red_hit.mean()) if len(low) else None,
        "red_4plus_draws": int((part.max_red_hit >= 4).sum()),
        "red_5plus_draws": int((part.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "fixed_return_ratio": float(fixed/(len(part)*4)),
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
        "best_prize_counts": {k:int(best.get(k,0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k:int(ticket_counts.get(k,0)) for k in PRIZE_ORDER},
    }


def main():
    p = argparse.ArgumentParser(description="V4.6 低信号回退组合实验")
    p.add_argument("--out", default="backtests/v4_6/fallback_experiment.json")
    p.add_argument("--no-live106", action="store_true")
    args = p.parse_args()

    df = load_dataframe(not args.no_live106)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high_groups, red_occ, actual_blue, START)
    signal_active, signal_mean = _signal_mask(red_pred, red_occ)

    fallbacks = {
        "v43_current": _current_v43_groups(red_pred, red_occ),
        "always_pair": _always_pair_groups(red_pred, red_occ),
        "always_split": _always_split_groups(red_pred),
        "pool13_omit1_pair": _pool13_omit_groups(red_pred, red_occ),
    }

    report = {
        "experiment": "V4.6 低信号V4.3回退优化",
        "history_draws": int(len(df)),
        "live106_holdout_only": not args.no_live106,
        "configs": {},
    }
    for name, groups in fallbacks.items():
        result = evaluate(df, red_pred, red_occ, groups, high_groups, blue_base, blue_high, signal_active)
        cfg = {seg: summary(result, lo, hi) for seg,(lo,hi) in SEGMENTS.items()}
        cfg["full"] = summary(result, START+1, int(df.iloc[-1]["seq"]))
        report["configs"][name] = cfg

    base = report["configs"]["v43_current"]
    promotion = {}
    for name in ("always_pair","always_split","pool13_omit1_pair"):
        cur = report["configs"][name]
        stable = True
        for seg in ("validation_1","validation_2","forward55"):
            stable &= cur[seg]["low_signal_max_red_hit_mean"] >= base[seg]["low_signal_max_red_hit_mean"] - 1e-12
            stable &= cur[seg]["red_5plus_draws"] >= base[seg]["red_5plus_draws"]
        promotion[name] = {
            "stable_low_signal_gate": bool(stable),
            "forward55_red_hit_delta": float(cur["forward55"]["max_red_hit_mean"] - base["forward55"]["max_red_hit_mean"]),
            "forward55_fixed_return_delta": int(cur["forward55"]["fixed_return_yuan"] - base["forward55"]["fixed_return_yuan"]),
            "full_fixed_return_delta": int(cur["full"]["fixed_return_yuan"] - base["full"]["fixed_return_yuan"]),
        }
    report["promotion"] = promotion

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
