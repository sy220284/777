from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
V45_PATH = ROOT / "models" / "v4_5" / "predictor.py"
LOAD_PATH = ROOT / "tools" / "load_history.py"
START = 750
TOPK = (6, 8, 10, 12, 15, 18, 20)


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v45 = _load_module("ssq_v45_diag", V45_PATH)
loader = _load_module("ssq_history_loader_diag", LOAD_PATH)
v4 = v45.v4


def _df() -> pd.DataFrame:
    rows = loader.load_history()
    return pd.DataFrame([
        {
            "seq": int(r["seq"]),
            **{f"red{i}": int(r["red"][i - 1]) for i in range(1, 7)},
            "blue": int(r["blue"]),
        }
        for r in rows
    ])


def _rank_desc(values: np.ndarray) -> np.ndarray:
    order = np.argsort(values)[::-1]
    ranks = np.empty(len(values), dtype=int)
    ranks[order] = np.arange(1, len(values) + 1)
    return ranks


def main() -> None:
    p = argparse.ArgumentParser(description="V4.5高命中事件诊断，不调参")
    p.add_argument("--out", default="backtests/v5_research/high_hit_diagnostics.json")
    args = p.parse_args()

    df = _df()
    red_pred, raw, red_occ = v4.walk_forward_red(df)
    groups, active, confidence = v45.build_red_portfolio(red_pred, red_occ, START)
    blue_models = v45.build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_fused, _ = v45.fuse_blue_predictions(blue_models, groups, red_occ, actual_blue, START)

    coverage = {str(k): {str(h): 0 for h in range(7)} for k in TOPK}
    all6 = {str(k): 0 for k in TOPK}
    fiveplus = {str(k): 0 for k in TOPK}
    max_actual_rank_hist: dict[str, int] = {}
    high_events = []

    for t in range(START, len(df)):
        actual_idx = np.where(red_occ[t] > 0.5)[0]
        red_ranks = _rank_desc(red_pred[t])
        actual_ranks = sorted(int(red_ranks[x]) for x in actual_idx)
        max_rank = max(actual_ranks)
        max_actual_rank_hist[str(max_rank)] = max_actual_rank_hist.get(str(max_rank), 0) + 1

        for k in TOPK:
            hit = sum(r <= k for r in actual_ranks)
            coverage[str(k)][str(hit)] += 1
            all6[str(k)] += int(hit == 6)
            fiveplus[str(k)] += int(hit >= 5)

        a, b = groups[t]
        h1 = int(red_occ[t, a].sum())
        h2 = int(red_occ[t, b].sum())
        mh = max(h1, h2)
        if mh < 4:
            continue

        best = a if h1 >= h2 else b
        best_hit = h1 if h1 >= h2 else h2
        missing_actual = [int(x) for x in actual_idx if int(x) not in set(int(y) for y in best)]
        blue_rank = int(_rank_desc(blue_fused[t])[actual_blue[t] - 1])
        blue_order = np.argsort(blue_fused[t])[::-1] + 1
        high_events.append({
            "seq": int(df.iloc[t]["seq"]),
            "max_red_hit": int(mh),
            "ticket1_hit": int(h1),
            "ticket2_hit": int(h2),
            "actual_red": [int(x) + 1 for x in actual_idx],
            "actual_red_ranks": actual_ranks,
            "minimum_topk_for_all6": int(max_rank),
            "top12_actual_hit": int(sum(r <= 12 for r in actual_ranks)),
            "best_ticket": [int(x) + 1 for x in best],
            "missing_actual": [x + 1 for x in missing_actual],
            "missing_actual_ranks": [int(red_ranks[x]) for x in missing_actual],
            "aggressive_gate_active": bool(active[t]),
            "red_partition_confidence": float(confidence[t]),
            "actual_blue": int(actual_blue[t]),
            "actual_blue_rank": blue_rank,
            "selected_blue_top2": [int(x) for x in blue_order[:2]],
            "raw_model_actual_red_ranks": {
                name: sorted(int(_rank_desc(pred[t])[x]) for x in actual_idx)
                for name, pred in raw.items()
            },
            "best_ticket_hit": int(best_hit),
        })

    high5 = [e for e in high_events if e["max_red_hit"] >= 5]
    result = {
        "experiment": "V4.5 high-hit diagnostics; read-only, no parameter selection",
        "draws": len(df) - START,
        "topk_candidate_coverage": {
            str(k): {
                "hit_distribution": coverage[str(k)],
                "fiveplus_draws": fiveplus[str(k)],
                "all6_draws": all6[str(k)],
            }
            for k in TOPK
        },
        "minimum_topk_for_all6_histogram": max_actual_rank_hist,
        "red_4plus_event_count": len(high_events),
        "red_5plus_event_count": len(high5),
        "red_5plus_events": high5,
        "all_red_4plus_events": high_events,
    }

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")

    print("candidate coverage:")
    for k in TOPK:
        row = result["topk_candidate_coverage"][str(k)]
        print(f"top{k}: 5+={row['fiveplus_draws']} all6={row['all6_draws']}")
    print("5-red events:")
    for e in high5:
        print(json.dumps(e, ensure_ascii=False))


if __name__ == "__main__":
    main()
