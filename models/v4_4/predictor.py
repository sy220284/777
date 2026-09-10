from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
V4_PATH = HERE.parent / "v4" / "predictor.py"
V43_PATH = HERE.parent / "v4_3" / "predictor.py"


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load_module("ssq_v4_base", V4_PATH)
v43 = _load_module("ssq_v43_base", V43_PATH)

BLUE_ALT_WINDOW = 750
BLUE_ALT_C = 0.05
BLUE_SELECTOR_WINDOW = 900
BLUE_ALT_COLUMNS = tuple(range(14)) + (16,)
PRIZE_RANK = v43.PRIZE_RANK
FIXED_PRIZE = v43.FIXED_PRIZE


def walk_forward_blue_alt(df: pd.DataFrame, start: int = 500, block: int = 250):
    x, y, t_index, occ = v4.build_blue_features(df)
    n = len(df)
    pred = np.full((n, v4.BLUE_COUNT), np.nan)
    cols = np.asarray(BLUE_ALT_COLUMNS, dtype=int)

    for bstart in range(start, n, block):
        bend = min(n, bstart + block)
        train = (t_index < bstart) & (t_index >= max(200, bstart - BLUE_ALT_WINDOW))
        test = (t_index >= bstart) & (t_index < bend)
        model = v4._logistic(c=BLUE_ALT_C)
        model.fit(
            x[train][:, :, cols].reshape(-1, len(cols)),
            y[train].reshape(-1),
        )
        p = model.predict_proba(x[test][:, :, cols].reshape(-1, len(cols)))[:, 1]
        pred[t_index[test]] = p.reshape(-1, v4.BLUE_COUNT)
    return pred, occ


def _top2_hit_series(pred: np.ndarray, blues: np.ndarray, start: int) -> np.ndarray:
    hit = np.zeros(len(blues), dtype=np.int8)
    for t in range(start, len(blues)):
        if not np.isfinite(pred[t]).all():
            continue
        top2 = np.argsort(pred[t])[::-1][:2] + 1
        hit[t] = int(int(blues[t]) in top2)
    return hit


def _select_blue_source(
    t: int,
    base_cumsum: np.ndarray,
    alt_cumsum: np.ndarray,
    start: int,
) -> str:
    left = max(start, t - BLUE_SELECTOR_WINDOW)
    width = max(1, t - left)
    base_rate = (base_cumsum[t] - base_cumsum[left]) / width
    alt_rate = (alt_cumsum[t] - alt_cumsum[left]) / width
    return "alt" if alt_rate > base_rate else "base"


def backtest(df: pd.DataFrame, start: int = 750) -> pd.DataFrame:
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_alt, _ = walk_forward_blue_alt(df)

    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    blues = df["blue"].to_numpy(int)
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)

    pair_choice: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    pair_conf = np.full(len(df), np.nan)
    for t in range(start, len(df)):
        order = np.argsort(red_pred[t])[::-1]
        top12 = order[:12]
        a, b, confidence = v43._pair_partition(
            top12,
            v43._pair_affinity(t, pair_cs, marg_cs),
        )
        pair_choice[t] = (a, b)
        pair_conf[t] = confidence

    base_hit = _top2_hit_series(blue_base, blues, start)
    alt_hit = _top2_hit_series(blue_alt, blues, start)
    base_cs = np.concatenate([[0], np.cumsum(base_hit)])
    alt_cs = np.concatenate([[0], np.cumsum(alt_hit)])

    rows = []
    for t in range(start, len(df)):
        order = np.argsort(red_pred[t])[::-1]
        split_a, split_b = order[:6], order[6:12]
        past = pair_conf[max(start, t - v43.PAIR_GATE_WINDOW):t]
        past = past[np.isfinite(past)]
        use_pair = (
            True
            if len(past) < v43.PAIR_GATE_MIN_HISTORY
            else pair_conf[t] >= np.median(past)
        )
        a, b = pair_choice[t] if use_pair else (split_a, split_b)

        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a

        blue_source = _select_blue_source(t, base_cs, alt_cs, start)
        blue_score = blue_alt[t] if blue_source == "alt" else blue_base[t]
        blue_order = np.argsort(blue_score)[::-1]
        blue1, blue2 = int(blue_order[0] + 1), int(blue_order[1] + 1)

        hit1 = int(red_occ[t, a].sum())
        hit2 = int(red_occ[t, b].sum())
        prize1 = v4.prize_level(hit1, blue1 == blues[t])
        prize2 = v4.prize_level(hit2, blue2 == blues[t])
        best = prize1 if PRIZE_RANK[prize1] <= PRIZE_RANK[prize2] else prize2
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "actual_red": " ".join(f"{x:02d}" for x in reds[t]),
            "actual_blue": int(blues[t]),
            "rank_top12": " ".join(f"{x + 1:02d}" for x in order[:12]),
            "ticket1": " ".join(f"{x + 1:02d}" for x in a),
            "blue1": blue1,
            "ticket2": " ".join(f"{x + 1:02d}" for x in b),
            "blue2": blue2,
            "hit1": hit1,
            "hit2": hit2,
            "prize1": prize1,
            "prize2": prize2,
            "best_prize": best,
            "pair_gate": bool(use_pair),
            "pair_confidence": float(pair_conf[t]),
            "blue_source": blue_source,
        })
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame) -> dict:
    counts = result["best_prize"].value_counts().to_dict()
    fixed_return = int(sum(FIXED_PRIZE[p] for p in result["best_prize"]))
    draws = len(result)
    return {
        "model": "双色球多尺度联合关系与自适应蓝球模型 V4.4",
        "tested_draws": draws,
        "tickets_per_draw": 2,
        "total_tickets": draws * 2,
        "ticket_cost_yuan": draws * 4,
        "best_prize_counts": {k: int(counts.get(k, 0)) for k in PRIZE_RANK},
        "winning_draw_rate": float((result["best_prize"] != "未中奖").mean()),
        "fixed_prize_return_yuan": fixed_return,
        "fixed_prize_return_ratio": float(fixed_return / (draws * 4)),
        "pair_gate_active_rate": float(result["pair_gate"].mean()),
        "blue_alt_active_rate": float((result["blue_source"] == "alt").mean()),
        "blue_selector_window": BLUE_SELECTOR_WINDOW,
        "blue_alt_train_window": BLUE_ALT_WINDOW,
        "note": "候选池4+状态识别、二级概率校准、监督式号码对模型、排名位置共现均未通过跨段验证；V4.4仅增加通过四分段复核的蓝球双模型因果选择。",
    }


def main():
    parser = argparse.ArgumentParser(description="双色球 V4.4 自适应蓝球模型")
    parser.add_argument("--history", default="data/ssq_history_2003_2026-05-03.csv.gz")
    parser.add_argument("--out", default="backtests/v4_4/backtest.csv")
    parser.add_argument("--summary", default="backtests/v4_4/summary.json")
    args = parser.parse_args()

    history = v4.load_history(args.history)
    result = backtest(history)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(out, index=False, encoding="utf-8-sig")
    summary_path = Path(args.summary)
    summary_path.parent.mkdir(parents=True, exist_ok=True)
    summary_path.write_text(
        json.dumps(summarize(result), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(result["best_prize"].value_counts().to_string())


if __name__ == "__main__":
    main()
