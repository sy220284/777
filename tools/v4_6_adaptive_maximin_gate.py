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


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


maximin = _load("v46_adaptive_maximin", ROOT / "tools" / "v4_6_maximin_rank_portfolio.py")
rankpat = maximin.rankpat
v4 = maximin.v4
v45 = maximin.v45


def realized_stats(df, groups, occ, blue_base):
    n = len(df)
    actual = df["blue"].to_numpy(int)
    maxhit = np.zeros(n, dtype=np.int16)
    red4 = np.zeros(n, dtype=np.int16)
    red5 = np.zeros(n, dtype=np.int16)
    fixed = np.zeros(n, dtype=np.int32)
    for t in range(START, n):
        a, b = groups[t]
        order = np.argsort(blue_base[t])[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        mh = max(h1, h2)
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        maxhit[t] = mh
        red4[t] = int(mh >= 4)
        red5[t] = int(mh >= 5)
        fixed[t] = rankpat.FIXED_PRIZE[p1] + rankpat.FIXED_PRIZE[p2]
    return {"maxhit": maxhit, "red4": red4, "red5": red5, "fixed": fixed}


def adaptive_mask(signal: np.ndarray, base_stats, cand_stats) -> np.ndarray:
    use = np.zeros(len(signal), dtype=bool)
    for t in range(START, len(signal)):
        if signal[t]:
            continue
        left = max(START, t - WINDOW)
        idx = np.asarray([u for u in range(left, t) if not signal[u]], dtype=int)
        if len(idx) < MIN_LOW_HISTORY:
            continue
        bmean = float(base_stats["maxhit"][idx].mean())
        cmean = float(cand_stats["maxhit"][idx].mean())
        b4 = int(base_stats["red4"][idx].sum())
        c4 = int(cand_stats["red4"][idx].sum())
        b5 = int(base_stats["red5"][idx].sum())
        c5 = int(cand_stats["red5"][idx].sum())
        bret = int(base_stats["fixed"][idx].sum())
        cret = int(cand_stats["fixed"][idx].sum())
        use[t] = cmean >= bmean - 1e-12 and c4 >= b4 and c5 >= b5 and cret >= bret
    return use


def evaluate(df, occ, base_low, cand_low, high_groups, signal, use, blue_base, blue_high):
    actual = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal[t])
        use_cand = bool(use[t]) and not high
        if high:
            a, b = high_groups[t]
            score = blue_high[t]
            strategy = "V4.5"
        elif use_cand:
            a, b = cand_low[t]
            score = blue_base[t]
            strategy = "maximin"
        else:
            a, b = base_low[t]
            score = blue_base[t]
            strategy = "V4.3"
        order = np.argsort(score)[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "use_maximin": use_cand,
            "selected_strategy": strategy,
            "hit1": h1,
            "hit2": h2,
            "max_red_hit": max(h1, h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": rankpat._best(p1, p2),
        })
    return pd.DataFrame(rows)


def summary(frame: pd.DataFrame, lo: int, hi: int) -> dict:
    p = frame[(frame.seq >= lo) & (frame.seq <= hi)]
    tickets = pd.concat([p.prize1, p.prize2], ignore_index=True)
    return {
        "draws": int(len(p)),
        "maximin_active": int(p.use_maximin.sum()),
        "max_red_hit_mean": float(p.max_red_hit.mean()),
        "red_4plus": int((p.max_red_hit >= 4).sum()),
        "red_5plus": int((p.max_red_hit >= 5).sum()),
        "fixed_return": int(sum(rankpat.FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((p.best_prize != "未中奖").mean()),
    }


def main() -> None:
    df = rankpat.load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(pred, occ)
    base_low, _, _ = v45._baseline_red_groups(pred, occ, START)
    cand_low = maximin.build_maximin_groups(pred, occ, base_low)
    high, _, _ = v45.build_red_portfolio(pred, occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual, START)

    base_stats = realized_stats(df, base_low, occ, blue_base)
    cand_stats = realized_stats(df, cand_low, occ, blue_base)
    use = adaptive_mask(signal, base_stats, cand_stats)
    no_use = np.zeros(len(signal), dtype=bool)

    base = evaluate(df, occ, base_low, cand_low, high, signal, no_use, blue_base, blue_high)
    cand = evaluate(df, occ, base_low, cand_low, high, signal, use, blue_base, blue_high)

    report = {
        "experiment": "V4.5.1低信号maximin历史表现门",
        "rule": {
            "window": WINDOW,
            "minimum_low_signal_history": MIN_LOW_HISTORY,
            "admission": "过去窗口低信号样本中maximin的平均最高红球、4红、5红、固定回报四项均不弱于V4.3",
            "current_draw_excluded": True,
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }
    for name, result in (("base", base), ("adaptive_maximin", cand)):
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in rankpat.SEGMENTS.items()}

    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = report["configs"]["base"][seg]
        c = report["configs"]["adaptive_maximin"][seg]
        dm = c["max_red_hit_mean"] - b["max_red_hit_mean"]
        d4 = c["red_4plus"] - b["red_4plus"]
        d5 = c["red_5plus"] - b["red_5plus"]
        dr = c["fixed_return"] - b["fixed_return"]
        dw = c["winning_draw_rate"] - b["winning_draw_rate"]
        nw = dm >= -1e-12 and d5 >= 0 and dr >= 0 and dw >= -1e-12
        imp = dm > 1e-12 or d4 > 0 or d5 > 0 or dr > 0 or dw > 1e-12
        ok &= nw
        positive += int(imp)
        checks[seg] = {
            "max_red_hit_mean_delta": float(dm),
            "red_4plus_delta": int(d4),
            "red_5plus_delta": int(d5),
            "fixed_return_delta": int(dr),
            "winning_draw_rate_delta": float(dw),
            "nonworse": bool(nw),
            "improved": bool(imp),
        }
    report["promotion"] = {
        "pass": bool(ok and positive >= 2),
        "minimum_positive_segments": 2,
        "positive_segments": int(positive),
        "checks": checks,
    }

    out = ROOT / "backtests" / "v4_6" / "adaptive_maximin_gate.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
