from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


exp = _load("v46_pool_exp", ROOT / "tools" / "v4_6_portfolio_experiment.py")
v4 = exp.v4
v45 = exp.v45
START = exp.START
FIXED = exp.FIXED_PRIZE


def _ticket_return(row: pd.Series) -> int:
    return int(FIXED[row["prize1"]] + FIXED[row["prize2"]])


def main() -> None:
    df = exp.load_dataframe(include_live106=True)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)

    base = exp.evaluate(df, red_pred, red_occ, 12, blue_models, blue_base)
    challenger = exp.evaluate(df, red_pred, red_occ, 13, blue_models, blue_base)
    if not base["seq"].equals(challenger["seq"]):
        raise RuntimeError("pool12/pool13 回测未对齐")

    paired = pd.DataFrame({
        "seq": base["seq"].to_numpy(int),
        "red_delta": challenger["max_red_hit"].to_numpy(int) - base["max_red_hit"].to_numpy(int),
        "return_delta": challenger.apply(_ticket_return, axis=1).to_numpy(int) - base.apply(_ticket_return, axis=1).to_numpy(int),
        "base_hit": base["max_red_hit"].to_numpy(int),
        "pool13_hit": challenger["max_red_hit"].to_numpy(int),
        "changed": (
            (base["hit1"].to_numpy(int) != challenger["hit1"].to_numpy(int))
            | (base["hit2"].to_numpy(int) != challenger["hit2"].to_numpy(int))
        ),
    })

    blocks = []
    first_seq = int(paired["seq"].min())
    last_seq = int(paired["seq"].max())
    for lo in range(first_seq, last_seq + 1, 250):
        hi = min(last_seq, lo + 249)
        part = paired[(paired.seq >= lo) & (paired.seq <= hi)]
        changed = part[part.changed]
        blocks.append({
            "seq_range": [lo, hi],
            "draws": int(len(part)),
            "changed_draws": int(len(changed)),
            "red_delta_sum": int(part.red_delta.sum()),
            "red_delta_mean": float(part.red_delta.mean()),
            "better_red_draws": int((part.red_delta > 0).sum()),
            "worse_red_draws": int((part.red_delta < 0).sum()),
            "return_delta_yuan": int(part.return_delta.sum()),
            "red4plus_delta": int((part.pool13_hit >= 4).sum() - (part.base_hit >= 4).sum()),
            "red5plus_delta": int((part.pool13_hit >= 5).sum() - (part.base_hit >= 5).sum()),
        })

    changed = paired[paired.changed]
    affected_blocks = [b for b in blocks if b["changed_draws"] > 0]
    positive_red_blocks = sum(b["red_delta_sum"] > 0 for b in affected_blocks)
    nonnegative_red_blocks = sum(b["red_delta_sum"] >= 0 for b in affected_blocks)
    negative_red_blocks = sum(b["red_delta_sum"] < 0 for b in affected_blocks)
    total_return_delta = int(paired.return_delta.sum())
    positive_return_parts = [max(0, b["return_delta_yuan"]) for b in affected_blocks]
    max_positive_share = (
        max(positive_return_parts) / max(1, sum(positive_return_parts))
        if positive_return_parts else 0.0
    )

    report = {
        "experiment": "pool13 高信号组合鲁棒性",
        "draws": int(len(paired)),
        "changed_draws": int(len(changed)),
        "paired": {
            "better_red_draws": int((paired.red_delta > 0).sum()),
            "worse_red_draws": int((paired.red_delta < 0).sum()),
            "tie_red_draws": int((paired.red_delta == 0).sum()),
            "red_delta_sum": int(paired.red_delta.sum()),
            "red_delta_mean": float(paired.red_delta.mean()),
            "return_delta_yuan": total_return_delta,
            "red4plus_delta": int((paired.pool13_hit >= 4).sum() - (paired.base_hit >= 4).sum()),
            "red5plus_delta": int((paired.pool13_hit >= 5).sum() - (paired.base_hit >= 5).sum()),
        },
        "blocks_250": blocks,
        "robustness": {
            "affected_blocks": len(affected_blocks),
            "positive_red_blocks": positive_red_blocks,
            "nonnegative_red_blocks": nonnegative_red_blocks,
            "negative_red_blocks": negative_red_blocks,
            "nonnegative_red_block_rate": float(nonnegative_red_blocks / max(1, len(affected_blocks))),
            "max_positive_return_block_share": float(max_positive_share),
            "pass": bool(
                len(affected_blocks) >= 4
                and nonnegative_red_blocks >= max(3, int(np.ceil(0.65 * len(affected_blocks))))
                and paired.red_delta.sum() > 0
                and (paired.pool13_hit >= 5).sum() >= (paired.base_hit >= 5).sum()
                and max_positive_share <= 0.80
            ),
        },
        "note": "现场两期仅随时间序列自然进入报告，不参与pool13参数产生；本检查关注增益是否分散在多个时间块。",
    }

    out = ROOT / "backtests" / "v4_6" / "pool13_robustness.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
