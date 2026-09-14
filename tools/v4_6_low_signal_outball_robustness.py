from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
BLOCK = 250
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


exp = _load("v46_low_order_robust_base", ROOT / "tools" / "v4_6_low_signal_outball.py")
poollock = exp.poollock
order_exp = exp.order_exp
v4 = exp.v4
v45 = exp.v45


def _build_results():
    df = order_exp.load_dataframe()
    order = order_exp.load_order_matrix(df)
    base, _, red_occ = v4.walk_forward_red(df)
    outball, _, occ2 = order_exp.walk_forward_augmented(df, order)
    if not np.array_equal(red_occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")
    signal = exp._signal_mask(base, red_occ)

    base_low, _, _ = v45._baseline_red_groups(base, red_occ, START)
    cand_pred = poollock.lock_pool_rerank(base, outball, 1.0)
    cand_low, _, _ = v45._baseline_red_groups(cand_pred, red_occ, START)
    high, _, _ = v45.build_red_portfolio(base, red_occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, red_occ, actual_blue, START)

    base_result = exp.evaluate(df, red_occ, signal, base_low, high, blue_base, blue_high)
    cand_result = exp.evaluate(df, red_occ, signal, cand_low, high, blue_base, blue_high)
    return df, base_result, cand_result


def _low_summary(part: pd.DataFrame) -> dict:
    low = part[~part.signal_active]
    if low.empty:
        return {"low_draws": 0}
    tickets = pd.concat([low.prize1, low.prize2], ignore_index=True)
    return {
        "low_draws": int(len(low)),
        "max_red_hit_mean": float(low.max_red_hit.mean()),
        "red_4plus": int((low.max_red_hit >= 4).sum()),
        "red_5plus": int((low.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(FIXED_PRIZE[x] for x in tickets)),
        "winning_draws": int((low.best_prize != "未中奖").sum()),
    }


def main() -> None:
    df, base, cand = _build_results()
    merged = base[["seq", "signal_active", "max_red_hit", "prize1", "prize2", "best_prize"]].merge(
        cand[["seq", "max_red_hit", "prize1", "prize2", "best_prize"]],
        on="seq",
        suffixes=("_base", "_cand"),
    )
    low = merged[~merged.signal_active].copy()
    low["red_delta"] = low.max_red_hit_cand - low.max_red_hit_base
    low["fixed_base"] = low.prize1_base.map(FIXED_PRIZE) + low.prize2_base.map(FIXED_PRIZE)
    low["fixed_cand"] = low.prize1_cand.map(FIXED_PRIZE) + low.prize2_cand.map(FIXED_PRIZE)
    low["fixed_delta"] = low.fixed_cand - low.fixed_base

    blocks = []
    max_seq = int(df.iloc[-2]["seq"])  # 2026105；2026106只作为live留出，不进鲁棒性选型。
    for lo in range(START + 1, max_seq + 1, BLOCK):
        hi = min(max_seq, lo + BLOCK - 1)
        b0 = base[(base.seq >= lo) & (base.seq <= hi)]
        c0 = cand[(cand.seq >= lo) & (cand.seq <= hi)]
        bs = _low_summary(b0)
        cs = _low_summary(c0)
        if bs["low_draws"] == 0:
            blocks.append({"lo": lo, "hi": hi, "low_draws": 0})
            continue
        blocks.append({
            "lo": lo,
            "hi": hi,
            "low_draws": bs["low_draws"],
            "base": bs,
            "candidate": cs,
            "delta": {
                "max_red_hit_mean": float(cs["max_red_hit_mean"] - bs["max_red_hit_mean"]),
                "red_4plus": int(cs["red_4plus"] - bs["red_4plus"]),
                "red_5plus": int(cs["red_5plus"] - bs["red_5plus"]),
                "fixed_return": int(cs["fixed_return"] - bs["fixed_return"]),
                "winning_draws": int(cs["winning_draws"] - bs["winning_draws"]),
            },
        })

    eligible = [b for b in blocks if b.get("low_draws", 0) >= 20]
    nonworse = [b for b in eligible if b["delta"]["max_red_hit_mean"] >= -1e-12 and b["delta"]["red_5plus"] >= 0 and b["delta"]["fixed_return"] >= 0]
    positive = [b for b in eligible if b["delta"]["max_red_hit_mean"] > 1e-12 or b["delta"]["red_4plus"] > 0 or b["delta"]["red_5plus"] > 0 or b["delta"]["fixed_return"] > 0]

    all_base = _low_summary(base[base.seq <= max_seq])
    all_cand = _low_summary(cand[cand.seq <= max_seq])
    report = {
        "experiment": "低信号出球顺序池内重排鲁棒性",
        "candidate": "low_poollock100",
        "low_signal_all": {
            "base": all_base,
            "candidate": all_cand,
            "delta": {
                "max_red_hit_mean": float(all_cand["max_red_hit_mean"] - all_base["max_red_hit_mean"]),
                "red_4plus": int(all_cand["red_4plus"] - all_base["red_4plus"]),
                "red_5plus": int(all_cand["red_5plus"] - all_base["red_5plus"]),
                "fixed_return": int(all_cand["fixed_return"] - all_base["fixed_return"]),
                "winning_draws": int(all_cand["winning_draws"] - all_base["winning_draws"]),
            },
        },
        "pairwise": {
            "low_signal_draws": int(len(low)),
            "red_improved": int((low.red_delta > 0).sum()),
            "red_equal": int((low.red_delta == 0).sum()),
            "red_worsened": int((low.red_delta < 0).sum()),
            "red_delta_sum": int(low.red_delta.sum()),
            "fixed_improved": int((low.fixed_delta > 0).sum()),
            "fixed_equal": int((low.fixed_delta == 0).sum()),
            "fixed_worsened": int((low.fixed_delta < 0).sum()),
            "fixed_delta_sum": int(low.fixed_delta.sum()),
        },
        "blocks": blocks,
        "robustness": {
            "eligible_blocks_min20": int(len(eligible)),
            "nonworse_blocks": int(len(nonworse)),
            "positive_blocks": int(len(positive)),
            "nonworse_rate": float(len(nonworse) / len(eligible)) if eligible else 0.0,
            "pass": bool(
                len(eligible) >= 3
                and len(nonworse) / len(eligible) >= 0.67
                and all_cand["red_5plus"] >= all_base["red_5plus"]
                and all_cand["fixed_return"] >= all_base["fixed_return"]
                and all_cand["max_red_hit_mean"] >= all_base["max_red_hit_mean"] - 1e-12
            ),
        },
    }
    out = ROOT / "backtests" / "v4_6" / "low_signal_outball_robustness.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
