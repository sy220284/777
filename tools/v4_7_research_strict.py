from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import v4_7_research as r

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_LEGACY_FIXED_RETURN = 4760


def _groups_strict(red_pred: np.ndarray, red_occ: np.ndarray, mode: str):
    baseline, _, _ = r.v45._baseline_red_groups(red_pred, red_occ, r.START)
    v45_groups, active, _ = r.v45.build_red_portfolio(red_pred, red_occ, r.START)
    signal = r._signal_array(red_pred, red_occ)
    final: dict[int, tuple[np.ndarray, np.ndarray]] = {}

    for t in range(r.START, len(red_pred)):
        if not signal[t]:
            final[t] = baseline[t]
            continue
        if mode == "standard":
            # 精确复现V4.5.1历史回测：信号开启时直接采用V4.5当期组合及其原顺序。
            final[t] = v45_groups[t]
            continue
        if mode != "attack_defense":
            raise ValueError(mode)
        if not active[t]:
            final[t] = baseline[t]
            continue

        ca, cb = v45_groups[t]
        primary = ca if red_pred[t, ca].sum() >= red_pred[t, cb].sum() else cb
        ba, bb = baseline[t]
        candidates = (ba, bb)
        overlaps = [len(set(primary.tolist()) & set(x.tolist())) for x in candidates]
        best_overlap = min(overlaps)
        options = [x for x, ov in zip(candidates, overlaps) if ov == best_overlap]
        secondary = max(options, key=lambda x: float(red_pred[t, x].sum()))
        final[t] = (primary, secondary)
    return final, v45_groups, signal


def _evaluate_strict(
    df: pd.DataFrame,
    red_pred: np.ndarray,
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
    grouping_mode: str,
    blue_calibrated: bool,
) -> pd.DataFrame:
    groups, v45_groups, signal = _groups_strict(red_pred, red_occ, grouping_mode)
    actual_blue = df["blue"].to_numpy(int)

    # V4.5蓝球融合的历史效用必须始终用V4.5自己的组合，不能用信号门控后的最终组合。
    fused, _ = r.v45.fuse_blue_predictions(
        blue_models, v45_groups, red_occ, actual_blue, r.START
    )
    blue_score = np.full_like(blue_models["base"], np.nan)
    for t in range(r.START, len(df)):
        blue_score[t] = fused[t] if signal[t] else blue_models["base"][t]
    blue_pick = r._blue_choices(blue_score, actual_blue, blue_calibrated)

    rows = []
    for t in range(r.START, len(df)):
        a, b = groups[t]
        b1, b2 = int(blue_pick[t, 0]), int(blue_pick[t, 1])
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = r.v4.prize_level(h1, b1 == actual_blue[t])
        p2 = r.v4.prize_level(h2, b2 == actual_blue[t])
        best = p1 if r.PRIZE_RANK[p1] <= r.PRIZE_RANK[p2] else p2
        rows.append({
            "t": t,
            "seq": int(df.iloc[t]["seq"]),
            "hit1": h1,
            "hit2": h2,
            "max_red_hit": max(h1, h2),
            "blue_hit": int(actual_blue[t] in (b1, b2)),
            "prize1": p1,
            "prize2": p2,
            "best_prize": best,
        })
    return pd.DataFrame(rows)


def main() -> None:
    df = r.load_current_dataframe()
    manifest = r.history_tool.load_manifest()
    dev_end = int(manifest["frozen_base_draws"])

    baseline_red, raw, red_occ = r.v4.walk_forward_red(df)
    red_candidates = {
        "baseline": baseline_red,
        "top12": r._objective_aligned_ensemble(raw, red_occ, "top12"),
        "dual": r._objective_aligned_ensemble(raw, red_occ, "dual"),
    }
    chosen_red = r._choose_red_ensemble(red_candidates, red_occ, dev_end)
    red_pred = red_candidates[chosen_red]

    blue_models = r.v45.build_blue_models(df)

    baseline_full = _evaluate_strict(
        df, baseline_red, red_occ, blue_models, "standard", False
    )
    baseline_dev = r._summary(baseline_full, r.START, dev_end)
    if baseline_dev["fixed_return"] != EXPECTED_LEGACY_FIXED_RETURN:
        raise RuntimeError(
            "V4.5.1基线复现失败: "
            f"expected={EXPECTED_LEGACY_FIXED_RETURN} actual={baseline_dev['fixed_return']}"
        )

    standard_plain = _evaluate_strict(
        df, red_pred, red_occ, blue_models, "standard", False
    )
    attack_plain = _evaluate_strict(
        df, red_pred, red_occ, blue_models, "attack_defense", False
    )
    grouping = r._choose_grouping(standard_plain, attack_plain, dev_end)
    chosen_plain = standard_plain if grouping == "standard" else attack_plain

    chosen_cal = _evaluate_strict(
        df, red_pred, red_occ, blue_models, grouping, True
    )
    blue_calibrated = r._choose_blue(chosen_plain, chosen_cal, dev_end)
    locked = chosen_cal if blue_calibrated else chosen_plain

    report = {
        "status": "research_complete",
        "method": "开发段选择与后56期前向验证严格分离；基线先强制复现历史V4.5.1固定回报4760元",
        "dataset_draws": int(len(df)),
        "development_draws": int(dev_end - r.START),
        "forward_draws": int(len(df) - dev_end),
        "red_pool_development": {
            name: r._red_pool_metrics(pred, red_occ, r.START, dev_end)
            for name, pred in red_candidates.items()
        },
        "red_pool_forward": {
            name: r._red_pool_metrics(pred, red_occ, dev_end, len(df))
            for name, pred in red_candidates.items()
        },
        "selection": {
            "red_ensemble": chosen_red,
            "grouping": grouping,
            "blue_rank_calibration": blue_calibrated,
        },
        "baseline": {
            "development": baseline_dev,
            "development_quarters": r._quarters(baseline_full, r.START, dev_end),
            "forward": r._summary(baseline_full, dev_end, len(df)),
        },
        "locked_candidate": {
            "development": r._summary(locked, r.START, dev_end),
            "development_quarters": r._quarters(locked, r.START, dev_end),
            "forward": r._summary(locked, dev_end, len(df)),
        },
        "component_development": {
            "standard_plain": r._summary(standard_plain, r.START, dev_end),
            "attack_defense_plain": r._summary(attack_plain, r.START, dev_end),
            "chosen_blue_calibrated": r._summary(chosen_cal, r.START, dev_end),
        },
    }

    b = report["baseline"]
    c = report["locked_candidate"]
    q_ok = sum(
        int(cq["fixed_return"] >= bq["fixed_return"])
        for cq, bq in zip(c["development_quarters"], b["development_quarters"])
    )
    dev_ok = (
        c["development"]["fixed_return"] > b["development"]["fixed_return"]
        and c["development"]["max_red_4plus"] >= b["development"]["max_red_4plus"]
        and c["development"]["max_red_5plus"] >= b["development"]["max_red_5plus"]
        and q_ok >= 3
    )
    forward_ok = (
        c["forward"]["fixed_return"] >= b["forward"]["fixed_return"]
        and c["forward"]["max_red_4plus"] >= b["forward"]["max_red_4plus"]
    )
    report["promotion_gate"] = {
        "development_pass": bool(dev_ok),
        "development_quarters_not_worse": int(q_ok),
        "forward_pass": bool(forward_ok),
        "promote": bool(dev_ok and forward_ok),
    }

    out = ROOT / "backtests" / "v4_7" / "research_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
