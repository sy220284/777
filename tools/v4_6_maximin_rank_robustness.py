from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
BLOCK = 250
MIN_EFFECTIVE_LOW_DRAWS = 10
ELIGIBLE_SEQ = 1751  # START=750 + 1000期完整历史后才允许maximin接管


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


exp = _load("v46_maximin_robust_exp", ROOT / "tools" / "v4_6_maximin_rank_portfolio.py")
rankpat = exp.rankpat
v4 = exp.v4
v45 = exp.v45


def build_results():
    df = rankpat.load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(pred, occ)
    base_low, _, _ = v45._baseline_red_groups(pred, occ, exp.START)
    maximin_low = exp.build_maximin_groups(pred, occ, base_low)
    high, _, _ = v45.build_red_portfolio(pred, occ, exp.START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual, exp.START)
    base = rankpat.evaluate(df, pred, occ, base_low, high, signal, blue_base, blue_high)
    cand = rankpat.evaluate(df, pred, occ, maximin_low, high, signal, blue_base, blue_high)
    return base, cand


def stats(frame: pd.DataFrame) -> dict:
    tickets = pd.concat([frame.prize1, frame.prize2], ignore_index=True)
    eligible_low = frame[(frame.seq >= ELIGIBLE_SEQ) & (~frame.signal_active)]
    return {
        "draws": int(len(frame)),
        "eligible_low_signal_draws": int(len(eligible_low)),
        "max_red_hit_mean": float(frame.max_red_hit.mean()) if len(frame) else 0.0,
        "red_4plus": int((frame.max_red_hit >= 4).sum()),
        "red_5plus": int((frame.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(rankpat.FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((frame.best_prize != "未中奖").mean()) if len(frame) else 0.0,
    }


def delta(base: dict, cand: dict) -> dict:
    return {
        "max_red_hit_mean_delta": float(cand["max_red_hit_mean"] - base["max_red_hit_mean"]),
        "red_4plus_delta": int(cand["red_4plus"] - base["red_4plus"]),
        "red_5plus_delta": int(cand["red_5plus"] - base["red_5plus"]),
        "fixed_return_delta": int(cand["fixed_return"] - base["fixed_return"]),
        "winning_draw_rate_delta": float(cand["winning_draw_rate"] - base["winning_draw_rate"]),
    }


def main() -> None:
    base, cand = build_results()
    seq_min, seq_max = int(base.seq.min()), int(base.seq.max())
    blocks = []
    effective = 0
    strict = 0
    worst_fixed = 0
    any_red5_loss = False

    lo = seq_min
    while lo <= seq_max:
        hi = min(seq_max, lo + BLOCK - 1)
        b = base[(base.seq >= lo) & (base.seq <= hi)]
        c = cand[(cand.seq >= lo) & (cand.seq <= hi)]
        bs, cs = stats(b), stats(c)
        d = delta(bs, cs)
        is_effective = cs["eligible_low_signal_draws"] >= MIN_EFFECTIVE_LOW_DRAWS
        nonworse = (
            d["max_red_hit_mean_delta"] >= -1e-12
            and d["red_5plus_delta"] >= 0
            and d["fixed_return_delta"] >= 0
        )
        if is_effective:
            effective += 1
            strict += int(nonworse)
            worst_fixed = min(worst_fixed, d["fixed_return_delta"])
            any_red5_loss |= d["red_5plus_delta"] < 0
        blocks.append({
            "seq_range": [int(lo), int(hi)],
            "effective": bool(is_effective),
            "base": bs,
            "maximin": cs,
            "delta": d,
            "strict_nonworse": bool(nonworse),
        })
        lo += BLOCK

    fb, fc = stats(base), stats(cand)
    fd = delta(fb, fc)
    ratio = strict / effective if effective else 0.0
    full_nonworse = (
        fd["max_red_hit_mean_delta"] >= -1e-12
        and fd["red_4plus_delta"] >= 0
        and fd["red_5plus_delta"] >= 0
        and fd["fixed_return_delta"] >= 0
        and fd["winning_draw_rate_delta"] >= -1e-12
    )

    # 与此前adaptive-outball鲁棒性标准保持同级，不因初筛漂亮而放宽。
    passed = bool(
        full_nonworse
        and effective >= 3
        and ratio >= 0.80
        and not any_red5_loss
        and worst_fixed >= -100
    )
    report = {
        "experiment": "V4.5.1低信号maximin分组全历史分块鲁棒性审计",
        "criteria": {
            "block_size": BLOCK,
            "minimum_effective_low_draws": MIN_EFFECTIVE_LOW_DRAWS,
            "strict_nonworse_block_ratio": 0.80,
            "no_red5_loss_in_effective_block": True,
            "worst_fixed_return_delta_floor": -100,
            "full_sample": "平均命中、4红、5红、固定回报、中奖期率均不退化",
        },
        "full": {"base": fb, "maximin": fc, "delta": fd, "nonworse": bool(full_nonworse)},
        "effective_blocks": int(effective),
        "strict_nonworse_blocks": int(strict),
        "strict_nonworse_ratio": float(ratio),
        "worst_effective_block_fixed_return_delta": int(worst_fixed),
        "any_effective_block_red5_loss": bool(any_red5_loss),
        "blocks": blocks,
        "promotion": {"pass": passed},
    }
    out = ROOT / "backtests" / "v4_6" / "maximin_rank_robustness.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
