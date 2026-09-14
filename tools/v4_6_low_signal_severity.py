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


fb = _load("v46_severity_fb", ROOT / "tools" / "v4_6_fallback_experiment.py")
v4, v45 = fb.v4, fb.v45
START = fb.START
FIXED = fb.FIXED_PRIZE
RANDOM = fb.RANDOM_TOP12_MEAN
TRAIN = (751, 2446)
VALIDATION = {
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}
THRESHOLDS = tuple(RANDOM - d for d in (0.00, 0.03, 0.06, 0.09, 0.12, 0.15, 0.18))


def _best(p1: str, p2: str) -> str:
    return p1 if fb.PRIZE_ORDER[p1] <= fb.PRIZE_ORDER[p2] else p2


def evaluate(df, red_occ, current_groups, pair_groups, high_groups, blue_base, blue_high, signal_active, signal_mean, threshold):
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        if signal_active[t]:
            groups = high_groups
            mode = "high"
            blue_score = blue_high[t]
        else:
            severe = np.isfinite(signal_mean[t]) and signal_mean[t] <= threshold
            groups = pair_groups if severe else current_groups
            mode = "always_pair" if severe else "v43_current"
            blue_score = blue_base[t]
        a, b = groups[t]
        bo = np.argsort(blue_score)[::-1]
        b1, b2 = int(bo[0] + 1), int(bo[1] + 1)
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": bool(signal_active[t]),
            "fallback_mode": mode,
            "max_red_hit": max(h1, h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1, p2),
        })
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    low = part[~part.signal_active]
    tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    fixed = int(sum(FIXED[p] for p in tickets))
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "always_pair_draws": int((part.fallback_mode == "always_pair").sum()),
        "max_red_hit_mean": float(part.max_red_hit.mean()) if len(part) else None,
        "low_signal_max_red_hit_mean": float(low.max_red_hit.mean()) if len(low) else None,
        "red_4plus_draws": int((part.max_red_hit >= 4).sum()),
        "red_5plus_draws": int((part.max_red_hit >= 5).sum()),
        "fixed_return_yuan": fixed,
        "fixed_return_ratio": float(fixed / (len(part) * 4)) if len(part) else None,
    }


def main() -> None:
    df = fb.load_dataframe(include_live106=True)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    blue_base, _ = v4.walk_forward_blue(df)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    current_groups = fb._current_v43_groups(red_pred, red_occ)
    pair_groups = fb._always_pair_groups(red_pred, red_occ)
    blue_models = v45.build_blue_models(df)
    blue_high, _ = v45.fuse_blue_predictions(blue_models, high_groups, red_occ, df["blue"].to_numpy(int), START)
    signal_active, signal_mean = fb._signal_mask(red_pred, red_occ)

    baseline = evaluate(
        df, red_occ, current_groups, current_groups, high_groups,
        blue_base, blue_high, signal_active, signal_mean, -999.0,
    )
    baseline_train = summarize(baseline, *TRAIN)

    candidates = {}
    eligible = []
    for threshold in THRESHOLDS:
        result = evaluate(
            df, red_occ, current_groups, pair_groups, high_groups,
            blue_base, blue_high, signal_active, signal_mean, threshold,
        )
        train = summarize(result, *TRAIN)
        validations = {name: summarize(result, *rng) for name, rng in VALIDATION.items()}
        red_ok = (
            train["low_signal_max_red_hit_mean"] is not None
            and baseline_train["low_signal_max_red_hit_mean"] is not None
            and train["low_signal_max_red_hit_mean"] >= baseline_train["low_signal_max_red_hit_mean"] - 1e-12
        )
        return_ok = train["fixed_return_yuan"] >= baseline_train["fixed_return_yuan"]
        key = f"{threshold:.6f}"
        candidates[key] = {
            "threshold": threshold,
            "train": train,
            "validation": validations,
            "train_red_nonworse": bool(red_ok),
            "train_return_nonworse": bool(return_ok),
        }
        if red_ok and return_ok and train["always_pair_draws"] > 0:
            red_gain = train["low_signal_max_red_hit_mean"] - baseline_train["low_signal_max_red_hit_mean"]
            return_gain = train["fixed_return_yuan"] - baseline_train["fixed_return_yuan"]
            score = red_gain + 0.0001 * return_gain
            eligible.append((score, key))

    eligible.sort(reverse=True)
    selected = eligible[0][1] if eligible else None
    selected_eval = candidates[selected] if selected else None

    promotion = {"selected": selected, "pass": False, "reasons": []}
    if selected_eval is not None:
        v2 = selected_eval["validation"]["validation_2"]
        f55 = selected_eval["validation"]["forward55"]
        b_v2 = summarize(baseline, *VALIDATION["validation_2"])
        b_f55 = summarize(baseline, *VALIDATION["forward55"])
        v2_red = v2["low_signal_max_red_hit_mean"] >= b_v2["low_signal_max_red_hit_mean"] - 1e-12
        f55_red = f55["low_signal_max_red_hit_mean"] >= b_f55["low_signal_max_red_hit_mean"] - 1e-12
        v2_prize = v2["fixed_return_yuan"] >= b_v2["fixed_return_yuan"]
        f55_prize = f55["fixed_return_yuan"] >= b_f55["fixed_return_yuan"]
        promotion["pass"] = bool(v2_red and f55_red and v2_prize and f55_prize)
        promotion["reasons"] = [
            f"validation2_red_nonworse={v2_red}",
            f"forward55_red_nonworse={f55_red}",
            f"validation2_return_nonworse={v2_prize}",
            f"forward55_return_nonworse={f55_prize}",
        ]
        promotion["deltas"] = {
            "validation2_red": float(v2["low_signal_max_red_hit_mean"] - b_v2["low_signal_max_red_hit_mean"]),
            "forward55_red": float(f55["low_signal_max_red_hit_mean"] - b_f55["low_signal_max_red_hit_mean"]),
            "validation2_return": int(v2["fixed_return_yuan"] - b_v2["fixed_return_yuan"]),
            "forward55_return": int(f55["fixed_return_yuan"] - b_f55["fixed_return_yuan"]),
        }

    report = {
        "experiment": "低信号严重度因果切换",
        "train_segment": TRAIN,
        "thresholds": THRESHOLDS,
        "selection_uses_only_train_segment": True,
        "live2_excluded_from_selection": True,
        "baseline_train": baseline_train,
        "candidates": candidates,
        "promotion": promotion,
    }
    out = ROOT / "backtests" / "v4_6" / "low_signal_severity.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
