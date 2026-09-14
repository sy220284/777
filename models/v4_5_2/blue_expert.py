from __future__ import annotations

import importlib.util
import math
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


v4 = _load_module("ssq_v4_v452_blue_expert", ROOT / "models" / "v4" / "predictor.py")

MODEL_START = 500
BLOCK = 250
TRAIN_WINDOW = 1000
HALF_LIVES = (8, 32, 128)
BLUE_COUNT = 16
BASE_RATE = 1 / BLUE_COUNT


def _with_placeholder(df: pd.DataFrame) -> pd.DataFrame:
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


def _current_block_start(target_t: int) -> int:
    if target_t < MODEL_START:
        raise ValueError("历史长度不足以进入蓝球模型训练区间")
    return MODEL_START + ((target_t - MODEL_START) // BLOCK) * BLOCK


def build_extra_features(df: pd.DataFrame, t_index: np.ndarray) -> dict[str, np.ndarray]:
    """构造只读取目标期之前信息的蓝球时序特征。"""
    blues = df["blue"].to_numpy(int)
    occ = v4._occurrence_matrix(blues, BLUE_COUNT)
    wanted = set(int(t) for t in t_index)

    alphas = [1.0 - math.exp(math.log(0.5) / h) for h in HALF_LIVES]
    ewm = [np.zeros(BLUE_COUNT, dtype=float) for _ in HALF_LIVES]
    transition = np.zeros((BLUE_COUNT, BLUE_COUNT), dtype=float)
    distance_counts = np.zeros(BLUE_COUNT, dtype=float)
    last_seen = np.full(BLUE_COUNT, -1, dtype=int)
    gap_hist = np.zeros(161, dtype=float)

    ewma_rows: dict[int, np.ndarray] = {}
    transition_rows: dict[int, np.ndarray] = {}
    distance_rows: dict[int, np.ndarray] = {}
    hazard_rows: dict[int, np.ndarray] = {}

    for t in range(len(df)):
        if t in wanted:
            e = np.stack(ewm, axis=1)
            ewma_rows[t] = np.column_stack([
                e,
                e[:, 0] - e[:, 1],
                e[:, 1] - e[:, 2],
            ])

            prev = int(blues[t - 1]) - 1 if t > 0 else 0
            trans_row = transition[prev]
            trans_prob = (trans_row + 1.0) / (trans_row.sum() + BLUE_COUNT)
            transition_rows[t] = np.column_stack([
                trans_prob,
                np.log((trans_prob + 1e-12) / BASE_RATE),
            ])

            distance_prob = np.zeros(BLUE_COUNT, dtype=float)
            denom = distance_counts.sum() + BLUE_COUNT
            for n0 in range(BLUE_COUNT):
                d = abs(n0 - prev)
                distance_prob[n0] = (distance_counts[d] + 1.0) / denom
            distance_rows[t] = np.column_stack([
                distance_prob,
                np.log((distance_prob + 1e-12) / BASE_RATE),
            ])

            hz = np.zeros((BLUE_COUNT, 2), dtype=float)
            for n0 in range(BLUE_COUNT):
                gap = t - last_seen[n0] if last_seen[n0] >= 0 else t + 1
                g = min(160, max(1, int(gap)))
                at_g = gap_hist[g]
                survivor = gap_hist[g:].sum()
                hazard = (at_g + 2.0 * BASE_RATE) / (survivor + 2.0)
                hz[n0, 0] = hazard
                hz[n0, 1] = math.log((hazard + 1e-12) / BASE_RATE)
            hazard_rows[t] = hz

        if t > 0:
            prev = int(blues[t - 1]) - 1
            cur = int(blues[t]) - 1
            transition[prev, cur] += 1.0
            distance_counts[abs(cur - prev)] += 1.0

        cur = int(blues[t]) - 1
        if last_seen[cur] >= 0:
            gap = min(160, max(1, int(t - last_seen[cur])))
            gap_hist[gap] += 1.0
        last_seen[cur] = t

        for i, alpha in enumerate(alphas):
            ewm[i] = (1.0 - alpha) * ewm[i] + alpha * occ[t]

    def stack(mapping: dict[int, np.ndarray]) -> np.ndarray:
        return np.stack([mapping[int(t)] for t in t_index])

    return {
        "ewma": stack(ewma_rows),
        "transition": stack(transition_rows),
        "distance": stack(distance_rows),
        "hazard": stack(hazard_rows),
    }


def _feature_matrix(df: pd.DataFrame):
    x, y, t_index, occ = v4.build_blue_features(df)
    extra = build_extra_features(df, t_index)
    xx = np.concatenate([
        x,
        extra["ewma"],
        extra["transition"],
        extra["distance"],
        extra["hazard"],
    ], axis=2)
    return xx, y, t_index, occ


def walk_forward(df: pd.DataFrame) -> np.ndarray:
    """与正式训练口径一致的严格时间顺序蓝球预测。"""
    x, y, t_index, _ = _feature_matrix(df)
    n = len(df)
    pred = np.full((n, BLUE_COUNT), np.nan)
    for bstart in range(MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        train = (t_index < bstart) & (t_index >= max(200, bstart - TRAIN_WINDOW))
        test = (t_index >= bstart) & (t_index < bend)
        model = v4._logistic()
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        pred[t_index[test]] = p.reshape(-1, BLUE_COUNT)
    return pred


def predict_next(df: pd.DataFrame) -> np.ndarray:
    """训练当前块模型并输出下一期16个蓝球的概率分数。"""
    n = len(df)
    extended = _with_placeholder(df)
    x, y, t_index, _ = _feature_matrix(extended)
    target = t_index == n
    if target.sum() != 1:
        raise RuntimeError("未能构建下一期低信号蓝球特征")

    bstart = _current_block_start(n)
    train = (t_index < bstart) & (t_index >= max(200, bstart - TRAIN_WINDOW))
    model = v4._logistic()
    model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
    return model.predict_proba(x[target].reshape(-1, x.shape[-1]))[:, 1]
