from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


maximin = _load("v46_hybrid_maximin", ROOT / "tools" / "v4_6_maximin_rank_portfolio.py")
rankpat = maximin.rankpat
v4 = maximin.v4
v45 = maximin.v45


def _primary(group: tuple[np.ndarray, np.ndarray], score: np.ndarray) -> np.ndarray:
    a, b = group
    return a if float(score[a].sum()) >= float(score[b].sum()) else b


def _secondary(group: tuple[np.ndarray, np.ndarray], primary: np.ndarray) -> np.ndarray:
    a, b = group
    return b if np.array_equal(a, primary) else a


def build_hybrid_groups(pred: np.ndarray, base_groups, maximin_groups):
    groups = {}
    for t in range(START, len(pred)):
        base_primary = _primary(base_groups[t], pred[t])
        max_primary = _primary(maximin_groups[t], pred[t])
        if set(int(x) for x in base_primary) != set(int(x) for x in max_primary):
            a, b = base_primary, max_primary
        else:
            # 两套主票相同则保留V4.3两票，避免为了差异强行制造次优组合。
            a, b = base_groups[t]
        if float(pred[t, a].sum()) < float(pred[t, b].sum()):
            a, b = b, a
        groups[t] = (a, b)
    return groups


def evaluate(df, pred, occ, low_groups, high_groups, signal, blue_base, blue_high):
    actual = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal[t])
        a, b = (high_groups if high else low_groups)[t]
        score = blue_high[t] if high else blue_base[t]
        bo = np.argsort(score)[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
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
    max_low = maximin.build_maximin_groups(pred, occ, base_low)
    hybrid_low = build_hybrid_groups(pred, base_low, max_low)
    high, _, _ = v45.build_red_portfolio(pred, occ, START)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high, occ, actual, START)

    base = evaluate(df, pred, occ, base_low, high, signal, blue_base, blue_high)
    hybrid = evaluate(df, pred, occ, hybrid_low, high, signal, blue_base, blue_high)
    report = {
        "experiment": "V4.5.1低信号V4.3×maximin双票对冲",
        "rule": {
            "ticket1": "V4.3两票中V4分数和较高者",
            "ticket2": "maximin两票中V4分数和较高者",
            "identical_primary": "若两主票相同则退回V4.3原两票",
            "candidate_pool": "保持V4前12不变",
            "ticket_count": 2,
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }
    for name, result in (("base", base), ("hybrid", hybrid)):
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in rankpat.SEGMENTS.items()}

    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = report["configs"]["base"][seg]
        c = report["configs"]["hybrid"][seg]
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

    out = ROOT / "backtests" / "v4_6" / "hybrid_low_signal_portfolio.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
