from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
BLOCK = 250
WEIGHT = 0.50
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


exp = _load("v46_low_order_robust50_base", ROOT / "tools" / "v4_6_low_signal_outball.py")
poollock = exp.poollock
order_exp = exp.order_exp
v4 = exp.v4
v45 = exp.v45


def _summary(part: pd.DataFrame) -> dict:
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


def _delta(base: dict, cand: dict) -> dict:
    return {
        "max_red_hit_mean": float(cand["max_red_hit_mean"] - base["max_red_hit_mean"]),
        "red_4plus": int(cand["red_4plus"] - base["red_4plus"]),
        "red_5plus": int(cand["red_5plus"] - base["red_5plus"]),
        "fixed_return": int(cand["fixed_return"] - base["fixed_return"]),
        "winning_draws": int(cand["winning_draws"] - base["winning_draws"]),
    }


def main() -> None:
    df = order_exp.load_dataframe()
    order = order_exp.load_order_matrix(df)
    base_pred, _, red_occ = v4.walk_forward_red(df)
    outball_pred, _, occ2 = order_exp.walk_forward_augmented(df, order)
    if not np.array_equal(red_occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")

    signal = exp._signal_mask(base_pred, red_occ)
    base_low, _, _ = v45._baseline_red_groups(base_pred, red_occ, START)
    cand_pred = poollock.lock_pool_rerank(base_pred, outball_pred, WEIGHT)
    cand_low, _, _ = v45._baseline_red_groups(cand_pred, red_occ, START)
    high, _, _ = v45.build_red_portfolio(base_pred, red_occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, red_occ, actual_blue, START)

    base = exp.evaluate(df, red_occ, signal, base_low, high, blue_base, blue_high)
    cand = exp.evaluate(df, red_occ, signal, cand_low, high, blue_base, blue_high)
    max_seq = int(df.iloc[-2]["seq"])

    blocks = []
    eligible = []
    for lo in range(START + 1, max_seq + 1, BLOCK):
        hi = min(max_seq, lo + BLOCK - 1)
        bs = _summary(base[(base.seq >= lo) & (base.seq <= hi)])
        cs = _summary(cand[(cand.seq >= lo) & (cand.seq <= hi)])
        if bs["low_draws"] == 0:
            blocks.append({"lo": lo, "hi": hi, "low_draws": 0})
            continue
        d = _delta(bs, cs)
        row = {"lo": lo, "hi": hi, "low_draws": bs["low_draws"], "base": bs, "candidate": cs, "delta": d}
        blocks.append(row)
        if bs["low_draws"] >= 20:
            eligible.append(row)

    nonworse = [b for b in eligible if b["delta"]["max_red_hit_mean"] >= -1e-12 and b["delta"]["red_5plus"] >= 0 and b["delta"]["fixed_return"] >= 0]
    positive = [b for b in eligible if b["delta"]["max_red_hit_mean"] > 1e-12 or b["delta"]["red_4plus"] > 0 or b["delta"]["red_5plus"] > 0 or b["delta"]["fixed_return"] > 0]

    all_base = _summary(base[base.seq <= max_seq])
    all_cand = _summary(cand[cand.seq <= max_seq])
    all_delta = _delta(all_base, all_cand)

    report = {
        "experiment": "低信号50%出球顺序池内重排鲁棒性",
        "candidate": "low_poollock50",
        "weight": WEIGHT,
        "low_signal_all": {"base": all_base, "candidate": all_cand, "delta": all_delta},
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
    out = ROOT / "backtests" / "v4_6" / "low_signal_outball_robustness50.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
