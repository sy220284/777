from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


fb = _load("v46overlap_fb", ROOT / "tools" / "v4_6_fallback_experiment.py")
v4, v43, v45 = fb.v4, fb.v43, fb.v45
START = fb.START
FIXED = fb.FIXED_PRIZE
PRIZE_ORDER = fb.PRIZE_ORDER
TRAIN = (751, 2446)
VALIDATION = {
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def _zscore(x: np.ndarray) -> np.ndarray:
    return (x - x.mean()) / (x.std() + 1e-9)


def _aggressive_with_overlap(top12, red_score, affinity, max_overlap: int):
    pair_values = affinity[top12[v45.RED_LOCAL_PAIRS[:, 0]], top12[v45.RED_LOCAL_PAIRS[:, 1]]]
    cohesion = v45.RED_WITHIN @ pair_values
    marginal = v45.RED_MASKS @ red_score[top12]
    score = v45.RED_PRIMARY_PAIR_WEIGHT * _zscore(cohesion) + v45.RED_MARGINAL_WEIGHT * _zscore(marginal)
    primary_idx = int(np.argmax(score))
    primary_mask = v45.RED_MASKS[primary_idx]
    overlaps = v45.RED_MASKS @ primary_mask.astype(float)
    allowed = np.where(overlaps <= max_overlap)[0]
    secondary_idx = int(allowed[np.argmax(score[allowed])])
    a, b = top12[primary_mask], top12[v45.RED_MASKS[secondary_idx]]
    if red_score[a].sum() < red_score[b].sum():
        a, b = b, a
    return a, b


def build_groups(red_pred, red_occ, max_overlap: int):
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    groups = {}
    for t in range(START, len(red_pred)):
        order = np.argsort(red_pred[t])[::-1][:12]
        groups[t] = _aggressive_with_overlap(
            order, red_pred[t], v43._pair_affinity(t, pair_cs, marg_cs), max_overlap
        )
    return groups


def _best(a: str, b: str) -> str:
    return a if PRIZE_ORDER[a] <= PRIZE_ORDER[b] else b


def evaluate(df, red_occ, low_groups, high_groups, blue_base, blue_high, signal_active):
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal_active[t])
        a, b = (high_groups if high else low_groups)[t]
        score = blue_high[t] if high else blue_base[t]
        bo = np.argsort(score)[::-1]
        b1, b2 = int(bo[0]+1), int(bo[1]+1)
        h1, h2 = int(red_occ[t,a].sum()), int(red_occ[t,b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "max_red_hit": max(h1,h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1,p2),
            "overlap": int(len(set(a.tolist()) & set(b.tolist()))),
        })
    return pd.DataFrame(rows)


def summarize(result, lo, hi):
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    low = part[~part.signal_active]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    fixed = int(sum(FIXED[p] for p in tickets))
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "low_max_red_hit_mean": float(low.max_red_hit.mean()) if len(low) else None,
        "low_red4plus": int((low.max_red_hit >= 4).sum()),
        "low_red5plus": int((low.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "mean_low_overlap": float(low.overlap.mean()) if len(low) else None,
    }


def main():
    df = fb.load_dataframe(include_live106=True)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    high_blue, _ = v45.fuse_blue_predictions(
        v45.build_blue_models(df), high_groups, red_occ, df["blue"].to_numpy(int), START
    )
    signal_active, _ = fb._signal_mask(red_pred, red_occ)

    strategies = {"v43_current": fb._current_v43_groups(red_pred, red_occ)}
    for overlap in (0,1,2,3):
        strategies[f"aggressive_overlap_{overlap}"] = build_groups(red_pred, red_occ, overlap)

    reports = {}
    for name, groups in strategies.items():
        result = evaluate(df, red_occ, groups, high_groups, blue_base, high_blue, signal_active)
        reports[name] = {
            "train": summarize(result, *TRAIN),
            "validation": {seg:summarize(result,*rng) for seg,rng in VALIDATION.items()},
        }

    base = reports["v43_current"]
    eligible = []
    for name, data in reports.items():
        if name == "v43_current":
            continue
        tr, bt = data["train"], base["train"]
        red_ok = tr["low_max_red_hit_mean"] >= bt["low_max_red_hit_mean"] - 1e-12
        prize_ok = tr["fixed_return_yuan"] >= bt["fixed_return_yuan"]
        five_ok = tr["low_red5plus"] >= bt["low_red5plus"]
        data["train_gate"] = bool(red_ok and prize_ok and five_ok)
        if data["train_gate"]:
            score = (
                tr["low_max_red_hit_mean"] - bt["low_max_red_hit_mean"]
                + 0.01 * (tr["low_red4plus"] - bt["low_red4plus"])
                + 0.0001 * (tr["fixed_return_yuan"] - bt["fixed_return_yuan"])
            )
            eligible.append((score,name))
    eligible.sort(reverse=True)
    selected = eligible[0][1] if eligible else None

    promotion = {"selected": selected, "pass": False, "checks": {}}
    if selected:
        positive = False
        all_nonworse = True
        for seg in ("validation_2","forward55"):
            cur = reports[selected]["validation"][seg]
            b = base["validation"][seg]
            red_delta = cur["low_max_red_hit_mean"] - b["low_max_red_hit_mean"]
            return_delta = cur["fixed_return_yuan"] - b["fixed_return_yuan"]
            five_delta = cur["low_red5plus"] - b["low_red5plus"]
            check = {
                "red_delta": float(red_delta),
                "return_delta_yuan": int(return_delta),
                "red5plus_delta": int(five_delta),
                "nonworse": bool(red_delta >= -1e-12 and return_delta >= 0 and five_delta >= 0),
            }
            promotion["checks"][seg] = check
            all_nonworse &= check["nonworse"]
            positive |= red_delta > 1e-12 or return_delta > 0 or five_delta > 0
        promotion["pass"] = bool(all_nonworse and positive)

    report = {
        "experiment":"低信号受控重叠组合",
        "selection_train_segment":TRAIN,
        "selection_uses_only_train_segment":True,
        "live2_excluded_from_selection":True,
        "strategies":reports,
        "promotion":promotion,
    }
    out = ROOT / "backtests" / "v4_6" / "low_signal_overlap.json"
    out.parent.mkdir(parents=True,exist_ok=True)
    out.write_text(json.dumps(report,ensure_ascii=False,indent=2),encoding="utf-8")
    print(json.dumps(report,ensure_ascii=False,indent=2))


if __name__ == "__main__":
    main()
