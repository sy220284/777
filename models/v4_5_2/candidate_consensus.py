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


v4 = _load_module("ssq_v4_consensus", ROOT / "models" / "v4" / "predictor.py")
history_tool = _load_module("ssq_history_consensus", ROOT / "tools" / "load_history.py")

START = 750
MODEL_NAMES = ("all", "w1000", "w500")


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


def _rank01(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def build_variants(ensemble: np.ndarray, raw: dict[str, np.ndarray]) -> dict[str, np.ndarray]:
    n, width = ensemble.shape
    rank_stack = np.full((n, len(MODEL_NAMES), width), np.nan)
    for t in range(n):
        if not np.isfinite(ensemble[t]).all():
            continue
        for i, name in enumerate(MODEL_NAMES):
            rank_stack[t, i] = _rank01(raw[name][t])

    mean_rank = np.nanmean(rank_stack, axis=1)
    median_rank = np.nanmedian(rank_stack, axis=1)
    rank_std = np.nanstd(rank_stack, axis=1)

    # 结构性候选：不使用当前或未来开奖标签，不做结果驱动调权。
    variants = {
        "v4_dynamic": ensemble.copy(),
        "equal_mean": mean_rank,
        "median": median_rank,
        "dynamic_consensus_010": ensemble - 0.10 * rank_std,
        "dynamic_consensus_020": ensemble - 0.20 * rank_std,
        "equal_consensus_010": mean_rank - 0.10 * rank_std,
    }
    return variants


def _segment_metrics(score: np.ndarray, occ: np.ndarray, indices: np.ndarray) -> dict:
    top6_hits = []
    top12_hits = []
    for t in indices:
        order = np.argsort(score[t])[::-1]
        top6_hits.append(int(occ[t, order[:6]].sum()))
        top12_hits.append(int(occ[t, order[:12]].sum()))
    a = np.asarray(top6_hits, dtype=int)
    b = np.asarray(top12_hits, dtype=int)
    return {
        "draws": int(len(indices)),
        "top6_mean": float(a.mean()) if len(a) else 0.0,
        "top6_3plus": int((a >= 3).sum()),
        "top6_4plus": int((a >= 4).sum()),
        "top12_mean": float(b.mean()) if len(b) else 0.0,
        "top12_4plus": int((b >= 4).sum()),
        "top12_5plus": int((b >= 5).sum()),
        "top12_6": int((b >= 6).sum()),
    }


def summarize_variant(score: np.ndarray, occ: np.ndarray, seq: np.ndarray) -> dict:
    valid = np.arange(START, len(seq), dtype=int)
    out = {
        "full": _segment_metrics(score, occ, valid),
        "legacy": _segment_metrics(score, occ, valid[seq[valid] <= 3446]),
        "forward56": _segment_metrics(score, occ, valid[seq[valid] >= 3447]),
    }
    chunks = np.array_split(valid, 4)
    for i, idx in enumerate(chunks, start=1):
        out[f"quarter{i}"] = _segment_metrics(score, occ, idx)
    return out


def delta(candidate: dict, baseline: dict) -> dict:
    return {
        "full_top12_mean": candidate["full"]["top12_mean"] - baseline["full"]["top12_mean"],
        "full_top12_4plus": candidate["full"]["top12_4plus"] - baseline["full"]["top12_4plus"],
        "full_top12_5plus": candidate["full"]["top12_5plus"] - baseline["full"]["top12_5plus"],
        "full_top6_mean": candidate["full"]["top6_mean"] - baseline["full"]["top6_mean"],
        "forward56_top12_mean": candidate["forward56"]["top12_mean"] - baseline["forward56"]["top12_mean"],
        "forward56_top12_4plus": candidate["forward56"]["top12_4plus"] - baseline["forward56"]["top12_4plus"],
        "forward56_top12_5plus": candidate["forward56"]["top12_5plus"] - baseline["forward56"]["top12_5plus"],
        "quarter_top12_mean": [
            candidate[f"quarter{i}"]["top12_mean"] - baseline[f"quarter{i}"]["top12_mean"]
            for i in range(1, 5)
        ],
    }


def promotion_check(candidate: dict, baseline: dict) -> dict:
    d = delta(candidate, baseline)
    quarter_nonnegative = sum(x >= -1e-12 for x in d["quarter_top12_mean"])
    passed = (
        d["full_top12_mean"] > 0
        and d["forward56_top12_mean"] >= 0
        and d["full_top12_4plus"] >= 0
        and d["full_top12_5plus"] >= 0
        and d["forward56_top12_4plus"] >= 0
        and quarter_nonnegative >= 3
    )
    return {
        "passed": bool(passed),
        "quarter_nonnegative": int(quarter_nonnegative),
        "delta": d,
    }


def run() -> dict:
    df = load_current_dataframe()
    print("运行一次 V4 红球滚动预测并提取三个子模型", flush=True)
    ensemble, raw, occ = v4.walk_forward_red(df)
    seq = df["seq"].to_numpy(int)
    variants = build_variants(ensemble, raw)
    summaries = {name: summarize_variant(score, occ, seq) for name, score in variants.items()}
    baseline = summaries["v4_dynamic"]
    checks = {
        name: promotion_check(summary, baseline)
        for name, summary in summaries.items()
        if name != "v4_dynamic"
    }
    return {
        "model": "V4.5.2 候选池共识稳定性实验",
        "baseline": "v4_dynamic",
        "summaries": summaries,
        "promotion_checks": checks,
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="红球候选池共识稳定性实验")
    parser.add_argument("--out", default="backtests/v4_5_2/candidate_consensus_summary.json")
    args = parser.parse_args()
    result = run()
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2), flush=True)


if __name__ == "__main__":
    main()
