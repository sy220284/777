from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
SIGNAL_WINDOW = 250
SIGNAL_MIN_HISTORY = 50
RANDOM_TOP12_MEAN = 6 * 12 / 33
SEGMENTS = {
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


corrected = _load("v46_poollock_corrected", ROOT / "tools" / "v4_6_outball_corrected.py")
order_exp = corrected.exp
v4 = order_exp.v4
v43 = _load("v46_poollock_v43", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load("v46_poollock_v45", ROOT / "models" / "v4_5" / "predictor.py")


def _rank01(x: np.ndarray) -> np.ndarray:
    order = np.argsort(x)
    rank = np.empty(len(x), dtype=float)
    rank[order] = np.arange(len(x), dtype=float)
    return rank / max(1, len(x) - 1)


def lock_pool_rerank(base: np.ndarray, outball: np.ndarray, weight: float) -> np.ndarray:
    """V4决定前12集合；出球顺序只允许改变这12个号码内部的相对强弱。"""
    result = np.full_like(base, np.nan, dtype=float)
    for t in range(len(base)):
        if not (np.isfinite(base[t]).all() and np.isfinite(outball[t]).all()):
            continue
        base_order = np.argsort(base[t])[::-1]
        pool = base_order[:12]
        outside = base_order[12:]
        b = _rank01(base[t, pool])
        o = _rank01(outball[t, pool])
        inside = (1.0 - weight) * b + weight * o
        # 前12始终严格高于池外；池外顺序完全沿用V4。
        result[t, pool] = 1.0 + inside
        if len(outside):
            outside_rank = np.linspace(0.0, 0.9, len(outside), endpoint=True)
            result[t, outside[::-1]] = outside_rank
        if set(np.argsort(result[t])[::-1][:12].tolist()) != set(pool.tolist()):
            raise RuntimeError(f"前12候选池锁定失败: t={t}")
    return result


def _signal_mask(base: np.ndarray, red_occ: np.ndarray) -> np.ndarray:
    hits = np.full(len(base), np.nan)
    for t in range(START, len(base)):
        if not np.isfinite(base[t]).all():
            continue
        pool = np.argsort(base[t])[::-1][:12]
        hits[t] = int(red_occ[t, pool].sum())
    active = np.zeros(len(base), dtype=bool)
    for t in range(START, len(base)):
        past = hits[max(START, t - SIGNAL_WINDOW):t]
        past = past[np.isfinite(past)]
        active[t] = len(past) >= SIGNAL_MIN_HISTORY and float(past.mean()) >= RANDOM_TOP12_MEAN
    return active


def _best(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def evaluate(df: pd.DataFrame, red_pred: np.ndarray, red_occ: np.ndarray, signal_active: np.ndarray) -> pd.DataFrame:
    low_groups, _, _ = v45._baseline_red_groups(red_pred, red_occ, START)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high_groups, red_occ, actual_blue, START)

    rows = []
    for t in range(START, len(df)):
        high = bool(signal_active[t])
        a, b = (high_groups if high else low_groups)[t]
        blue_score = blue_high[t] if high else blue_base[t]
        bo = np.argsort(blue_score)[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "hit1": h1,
            "hit2": h2,
            "max_red_hit": max(h1, h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1, p2),
            "overlap": int(len(set(a.tolist()) & set(b.tolist()))),
        })
    return pd.DataFrame(rows)


def summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    best = part.best_prize.value_counts().to_dict()
    ticket_counts = tickets.value_counts().to_dict()
    fixed = int(sum(FIXED_PRIZE[p] for p in tickets))
    return {
        "draws": int(len(part)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "red_4plus_draws": int((part.max_red_hit >= 4).sum()),
        "red_5plus_draws": int((part.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
        "mean_overlap": float(part.overlap.mean()),
        "best_prize_counts": {k: int(best.get(k, 0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k: int(ticket_counts.get(k, 0)) for k in PRIZE_ORDER},
    }


def main() -> None:
    df = order_exp.load_dataframe()
    order = order_exp.load_order_matrix(df)
    base, _, red_occ = v4.walk_forward_red(df)
    outball, _, occ2 = order_exp.walk_forward_augmented(df, order)
    if not np.array_equal(red_occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")
    signal_active = _signal_mask(base, red_occ)

    preds = {
        "base": base,
        "poollock25": lock_pool_rerank(base, outball, 0.25),
        "poollock50": lock_pool_rerank(base, outball, 0.50),
        "poollock100": lock_pool_rerank(base, outball, 1.00),
    }
    report = {
        "experiment": "V4前12锁定+出球顺序池内集中",
        "rule": "候选池始终由V4决定，出球顺序只改变池内相对强弱；2026106真实出球顺序不参与特征",
        "configs": {},
        "promotion": {},
    }
    for name, pred in preds.items():
        result = evaluate(df, pred, red_occ, signal_active)
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in SEGMENTS.items()}

    base_cfg = report["configs"]["base"]
    for name in ("poollock25", "poollock50", "poollock100"):
        cur = report["configs"][name]
        checks = {}
        all_nonworse = True
        positive_segments = 0
        for seg in ("validation_1", "validation_2", "forward55"):
            dmean = cur[seg]["max_red_hit_mean"] - base_cfg[seg]["max_red_hit_mean"]
            d4 = cur[seg]["red_4plus_draws"] - base_cfg[seg]["red_4plus_draws"]
            d5 = cur[seg]["red_5plus_draws"] - base_cfg[seg]["red_5plus_draws"]
            dret = cur[seg]["fixed_return_yuan"] - base_cfg[seg]["fixed_return_yuan"]
            nonworse = dmean >= -1e-12 and d5 >= 0 and dret >= 0
            improved = dmean > 1e-12 or d4 > 0 or d5 > 0 or dret > 0
            checks[seg] = {
                "max_red_hit_mean_delta": float(dmean),
                "red_4plus_delta": int(d4),
                "red_5plus_delta": int(d5),
                "fixed_return_delta": int(dret),
                "nonworse": bool(nonworse),
                "improved": bool(improved),
            }
            all_nonworse &= nonworse
            positive_segments += int(improved)
        report["promotion"][name] = {
            "pass": bool(all_nonworse and positive_segments >= 2),
            "positive_segments": int(positive_segments),
            "checks": checks,
        }

    out = ROOT / "backtests" / "v4_6" / "outball_poollock.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
