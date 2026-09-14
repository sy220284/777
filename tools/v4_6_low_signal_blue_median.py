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


rankpat = _load("v46_blue_med_rankpat", ROOT / "tools" / "v4_6_rank_pattern_portfolio.py")
v4 = rankpat.v4
v45 = rankpat.v45


def median_blue(models: dict[str, np.ndarray]) -> np.ndarray:
    names = list(v45.BLUE_MODEL_NAMES)
    n = len(models[names[0]])
    out = np.full_like(models[names[0]], np.nan, dtype=float)
    for t in range(n):
        ranks = []
        for name in names:
            row = models[name][t]
            if not np.isfinite(row).all():
                break
            ranks.append(v45._rank01(row))
        if len(ranks) == len(names):
            out[t] = np.median(np.stack(ranks), axis=0)
    return out


def evaluate(df, occ, low_groups, high_groups, signal, blue_low, blue_high):
    actual = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal[t])
        a, b = (high_groups if high else low_groups)[t]
        score = blue_high[t] if high else blue_low[t]
        order = np.argsort(score)[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(occ[t, a].sum()), int(occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual[t])
        p2 = v4.prize_level(h2, b2 == actual[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
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
    return {
        "draws": int(len(p)),
        "low_signal_draws": int(len(low)),
        "low_signal_blue_top2_hits": int(low.blue_top2_hit.sum()),
        "low_signal_blue_top2_rate": float(low.blue_top2_hit.mean()) if len(low) else None,
        "fixed_return": int(sum(rankpat.FIXED_PRIZE[x] for x in tickets)),
        "winning_draw_rate": float((p.best_prize != "未中奖").mean()),
    }


def main() -> None:
    df = rankpat.load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(pred, occ)
    low_groups, _, _ = v45._baseline_red_groups(pred, occ, START)
    high_groups, _, _ = v45.build_red_portfolio(pred, occ, START)

    models = v45.build_blue_models(df)
    base_blue = models["base"]
    med_blue = median_blue(models)
    actual = df["blue"].to_numpy(int)
    blue_high, _ = v45.fuse_blue_predictions(models, high_groups, occ, actual, START)

    base = evaluate(df, occ, low_groups, high_groups, signal, base_blue, blue_high)
    cand = evaluate(df, occ, low_groups, high_groups, signal, med_blue, blue_high)
    report = {
        "experiment": "V4.5.1低信号蓝球三模型排名中位数共识",
        "rule": {
            "low_signal": "base/full750/freq1000逐蓝球排名取中位数",
            "high_signal": "保持V4.5原蓝球效用融合",
            "red_strategy": "完全保持V4.5.1",
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }
    for name, result in (("base", base), ("blue_median", cand)):
        report["configs"][name] = {seg: summary(result, *rng) for seg, rng in rankpat.SEGMENTS.items()}

    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = report["configs"]["base"][seg]
        c = report["configs"]["blue_median"][seg]
        dh = c["low_signal_blue_top2_hits"] - b["low_signal_blue_top2_hits"]
        dr = c["fixed_return"] - b["fixed_return"]
        dw = c["winning_draw_rate"] - b["winning_draw_rate"]
        # 没有低信号样本的区间不要求变化；有样本时三项均不得退化。
        has_low = b["low_signal_draws"] > 0
        nw = True if not has_low else (dh >= 0 and dr >= 0 and dw >= -1e-12)
        imp = has_low and (dh > 0 or dr > 0 or dw > 1e-12)
        ok &= nw
        positive += int(imp)
        checks[seg] = {
            "low_signal_blue_top2_hits_delta": int(dh),
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

    out = ROOT / "backtests" / "v4_6" / "low_signal_blue_median.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
