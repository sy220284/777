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


v4 = _load_module("ssq_v4_blue_prize", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_blue_prize", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_blue_prize", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_gate451_blue_prize", ROOT / "models" / "v4_5_1" / "signal_gate.py")
blue_exp = _load_module("ssq_blue_exp_prize", HERE / "blue_feature_experiment.py")

START = 750
SWITCH_WINDOW = 250
SWITCH_MIN_HISTORY = 50


def _all_new_blue_predictions(df: pd.DataFrame) -> np.ndarray:
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


def _reprice_row(row: pd.Series | dict, blue1: int, blue2: int) -> dict:
    out = dict(row)
    actual_blue = int(out["actual_blue"])
    hit1 = int(out["hit1"])
    hit2 = int(out["hit2"])
    p1 = v4.prize_level(hit1, blue1 == actual_blue)
    p2 = v4.prize_level(hit2, blue2 == actual_blue)
    best = p1 if gate451.PRIZE_ORDER[p1] <= gate451.PRIZE_ORDER[p2] else p2
    out["blue1"] = int(blue1)
    out["blue2"] = int(blue2)
    out["prize1"] = p1
    out["prize2"] = p2
    out["best_prize"] = best
    return out


def _direct_new_blue(baseline: pd.DataFrame, new_pred: np.ndarray) -> pd.DataFrame:
    rows = []
    for _, row in baseline.iterrows():
        t = int(row["seq"]) - 1
        order = np.argsort(new_pred[t])[::-1]
        rows.append(_reprice_row(row, int(order[0] + 1), int(order[1] + 1)))
    return pd.DataFrame(rows)


def _fixed_draw_return(row: pd.Series | dict) -> int:
    return gate451.FIXED_PRIZE[str(row["prize1"])] + gate451.FIXED_PRIZE[str(row["prize2"])]


def _top2_hit(row: pd.Series | dict) -> int:
    actual = int(row["actual_blue"])
    return int(actual in (int(row["blue1"]), int(row["blue2"])))


def _causal_switch(
    baseline: pd.DataFrame,
    candidate: pd.DataFrame,
    *,
    metric: str,
) -> pd.DataFrame:
    if not baseline["seq"].reset_index(drop=True).equals(candidate["seq"].reset_index(drop=True)):
        raise ValueError("baseline/candidate seq 未对齐")

    if metric == "top2":
        base_value = np.asarray([_top2_hit(row) for _, row in baseline.iterrows()], dtype=float)
        cand_value = np.asarray([_top2_hit(row) for _, row in candidate.iterrows()], dtype=float)
    elif metric == "fixed_return":
        base_value = np.asarray([_fixed_draw_return(row) for _, row in baseline.iterrows()], dtype=float)
        cand_value = np.asarray([_fixed_draw_return(row) for _, row in candidate.iterrows()], dtype=float)
    else:
        raise ValueError(metric)

    rows = []
    for i in range(len(baseline)):
        left = max(0, i - SWITCH_WINDOW)
        width = i - left
        use_candidate = False
        if width >= SWITCH_MIN_HISTORY:
            base_mean = float(base_value[left:i].mean())
            cand_mean = float(cand_value[left:i].mean())
            use_candidate = cand_mean > base_mean
        source = candidate.iloc[i] if use_candidate else baseline.iloc[i]
        out = source.to_dict()
        out["blue_source"] = "all_new" if use_candidate else "v451"
        out["blue_switch_metric"] = metric
        rows.append(out)
    return pd.DataFrame(rows)


def _summary(frame: pd.DataFrame) -> dict:
    s = gate451.summarize(frame)
    s["blue_top2_hits"] = int(sum(_top2_hit(row) for _, row in frame.iterrows()))
    s["blue_top1_hits"] = int(sum(int(int(row["actual_blue"]) == int(row["blue1"])) for _, row in frame.iterrows()))
    if "blue_source" in frame.columns:
        s["all_new_source_rate"] = float((frame["blue_source"] == "all_new").mean())
    return s


def _segments(frame: pd.DataFrame) -> dict:
    out = {"full": _summary(frame)}
    out["legacy"] = _summary(frame[frame["seq"] <= 3446])
    out["forward56"] = _summary(frame[frame["seq"] >= 3447])
    indices = np.array_split(np.arange(len(frame)), 4)
    for i, idx in enumerate(indices, start=1):
        out[f"quarter{i}"] = _summary(frame.iloc[idx])
    return out


def _delta(candidate: dict, baseline: dict) -> dict:
    return {
        "full_fixed_return": candidate["full"]["fixed_return_yuan"] - baseline["full"]["fixed_return_yuan"],
        "forward56_fixed_return": candidate["forward56"]["fixed_return_yuan"] - baseline["forward56"]["fixed_return_yuan"],
        "full_top1_hits": candidate["full"]["blue_top1_hits"] - baseline["full"]["blue_top1_hits"],
        "full_top2_hits": candidate["full"]["blue_top2_hits"] - baseline["full"]["blue_top2_hits"],
        "forward56_top1_hits": candidate["forward56"]["blue_top1_hits"] - baseline["forward56"]["blue_top1_hits"],
        "forward56_top2_hits": candidate["forward56"]["blue_top2_hits"] - baseline["forward56"]["blue_top2_hits"],
        "full_fourth": candidate["full"]["best_prize_counts"]["四等奖"] - baseline["full"]["best_prize_counts"]["四等奖"],
        "full_fifth": candidate["full"]["best_prize_counts"]["五等奖"] - baseline["full"]["best_prize_counts"]["五等奖"],
        "full_sixth": candidate["full"]["best_prize_counts"]["六等奖"] - baseline["full"]["best_prize_counts"]["六等奖"],
        "quarter_fixed_return": [
            candidate[f"quarter{i}"]["fixed_return_yuan"] - baseline[f"quarter{i}"]["fixed_return_yuan"]
            for i in range(1, 5)
        ],
    }


def run() -> dict:
    df = blue_exp.load_current_dataframe()
    print("[1/5] 运行V4.3基线", flush=True)
    r43 = v43.backtest(df, start=START)
    print("[2/5] 运行V4.5基线", flush=True)
    r45 = v45.backtest(df, start=START)
    print("[3/5] 构造V4.5.1基线", flush=True)
    baseline = gate451.apply_signal_gate(r43, r45)
    print("[4/5] 生成all_new蓝球预测", flush=True)
    new_pred = _all_new_blue_predictions(df)
    direct = _direct_new_blue(baseline, new_pred)
    print("[5/5] 运行因果蓝球切换", flush=True)
    switch_top2 = _causal_switch(baseline, direct, metric="top2")
    switch_prize = _causal_switch(baseline, direct, metric="fixed_return")

    frames = {
        "v451_baseline": baseline,
        "all_new_direct": direct,
        "causal_top2_switch": switch_top2,
        "causal_prize_switch": switch_prize,
    }
    summaries = {name: _segments(frame) for name, frame in frames.items()}
    base = summaries["v451_baseline"]
    deltas = {name: _delta(summary, base) for name, summary in summaries.items() if name != "v451_baseline"}
    return {
        "model": "V4.5.2 蓝球新特征最终奖级转化实验",
        "switch_rule": {
            "window": SWITCH_WINDOW,
            "minimum_history": SWITCH_MIN_HISTORY,
            "top2": "过去250期all_new前2命中率严格高于V4.5.1才切换",
            "fixed_return": "过去250期all_new固定奖回报严格高于V4.5.1才切换",
        },
        "summaries": summaries,
        "delta_vs_v451": deltas,
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2 蓝球奖级转化实验")
    p.add_argument("--out", default="backtests/v4_5_2/blue_prize_conversion_summary.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
