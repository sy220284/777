from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier

ROOT = Path(__file__).resolve().parents[1]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load_module("v46_v4", ROOT / "models" / "v4" / "predictor.py")
v45 = _load_module("v46_v45", ROOT / "models" / "v4_5" / "predictor.py")
history_tool = _load_module("v46_history", ROOT / "tools" / "load_history.py")

START = 750
MODEL_START = 500
BLOCK = 250
RANDOM_TOP12_MEAN = 6 * 12 / 33
SIGNAL_WINDOW = 250
SIGNAL_MIN_HISTORY = 50
LIVE_106 = {
    "seq": 3503,
    "red1": 6,
    "red2": 11,
    "red3": 13,
    "red4": 14,
    "red5": 22,
    "red6": 30,
    "blue": 14,
}
FIXED_PRIZE = {
    "一等奖": 0,
    "二等奖": 0,
    "三等奖": 3000,
    "四等奖": 200,
    "五等奖": 10,
    "六等奖": 5,
    "未中奖": 0,
}
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}

SEGMENTS = {
    "legacy_early": (751, 2446),
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def load_dataframe(include_live106: bool = True) -> pd.DataFrame:
    rows = history_tool.load_history(history_tool.BASE_SNAPSHOT, history_tool.CURRENT_INCREMENT)
    records = []
    for row in rows:
        records.append({
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        })
    df = pd.DataFrame(records)
    if include_live106 and int(df.iloc[-1]["seq"]) == 3502:
        df = pd.concat([df, pd.DataFrame([LIVE_106])], ignore_index=True)
    if include_live106 and int(df.iloc[-1]["seq"]) != 3503:
        raise ValueError(f"现场留出边界异常，末seq={int(df.iloc[-1]['seq'])}")
    return df


def _rank01(row: np.ndarray) -> np.ndarray:
    if not np.isfinite(row).all():
        return np.full_like(row, np.nan, dtype=float)
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def _rank_matrix(pred: np.ndarray) -> np.ndarray:
    out = np.full_like(pred, np.nan, dtype=float)
    for t in range(len(pred)):
        if np.isfinite(pred[t]).all():
            out[t] = _rank01(pred[t])
    return out


def walk_forward_hgb(
    df: pd.DataFrame,
    *,
    train_window: int,
    block: int = BLOCK,
) -> tuple[np.ndarray, np.ndarray]:
    """在与V4相同的时间边界上滚动训练保守的非线性挑战模型。"""
    x, y, t_index, occ = v4.build_red_features(df)
    n = len(df)
    pred = np.full((n, v4.RED_COUNT), np.nan, dtype=float)

    for bstart in range(MODEL_START, n, block):
        bend = min(n, bstart + block)
        train = (t_index < bstart) & (t_index >= max(200, bstart - train_window))
        test = (t_index >= bstart) & (t_index < bend)
        if not train.any() or not test.any():
            continue

        model = HistGradientBoostingClassifier(
            learning_rate=0.045,
            max_iter=60,
            max_leaf_nodes=7,
            min_samples_leaf=120,
            l2_regularization=3.0,
            max_bins=64,
            early_stopping=False,
            random_state=20260914,
        )
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        pred[t_index[test]] = p.reshape(-1, v4.RED_COUNT)
    return pred, occ


def blend_rank(base: np.ndarray, challenger: np.ndarray, weight: float) -> np.ndarray:
    base_rank = _rank_matrix(base)
    challenger_rank = _rank_matrix(challenger)
    out = (1.0 - weight) * base_rank + weight * challenger_rank
    invalid = ~np.isfinite(base_rank).all(axis=1) | ~np.isfinite(challenger_rank).all(axis=1)
    out[invalid] = np.nan
    return out


def average_rank(a: np.ndarray, b: np.ndarray) -> np.ndarray:
    ar, br = _rank_matrix(a), _rank_matrix(b)
    out = 0.5 * ar + 0.5 * br
    invalid = ~np.isfinite(ar).all(axis=1) | ~np.isfinite(br).all(axis=1)
    out[invalid] = np.nan
    return out


def candidate_metrics(pred: np.ndarray, occ: np.ndarray, seqs: np.ndarray, lo: int, hi: int) -> dict:
    indices = np.where((seqs >= lo) & (seqs <= hi))[0]
    indices = [t for t in indices if np.isfinite(pred[t]).all()]
    if not indices:
        return {"draws": 0}

    top6, top10, top12 = [], [], []
    for t in indices:
        order = np.argsort(pred[t])[::-1]
        top6.append(int(occ[t, order[:6]].sum()))
        top10.append(int(occ[t, order[:10]].sum()))
        top12.append(int(occ[t, order[:12]].sum()))

    a6 = np.asarray(top6)
    a10 = np.asarray(top10)
    a12 = np.asarray(top12)
    return {
        "draws": len(indices),
        "top6_mean": float(a6.mean()),
        "top10_mean": float(a10.mean()),
        "top12_mean": float(a12.mean()),
        "top12_4plus": int((a12 >= 4).sum()),
        "top12_5plus": int((a12 >= 5).sum()),
        "top12_6": int((a12 >= 6).sum()),
        "top6_4plus": int((a6 >= 4).sum()),
        "top6_5plus": int((a6 >= 5).sum()),
    }


def _signal_mask(pred: np.ndarray, occ: np.ndarray, start: int = START) -> tuple[np.ndarray, np.ndarray]:
    n = len(pred)
    hits = np.full(n, np.nan)
    for t in range(start, n):
        if np.isfinite(pred[t]).all():
            order = np.argsort(pred[t])[::-1][:12]
            hits[t] = int(occ[t, order].sum())

    active = np.zeros(n, dtype=bool)
    means = np.full(n, np.nan)
    for t in range(start, n):
        left = max(start, t - SIGNAL_WINDOW)
        past = hits[left:t]
        past = past[np.isfinite(past)]
        if len(past):
            means[t] = float(past.mean())
        active[t] = len(past) >= SIGNAL_MIN_HISTORY and means[t] >= RANDOM_TOP12_MEAN
    return active, means


def _best(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def portfolio_backtest(
    df: pd.DataFrame,
    pred: np.ndarray,
    occ: np.ndarray,
    *,
    blue_models: dict[str, np.ndarray],
    blue_base: np.ndarray,
    start: int = START,
) -> pd.DataFrame:
    """用同一红球排序分别构建V4.3/V4.5，再按V4.5.1信号做因果切换。"""
    v43_groups, _, _ = v45._baseline_red_groups(pred, occ, start)
    v45_groups, concentration_active, concentration_conf = v45.build_red_portfolio(pred, occ, start)
    actual_blue = df["blue"].to_numpy(int)
    blue_v45, _ = v45.fuse_blue_predictions(blue_models, v45_groups, occ, actual_blue, start)
    signal_active, signal_mean = _signal_mask(pred, occ, start)

    rows = []
    for t in range(start, len(df)):
        use_v45 = bool(signal_active[t])
        groups = v45_groups if use_v45 else v43_groups
        a, b = groups[t]
        blue_score = blue_v45[t] if use_v45 else blue_base[t]
        blue_order = np.argsort(blue_score)[::-1]
        b1, b2 = int(blue_order[0] + 1), int(blue_order[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "selected_strategy": "V4.5" if use_v45 else "V4.3",
            "signal_mean": float(signal_mean[t]) if np.isfinite(signal_mean[t]) else None,
            "concentration_gate": bool(concentration_active[t]),
            "concentration_confidence": float(concentration_conf[t]),
            "hit1": h1,
            "hit2": h2,
            "max_red_hit": max(h1, h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1, p2),
        })
    return pd.DataFrame(rows)


def prize_summary(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result["seq"] >= lo) & (result["seq"] <= hi)].copy()
    if part.empty:
        return {"draws": 0}
    tickets = pd.concat([part["prize1"], part["prize2"]], ignore_index=True)
    best = part["best_prize"].value_counts().to_dict()
    ticket_counts = tickets.value_counts().to_dict()
    fixed = int(sum(FIXED_PRIZE[p] for p in tickets))
    return {
        "draws": int(len(part)),
        "fixed_return_yuan": fixed,
        "fixed_return_ratio": float(fixed / (len(part) * 4)),
        "winning_draw_rate": float((part["best_prize"] != "未中奖").mean()),
        "max_red_hit_mean": float(part["max_red_hit"].mean()),
        "red_4plus_draws": int((part["max_red_hit"] >= 4).sum()),
        "red_5plus_draws": int((part["max_red_hit"] >= 5).sum()),
        "best_prize_counts": {k: int(best.get(k, 0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k: int(ticket_counts.get(k, 0)) for k in PRIZE_ORDER},
    }


def selection_score(metrics: dict, baseline: dict) -> tuple[bool, float, dict]:
    deltas = {}
    for seg in ("validation_1", "validation_2", "forward55"):
        deltas[seg] = metrics[seg]["top12_mean"] - baseline[seg]["top12_mean"]

    stable = (
        deltas["validation_1"] >= 0.0
        and deltas["validation_2"] >= 0.0
        and deltas["forward55"] >= -0.02
    )
    fourplus_wins = sum(
        metrics[seg]["top12_4plus"] >= baseline[seg]["top12_4plus"]
        for seg in ("validation_1", "validation_2", "forward55")
    )
    stable = stable and fourplus_wins >= 2
    score = (
        deltas["validation_1"]
        + deltas["validation_2"]
        + 0.25 * deltas["forward55"]
    )
    return bool(stable), float(score), {"top12_mean_delta": deltas, "fourplus_nonworse_segments": int(fourplus_wins)}


def main() -> None:
    parser = argparse.ArgumentParser(description="V4.6 非线性红球挑战模型严格滚动实验")
    parser.add_argument("--out", default="backtests/v4_6/nonlinear_experiment.json")
    parser.add_argument("--no-live106", action="store_true")
    args = parser.parse_args()

    df = load_dataframe(include_live106=not args.no_live106)
    seqs = df["seq"].to_numpy(int)
    base_pred, _, occ = v4.walk_forward_red(df)

    hgb1000, occ_hgb = walk_forward_hgb(df, train_window=1000)
    hgb500, _ = walk_forward_hgb(df, train_window=500)
    if not np.array_equal(occ, occ_hgb):
        raise RuntimeError("挑战模型与V4红球发生矩阵不一致")
    hgb_mean = average_rank(hgb1000, hgb500)

    configs = {
        "v451_base": base_pred,
        "hgb1000": hgb1000,
        "hgb500": hgb500,
        "hgb_mean": hgb_mean,
        "base75_hgb1000_25": blend_rank(base_pred, hgb1000, 0.25),
        "base65_hgb1000_35": blend_rank(base_pred, hgb1000, 0.35),
        "base50_hgb1000_50": blend_rank(base_pred, hgb1000, 0.50),
        "base75_hgbmean_25": blend_rank(base_pred, hgb_mean, 0.25),
        "base65_hgbmean_35": blend_rank(base_pred, hgb_mean, 0.35),
        "base50_hgbmean_50": blend_rank(base_pred, hgb_mean, 0.50),
    }

    candidate = {}
    for name, pred in configs.items():
        candidate[name] = {
            seg: candidate_metrics(pred, occ, seqs, lo, hi)
            for seg, (lo, hi) in SEGMENTS.items()
        }

    baseline = candidate["v451_base"]
    selection = {}
    eligible = []
    for name in configs:
        if name == "v451_base":
            continue
        stable, score, detail = selection_score(candidate[name], baseline)
        selection[name] = {"stable_candidate": stable, "score": score, **detail}
        if stable:
            eligible.append((score, name))
    eligible.sort(reverse=True)

    # 奖级层只评估基线和候选池门槛最好的至多3个，避免把奖级偶然事件反向用于大范围筛参。
    ranked = sorted(
        ((selection[name]["score"], name) for name in selection),
        reverse=True,
    )
    portfolio_names = ["v451_base"] + [name for _, name in ranked[:3]]
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    portfolio = {}
    for name in portfolio_names:
        result = portfolio_backtest(
            df,
            configs[name],
            occ,
            blue_models=blue_models,
            blue_base=blue_base,
        )
        portfolio[name] = {
            seg: prize_summary(result, lo, hi)
            for seg, (lo, hi) in SEGMENTS.items()
        }
        portfolio[name]["full"] = prize_summary(result, START + 1, int(seqs[-1]))

    report = {
        "experiment": "V4.6 非线性红球挑战模型",
        "history_draws": int(len(df)),
        "last_seq": int(seqs[-1]),
        "live106_included_as_holdout": not args.no_live106,
        "live106": LIVE_106 if not args.no_live106 else None,
        "principle": {
            "candidate_selection_excludes_live2": True,
            "portfolio_prize_not_used_for_broad_parameter_search": True,
            "promotion_requires_multi_segment_candidate_stability": True,
        },
        "segments": SEGMENTS,
        "candidate_metrics": candidate,
        "selection": selection,
        "eligible_candidates": [name for _, name in eligible],
        "portfolio_evaluation": portfolio,
    }

    out = ROOT / args.out
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
