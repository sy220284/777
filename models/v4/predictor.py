from __future__ import annotations

import gzip
import math
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.linear_model import LogisticRegression
from sklearn.pipeline import make_pipeline
from sklearn.preprocessing import StandardScaler

RED_COUNT = 33
BLUE_COUNT = 16
RED_PICK = 6
RED_WINDOWS = (5, 10, 20, 50, 100, 200)
BLUE_WINDOWS = (5, 10, 20, 50, 100, 200)


def load_history(path: str | Path) -> pd.DataFrame:
    path = Path(path)
    opener = gzip.open if path.suffix == ".gz" else open
    with opener(path, "rt", encoding="utf-8-sig", newline="") as f:
        df = pd.read_csv(f)
    required = ["seq", *(f"red{i}" for i in range(1, 7)), "blue"]
    missing = [c for c in required if c not in df.columns]
    if missing:
        raise ValueError(f"历史数据缺少字段: {missing}")
    return df[required].copy()


def _occurrence_matrix(values: np.ndarray, count: int) -> np.ndarray:
    out = np.zeros((len(values), count), dtype=np.int8)
    if values.ndim == 1:
        for i, n in enumerate(values):
            out[i, int(n) - 1] = 1
    else:
        for i, row in enumerate(values):
            out[i, row.astype(int) - 1] = 1
    return out


def build_red_features(df: pd.DataFrame, min_history: int = 200):
    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    n_draws = len(df)
    occ = _occurrence_matrix(reds, RED_COUNT)
    draw_sum = reds.sum(axis=1)
    draw_odd = (reds % 2 == 1).sum(axis=1)
    draw_big = (reds >= 17).sum(axis=1)

    last_seen = np.full(RED_COUNT, -1, dtype=int)
    gap_sum = np.zeros(RED_COUNT, dtype=float)
    gap_count = np.zeros(RED_COUNT, dtype=int)
    pair_counts = np.zeros((RED_COUNT, RED_COUNT), dtype=float)
    num_counts = np.zeros(RED_COUNT, dtype=float)

    xs, ys, ts = [], [], []
    for t in range(n_draws):
        if t >= min_history:
            freqs = {w: occ[t - w:t].mean(axis=0) for w in RED_WINDOWS}
            long_freq = occ[:t].mean(axis=0)
            mu_sum = draw_sum[:t].mean()
            sd_sum = draw_sum[:t].std(ddof=0) or 1.0
            last_sum_z = (draw_sum[t - 1] - mu_sum) / sd_sum
            recent5_sum_z = (draw_sum[t - 5:t].mean() - mu_sum) / sd_sum
            recent20_sum_z = (draw_sum[t - 20:t].mean() - mu_sum) / sd_sum
            last_odd_dev = float(draw_odd[t - 1] - 3.0)
            last_big_dev = float(draw_big[t - 1] - 6 * 17 / 33)
            prev = set(int(x) for x in reds[t - 1])
            recent3 = set(int(x) for x in reds[max(0, t - 3):t].ravel())

            cond = (pair_counts + 1.0) / (num_counts[:, None] + 5.5)
            prev_idx = np.array([x - 1 for x in prev], dtype=int)
            recent3_idx = np.array([x - 1 for x in recent3], dtype=int)

            rows = []
            for n0 in range(RED_COUNT):
                n = n0 + 1
                gap = t - last_seen[n0] if last_seen[n0] >= 0 else t + 1
                mean_gap = gap_sum[n0] / gap_count[n0] if gap_count[n0] else 5.5
                cooc_prev = float(cond[prev_idx, n0].mean()) if len(prev_idx) else 0.0
                cooc_recent3 = float(cond[recent3_idx, n0].mean()) if len(recent3_idx) else 0.0
                is_odd = float(n % 2 == 1)
                is_big = float(n >= 17)
                num_norm = (n - 17) / 16
                rows.append([
                    *(freqs[w][n0] for w in RED_WINDOWS),
                    freqs[5][n0] - freqs[20][n0],
                    freqs[10][n0] - freqs[50][n0],
                    freqs[20][n0] - freqs[100][n0],
                    gap,
                    math.log1p(gap),
                    gap / (mean_gap + 1e-9),
                    float(n in prev),
                    sum(abs(n - x) == 1 for x in prev),
                    sum(abs(n - x) == 2 for x in prev),
                    cooc_prev,
                    cooc_recent3,
                    num_norm,
                    is_odd,
                    is_big,
                    float(n <= 11),
                    float(12 <= n <= 22),
                    float(n >= 23),
                    num_norm * (-last_sum_z),
                    is_odd * (-last_odd_dev),
                    is_big * (-last_big_dev),
                    last_sum_z,
                    last_odd_dev,
                    last_big_dev,
                    recent5_sum_z,
                    recent20_sum_z,
                    long_freq[n0],
                ])
            xs.append(np.asarray(rows, dtype=float))
            ys.append(occ[t].copy())
            ts.append(t)

        current = reds[t] - 1
        for n0 in current:
            if last_seen[n0] >= 0:
                gap_sum[n0] += t - last_seen[n0]
                gap_count[n0] += 1
            last_seen[n0] = t
        num_counts[current] += 1
        for i in current:
            for j in current:
                if i != j:
                    pair_counts[i, j] += 1

    return np.stack(xs), np.stack(ys), np.asarray(ts), occ


def build_blue_features(df: pd.DataFrame, min_history: int = 200):
    blues = df["blue"].to_numpy(int)
    occ = _occurrence_matrix(blues, BLUE_COUNT)
    last_seen = np.full(BLUE_COUNT, -1, dtype=int)
    gap_sum = np.zeros(BLUE_COUNT, dtype=float)
    gap_count = np.zeros(BLUE_COUNT, dtype=int)
    xs, ys, ts = [], [], []

    for t in range(len(df)):
        if t >= min_history:
            freqs = {w: occ[t - w:t].mean(axis=0) for w in BLUE_WINDOWS}
            long_freq = occ[:t].mean(axis=0)
            prev = int(blues[t - 1])
            rows = []
            for n0 in range(BLUE_COUNT):
                n = n0 + 1
                gap = t - last_seen[n0] if last_seen[n0] >= 0 else t + 1
                mean_gap = gap_sum[n0] / gap_count[n0] if gap_count[n0] else 16.0
                rows.append([
                    *(freqs[w][n0] for w in BLUE_WINDOWS),
                    freqs[5][n0] - freqs[20][n0],
                    freqs[10][n0] - freqs[50][n0],
                    freqs[20][n0] - freqs[100][n0],
                    gap,
                    math.log1p(gap),
                    gap / (mean_gap + 1e-9),
                    float(n == prev),
                    float(abs(n - prev) == 1),
                    (n - 8.5) / 7.5,
                    float(n % 2 == 1),
                    long_freq[n0],
                ])
            xs.append(np.asarray(rows, dtype=float))
            ys.append(occ[t].copy())
            ts.append(t)

        n0 = blues[t] - 1
        if last_seen[n0] >= 0:
            gap_sum[n0] += t - last_seen[n0]
            gap_count[n0] += 1
        last_seen[n0] = t

    return np.stack(xs), np.stack(ys), np.asarray(ts), occ


def _logistic(c: float = 0.2):
    return make_pipeline(
        StandardScaler(),
        LogisticRegression(C=c, max_iter=400, solver="lbfgs"),
    )


def _rank_score(prob: np.ndarray) -> np.ndarray:
    order = np.argsort(prob)
    ranks = np.empty(len(prob), dtype=float)
    ranks[order] = np.arange(len(prob), dtype=float)
    return ranks / max(1, len(prob) - 1)


def _topk_hits(prob: np.ndarray, target: np.ndarray, k: int) -> int:
    return int(target[np.argsort(prob)[::-1][:k]].sum())


def walk_forward_red(df: pd.DataFrame, start: int = 500, block: int = 250):
    x, y, t_index, occ = build_red_features(df)
    n = len(df)
    model_defs = {
        "all": (None, _logistic),
        "w1000": (1000, _logistic),
        "w500": (500, _logistic),
    }
    raw = {name: np.full((n, RED_COUNT), np.nan) for name in model_defs}

    for bstart in range(start, n, block):
        bend = min(n, bstart + block)
        test = (t_index >= bstart) & (t_index < bend)
        for name, (window, factory) in model_defs.items():
            train = t_index < bstart
            if window is not None:
                train &= t_index >= max(200, bstart - window)
            model = factory()
            model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
            p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
            raw[name][t_index[test]] = p.reshape(-1, RED_COUNT)

    ensemble = np.full((n, RED_COUNT), np.nan)
    names = list(model_defs)
    hit_cumsum = {}
    for name in names:
        hits = np.zeros(n, dtype=float)
        for u in range(start, n):
            hits[u] = _topk_hits(raw[name][u], occ[u], 10)
        hit_cumsum[name] = np.concatenate([[0.0], np.cumsum(hits)])

    for t in range(start + block, n):
        left = max(start, t - block)
        perf = np.asarray([
            (hit_cumsum[name][t] - hit_cumsum[name][left]) / max(1, t - left)
            for name in names
        ])
        baseline = RED_PICK * 10 / RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        weights = np.exp(z - z.max())
        weights /= weights.sum()
        ensemble[t] = sum(weights[i] * _rank_score(raw[name][t]) for i, name in enumerate(names))
    return ensemble, raw, occ


def walk_forward_blue(df: pd.DataFrame, start: int = 500, block: int = 250, train_window: int = 1000):
    x, y, t_index, occ = build_blue_features(df)
    n = len(df)
    pred = np.full((n, BLUE_COUNT), np.nan)
    for bstart in range(start, n, block):
        bend = min(n, bstart + block)
        train = (t_index < bstart) & (t_index >= max(200, bstart - train_window))
        test = (t_index >= bstart) & (t_index < bend)
        model = _logistic()
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        pred[t_index[test]] = p.reshape(-1, BLUE_COUNT)
    return pred, occ


def prize_level(red_hit: int, blue_hit: bool) -> str:
    if red_hit == 6 and blue_hit:
        return "一等奖"
    if red_hit == 6:
        return "二等奖"
    if red_hit == 5 and blue_hit:
        return "三等奖"
    if red_hit == 5 or (red_hit == 4 and blue_hit):
        return "四等奖"
    if red_hit == 4 or (red_hit == 3 and blue_hit):
        return "五等奖"
    if blue_hit and red_hit <= 2:
        return "六等奖"
    return "未中奖"


def backtest(df: pd.DataFrame, start: int = 750) -> pd.DataFrame:
    red_pred, _, red_occ = walk_forward_red(df)
    blue_pred, _ = walk_forward_blue(df)
    reds = df[[f"red{i}" for i in range(1, 7)]].to_numpy(int)
    blues = df["blue"].to_numpy(int)
    rows = []
    rank = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}

    for t in range(start, len(df)):
        red_order = np.argsort(red_pred[t])[::-1]
        blue_order = np.argsort(blue_pred[t])[::-1]
        t1 = red_order[:6]
        t2 = red_order[6:12]
        b1, b2 = int(blue_order[0] + 1), int(blue_order[1] + 1)
        h1 = int(red_occ[t, t1].sum())
        h2 = int(red_occ[t, t2].sum())
        p1 = prize_level(h1, b1 == blues[t])
        p2 = prize_level(h2, b2 == blues[t])
        best = p1 if rank[p1] <= rank[p2] else p2
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "actual_red": " ".join(f"{x:02d}" for x in reds[t]),
            "actual_blue": int(blues[t]),
            "rank_top12": " ".join(f"{x + 1:02d}" for x in red_order[:12]),
            "ticket1": " ".join(f"{x + 1:02d}" for x in t1),
            "blue1": b1,
            "ticket2": " ".join(f"{x + 1:02d}" for x in t2),
            "blue2": b2,
            "hit1": h1,
            "hit2": h2,
            "prize1": p1,
            "prize2": p2,
            "best_prize": best,
        })
    return pd.DataFrame(rows)


if __name__ == "__main__":
    import argparse

    parser = argparse.ArgumentParser(description="双色球 V4 号码级概率排序模型")
    parser.add_argument("--history", default="data/ssq_history_2003_2026-05-03.csv.gz")
    parser.add_argument("--out", default="backtests/v4/backtest.csv")
    args = parser.parse_args()

    history = load_history(args.history)
    result = backtest(history)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    result.to_csv(out, index=False, encoding="utf-8-sig")
    print(result["best_prize"].value_counts().to_string())
