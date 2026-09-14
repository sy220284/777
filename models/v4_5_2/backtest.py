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


v4 = _load_module("ssq_v4_v452_backtest", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_v452_backtest", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_v452_backtest", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_gate451_v452_backtest", ROOT / "models" / "v4_5_1" / "signal_gate.py")
blue_expert = _load_module("ssq_blue_expert_v452_backtest", HERE / "blue_expert.py")
predictor = _load_module("ssq_predictor_v452_backtest", HERE / "predictor.py")

START = 750


def _reprice(row: pd.Series | dict, blue1: int, blue2: int) -> dict:
    out = dict(row)
    actual = int(out["actual_blue"])
    p1 = v4.prize_level(int(out["hit1"]), int(blue1) == actual)
    p2 = v4.prize_level(int(out["hit2"]), int(blue2) == actual)
    best = p1 if gate451.PRIZE_ORDER[p1] <= gate451.PRIZE_ORDER[p2] else p2
    out.update({
        "blue1": int(blue1),
        "blue2": int(blue2),
        "prize1": p1,
        "prize2": p2,
        "best_prize": best,
    })
    return out


def apply_v452(baseline: pd.DataFrame, low_blue: np.ndarray) -> pd.DataFrame:
    rows = []
    for _, row in baseline.iterrows():
        active = bool(row["signal_active"])
        if active:
            rows.append(row.to_dict())
            continue
        t = int(row["seq"]) - 1
        order = np.argsort(low_blue[t])[::-1]
        rows.append(_reprice(row, int(order[0] + 1), int(order[1] + 1)))
    return pd.DataFrame(rows)


def _segment(frame: pd.DataFrame) -> dict:
    return gate451.summarize(frame)


def summarize(frame: pd.DataFrame) -> dict:
    return {
        "full": _segment(frame),
        "legacy": _segment(frame[frame["seq"] <= 3446]),
        "forward56": _segment(frame[frame["seq"] >= 3447]),
    }


def run() -> dict:
    df = predictor.load_current_dataframe()
    r43 = v43.backtest(df, start=START)
    r45 = v45.backtest(df, start=START)
    baseline = gate451.apply_signal_gate(r43, r45)
    low_blue = blue_expert.walk_forward(df)
    candidate = apply_v452(baseline, low_blue)

    base_summary = summarize(baseline)
    candidate_summary = summarize(candidate)
    return {
        "model": "V4.5.2 红球状态驱动蓝球专家门控",
        "dataset_draws": int(len(df)),
        "tested_draws": int(len(candidate)),
        "rule": (
            "V4.5.1红球信号开启：保持V4.5红球与奖级效用蓝球融合；"
            "信号关闭：保持V4.3红球，只切换低信号时序蓝球专家"
        ),
        "baseline_v451": base_summary,
        "v452": candidate_summary,
        "delta": {
            "full_fixed_return_yuan": int(
                candidate_summary["full"]["fixed_return_yuan"] - base_summary["full"]["fixed_return_yuan"]
            ),
            "forward56_fixed_return_yuan": int(
                candidate_summary["forward56"]["fixed_return_yuan"] - base_summary["forward56"]["fixed_return_yuan"]
            ),
            "full_fourth": int(
                candidate_summary["full"]["best_prize_counts"]["四等奖"]
                - base_summary["full"]["best_prize_counts"]["四等奖"]
            ),
            "full_fifth": int(
                candidate_summary["full"]["best_prize_counts"]["五等奖"]
                - base_summary["full"]["best_prize_counts"]["五等奖"]
            ),
            "forward56_sixth": int(
                candidate_summary["forward56"]["best_prize_counts"]["六等奖"]
                - base_summary["forward56"]["best_prize_counts"]["六等奖"]
            ),
        },
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2严格前向回测")
    p.add_argument("--out", default="backtests/v4_5_2/summary.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
