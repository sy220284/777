from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import v4_7_research as r
import v4_7_research_strict as s
import v4_7_expert_expansion as e

ROOT = Path(__file__).resolve().parents[1]
WINDOW = 250
MIN_HISTORY = 100
EXPECTED_DEV_FIXED_RETURN = 4760
PRIZE_POINTS = {
    "未中奖": 0.0,
    "六等奖": 1.0,
    "五等奖": 2.0,
    "四等奖": 4.0,
    "三等奖": 6.0,
    "二等奖": 6.0,
    "一等奖": 6.0,
}


def _rolling_mean(values: np.ndarray, t: int) -> float:
    left = max(r.START, t - WINDOW)
    part = values[left - r.START : t - r.START]
    if len(part) < MIN_HISTORY:
        return float("nan")
    return float(np.mean(part))


def _route(
    baseline: pd.DataFrame,
    recent: pd.DataFrame,
    mode: str,
) -> tuple[pd.DataFrame, np.ndarray]:
    if len(baseline) != len(recent):
        raise RuntimeError("策略结果长度不一致")

    if mode == "red_quality":
        base_metric = baseline["max_red_hit"].to_numpy(float)
        recent_metric = recent["max_red_hit"].to_numpy(float)
    elif mode == "capped_prize":
        base_metric = baseline["best_prize"].map(PRIZE_POINTS).to_numpy(float)
        recent_metric = recent["best_prize"].map(PRIZE_POINTS).to_numpy(float)
    else:
        raise ValueError(mode)

    rows = []
    active = np.zeros(len(baseline), dtype=bool)
    for i in range(len(baseline)):
        t = int(baseline.iloc[i]["t"])
        b = _rolling_mean(base_metric, t)
        q = _rolling_mean(recent_metric, t)
        choose_recent = np.isfinite(b) and np.isfinite(q) and q > b
        active[i] = bool(choose_recent)
        rows.append((recent if choose_recent else baseline).iloc[i].to_dict())
    return pd.DataFrame(rows), active


def _choose(
    baseline: pd.DataFrame,
    candidates: dict[str, pd.DataFrame],
    dev_end: int,
) -> str:
    base = r._summary(baseline, r.START, dev_end)
    base_q = r._quarters(baseline, r.START, dev_end)
    eligible = ["baseline"]
    for name, result in candidates.items():
        m = r._summary(result, r.START, dev_end)
        q = r._quarters(result, r.START, dev_end)
        q_ok = sum(int(c["fixed_return"] >= b["fixed_return"]) for c, b in zip(q, base_q))
        if (
            m["fixed_return"] > base["fixed_return"]
            and m["max_red_4plus"] >= base["max_red_4plus"]
            and m["max_red_5plus"] >= base["max_red_5plus"]
            and q_ok >= 3
        ):
            eligible.append(name)
    all_results = {"baseline": baseline, **candidates}
    return max(
        eligible,
        key=lambda name: (
            r._summary(all_results[name], r.START, dev_end)["fixed_return"],
            r._summary(all_results[name], r.START, dev_end)["max_red_5plus"],
            r._summary(all_results[name], r.START, dev_end)["max_red_4plus"],
        ),
    )


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

    routed = {}
    active = {}
    for mode in ("red_quality", "capped_prize"):
        routed[mode], active[mode] = _route(baseline, recent, mode)

    chosen = _choose(baseline, routed, dev_end)
    all_results = {"baseline": baseline, **routed}
    result = all_results[chosen]

    report_results = {}
    for name, xdf in all_results.items():
        report_results[name] = {
            "development": r._summary(xdf, r.START, dev_end),
            "quarters": r._quarters(xdf, r.START, dev_end),
            "forward": r._summary(xdf, dev_end, len(df)),
        }

    for mode in active:
        ts = routed[mode]["t"].to_numpy(int)
        a = active[mode]
        report_results[mode]["route_recent_rate_development"] = float(a[ts < dev_end].mean())
        report_results[mode]["route_recent_rate_forward"] = float(a[ts >= dev_end].mean())

    base = report_results["baseline"]
    cand = report_results[chosen]
    q_ok = sum(
        int(c["fixed_return"] >= b["fixed_return"])
        for c, b in zip(cand["quarters"], base["quarters"])
    )
    dev_ok = chosen != "baseline" and (
        cand["development"]["fixed_return"] > base["development"]["fixed_return"]
        and cand["development"]["max_red_4plus"] >= base["development"]["max_red_4plus"]
        and cand["development"]["max_red_5plus"] >= base["development"]["max_red_5plus"]
        and q_ok >= 3
    )
    forward_ok = (
        cand["forward"]["fixed_return"] >= base["forward"]["fixed_return"]
        and cand["forward"]["max_red_4plus"] >= base["forward"]["max_red_4plus"]
    )

    report = {
        "status": "research_complete",
        "principle": "现版与近250期专家各自完整生成两注；每期开奖前仅按过去250期表现选择整套策略，三等奖以上积分封顶避免偶发大奖长期支配路由",
        "window": WINDOW,
        "results": report_results,
        "selection": chosen,
        "promotion_gate": {
            "development_pass": bool(dev_ok),
            "development_quarters_not_worse": int(q_ok),
            "forward_pass": bool(forward_ok),
            "promote": bool(dev_ok and forward_ok),
        },
    }

    out = ROOT / "backtests" / "v4_7" / "adaptive_router_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
