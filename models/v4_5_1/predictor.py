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


v4 = _load_module("ssq_v4_final_base", ROOT / "models" / "v4" / "predictor.py")
v43 = _load_module("ssq_v43_final_base", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load_module("ssq_v45_final_base", ROOT / "models" / "v4_5" / "predictor.py")
gate = _load_module("ssq_v451_gate", HERE / "signal_gate.py")
history_tool = _load_module("ssq_history_tool", ROOT / "tools" / "load_history.py")

START = 750
MODEL_START = 500
BLOCK = 250


def load_current_dataframe(
    base_path: str | Path = history_tool.BASE_SNAPSHOT,
    increment_path: str | Path | None = history_tool.CURRENT_INCREMENT,
) -> pd.DataFrame:
    rows = history_tool.load_history(base_path, increment_path)
    records = []
    for row in rows:
        records.append({
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        })
    return pd.DataFrame(records)


def _with_placeholder(df: pd.DataFrame) -> pd.DataFrame:
    if len(df) < START + 1:
        raise ValueError(f"历史数据至少需要 {START + 1} 期，当前只有 {len(df)} 期")
    row = {
        "seq": int(df.iloc[-1]["seq"]) + 1,
        "red1": 1,
        "red2": 2,
        "red3": 3,
        "red4": 4,
        "red5": 5,
        "red6": 6,
        "blue": 1,
    }
    return pd.concat([df, pd.DataFrame([row])], ignore_index=True)


def _current_block_start(target_t: int, start: int = MODEL_START, block: int = BLOCK) -> int:
    if target_t < start:
        raise ValueError("历史长度不足以进入模型训练区间")
    return start + ((target_t - start) // block) * block


def _predict_red_next(df: pd.DataFrame):
    n = len(df)
    extended = _with_placeholder(df)
    x, y, t_index, _ = v4.build_red_features(extended)
    target = t_index == n
    if target.sum() != 1:
        raise RuntimeError("未能构建下一期红球特征")

    bstart = _current_block_start(n)
    model_defs = {
        "all": None,
        "w1000": 1000,
        "w500": 500,
    }
    next_raw: dict[str, np.ndarray] = {}
    for name, window in model_defs.items():
        train = t_index < bstart
        if window is not None:
            train &= t_index >= max(200, bstart - window)
        model = v4._logistic()
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        next_raw[name] = model.predict_proba(x[target].reshape(-1, x.shape[-1]))[:, 1]

    historical, historical_raw, red_occ = v4.walk_forward_red(df)
    names = list(model_defs)
    left = max(MODEL_START, n - BLOCK)
    perf = []
    for name in names:
        hits = [v4._topk_hits(historical_raw[name][t], red_occ[t], 10) for t in range(left, n)]
        perf.append(float(np.mean(hits)))
    perf_arr = np.asarray(perf, dtype=float)
    baseline = v4.RED_PICK * 10 / v4.RED_COUNT
    z = np.clip((perf_arr - baseline) / 0.08, -3.0, 3.0)
    weights = np.exp(z - z.max())
    weights /= weights.sum()
    next_score = sum(weights[i] * v4._rank_score(next_raw[name]) for i, name in enumerate(names))
    return next_score, historical, red_occ, {name: float(weights[i]) for i, name in enumerate(names)}


def _predict_blue_next_custom(
    df: pd.DataFrame,
    *,
    train_window: int,
    columns: tuple[int, ...] | None,
    c: float,
):
    n = len(df)
    extended = _with_placeholder(df)
    x, y, t_index, _ = v4.build_blue_features(extended)
    target = t_index == n
    if target.sum() != 1:
        raise RuntimeError("未能构建下一期蓝球特征")
    bstart = _current_block_start(n)
    train = (t_index < bstart) & (t_index >= max(200, bstart - train_window))
    cols = np.arange(x.shape[-1], dtype=int) if columns is None else np.asarray(columns, dtype=int)
    model = v4._logistic(c=c)
    model.fit(x[train][:, :, cols].reshape(-1, len(cols)), y[train].reshape(-1))
    return model.predict_proba(x[target][:, :, cols].reshape(-1, len(cols)))[:, 1]


def _historical_pair_confidence(red_pred: np.ndarray, pair_cs: np.ndarray, marg_cs: np.ndarray):
    conf = np.full(len(red_pred), np.nan)
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1]
        _, _, value = v43._pair_partition(order[:12], v43._pair_affinity(t, pair_cs, marg_cs))
        conf[t] = value
    return conf


def _v43_next_groups(red_next: np.ndarray, red_hist: np.ndarray, red_occ: np.ndarray):
    n = len(red_hist)
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    affinity = v43._pair_affinity(n, pair_cs, marg_cs)
    order = np.argsort(red_next)[::-1]
    pair_a, pair_b, next_conf = v43._pair_partition(order[:12], affinity)
    split_a, split_b = order[:6], order[6:12]

    hist_conf = _historical_pair_confidence(red_hist, pair_cs, marg_cs)
    past = hist_conf[max(START, n - v43.PAIR_GATE_WINDOW):n]
    past = past[np.isfinite(past)]
    use_pair = True if len(past) < v43.PAIR_GATE_MIN_HISTORY else next_conf >= np.median(past)
    a, b = (pair_a, pair_b) if use_pair else (split_a, split_b)
    if red_next[a].sum() < red_next[b].sum():
        a, b = b, a
    return a, b, bool(use_pair), float(next_conf), affinity


def _v45_next_groups(
    red_next: np.ndarray,
    red_hist: np.ndarray,
    red_occ: np.ndarray,
    baseline_a: np.ndarray,
    baseline_b: np.ndarray,
    affinity: np.ndarray,
):
    n = len(red_hist)
    order = np.argsort(red_next)[::-1]
    aggressive_a, aggressive_b, next_conf = v45._aggressive_partition(order[:12], red_next, affinity)

    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    hist_conf = np.full(n, np.nan)
    for t in range(START, n):
        hist_order = np.argsort(red_hist[t])[::-1]
        _, _, value = v45._aggressive_partition(
            hist_order[:v45.RED_POOL_SIZE],
            red_hist[t],
            v43._pair_affinity(t, pair_cs, marg_cs),
        )
        hist_conf[t] = value
    past = hist_conf[max(START, n - v45.RED_GATE_WINDOW):n]
    past = past[np.isfinite(past)]
    use = True if len(past) < v45.RED_GATE_MIN_HISTORY else next_conf >= np.quantile(past, v45.RED_GATE_QUANTILE)
    a, b = (aggressive_a, aggressive_b) if use else (baseline_a, baseline_b)
    if red_next[a].sum() < red_next[b].sum():
        a, b = b, a
    return a, b, bool(use), float(next_conf)


def _signal_state(red_hist: np.ndarray, red_occ: np.ndarray):
    n = len(red_hist)
    hits = []
    left = max(START, n - gate.WINDOW)
    for t in range(left, n):
        order = np.argsort(red_hist[t])[::-1][:12]
        hits.append(int(red_occ[t, order].sum()))
    rolling_mean = float(np.mean(hits)) if hits else float("nan")
    active = len(hits) >= gate.MIN_HISTORY and rolling_mean >= gate.RANDOM_TOP12_MEAN
    return bool(active), rolling_mean, len(hits)


def _v45_next_blue(df: pd.DataFrame, red_hist: np.ndarray, red_occ: np.ndarray):
    n = len(df)
    historical_models = v45.build_blue_models(df)
    historical_groups, _, _ = v45.build_red_portfolio(red_hist, red_occ, START)
    actual_blue = df["blue"].to_numpy(int)

    next_models = {
        "base": _predict_blue_next_custom(df, train_window=1000, columns=None, c=0.2),
        "full750": _predict_blue_next_custom(
            df,
            train_window=v45.BLUE_FULL750_WINDOW,
            columns=None,
            c=v45.BLUE_FULL750_C,
        ),
        "freq1000": _predict_blue_next_custom(
            df,
            train_window=v45.BLUE_FREQ1000_WINDOW,
            columns=v45.BLUE_FREQ_COLUMNS,
            c=v45.BLUE_FREQ1000_C,
        ),
    }

    names = list(v45.BLUE_MODEL_NAMES)
    utilities = {}
    for name in names:
        utilities[name], _ = v45._blue_realized_utility(
            historical_models[name], historical_groups, red_occ, actual_blue, START
        )

    if n - START < 50:
        weights = np.asarray([1.0, 0.0, 0.0])
    else:
        left = max(START, n - v45.BLUE_FUSION_WINDOW)
        width = max(1, n - left)
        perf = np.asarray([utilities[name][left:n].sum() / width for name in names], dtype=float)
        z = (perf - perf.mean()) / (perf.std() + 1e-9)
        soft = np.exp(np.clip(z / v45.BLUE_FUSION_TEMPERATURE, -10.0, 10.0))
        weights = soft / soft.sum()
        weights = (
            (1.0 - v45.BLUE_FUSION_EQUAL_SHRINK) * weights
            + v45.BLUE_FUSION_EQUAL_SHRINK / len(names)
        )

    fused = np.zeros(v4.BLUE_COUNT, dtype=float)
    for i, name in enumerate(names):
        fused += weights[i] * v45._rank01(next_models[name])
    return fused, next_models["base"], {name: float(weights[i]) for i, name in enumerate(names)}


def _fmt_numbers(indices: np.ndarray) -> list[int]:
    return sorted(int(x) + 1 for x in indices)


def predict_next(df: pd.DataFrame) -> dict:
    if len(df) < START + 1:
        raise ValueError("历史数据不足，无法运行最终模型")

    red_next, red_hist, red_occ, red_weights = _predict_red_next(df)
    v43_a, v43_b, pair_gate, pair_conf, affinity = _v43_next_groups(red_next, red_hist, red_occ)
    v45_a, v45_b, concentration_gate, concentration_conf = _v45_next_groups(
        red_next, red_hist, red_occ, v43_a, v43_b, affinity
    )
    signal_active, signal_mean, signal_count = _signal_state(red_hist, red_occ)

    blue_v45, blue_v43, blue_weights = _v45_next_blue(df, red_hist, red_occ)
    if signal_active:
        strategy = "V4.5"
        a, b = v45_a, v45_b
        blue_score = blue_v45
    else:
        strategy = "V4.3"
        a, b = v43_a, v43_b
        blue_score = blue_v43

    blue_order = np.argsort(blue_score)[::-1]
    top12 = np.argsort(red_next)[::-1][:12]
    return {
        "model": "V4.5.1 可预测性信号门控",
        "history_draws": int(len(df)),
        "after_seq": int(df.iloc[-1]["seq"]),
        "selected_strategy": strategy,
        "tickets": [
            {"red": _fmt_numbers(a), "blue": int(blue_order[0] + 1)},
            {"red": _fmt_numbers(b), "blue": int(blue_order[1] + 1)},
        ],
        "red_top12": _fmt_numbers(top12),
        "signal": {
            "active": signal_active,
            "window": gate.WINDOW,
            "observations": signal_count,
            "top12_mean_hits": signal_mean,
            "random_baseline": gate.RANDOM_TOP12_MEAN,
        },
        "diagnostics": {
            "red_model_weights": red_weights,
            "v43_pair_gate": pair_gate,
            "v43_pair_confidence": pair_conf,
            "v45_concentration_gate": concentration_gate,
            "v45_concentration_confidence": concentration_conf,
            "v45_blue_weights": blue_weights,
        },
        "warning": "当前历史检验未证明存在稳定高于随机基线的预测能力；输出仅用于模型研究与复现实验。",
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="双色球 V4.5.1 最终研究版下一期执行器")
    parser.add_argument("--base", default=str(history_tool.BASE_SNAPSHOT))
    parser.add_argument("--increment", default=str(history_tool.CURRENT_INCREMENT))
    parser.add_argument("--out", default="predictions/latest.json")
    args = parser.parse_args()

    df = load_current_dataframe(args.base, args.increment)
    result = predict_next(df)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
