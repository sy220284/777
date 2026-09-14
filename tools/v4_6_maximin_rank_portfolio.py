from __future__ import annotations

import importlib.util
import itertools
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
WINDOW = 1000
SUBBLOCK = 250
MIN_FULL_HISTORY = WINDOW


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rankpat = _load("v46_maximin_rankpat", ROOT / "tools" / "v4_6_rank_pattern_portfolio.py")
v4 = rankpat.v4
v45 = rankpat.v45


def _partition_masks() -> np.ndarray:
    masks = []
    for choice in itertools.combinations(range(12), 6):
        if 0 not in choice:
            continue  # A/B互补对称，只保留一半
        m = np.zeros(12, dtype=np.int8)
        m[list(choice)] = 1
        masks.append(m)
    return np.asarray(masks, dtype=np.int8)


MASKS = _partition_masks()


def build_maximin_groups(pred: np.ndarray, occ: np.ndarray, fallback) -> dict[int, tuple[np.ndarray, np.ndarray]]:
    """用过去1000期四个250期子块的最差表现选6+6排名位置分组。"""
    hit_hist = rankpat.build_rank_hit_history(pred, occ)
    valid = np.where(hit_hist[:, 0] >= 0, hit_hist, 0).astype(np.int16)
    totals = valid.sum(axis=1)
    hit_a = valid @ MASKS.T
    hit_b = totals[:, None] - hit_a
    max_hit = np.maximum(hit_a, hit_b).astype(np.int16)

    pref4 = np.vstack([np.zeros((1, len(MASKS)), dtype=np.int32), np.cumsum(max_hit >= 4, axis=0, dtype=np.int32)])
    pref5 = np.vstack([np.zeros((1, len(MASKS)), dtype=np.int32), np.cumsum(max_hit >= 5, axis=0, dtype=np.int32)])
    prefm = np.vstack([np.zeros((1, len(MASKS)), dtype=np.int32), np.cumsum(max_hit, axis=0, dtype=np.int32)])

    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    for t in range(START, len(pred)):
        order = np.argsort(pred[t])[::-1]
        if t - START < MIN_FULL_HISTORY:
            groups[t] = fallback[t]
            continue

        left = t - WINDOW
        boundaries = [left, left + SUBBLOCK, left + 2 * SUBBLOCK, left + 3 * SUBBLOCK, t]
        block4 = []
        block5 = []
        blockm = []
        for a, b in zip(boundaries[:-1], boundaries[1:]):
            block4.append(pref4[b] - pref4[a])
            block5.append(pref5[b] - pref5[a])
            blockm.append((prefm[b] - prefm[a]) / float(b - a))
        b4 = np.stack(block4)
        b5 = np.stack(block5)
        bm = np.stack(blockm)

        min5 = b5.min(axis=0)
        min4 = b4.min(axis=0)
        minm = bm.min(axis=0)
        total5 = b5.sum(axis=0)
        total4 = b4.sum(axis=0)
        totalm = bm.mean(axis=0)
        # lexsort最后一个键优先；目标按“最差块5红→4红→平均命中→全窗表现”严格排序。
        ranked = np.lexsort((totalm, total4, total5, minm, min4, min5))
        best = int(ranked[-1])
        mask = MASKS[best].astype(bool)
        top12 = order[:12]
        a, b = top12[mask], top12[~mask]
        if pred[t, a].sum() < pred[t, b].sum():
            a, b = b, a
        groups[t] = (a, b)
    return groups


def main() -> None:
    df = rankpat.load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(pred, occ)
    base_low, _, _ = v45._baseline_red_groups(pred, occ, START)
    maximin_low = build_maximin_groups(pred, occ, base_low)
    high, _, _ = v45.build_red_portfolio(pred, occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual, START)

    base = rankpat.evaluate(df, pred, occ, base_low, high, signal, blue_base, blue_high)
    cand = rankpat.evaluate(df, pred, occ, maximin_low, high, signal, blue_base, blue_high)
    report = {
        "experiment": "V4.5.1低信号1000期四块最差表现最大化组合",
        "rule": {
            "window": WINDOW,
            "subblocks": 4,
            "subblock_draws": SUBBLOCK,
            "candidate_partitions": int(len(MASKS)),
            "priority": ["最差块5红次数", "最差块4红次数", "最差块最高红球均值", "全窗5红", "全窗4红", "全窗平均命中"],
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }
    for name, result in (("base", base), ("maximin", cand)):
        report["configs"][name] = {
            seg: rankpat.summary(result, *rng) for seg, rng in rankpat.SEGMENTS.items()
        }

    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = report["configs"]["base"][seg]
        c = report["configs"]["maximin"][seg]
        dm = c["max_red_hit_mean"] - b["max_red_hit_mean"]
        d4 = c["red_4plus"] - b["red_4plus"]
        d5 = c["red_5plus"] - b["red_5plus"]
        dr = c["fixed_return"] - b["fixed_return"]
        nw = dm >= -1e-12 and d5 >= 0 and dr >= 0
        imp = dm > 1e-12 or d4 > 0 or d5 > 0 or dr > 0
        ok &= nw
        positive += int(imp)
        checks[seg] = {
            "max_red_hit_mean_delta": float(dm),
            "red_4plus_delta": int(d4),
            "red_5plus_delta": int(d5),
            "fixed_return_delta": int(dr),
            "nonworse": bool(nw),
            "improved": bool(imp),
        }
    report["promotion"] = {
        "pass": bool(ok and positive >= 2),
        "minimum_positive_segments": 2,
        "positive_segments": int(positive),
        "checks": checks,
    }

    out = ROOT / "backtests" / "v4_6" / "maximin_rank_portfolio.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
