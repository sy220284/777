from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import v4_7_research as r
import v4_7_research_strict as s

ROOT = Path(__file__).resolve().parents[1]
EXPECTED_DEV_FIXED_RETURN = 4760


def evaluate_assignment(
    df: pd.DataFrame,
    red_pred: np.ndarray,
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
    mode: str,
) -> pd.DataFrame:
    groups, v45_groups, signal = s._groups_strict(red_pred, red_occ, "standard")
    actual_blue = df["blue"].to_numpy(int)
    fused, _ = r.v45.fuse_blue_predictions(
        blue_models, v45_groups, red_occ, actual_blue, r.START
    )

    rows = []
    for t in range(r.START, len(df)):
        a, b = groups[t]
        order = np.argsort(
            fused[t] if signal[t] else blue_models["base"][t]
        )[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)

        if mode == "stronger_ticket_top1":
            # 红球组合和两个蓝球集合完全不变，只把蓝球第一候选配给当前红球边际总分更高的一注。
            if float(red_pred[t, a].sum()) < float(red_pred[t, b].sum()):
                b1, b2 = b2, b1
        elif mode != "baseline":
            raise ValueError(mode)

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
    dev_end = int(r.history_tool.load_manifest()["frozen_base_draws"])
    red_pred, _, red_occ = r.v4.walk_forward_red(df)
    blue_models = r.v45.build_blue_models(df)

    baseline = evaluate_assignment(df, red_pred, red_occ, blue_models, "baseline")
    stronger = evaluate_assignment(df, red_pred, red_occ, blue_models, "stronger_ticket_top1")

    base_dev = r._summary(baseline, r.START, dev_end)
    if base_dev["fixed_return"] != EXPECTED_DEV_FIXED_RETURN:
        raise RuntimeError(
            f"V4.5.1基线复现失败: expected={EXPECTED_DEV_FIXED_RETURN} actual={base_dev['fixed_return']}"
        )

    cand_dev = r._summary(stronger, r.START, dev_end)
    base_q = r._quarters(baseline, r.START, dev_end)
    cand_q = r._quarters(stronger, r.START, dev_end)
    base_forward = r._summary(baseline, dev_end, len(df))
    cand_forward = r._summary(stronger, dev_end, len(df))
    q_ok = sum(int(c["fixed_return"] >= b["fixed_return"]) for c, b in zip(cand_q, base_q))

    # 红球组合未改变，因此红球4+/5+必须逐期完全一致；不一致直接视为实现错误。
    if not np.array_equal(baseline["max_red_hit"].to_numpy(), stronger["max_red_hit"].to_numpy()):
        raise RuntimeError("蓝球分配实验意外改变了红球结果")

    dev_ok = cand_dev["fixed_return"] > base_dev["fixed_return"] and q_ok >= 3
    forward_ok = cand_forward["fixed_return"] >= base_forward["fixed_return"]

    report = {
        "status": "research_complete",
        "principle": "不改任何红球号码、不改两个蓝球候选集合，只把蓝球第一候选配给当前红球边际总分更高的一注",
        "baseline": {
            "development": base_dev,
            "quarters": base_q,
            "forward": base_forward,
        },
        "stronger_ticket_top1": {
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

    out = ROOT / "backtests" / "v4_7" / "blue_assignment_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
