from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import v4_7_research as r
import v4_7_research_strict as s
import v4_7_expert_expansion as e
import v4_7_adaptive_router as a

ROOT = Path(__file__).resolve().parents[1]
WINDOW = 250
MIN_HISTORY = 100
EXPECTED_DEV_FIXED_RETURN = 4760


def _past_mean(values: np.ndarray, i: int) -> float:
    left = max(0, i - WINDOW)
    part = values[left:i]
    if len(part) < MIN_HISTORY:
        return float("nan")
    return float(np.mean(part))


def guarded_router(
    baseline: pd.DataFrame,
    recent: pd.DataFrame,
) -> tuple[pd.DataFrame, np.ndarray]:
    base_prize = baseline["best_prize"].map(a.PRIZE_POINTS).to_numpy(float)
    recent_prize = recent["best_prize"].map(a.PRIZE_POINTS).to_numpy(float)
    base_red = baseline["max_red_hit"].to_numpy(float)
    recent_red = recent["max_red_hit"].to_numpy(float)
    base_4 = (base_red >= 4).astype(float)
    recent_4 = (recent_red >= 4).astype(float)

    active = np.zeros(len(baseline), dtype=bool)
    rows = []
    for i in range(len(baseline)):
        bp = _past_mean(base_prize, i)
        rp = _past_mean(recent_prize, i)
        br = _past_mean(base_red, i)
        rr = _past_mean(recent_red, i)
        b4 = _past_mean(base_4, i)
        r4 = _past_mean(recent_4, i)
        choose = bool(
            np.isfinite(bp)
            and rp > bp
            and rr >= br
            and r4 >= b4
        )
        active[i] = choose
        rows.append((recent if choose else baseline).iloc[i].to_dict())
    return pd.DataFrame(rows), active


def main() -> None:
    df = r.load_current_dataframe()
    dev_end = int(r.history_tool.load_manifest()["frozen_base_draws"])
    baseline_red, base_raw, occ = r.v4.walk_forward_red(df)
    x, y, t_index, _ = r.v4.build_red_features(df)

    raw_recent = dict(base_raw)
    raw_recent["w250"] = e._fit_logistic_expert(x, y, t_index, len(df), 250)
    recent_red = e._ensemble(raw_recent, occ)

    blue_models = r.v45.build_blue_models(df)
    baseline = s._evaluate_strict(df, baseline_red, occ, blue_models, "standard", False)
    recent = s._evaluate_strict(df, recent_red, occ, blue_models, "standard", False)
    if r._summary(baseline, r.START, dev_end)["fixed_return"] != EXPECTED_DEV_FIXED_RETURN:
        raise RuntimeError("V4.5.1基线复现失败")

    candidate, active = guarded_router(baseline, recent)
    base_dev = r._summary(baseline, r.START, dev_end)
    cand_dev = r._summary(candidate, r.START, dev_end)
    base_q = r._quarters(baseline, r.START, dev_end)
    cand_q = r._quarters(candidate, r.START, dev_end)
    base_forward = r._summary(baseline, dev_end, len(df))
    cand_forward = r._summary(candidate, dev_end, len(df))

    t = candidate["t"].to_numpy(int)
    q_ok = sum(int(c["fixed_return"] >= b["fixed_return"]) for c, b in zip(cand_q, base_q))
    dev_ok = (
        cand_dev["fixed_return"] > base_dev["fixed_return"]
        and cand_dev["max_red_4plus"] >= base_dev["max_red_4plus"]
        and cand_dev["max_red_5plus"] >= base_dev["max_red_5plus"]
        and q_ok >= 3
    )
    forward_ok = (
        cand_forward["fixed_return"] >= base_forward["fixed_return"]
        and cand_forward["max_red_4plus"] >= base_forward["max_red_4plus"]
        and cand_forward["max_red_5plus"] >= base_forward["max_red_5plus"]
    )

    report = {
        "status": "research_complete",
        "principle": "近250专家只有在过去250期封顶奖级积分更高、平均最大红球命中不低、4红以上频率不低时才接管整套两注",
        "router": {
            "window": WINDOW,
            "development_recent_rate": float(active[t < dev_end].mean()),
            "forward_recent_rate": float(active[t >= dev_end].mean()),
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
            "development_quarters_not_worse": int(q_ok),
            "forward_pass": bool(forward_ok),
            "promote": bool(dev_ok and forward_ok),
        },
    }

    out = ROOT / "backtests" / "v4_7" / "guarded_router_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
