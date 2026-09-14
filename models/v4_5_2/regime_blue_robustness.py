from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


regime = _load_module("ssq_regime_blue_robust", HERE / "regime_blue_experiment.py")
v43 = _load_module("ssq_v43_regime_robust", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_regime_robust", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_gate451_regime_robust", ROOT / "models" / "v4_5_1" / "signal_gate.py")

START = 750
BLOCK = 250


def _fixed(row: pd.Series | dict) -> int:
    return gate451.FIXED_PRIZE[str(row["prize1"])] + gate451.FIXED_PRIZE[str(row["prize2"])]


def _top2(row: pd.Series | dict) -> int:
    actual = int(row["actual_blue"])
    return int(actual in (int(row["blue1"]), int(row["blue2"])))


def _summary(frame: pd.DataFrame) -> dict:
    if frame.empty:
        return {"draws": 0, "fixed_return": 0, "blue_top2": 0, "best_prize_counts": {}}
    return {
        "draws": int(len(frame)),
        "fixed_return": int(sum(_fixed(row) for _, row in frame.iterrows())),
        "blue_top2": int(sum(_top2(row) for _, row in frame.iterrows())),
        "best_prize_counts": {
            str(k): int(v) for k, v in frame["best_prize"].value_counts().to_dict().items()
        },
    }


def _delta(candidate: pd.DataFrame, baseline: pd.DataFrame) -> dict:
    c = _summary(candidate)
    b = _summary(baseline)
    return {
        "draws": b["draws"],
        "fixed_return_delta": c["fixed_return"] - b["fixed_return"],
        "blue_top2_delta": c["blue_top2"] - b["blue_top2"],
        "baseline_fixed_return": b["fixed_return"],
        "candidate_fixed_return": c["fixed_return"],
    }


def _signal_active(value) -> bool:
    if isinstance(value, str):
        return value.lower() in {"true", "1", "yes"}
    return bool(value)


def run() -> dict:
    df = regime.blue_exp.load_current_dataframe()
    print("[1/4] 运行V4.3", flush=True)
    r43 = v43.backtest(df, start=START)
    print("[2/4] 运行V4.5", flush=True)
    r45 = v45.backtest(df, start=START)
    print("[3/4] 构造V4.5.1与低信号蓝球候选", flush=True)
    baseline = gate451.apply_signal_gate(r43, r45).reset_index(drop=True)
    new_blue = regime._all_new_blue(df)
    candidate = regime.build_variants(baseline, new_blue)["all_new_when_signal_off"].reset_index(drop=True)

    if not baseline["seq"].equals(candidate["seq"]):
        raise RuntimeError("baseline/candidate seq 未对齐")

    print("[4/4] 分段稳健性核验", flush=True)
    blocks = []
    for start in range(0, len(baseline), BLOCK):
        end = min(len(baseline), start + BLOCK)
        b = baseline.iloc[start:end]
        c = candidate.iloc[start:end]
        row = _delta(c, b)
        row.update({
            "block": len(blocks) + 1,
            "seq_start": int(b.iloc[0]["seq"]),
            "seq_end": int(b.iloc[-1]["seq"]),
        })
        blocks.append(row)

    # 只看真正发生切换的低信号样本，避免80%未改变样本稀释差异。
    active = baseline["signal_active"].map(_signal_active).to_numpy(bool)
    off_idx = np.where(~active)[0]
    off_base = baseline.iloc[off_idx]
    off_cand = candidate.iloc[off_idx]

    off_blocks = []
    for start in range(0, len(off_idx), 100):
        idx = off_idx[start:start + 100]
        b = baseline.iloc[idx]
        c = candidate.iloc[idx]
        row = _delta(c, b)
        row.update({
            "off_block": len(off_blocks) + 1,
            "seq_start": int(b.iloc[0]["seq"]),
            "seq_end": int(b.iloc[-1]["seq"]),
        })
        off_blocks.append(row)

    # 截止点稳定性：逐步扩大样本，检查累计增益是否只来自末段。
    checkpoints = []
    for end in range(500, len(baseline) + 1, 250):
        row = _delta(candidate.iloc[:end], baseline.iloc[:end])
        row["through_seq"] = int(baseline.iloc[end - 1]["seq"])
        checkpoints.append(row)
    if not checkpoints or checkpoints[-1]["through_seq"] != int(baseline.iloc[-1]["seq"]):
        row = _delta(candidate, baseline)
        row["through_seq"] = int(baseline.iloc[-1]["seq"])
        checkpoints.append(row)

    block_nonnegative = sum(int(x["fixed_return_delta"] >= 0) for x in blocks)
    off_block_nonnegative = sum(int(x["fixed_return_delta"] >= 0) for x in off_blocks)
    max_block_loss = min((x["fixed_return_delta"] for x in blocks), default=0)

    return {
        "model": "V4.5.2 低红球信号蓝球专家稳健性核验",
        "overall": _delta(candidate, baseline),
        "signal_off_only": _delta(off_cand, off_base),
        "signal_off_draws": int(len(off_idx)),
        "chronological_250_draw_blocks": blocks,
        "signal_off_100_draw_blocks": off_blocks,
        "cumulative_checkpoints": checkpoints,
        "robustness": {
            "chronological_blocks_nonnegative": int(block_nonnegative),
            "chronological_blocks_total": int(len(blocks)),
            "signal_off_blocks_nonnegative": int(off_block_nonnegative),
            "signal_off_blocks_total": int(len(off_blocks)),
            "worst_chronological_block_delta": int(max_block_loss),
        },
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2低信号蓝球专家稳健性核验")
    p.add_argument("--out", default="backtests/v4_5_2/regime_blue_robustness.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
