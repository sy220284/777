from __future__ import annotations

import argparse
import importlib.util
import itertools
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

# 红球高奖级集中层：平台中部参数，不采用单点最优值。
RED_POOL_SIZE = 12
RED_PRIMARY_PAIR_WEIGHT = 1.0
RED_MARGINAL_WEIGHT = 1.25
RED_MAX_OVERLAP = 3
RED_GATE_WINDOW = 800
RED_GATE_QUANTILE = 0.75
RED_GATE_MIN_HISTORY = 50

# 蓝球奖级效用融合层。
BLUE_FULL750_WINDOW = 750
BLUE_FREQ1000_WINDOW = 1000
BLUE_FREQ1000_C = 0.05
BLUE_FULL750_C = 0.2
BLUE_FUSION_WINDOW = 1100
BLUE_FUSION_TEMPERATURE = 0.75
BLUE_FUSION_EQUAL_SHRINK = 0.15
BLUE_TOP2_SHRINK_WEIGHT = 20.0
BLUE_FREQ_COLUMNS = (0, 1, 2, 3, 4, 5, 9, 10, 11, 12, 13, 16)
BLUE_MODEL_NAMES = ("base", "full750", "freq1000")

PRIZE_RANK = v43.PRIZE_RANK
FIXED_PRIZE = v43.FIXED_PRIZE
PRIZE_UTILITY = {
    "一等奖": 100.0,
    "二等奖": 50.0,
    "三等奖": 20.0,
    "四等奖": 8.0,
    "五等奖": 2.0,
    "六等奖": 0.5,
    "未中奖": 0.0,
}


def _red_subset_tables():
    masks = []
    for choice in itertools.combinations(range(RED_POOL_SIZE), 6):
        mask = np.zeros(RED_POOL_SIZE, dtype=bool)
        mask[list(choice)] = True
        masks.append(mask)
    masks = np.asarray(masks)
    local_pairs = np.asarray(list(itertools.combinations(range(RED_POOL_SIZE), 2)), dtype=int)
    within = (masks[:, local_pairs[:, 0]] & masks[:, local_pairs[:, 1]]).astype(float)
    return masks, local_pairs, within


RED_MASKS, RED_LOCAL_PAIRS, RED_WITHIN = _red_subset_tables()


def _zscore(x: np.ndarray) -> np.ndarray:
    return (x - x.mean()) / (x.std() + 1e-9)


def _aggressive_partition(top12: np.ndarray, red_score: np.ndarray, affinity: np.ndarray):
    pair_values = affinity[top12[RED_LOCAL_PAIRS[:, 0]], top12[RED_LOCAL_PAIRS[:, 1]]]
    cohesion = RED_WITHIN @ pair_values
    marginal = RED_MASKS @ red_score[top12]
    score = RED_PRIMARY_PAIR_WEIGHT * _zscore(cohesion) + RED_MARGINAL_WEIGHT * _zscore(marginal)

    primary_idx = int(np.argmax(score))
    primary_mask = RED_MASKS[primary_idx]
    overlaps = RED_MASKS @ primary_mask.astype(float)
    allowed = np.where(overlaps <= RED_MAX_OVERLAP)[0]
    secondary_idx = int(allowed[np.argmax(score[allowed])])

    primary = top12[primary_mask]
    secondary = top12[RED_MASKS[secondary_idx]]
    confidence = float((score[primary_idx] - np.median(score)) / (score.std() + 1e-9))
    return primary, secondary, confidence


def _baseline_red_groups(red_pred: np.ndarray, red_occ: np.ndarray, start: int):
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    n = len(red_pred)
    pair_choice: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    pair_conf = np.full(n, np.nan)

    for t in range(start, n):
        order = np.argsort(red_pred[t])[::-1]
        a, b, confidence = v43._pair_partition(
            order[:12],
            v43._pair_affinity(t, pair_cs, marg_cs),
        )
        pair_choice[t] = (a, b)
        pair_conf[t] = confidence

    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    for t in range(start, n):
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
        groups[t] = (a, b)
    return groups, pair_cs, marg_cs


def build_red_portfolio(red_pred: np.ndarray, red_occ: np.ndarray, start: int):
    baseline, pair_cs, marg_cs = _baseline_red_groups(red_pred, red_occ, start)
    n = len(red_pred)
    aggressive: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    confidence = np.full(n, np.nan)

    for t in range(start, n):
        order = np.argsort(red_pred[t])[::-1]
        a, b, conf = _aggressive_partition(
            order[:RED_POOL_SIZE],
            red_pred[t],
            v43._pair_affinity(t, pair_cs, marg_cs),
        )
        aggressive[t] = (a, b)
        confidence[t] = conf

    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    active = np.zeros(n, dtype=bool)
    for t in range(start, n):
        past = confidence[max(start, t - RED_GATE_WINDOW):t]
        past = past[np.isfinite(past)]
        use = (
            True
            if len(past) < RED_GATE_MIN_HISTORY
            else confidence[t] >= np.quantile(past, RED_GATE_QUANTILE)
        )
        active[t] = use
        groups[t] = aggressive[t] if use else baseline[t]
    return groups, active, confidence


def _walk_forward_blue_custom(
    df: pd.DataFrame,
    *,
    train_window: int,
    columns: tuple[int, ...] | None,
    c: float,
    start: int = 500,
    block: int = 250,
):
    x, y, t_index, _ = v4.build_blue_features(df)
    n = len(df)
    pred = np.full((n, v4.BLUE_COUNT), np.nan)
    cols = np.arange(x.shape[-1], dtype=int) if columns is None else np.asarray(columns, dtype=int)

    for bstart in range(start, n, block):
        bend = min(n, bstart + block)
        train = (t_index < bstart) & (t_index >= max(200, bstart - train_window))
        test = (t_index >= bstart) & (t_index < bend)
        model = v4._logistic(c=c)
        model.fit(
            x[train][:, :, cols].reshape(-1, len(cols)),
            y[train].reshape(-1),
        )
        p = model.predict_proba(x[test][:, :, cols].reshape(-1, len(cols)))[:, 1]
        pred[t_index[test]] = p.reshape(-1, v4.BLUE_COUNT)
    return pred


def build_blue_models(df: pd.DataFrame):
    base, _ = v4.walk_forward_blue(df)
    full750 = _walk_forward_blue_custom(
        df,
        train_window=BLUE_FULL750_WINDOW,
        columns=None,
        c=BLUE_FULL750_C,
    )
    freq1000 = _walk_forward_blue_custom(
        df,
        train_window=BLUE_FREQ1000_WINDOW,
        columns=BLUE_FREQ_COLUMNS,
        c=BLUE_FREQ1000_C,
    )
    return {"base": base, "full750": full750, "freq1000": freq1000}


def _rank01(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def _blue_realized_utility(
    pred: np.ndarray,
    groups: dict[int, tuple[np.ndarray, np.ndarray]],
    red_occ: np.ndarray,
    actual_blue: np.ndarray,
    start: int,
):
    n = len(actual_blue)
    utility = np.zeros(n, dtype=float)
    top2_hit = np.zeros(n, dtype=float)
    for t in range(start, n):
        order = np.argsort(pred[t])[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        a, b = groups[t]
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        best = p1 if PRIZE_RANK[p1] <= PRIZE_RANK[p2] else p2
        hit = float(actual_blue[t] in (b1, b2))
        top2_hit[t] = hit
        utility[t] = PRIZE_UTILITY[best] + BLUE_TOP2_SHRINK_WEIGHT * hit
    return utility, top2_hit


def fuse_blue_predictions(
    models: dict[str, np.ndarray],
    groups: dict[int, tuple[np.ndarray, np.ndarray]],
    red_occ: np.ndarray,
    actual_blue: np.ndarray,
    start: int,
):
    names = list(BLUE_MODEL_NAMES)
    utilities = {}
    for name in names:
        utilities[name], _ = _blue_realized_utility(
            models[name], groups, red_occ, actual_blue, start
        )
    cumsums = {
        name: np.concatenate([[0.0], np.cumsum(utilities[name])])
        for name in names
    }

    n = len(actual_blue)
    fused = np.full((n, v4.BLUE_COUNT), np.nan)
    weights = np.full((n, len(names)), np.nan)
    for t in range(start, n):
        j = t - start
        if j < 50:
            w = np.asarray([1.0, 0.0, 0.0])
        else:
            left_t = max(start, t - BLUE_FUSION_WINDOW)
            width = max(1, t - left_t)
            perf = np.asarray([
                (cumsums[name][t] - cumsums[name][left_t]) / width
                for name in names
            ])
            z = (perf - perf.mean()) / (perf.std() + 1e-9)
            soft = np.exp(np.clip(z / BLUE_FUSION_TEMPERATURE, -10.0, 10.0))
            w = soft / soft.sum()
            w = (1.0 - BLUE_FUSION_EQUAL_SHRINK) * w + BLUE_FUSION_EQUAL_SHRINK / len(names)
        score = np.zeros(v4.BLUE_COUNT, dtype=float)
        for i, name in enumerate(names):
            score += w[i] * _rank01(models[name][t])
        fused[t] = score
        weights[t] = w
    return fused, weights


def backtest(df: pd.DataFrame, start: int = 750) -> pd.DataFrame:
    red_pred, _, red_occ = v4.walk_forward_red(df)
    groups, red_active, red_conf = build_red_portfolio(red_pred, red_occ, start)
    blue_models = build_blue_models(df)
    actual_blue = df["blue"].to_numpy(int)
    blue_fused, blue_weights = fuse_blue_predictions(
        blue_models, groups, red_occ, actual_blue, start
    )

    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    rows = []
    for t in range(start, len(df)):
        a, b = groups[t]
        blue_order = np.argsort(blue_fused[t])[::-1]
        blue1, blue2 = int(blue_order[0] + 1), int(blue_order[1] + 1)
        hit1, hit2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        prize1 = v4.prize_level(hit1, blue1 == actual_blue[t])
        prize2 = v4.prize_level(hit2, blue2 == actual_blue[t])
        best = prize1 if PRIZE_RANK[prize1] <= PRIZE_RANK[prize2] else prize2
        overlap = int(len(set(int(x) for x in a) & set(int(x) for x in b)))
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "actual_red": " ".join(f"{x:02d}" for x in reds[t]),
            "actual_blue": int(actual_blue[t]),
            "rank_top12": " ".join(f"{x + 1:02d}" for x in np.argsort(red_pred[t])[::-1][:12]),
            "ticket1": " ".join(f"{x + 1:02d}" for x in a),
            "blue1": blue1,
            "ticket2": " ".join(f"{x + 1:02d}" for x in b),
            "blue2": blue2,
            "red_overlap": overlap,
            "hit1": hit1,
            "hit2": hit2,
            "max_red_hit": max(hit1, hit2),
            "prize1": prize1,
            "prize2": prize2,
            "best_prize": best,
            "red_concentration_gate": bool(red_active[t]),
            "red_concentration_confidence": float(red_conf[t]),
            "blue_weight_base": float(blue_weights[t, 0]),
            "blue_weight_full750": float(blue_weights[t, 1]),
            "blue_weight_freq1000": float(blue_weights[t, 2]),
        })
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame) -> dict:
    # 每期最佳奖级用于衡量“这一期最高中了几等奖”；奖金与单注中奖率必须逐票统计，
    # 否则同一期两张票都中奖时会漏计第二张票的奖金。
    best_counts = result["best_prize"].value_counts().to_dict()
    ticket_prizes = pd.concat([result["prize1"], result["prize2"]], ignore_index=True)
    ticket_counts = ticket_prizes.value_counts().to_dict()
    fixed_return = int(sum(FIXED_PRIZE[p] for p in ticket_prizes))
    draws = len(result)
    tickets = draws * 2
    maxhit = result["max_red_hit"].value_counts().to_dict()
    return {
        "model": "双色球高奖级集中组合模型 V4.5",
        "tested_draws": draws,
        "tickets_per_draw": 2,
        "total_tickets": tickets,
        "ticket_cost_yuan": draws * 4,
        "best_prize_counts": {k: int(best_counts.get(k, 0)) for k in PRIZE_RANK},
        "ticket_prize_counts": {k: int(ticket_counts.get(k, 0)) for k in PRIZE_RANK},
        "winning_draw_rate": float((result["best_prize"] != "未中奖").mean()),
        "winning_ticket_rate": float((ticket_prizes != "未中奖").mean()),
        "double_winning_draws": int(((result["prize1"] != "未中奖") & (result["prize2"] != "未中奖")).sum()),
        "fixed_prize_return_yuan": fixed_return,
        "fixed_prize_return_ratio": float(fixed_return / (draws * 4)),
        "red_max_hit_distribution": {str(k): int(v) for k, v in sorted(maxhit.items())},
        "red_5plus_draws": int((result["max_red_hit"] >= 5).sum()),
        "red_concentration_gate_active_rate": float(result["red_concentration_gate"].mean()),
        "average_red_overlap": float(result["red_overlap"].mean()),
        "average_blue_weights": {
            "base": float(result["blue_weight_base"].mean()),
            "full750": float(result["blue_weight_full750"].mean()),
            "freq1000": float(result["blue_weight_freq1000"].mean()),
        },
        "note": "V4.5把优化目标从覆盖率进一步上移到奖级集中：仅在高置信状态允许最多3个红球重合，并用过去奖级效用对三个蓝球模型做收缩式融合。历史提升不代表未来可稳定预测。",
    }


def main():
    parser = argparse.ArgumentParser(description="双色球 V4.5 高奖级集中组合模型")
    parser.add_argument("--history", default="data/ssq_history_2003_2026-05-03.csv.gz")
    parser.add_argument("--out", default="backtests/v4_5/backtest.csv")
    parser.add_argument("--summary", default="backtests/v4_5/summary.json")
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
