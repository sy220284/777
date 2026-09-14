from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
WINDOW = 250
MIN_HISTORY = 50
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


poollock = _load("v46_low_order_poollock", ROOT / "tools" / "v4_6_outball_poollock.py")
order_exp = poollock.order_exp
v4 = poollock.v4
v45 = poollock.v45


def _signal_mask(base: np.ndarray, red_occ: np.ndarray) -> np.ndarray:
    hits = np.full(len(base), np.nan)
    for t in range(START, len(base)):
        if np.isfinite(base[t]).all():
            p = np.argsort(base[t])[::-1][:12]
            hits[t] = int(red_occ[t, p].sum())
    active = np.zeros(len(base), dtype=bool)
    for t in range(START, len(base)):
        past = hits[max(START, t - WINDOW):t]
        past = past[np.isfinite(past)]
        active[t] = len(past) >= MIN_HISTORY and float(past.mean()) >= RANDOM_TOP12_MEAN
    return active


def _best(a: str, b: str) -> str:
    return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def evaluate(df, red_occ, signal_active, low_groups, high_groups, blue_base, blue_high):
    actual_blue = df["blue"].to_numpy(int)
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
        })
    return pd.DataFrame(rows)


def summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    low = part[~part.signal_active]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    fixed = int(sum(FIXED_PRIZE[x] for x in tickets))
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "max_red_hit_mean": float(part.max_red_hit.mean()),
        "low_signal_max_red_hit_mean": float(low.max_red_hit.mean()) if len(low) else None,
        "red_4plus_draws": int((part.max_red_hit >= 4).sum()),
        "red_5plus_draws": int((part.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()),
    }


def main() -> None:
    df = order_exp.load_dataframe()
    order = order_exp.load_order_matrix(df)
    base, _, red_occ = v4.walk_forward_red(df)
    outball, _, occ2 = order_exp.walk_forward_augmented(df, order)
    if not np.array_equal(red_occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")
    signal_active = _signal_mask(base, red_occ)

    # 高信号阶段完全冻结生产版V4.5，只有低信号V4.3回退组合允许池内重排。
    base_low, _, _ = v45._baseline_red_groups(base, red_occ, START)
    base_high, _, _ = v45.build_red_portfolio(base, red_occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, base_high, red_occ, actual_blue, START)

    locked = {
        "base": base,
        "low_poollock25": poollock.lock_pool_rerank(base, outball, 0.25),
        "low_poollock50": poollock.lock_pool_rerank(base, outball, 0.50),
        "low_poollock100": poollock.lock_pool_rerank(base, outball, 1.00),
    }
    report = {
        "experiment": "V4.5.1低信号阶段出球顺序池内重排",
        "rule": "高信号阶段完全保持V4.5；仅低信号回退到V4.3时允许出球顺序重排V4原前12",
        "configs": {},
        "promotion": {},
    }
    for name, pred in locked.items():
        low_groups = base_low if name == "base" else v45._baseline_red_groups(pred, red_occ, START)[0]
        result = evaluate(df, red_occ, signal_active, low_groups, base_high, blue_base, blue_high)
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in SEGMENTS.items()}

    base_cfg = report["configs"]["base"]
    for name in ("low_poollock25", "low_poollock50", "low_poollock100"):
        cur = report["configs"][name]
        checks = {}
        all_nonworse = True
        positive_segments = 0
        for seg in ("validation_1", "validation_2", "forward55"):
            dmean = cur[seg]["max_red_hit_mean"] - base_cfg[seg]["max_red_hit_mean"]
            d4 = cur[seg]["red_4plus_draws"] - base_cfg[seg]["red_4plus_draws"]
            d5 = cur[seg]["red_5plus_draws"] - base_cfg[seg]["red_5plus_draws"]
            dret = cur[seg]["fixed_return_yuan"] - base_cfg[seg]["fixed_return_yuan"]
            # 没有低信号样本的段必须天然等价；有样本时要求不伤5红和固定回报。
            nonworse = dmean >= -1e-12 and d5 >= 0 and dret >= 0
            improved = dmean > 1e-12 or d4 > 0 or d5 > 0 or dret > 0
            checks[seg] = {
                "low_signal_draws": int(cur[seg]["low_signal_draws"]),
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
            "pass": bool(all_nonworse and positive_segments >= 1),
            "positive_segments": int(positive_segments),
            "checks": checks,
        }

    out = ROOT / "backtests" / "v4_6" / "low_signal_outball.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
