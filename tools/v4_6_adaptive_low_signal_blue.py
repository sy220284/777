from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
WINDOW = 250
MIN_LOW_HISTORY = 30
CANDIDATES = ("full750", "freq1000")


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rankpat = _load("v46_adapt_blue_rankpat", ROOT / "tools" / "v4_6_rank_pattern_portfolio.py")
v4 = rankpat.v4
v45 = rankpat.v45


def _realized(df, groups, occ, pred: np.ndarray) -> dict[str, np.ndarray]:
    n = len(df)
    actual = df["blue"].to_numpy(int)
    top2 = np.zeros(n, dtype=np.int16)
    fixed = np.zeros(n, dtype=np.int32)
    win = np.zeros(n, dtype=np.int16)
    for t in range(START, n):
        order = np.argsort(pred[t])[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        a, b = groups[t]
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        top2[t] = int(actual[t] in (b1, b2))
        fixed[t] = rankpat.FIXED_PRIZE[p1] + rankpat.FIXED_PRIZE[p2]
        win[t] = int(p1 != "未中奖" or p2 != "未中奖")
    return {"top2": top2, "fixed": fixed, "win": win}


def select_models(signal: np.ndarray, realized: dict[str, dict[str, np.ndarray]]) -> list[str]:
    n = len(signal)
    selected = ["base"] * n
    for t in range(START, n):
        if signal[t]:
            continue
        left = max(START, t - WINDOW)
        idx = np.asarray([u for u in range(left, t) if not signal[u]], dtype=int)
        if len(idx) < MIN_LOW_HISTORY:
            continue
        b = realized["base"]
        base_vals = (
            int(b["fixed"][idx].sum()),
            int(b["top2"][idx].sum()),
            int(b["win"][idx].sum()),
        )
        passed = []
        for name in CANDIDATES:
            r = realized[name]
            vals = (
                int(r["fixed"][idx].sum()),
                int(r["top2"][idx].sum()),
                int(r["win"][idx].sum()),
            )
            # 三项都不弱于基础蓝球才允许接管；排序优先固定回报，其次前2命中，再次中奖期数。
            if vals[0] >= base_vals[0] and vals[1] >= base_vals[1] and vals[2] >= base_vals[2]:
                passed.append((vals, name))
        if passed:
            passed.sort(key=lambda x: x[0])
            selected[t] = passed[-1][1]
    return selected


def evaluate(df, occ, low_groups, high_groups, signal, low_scores, selected, high_score):
    actual = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal[t])
        a, b = (high_groups if high else low_groups)[t]
        name = "high_fusion" if high else selected[t]
        score = high_score[t] if high else low_scores[name][t]
        order = np.argsort(score)[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "blue_model": name,
            "blue_top2_hit": int(actual[t] in (b1, b2)),
            "prize1": p1,
            "prize2": p2,
            "best_prize": rankpat._best(p1, p2),
        })
    return pd.DataFrame(rows)


def summary(frame: pd.DataFrame, lo: int, hi: int) -> dict:
    p = frame[(frame.seq >= lo) & (frame.seq <= hi)]
    low = p[~p.signal_active]
    tickets = pd.concat([p.prize1, p.prize2], ignore_index=True)
    counts = low.blue_model.value_counts().to_dict()
    return {
        "draws": int(len(p)),
        "low_signal_draws": int(len(low)),
        "low_signal_blue_top2_hits": int(low.blue_top2_hit.sum()),
        "fixed_return": int(sum(rankpat.FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((p.best_prize != "未中奖").mean()),
        "low_signal_model_counts": {k: int(v) for k, v in counts.items()},
    }


def main() -> None:
    df = rankpat.load_dataframe()
    red_pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(red_pred, occ)
    low_groups, _, _ = v45._baseline_red_groups(red_pred, occ, START)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, occ, START)

    models = v45.build_blue_models(df)
    realized = {name: _realized(df, low_groups, occ, pred) for name, pred in models.items()}
    selected = select_models(signal, realized)
    base_selected = ["base"] * len(df)
    actual = df["blue"].to_numpy(int)
    high_fused, _ = v45.fuse_blue_predictions(models, high_groups, occ, actual, START)

    base = evaluate(df, occ, low_groups, high_groups, signal, models, base_selected, high_fused)
    cand = evaluate(df, occ, low_groups, high_groups, signal, models, selected, high_fused)
    report = {
        "experiment": "V4.5.1低信号蓝球因果选择门",
        "rule": {
            "window": WINDOW,
            "minimum_low_signal_history": MIN_LOW_HISTORY,
            "candidates": list(CANDIDATES),
            "admission": "过去窗口低信号样本中固定回报、蓝球前2命中、中奖期数三项均不弱于base",
            "selection": "通过者按固定回报→前2命中→中奖期数词典序择优",
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }
    for name, result in (("base", base), ("adaptive_blue", cand)):
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in rankpat.SEGMENTS.items()}

    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = report["configs"]["base"][seg]
        c = report["configs"]["adaptive_blue"][seg]
        dh = c["low_signal_blue_top2_hits"] - b["low_signal_blue_top2_hits"]
        dr = c["fixed_return"] - b["fixed_return"]
        dw = c["winning_draw_rate"] - b["winning_draw_rate"]
        has_low = b["low_signal_draws"] > 0
        nw = True if not has_low else (dh >= 0 and dr >= 0 and dw >= -1e-12)
        imp = has_low and (dh > 0 or dr > 0 or dw > 1e-12)
        ok &= nw
        positive += int(imp)
        checks[seg] = {
            "blue_top2_hits_delta": int(dh),
            "fixed_return_delta": int(dr),
            "winning_draw_rate_delta": float(dw),
            "nonworse": bool(nw),
            "improved": bool(imp),
        }
    report["promotion"] = {
        "pass": bool(ok and positive >= 1),
        "positive_segments": int(positive),
        "checks": checks,
    }

    out = ROOT / "backtests" / "v4_6" / "adaptive_low_signal_blue.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
