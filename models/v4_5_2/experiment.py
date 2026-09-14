from __future__ import annotations

import argparse
import importlib.util
import itertools
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


v4 = _load_module("ssq_v4_v452", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_v452", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_v452", ROOT / "models" / "v4_5" / "predictor.py")
gate451 = _load_module("ssq_v451_gate_v452", ROOT / "models" / "v4_5_1" / "signal_gate.py")
history_tool = _load_module("ssq_history_v452", ROOT / "tools" / "load_history.py")

START = 750
SIGNAL_WINDOW = 250
SIGNAL_MIN_HISTORY = 50
RANDOM_TOP12_MEAN = 6 * 12 / 33
# 单期 top12 命中数服从超几何分布；250期均值的随机标准误约0.068454。
RANDOM_TOP12_SE = 0.06845400217096982
STRONG_SIGNAL_THRESHOLD = RANDOM_TOP12_MEAN + RANDOM_TOP12_SE
FIXED_PRIZE = gate451.FIXED_PRIZE
PRIZE_ORDER = gate451.PRIZE_ORDER


def load_current_dataframe() -> pd.DataFrame:
    rows = history_tool.validate_current_state()
    records = []
    for row in rows:
        records.append({
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        })
    return pd.DataFrame(records)


def _subset_tables(pool_size: int):
    masks = []
    for choice in itertools.combinations(range(pool_size), 6):
        mask = np.zeros(pool_size, dtype=bool)
        mask[list(choice)] = True
        masks.append(mask)
    masks = np.asarray(masks)
    pairs = np.asarray(list(itertools.combinations(range(pool_size), 2)), dtype=int)
    within = (masks[:, pairs[:, 0]] & masks[:, pairs[:, 1]]).astype(float)
    return masks, pairs, within


def _zscore(x: np.ndarray) -> np.ndarray:
    return (x - x.mean()) / (x.std() + 1e-9)


def _concentrated_partition(
    top_pool: np.ndarray,
    red_score: np.ndarray,
    affinity: np.ndarray,
    *,
    max_overlap: int,
):
    pool_size = len(top_pool)
    masks, pairs, within = _subset_tables(pool_size)
    pair_values = affinity[top_pool[pairs[:, 0]], top_pool[pairs[:, 1]]]
    cohesion = within @ pair_values
    marginal = masks @ red_score[top_pool]
    score = v45.RED_PRIMARY_PAIR_WEIGHT * _zscore(cohesion) + v45.RED_MARGINAL_WEIGHT * _zscore(marginal)

    primary_idx = int(np.argmax(score))
    primary_mask = masks[primary_idx]
    overlaps = masks @ primary_mask.astype(float)
    allowed = np.where(overlaps <= max_overlap)[0]
    if len(allowed) == 0:
        raise RuntimeError(f"pool_size={pool_size} max_overlap={max_overlap} 没有可用第二票")
    secondary_idx = int(allowed[np.argmax(score[allowed])])
    return top_pool[primary_mask], top_pool[masks[secondary_idx]]


def build_strong_backtest(df: pd.DataFrame, *, pool_size: int, max_overlap: int) -> pd.DataFrame:
    red_pred, _, red_occ = v4.walk_forward_red(df)
    _, pair_cs, marg_cs = v45._baseline_red_groups(red_pred, red_occ, START)
    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}

    for t in range(START, len(df)):
        order = np.argsort(red_pred[t])[::-1]
        groups[t] = _concentrated_partition(
            order[:pool_size],
            red_pred[t],
            v43._pair_affinity(t, pair_cs, marg_cs),
            max_overlap=max_overlap,
        )

    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_fused, _ = v45.fuse_blue_predictions(blue_models, groups, red_occ, actual_blue, START)
    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)

    rows = []
    for t in range(START, len(df)):
        a, b = groups[t]
        blue_order = np.argsort(blue_fused[t])[::-1]
        blue1, blue2 = int(blue_order[0] + 1), int(blue_order[1] + 1)
        hit1, hit2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        prize1 = v4.prize_level(hit1, blue1 == actual_blue[t])
        prize2 = v4.prize_level(hit2, blue2 == actual_blue[t])
        best = prize1 if PRIZE_ORDER[prize1] <= PRIZE_ORDER[prize2] else prize2
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "actual_red": " ".join(f"{x:02d}" for x in reds[t]),
            "actual_blue": int(actual_blue[t]),
            "rank_top12": " ".join(f"{x + 1:02d}" for x in np.argsort(red_pred[t])[::-1][:12]),
            "ticket1": " ".join(f"{x + 1:02d}" for x in a),
            "blue1": blue1,
            "ticket2": " ".join(f"{x + 1:02d}" for x in b),
            "blue2": blue2,
            "red_overlap": int(len(set(int(x) for x in a) & set(int(x) for x in b))),
            "hit1": hit1,
            "hit2": hit2,
            "max_red_hit": max(hit1, hit2),
            "prize1": prize1,
            "prize2": prize2,
            "best_prize": best,
        })
    return pd.DataFrame(rows)


def _top12_hits(frame: pd.DataFrame) -> list[int]:
    hits = []
    for _, row in frame.iterrows():
        pool = {int(x) for x in str(row["rank_top12"]).split()}
        actual = {int(x) for x in str(row["actual_red"]).split()}
        hits.append(len(pool & actual))
    return hits


def apply_tiered_gate(
    v43_result: pd.DataFrame,
    v45_result: pd.DataFrame,
    strong_result: pd.DataFrame,
) -> pd.DataFrame:
    for other in (v45_result, strong_result):
        if not v43_result["seq"].reset_index(drop=True).equals(other["seq"].reset_index(drop=True)):
            raise ValueError("三种策略 seq 未对齐")

    hits = _top12_hits(v45_result)
    rows = []
    for i in range(len(v45_result)):
        left = max(0, i - SIGNAL_WINDOW)
        past = hits[left:i]
        mean = sum(past) / len(past) if past else float("nan")
        if len(past) < SIGNAL_MIN_HISTORY or mean < RANDOM_TOP12_MEAN:
            source = v43_result.iloc[i]
            tier = "low"
        elif mean < STRONG_SIGNAL_THRESHOLD:
            source = v45_result.iloc[i]
            tier = "medium"
        else:
            source = strong_result.iloc[i]
            tier = "strong"
        out = source.to_dict()
        out["signal_tier"] = tier
        out["signal_top12_mean"] = mean
        out["signal_random_baseline"] = RANDOM_TOP12_MEAN
        out["signal_strong_threshold"] = STRONG_SIGNAL_THRESHOLD
        rows.append(out)
    return pd.DataFrame(rows)


def summarize(frame: pd.DataFrame) -> dict:
    ticket_prizes = pd.concat([frame["prize1"], frame["prize2"]], ignore_index=True)
    best = frame["best_prize"].value_counts().to_dict()
    max_red = np.maximum(frame["hit1"].to_numpy(int), frame["hit2"].to_numpy(int))
    fixed = int(sum(FIXED_PRIZE[p] for p in ticket_prizes))
    return {
        "draws": int(len(frame)),
        "fixed_return_yuan": fixed,
        "fixed_return_ratio": float(fixed / max(1, len(frame) * 4)),
        "best_prize_counts": {k: int(best.get(k, 0)) for k in PRIZE_ORDER},
        "max_red_4plus_draws": int((max_red >= 4).sum()),
        "max_red_5plus_draws": int((max_red >= 5).sum()),
        "max_red_mean": float(max_red.mean()),
        "winning_draw_rate": float((frame["best_prize"] != "未中奖").mean()),
    }


def segment_summary(frame: pd.DataFrame) -> dict:
    out = {"full": summarize(frame)}
    out["legacy"] = summarize(frame[frame["seq"] <= 3446])
    out["forward56"] = summarize(frame[frame["seq"] >= 3447])
    chunks = np.array_split(np.arange(len(frame)), 4)
    for i, idx in enumerate(chunks, start=1):
        out[f"quarter{i}"] = summarize(frame.iloc[idx])
    return out


def _promotion_delta(candidate: dict, baseline: dict) -> dict:
    return {
        "full_fixed_return": candidate["full"]["fixed_return_yuan"] - baseline["full"]["fixed_return_yuan"],
        "forward56_fixed_return": candidate["forward56"]["fixed_return_yuan"] - baseline["forward56"]["fixed_return_yuan"],
        "full_4plus": candidate["full"]["max_red_4plus_draws"] - baseline["full"]["max_red_4plus_draws"],
        "full_5plus": candidate["full"]["max_red_5plus_draws"] - baseline["full"]["max_red_5plus_draws"],
        "forward56_4plus": candidate["forward56"]["max_red_4plus_draws"] - baseline["forward56"]["max_red_4plus_draws"],
        "quarter_fixed_return_delta": [
            candidate[f"quarter{i}"]["fixed_return_yuan"] - baseline[f"quarter{i}"]["fixed_return_yuan"]
            for i in range(1, 5)
        ],
    }


def benchmark(df: pd.DataFrame) -> dict:
    print("[1/4] 运行 V4.3 基线", flush=True)
    r43 = v43.backtest(df, start=START)
    print("[2/4] 运行 V4.5 基线", flush=True)
    r45 = v45.backtest(df, start=START)
    print("[3/4] 构造 V4.5.1 当前基线", flush=True)
    r451 = gate451.apply_signal_gate(r43, r45)

    variants = {
        "pool10_overlap4": (10, 4),
        "pool11_overlap4": (11, 4),
    }
    base_summary = segment_summary(r451)
    results = {
        "model": "V4.5.2 分层集中度实验",
        "rule": {
            "window": SIGNAL_WINDOW,
            "low": f"top12均值 < {RANDOM_TOP12_MEAN:.6f}: V4.3",
            "medium": f"{RANDOM_TOP12_MEAN:.6f} <= top12均值 < {STRONG_SIGNAL_THRESHOLD:.6f}: V4.5",
            "strong": f"top12均值 >= {STRONG_SIGNAL_THRESHOLD:.6f}: 更集中双票",
            "strong_threshold_basis": "随机top12命中均值 + 250期均值的1个超几何标准误",
        },
        "baseline_v451": base_summary,
        "variants": {},
    }

    for name, (pool, overlap) in variants.items():
        print(f"[4/4] 运行强信号方案 {name}", flush=True)
        strong = build_strong_backtest(df, pool_size=pool, max_overlap=overlap)
        tiered = apply_tiered_gate(r43, r45, strong)
        summary = segment_summary(tiered)
        tier_counts = tiered["signal_tier"].value_counts().to_dict()
        summary["signal_tier_counts"] = {k: int(v) for k, v in tier_counts.items()}
        summary["delta_vs_v451"] = _promotion_delta(summary, base_summary)
        results["variants"][name] = summary
    return results


def main() -> None:
    parser = argparse.ArgumentParser(description="V4.5.2 分层集中度实验")
    parser.add_argument("--out", default="backtests/v4_5_2/experiment_summary.json")
    args = parser.parse_args()
    df = load_current_dataframe()
    result = benchmark(df)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
