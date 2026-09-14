from __future__ import annotations

import json
from pathlib import Path

import numpy as np

import v4_7_research as r
import v4_7_research_strict as s
import v4_7_fast_repair as f

ROOT = Path(__file__).resolve().parents[1]
WINDOW = 100
MIN_HISTORY = 50
DELTA_FOR_HALF_WEIGHT = 0.10
MAX_FAST_WEIGHT = 0.50
EXPECTED_DEV_FIXED_RETURN = 4760


def causal_blend(stable: np.ndarray, fast: np.ndarray, occ: np.ndarray):
    stable_hits = f._topk_hits_series(stable, occ, 10)
    fast_hits = f._topk_hits_series(fast, occ, 10)
    out = stable.copy()
    weights = np.zeros(len(stable), dtype=float)
    deltas = np.zeros(len(stable), dtype=float)

    for t in range(r.START, len(stable)):
        left = max(r.START, t - WINDOW)
        a = stable_hits[left:t]
        b = fast_hits[left:t]
        valid = np.isfinite(a) & np.isfinite(b)
        if int(valid.sum()) < MIN_HISTORY:
            continue
        delta = float(b[valid].mean() - a[valid].mean())
        deltas[t] = delta
        # 快模型领先0.10个红球/期时最多给50%权重；领先更小则线性收缩，落后则完全不用。
        w = float(np.clip(delta / DELTA_FOR_HALF_WEIGHT * MAX_FAST_WEIGHT, 0.0, MAX_FAST_WEIGHT))
        weights[t] = w
        if w <= 0:
            continue
        slow_rank = r.v4._rank_score(stable[t])
        fast_rank = r.v4._rank_score(fast[t])
        out[t] = (1.0 - w) * slow_rank + w * fast_rank
    return out, weights, deltas


def main() -> None:
    df = r.load_current_dataframe()
    dev_end = int(r.history_tool.load_manifest()["frozen_base_draws"])
    stable, _, occ = r.v4.walk_forward_red(df)
    fast = f.walk_fast_red(df)
    blend, weights, deltas = causal_blend(stable, fast, occ)

    blue_models = r.v45.build_blue_models(df)
    baseline = s._evaluate_strict(df, stable, occ, blue_models, "standard", False)
    candidate = s._evaluate_strict(df, blend, occ, blue_models, "standard", False)

    base_dev = r._summary(baseline, r.START, dev_end)
    if base_dev["fixed_return"] != EXPECTED_DEV_FIXED_RETURN:
        raise RuntimeError("V4.5.1基线复现失败")

    cand_dev = r._summary(candidate, r.START, dev_end)
    base_forward = r._summary(baseline, dev_end, len(df))
    cand_forward = r._summary(candidate, dev_end, len(df))
    base_q = r._quarters(baseline, r.START, dev_end)
    cand_q = r._quarters(candidate, r.START, dev_end)
    quarter_ok = sum(int(c["fixed_return"] >= b["fixed_return"]) for c, b in zip(cand_q, base_q))

    dev_ok = (
        cand_dev["fixed_return"] > base_dev["fixed_return"]
        and cand_dev["max_red_4plus"] >= base_dev["max_red_4plus"]
        and cand_dev["max_red_5plus"] >= base_dev["max_red_5plus"]
        and quarter_ok >= 3
    )
    forward_ok = (
        cand_forward["fixed_return"] >= base_forward["fixed_return"]
        and cand_forward["max_red_4plus"] >= base_forward["max_red_4plus"]
    )

    report = {
        "status": "research_complete",
        "principle": "快慢模型只按开奖前过去100期表现差连续融合；快模型落后时权重归零，领先0.10时最多占50%",
        "weighting": {
            "window": WINDOW,
            "max_fast_weight": MAX_FAST_WEIGHT,
            "development_active_rate": float((weights[r.START:dev_end] > 0).mean()),
            "development_mean_weight": float(weights[r.START:dev_end].mean()),
            "forward_active_rate": float((weights[dev_end:] > 0).mean()),
            "forward_mean_weight": float(weights[dev_end:].mean()),
            "forward_mean_delta": float(deltas[dev_end:].mean()),
        },
        "red_pool": {
            "baseline_development": r._red_pool_metrics(stable, occ, r.START, dev_end),
            "blend_development": r._red_pool_metrics(blend, occ, r.START, dev_end),
            "baseline_forward": r._red_pool_metrics(stable, occ, dev_end, len(df)),
            "blend_forward": r._red_pool_metrics(blend, occ, dev_end, len(df)),
        },
        "baseline": {
            "development": base_dev,
            "quarters": base_q,
            "forward": base_forward,
        },
        "candidate": {
            "development": cand_dev,
            "quarters": cand_q,
            "forward": cand_forward,
        },
        "promotion_gate": {
            "development_pass": bool(dev_ok),
            "development_quarters_not_worse": int(quarter_ok),
            "forward_pass": bool(forward_ok),
            "promote": bool(dev_ok and forward_ok),
        },
    }

    out = ROOT / "backtests" / "v4_7" / "continuous_blend_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
