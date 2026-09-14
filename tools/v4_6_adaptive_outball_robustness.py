from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
BLOCK = 250
MIN_ACTIVE_IN_BLOCK = 10


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


adapt = _load("v46_adaptive_gate", ROOT / "tools" / "v4_6_adaptive_outball_gate.py")
base_exp = adapt.base_exp
poollock = adapt.poollock
order_exp = adapt.order_exp
v4 = adapt.v4
v45 = adapt.v45


def _build_results():
    df = order_exp.load_dataframe()
    order = order_exp.load_order_matrix(df)
    base_pred, _, red_occ = v4.walk_forward_red(df)
    outball_pred, _, occ2 = order_exp.walk_forward_augmented(df, order)
    if not np.array_equal(red_occ, occ2):
        raise RuntimeError("红球发生矩阵不一致")

    signal = base_exp._signal_mask(base_pred, red_occ)
    base_low, _, _ = v45._baseline_red_groups(base_pred, red_occ, adapt.START)
    cand_pred = poollock.lock_pool_rerank(base_pred, outball_pred, 1.0)
    cand_low, _, _ = v45._baseline_red_groups(cand_pred, red_occ, adapt.START)
    high, _, _ = v45.build_red_portfolio(base_pred, red_occ, adapt.START)

    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, red_occ, actual, adapt.START)

    bstats = adapt._strategy_stats(df, base_low, red_occ, blue_base)
    cstats = adapt._strategy_stats(df, cand_low, red_occ, blue_base)
    use = adapt._adaptive_mask(signal, bstats, cstats)
    base_use = np.zeros(len(signal), dtype=bool)

    base = adapt.evaluate(df, signal, base_use, base_low, cand_low, high, red_occ, blue_base, blue_high)
    adaptive = adapt.evaluate(df, signal, use, base_low, cand_low, high, red_occ, blue_base, blue_high)
    return base, adaptive


def _stats(frame: pd.DataFrame) -> dict:
    tickets = pd.concat([frame.prize1, frame.prize2], ignore_index=True)
    return {
        "draws": int(len(frame)),
        "active_draws": int(frame.use_outball.sum()),
        "max_red_hit_mean": float(frame.max_red_hit.mean()) if len(frame) else 0.0,
        "red_4plus": int((frame.max_red_hit >= 4).sum()),
        "red_5plus": int((frame.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(adapt.FIXED_PRIZE[x] for x in tickets)),
    }


def _delta(base: dict, cand: dict) -> dict:
    return {
        "max_red_hit_mean_delta": float(cand["max_red_hit_mean"] - base["max_red_hit_mean"]),
        "red_4plus_delta": int(cand["red_4plus"] - base["red_4plus"]),
        "red_5plus_delta": int(cand["red_5plus"] - base["red_5plus"]),
        "fixed_return_delta": int(cand["fixed_return"] - base["fixed_return"]),
    }


def main() -> None:
    base, adaptive = _build_results()
    seq_min = int(base.seq.min())
    seq_max = int(base.seq.max())

    blocks = []
    strict_nonworse = 0
    effective_blocks = 0
    worst_fixed = 0
    any_red5_loss = False

    lo = seq_min
    while lo <= seq_max:
        hi = min(seq_max, lo + BLOCK - 1)
        b = base[(base.seq >= lo) & (base.seq <= hi)]
        c = adaptive[(adaptive.seq >= lo) & (adaptive.seq <= hi)]
        bs, cs = _stats(b), _stats(c)
        d = _delta(bs, cs)
        effective = cs["active_draws"] >= MIN_ACTIVE_IN_BLOCK
        nonworse = (
            d["max_red_hit_mean_delta"] >= -1e-12
            and d["red_5plus_delta"] >= 0
            and d["fixed_return_delta"] >= 0
        )
        if effective:
            effective_blocks += 1
            strict_nonworse += int(nonworse)
            worst_fixed = min(worst_fixed, d["fixed_return_delta"])
            any_red5_loss |= d["red_5plus_delta"] < 0
        blocks.append({
            "seq_range": [int(lo), int(hi)],
            "effective": bool(effective),
            "base": bs,
            "adaptive": cs,
            "delta": d,
            "strict_nonworse": bool(nonworse),
        })
        lo += BLOCK

    full_base = _stats(base)
    full_adaptive = _stats(adaptive)
    full_delta = _delta(full_base, full_adaptive)
    ratio = strict_nonworse / effective_blocks if effective_blocks else 0.0

    # 门槛在看结果前固定：
    # 1) 全样本平均命中、4红、5红、固定回报均不退化；
    # 2) 至少80%的有效250期分块严格不退化；
    # 3) 不允许任一有效分块损失5红，或固定回报单块下降超过100元。
    full_nonworse = (
        full_delta["max_red_hit_mean_delta"] >= -1e-12
        and full_delta["red_4plus_delta"] >= 0
        and full_delta["red_5plus_delta"] >= 0
        and full_delta["fixed_return_delta"] >= 0
    )
    passed = bool(
        full_nonworse
        and ratio >= 0.80
        and not any_red5_loss
        and worst_fixed >= -100
        and effective_blocks >= 3
    )

    report = {
        "experiment": "V4.5.1低信号出球顺序因果门全历史分块鲁棒性审计",
        "block_size": BLOCK,
        "minimum_active_draws_per_block": MIN_ACTIVE_IN_BLOCK,
        "criteria": {
            "full_sample_nonworse": "平均命中、4红、5红、固定回报四项均不退化",
            "strict_nonworse_block_ratio": 0.80,
            "no_red5_loss_in_effective_block": True,
            "worst_fixed_return_delta_floor": -100,
            "minimum_effective_blocks": 3,
        },
        "full": {
            "base": full_base,
            "adaptive": full_adaptive,
            "delta": full_delta,
            "nonworse": bool(full_nonworse),
        },
        "effective_blocks": int(effective_blocks),
        "strict_nonworse_blocks": int(strict_nonworse),
        "strict_nonworse_ratio": float(ratio),
        "worst_effective_block_fixed_return_delta": int(worst_fixed),
        "any_effective_block_red5_loss": bool(any_red5_loss),
        "blocks": blocks,
        "promotion": {"pass": passed},
    }

    out = ROOT / "backtests" / "v4_6" / "adaptive_outball_robustness.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
