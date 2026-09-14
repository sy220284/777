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


v4 = _load_module("ssq_v4_joint_align", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_joint_align", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_joint_align", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_gate451_joint_align", ROOT / "models" / "v4_5_1" / "signal_gate.py")
red_exp = _load_module("ssq_red_feature_joint_align", HERE / "feature_experiment.py")
blue_exp = _load_module("ssq_blue_feature_joint_align", HERE / "blue_feature_experiment.py")

START = 750


def _numbers(text: str) -> np.ndarray:
    return np.asarray([int(x) - 1 for x in str(text).split()], dtype=int)


def _reprice(row: pd.Series | dict, blue_for_ticket1: int, blue_for_ticket2: int) -> dict:
    out = dict(row)
    actual = int(out["actual_blue"])
    p1 = v4.prize_level(int(out["hit1"]), int(blue_for_ticket1) == actual)
    p2 = v4.prize_level(int(out["hit2"]), int(blue_for_ticket2) == actual)
    best = p1 if gate451.PRIZE_ORDER[p1] <= gate451.PRIZE_ORDER[p2] else p2
    out["blue1"] = int(blue_for_ticket1)
    out["blue2"] = int(blue_for_ticket2)
    out["prize1"] = p1
    out["prize2"] = p2
    out["best_prize"] = best
    return out


def _ticket_strength(score: np.ndarray, t: int, row: pd.Series | dict) -> tuple[float, float]:
    a = _numbers(str(row["ticket1"]))
    b = _numbers(str(row["ticket2"]))
    return float(score[t, a].sum()), float(score[t, b].sum())


def _assign_pair(
    row: pd.Series | dict,
    primary_blue: int,
    secondary_blue: int,
    strength1: float,
    strength2: float,
) -> dict:
    if strength2 > strength1:
        return _reprice(row, secondary_blue, primary_blue)
    return _reprice(row, primary_blue, secondary_blue)


def _build_red_scores(df: pd.DataFrame) -> tuple[np.ndarray, np.ndarray]:
    print("[4/7] 生成V4与EWMA增强红票强度", flush=True)
    v4_score, _, _ = v4.walk_forward_red(df)
    x, y, t_index, occ = v4.build_red_features(df)
    extra = red_exp.build_extra_features(df, t_index)
    xx = np.concatenate([x, extra["ewma"]], axis=2)
    ewma_score = red_exp.walk_forward_custom(xx, y, t_index, occ)
    return v4_score, ewma_score


def _build_all_new_blue(df: pd.DataFrame) -> np.ndarray:
    print("[5/7] 生成all_new蓝球候选", flush=True)
    x, y, t_index, _ = v4.build_blue_features(df)
    extra = blue_exp.build_extra_features(df, t_index)
    xx = np.concatenate([
        x,
        extra["ewma"],
        extra["transition"],
        extra["distance"],
        extra["hazard"],
    ], axis=2)
    return blue_exp.walk_forward(xx, y, t_index, len(df))


def build_variants(
    baseline: pd.DataFrame,
    new_blue: np.ndarray,
    v4_score: np.ndarray,
    ewma_score: np.ndarray,
) -> dict[str, pd.DataFrame]:
    variants: dict[str, list[dict]] = {
        "baseline_ewma_assignment": [],
        "all_new_original_assignment": [],
        "all_new_v4_assignment": [],
        "all_new_ewma_assignment": [],
        "all_new_blended_assignment": [],
    }

    for _, row in baseline.iterrows():
        t = int(row["seq"]) - 1
        old_b1, old_b2 = int(row["blue1"]), int(row["blue2"])
        order = np.argsort(new_blue[t])[::-1]
        new_b1, new_b2 = int(order[0] + 1), int(order[1] + 1)

        v41, v42 = _ticket_strength(v4_score, t, row)
        e1, e2 = _ticket_strength(ewma_score, t, row)
        # 两种排序都先转为票内相对差，避免不同模型绝对分值尺度干扰。
        vdiff = v41 - v42
        ediff = e1 - e2
        blended1, blended2 = vdiff + ediff, 0.0

        variants["baseline_ewma_assignment"].append(
            _assign_pair(row, old_b1, old_b2, e1, e2)
        )
        variants["all_new_original_assignment"].append(
            _reprice(row, new_b1, new_b2)
        )
        variants["all_new_v4_assignment"].append(
            _assign_pair(row, new_b1, new_b2, v41, v42)
        )
        variants["all_new_ewma_assignment"].append(
            _assign_pair(row, new_b1, new_b2, e1, e2)
        )
        variants["all_new_blended_assignment"].append(
            _assign_pair(row, new_b1, new_b2, blended1, blended2)
        )

    return {name: pd.DataFrame(rows) for name, rows in variants.items()}


def _top2_hit(row: pd.Series | dict) -> int:
    actual = int(row["actual_blue"])
    return int(actual in (int(row["blue1"]), int(row["blue2"])))


def _summary(frame: pd.DataFrame) -> dict:
    s = gate451.summarize(frame)
    s["blue_top2_hits"] = int(sum(_top2_hit(row) for _, row in frame.iterrows()))
    s["blue_primary_hits"] = int(sum(int(int(row["actual_blue"]) == int(row["blue1"])) for _, row in frame.iterrows()))
    s["blue_on_4plus_red_tickets"] = int(sum(
        (int(row["hit1"]) >= 4 and int(row["blue1"]) == int(row["actual_blue"]))
        + (int(row["hit2"]) >= 4 and int(row["blue2"]) == int(row["actual_blue"]))
        for _, row in frame.iterrows()
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
        "full_blue_4plus_alignment": candidate["full"]["blue_on_4plus_red_tickets"] - baseline["full"]["blue_on_4plus_red_tickets"],
        "forward56_blue_top2": candidate["forward56"]["blue_top2_hits"] - baseline["forward56"]["blue_top2_hits"],
        "quarter_fixed_return": [
            candidate[f"quarter{i}"]["fixed_return_yuan"] - baseline[f"quarter{i}"]["fixed_return_yuan"]
            for i in range(1, 5)
        ],
    }


def run() -> dict:
    df = blue_exp.load_current_dataframe()
    print("[1/7] 运行V4.3", flush=True)
    r43 = v43.backtest(df, start=START)
    print("[2/7] 运行V4.5", flush=True)
    r45 = v45.backtest(df, start=START)
    print("[3/7] 构造V4.5.1基线", flush=True)
    baseline = gate451.apply_signal_gate(r43, r45)
    v4_score, ewma_score = _build_red_scores(df)
    new_blue = _build_all_new_blue(df)
    print("[6/7] 重配蓝球与红票", flush=True)
    variants = build_variants(baseline, new_blue, v4_score, ewma_score)
    print("[7/7] 汇总奖级", flush=True)

    summaries = {"v451_baseline": _segments(baseline)}
    summaries.update({name: _segments(frame) for name, frame in variants.items()})
    base = summaries["v451_baseline"]
    deltas = {name: _delta(summary, base) for name, summary in summaries.items() if name != "v451_baseline"}
    return {
        "model": "V4.5.2 红票强弱×蓝球候选配对实验",
        "principle": "红球集合完全不变，只改变蓝球候选来源及第一/第二蓝球与两张红票的配对",
        "summaries": summaries,
        "delta_vs_v451": deltas,
    }


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5.2 红蓝奖级配对实验")
    p.add_argument("--out", default="backtests/v4_5_2/joint_alignment_summary.json")
    args = p.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
