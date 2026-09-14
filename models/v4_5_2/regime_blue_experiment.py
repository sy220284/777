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


v4 = _load_module("ssq_v4_regime_blue", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_regime_blue", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_regime_blue", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_gate451_regime_blue", ROOT / "models" / "v4_5_1" / "signal_gate.py")
blue_exp = _load_module("ssq_blue_exp_regime", HERE / "blue_feature_experiment.py")

START = 750


def _all_new_blue(df: pd.DataFrame) -> np.ndarray:
    x, y, t_index, _ = v4.build_blue_features(df)
    extras = blue_exp.build_extra_features(df, t_index)
    xx = np.concatenate([
        x,
        extras["ewma"],
        extras["transition"],
        extras["distance"],
        extras["hazard"],
    ], axis=2)
    return blue_exp.walk_forward(xx, y, t_index, len(df))


def _reprice(row: pd.Series | dict, blue1: int, blue2: int) -> dict:
    out = dict(row)
    actual = int(out["actual_blue"])
    p1 = v4.prize_level(int(out["hit1"]), int(blue1) == actual)
    p2 = v4.prize_level(int(out["hit2"]), int(blue2) == actual)
    best = p1 if gate451.PRIZE_ORDER[p1] <= gate451.PRIZE_ORDER[p2] else p2
    out["blue1"] = int(blue1)
    out["blue2"] = int(blue2)
    out["prize1"] = p1
    out["prize2"] = p2
    out["best_prize"] = best
    return out


def _signal_active(row: pd.Series | dict) -> bool:
    value = row.get("signal_active", False)
    if isinstance(value, str):
        return value.lower() in {"true", "1", "yes"}
    return bool(value)


def build_variants(baseline: pd.DataFrame, new_blue: np.ndarray) -> dict[str, pd.DataFrame]:
    signal_off_rows = []
    signal_on_rows = []
    off_primary_only_rows = []

    for _, row in baseline.iterrows():
        t = int(row["seq"]) - 1
        order = np.argsort(new_blue[t])[::-1]
        nb1, nb2 = int(order[0] + 1), int(order[1] + 1)
        active = _signal_active(row)

        signal_off_rows.append(
            row.to_dict() if active else _reprice(row, nb1, nb2)
        )
        signal_on_rows.append(
            _reprice(row, nb1, nb2) if active else row.to_dict()
        )
        # 更保守版本：低信号期只用新模型第一候选，第二候选仍沿用基线第二蓝球。
        off_primary_only_rows.append(
            row.to_dict() if active else _reprice(row, nb1, int(row["blue2"]))
        )

    return {
        "all_new_when_signal_off": pd.DataFrame(signal_off_rows),
        "all_new_when_signal_on": pd.DataFrame(signal_on_rows),
        "new_primary_when_signal_off": pd.DataFrame(off_primary_only_rows),
    }


def _top2_hit(row: pd.Series | dict) -> int:
    actual = int(row["actual_blue"])
    return int(actual in (int(row["blue1"]), int(row["blue2"])))


def _summary(frame: pd.DataFrame) -> dict:
    s = gate451.summarize(frame)
    s["blue_top2_hits"] = int(sum(_top2_hit(row) for _, row in frame.iterrows()))
    s["blue_primary_hits"] = int(sum(
        int(int(row["actual_blue"]) == int(row["blue1"])) for _, row in frame.iterrows()
    ))
    return s


def _segments(frame: pd.DataFrame) -> dict:
    out = {
        "full": _summary(frame),
        "legacy": _summary(frame[frame["seq"] <= 3446]),
        "forward56": _summary(frame[frame["seq"] >= 3447]),
    }
    for i, idx in enumerate(np.array_split(np.arange(len(frame)), 4), start=1):
        out[f"quarter{i}"] = _summary(frame.iloc[idx])
    return out


def _delta(candidate: dict, baseline: dict) -> dict:
    return {
        "full_fixed_return": candidate["full"]["fixed_return_yuan"] - baseline["full"]["fixed_return_yuan"],
        "forward56_fixed_return": candidate["forward56"]["fixed_return_yuan"] - baseline["forward56"]["fixed_return_yuan"],
        "full_fourth": candidate["full"]["best_prize_counts"]["四等奖"] - baseline["full"]["best_prize_counts"]["四等奖"],
        "full_fifth": candidate["full"]["best_prize_counts"]["五等奖"] - baseline["full"]["best_prize_counts"]["五等奖"],
        "full_sixth": candidate["full"]["best_prize_counts"]["六等奖"] - baseline["full"]["best_prize_counts"]["六等奖"],
        "full_blue_top2": candidate["full"]["blue_top2_hits"] - baseline["full"]["blue_top2_hits"],
        "forward56_blue_top2": candidate["forward56"]["blue_top2_hits"] - baseline["forward56"]["blue_top2_hits"],
        "quarter_fixed_return": [
            candidate[f"quarter{i}"]["fixed_return_yuan"] - baseline[f"quarter{i}"]["fixed_return_yuan"]
            for i in range(1, 5)
        ],
    }


def run() -> dict:
    df = blue_exp.load_current_dataframe()
    print("[1/5] 运行V4.3", flush=True)
    r43 = v43.backtest(df, start=START)
    print("[2/5] 运行V4.5", flush=True)
    r45 = v45.backtest(df, start=START)
    print("[3/5] 构造V4.5.1基线", flush=True)
    baseline = gate451.apply_signal_gate(r43, r45)
    print("[4/5] 生成新蓝球候选", flush=True)
    new_blue = _all_new_blue(df)
    print("[5/5] 按既有红球信号状态切换蓝球", flush=True)
    variants = build_variants(baseline, new_blue)

    summaries = {"v451_baseline": _segments(baseline)}
    summaries.update({name: _segments(frame) for name, frame in variants.items()})
    base = summaries["v451_baseline"]
    return {
        "model": "V4.5.2 红球状态驱动蓝球专家切换实验",
        "principle": "不新增阈值；直接复用V4.5.1既有红球信号门控。高信号保留奖级效用蓝球，低信号测试命中率更高的新蓝球。",
        "summaries": summaries,
        "delta_vs_v451": {
            name: _delta(summary, base)
            for name, summary in summaries.items()
            if name != "v451_baseline"
        },
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2 红球状态驱动蓝球切换实验")
    p.add_argument("--out", default="backtests/v4_5_2/regime_blue_summary.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
