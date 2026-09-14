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


fb = _load("v46blue_fb", ROOT / "tools" / "v4_6_fallback_experiment.py")
v4, v45 = fb.v4, fb.v45
START = fb.START
FIXED = fb.FIXED_PRIZE
PRIZE_ORDER = fb.PRIZE_ORDER
TRAIN = (751, 2446)
VALIDATION = {
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def _rank01(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    rank = np.empty(len(row), dtype=float)
    rank[order] = np.arange(len(row), dtype=float)
    return rank / max(1, len(row) - 1)


def _equal_rank(models: dict[str, np.ndarray]) -> np.ndarray:
    names = list(v45.BLUE_MODEL_NAMES)
    n = len(models[names[0]])
    out = np.full_like(models[names[0]], np.nan, dtype=float)
    for t in range(n):
        rows = [models[name][t] for name in names]
        if not all(np.isfinite(row).all() for row in rows):
            continue
        out[t] = sum(_rank01(row) for row in rows) / len(rows)
    return out


def _best(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def evaluate(df, red_occ, low_groups, high_groups, low_blue, high_blue, signal_active):
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(START, len(df)):
        high = bool(signal_active[t])
        a, b = (high_groups if high else low_groups)[t]
        blue_score = high_blue[t] if high else low_blue[t]
        order = np.argsort(blue_score)[::-1]
        b1, b2 = int(order[0] + 1), int(order[1] + 1)
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        rows.append({
            "seq": int(df.iloc[t]["seq"]),
            "signal_active": high,
            "actual_blue": int(actual_blue[t]),
            "blue1": b1,
            "blue2": b2,
            "blue_top2_hit": bool(actual_blue[t] in (b1, b2)),
            "max_red_hit": max(h1, h2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best(p1, p2),
        })
    return pd.DataFrame(rows)


def summarize(result: pd.DataFrame, lo: int, hi: int) -> dict:
    part = result[(result.seq >= lo) & (result.seq <= hi)]
    low = part[~part.signal_active]
    low_tickets = pd.concat([low.prize1, low.prize2], ignore_index=True)
    all_tickets = pd.concat([part.prize1, part.prize2], ignore_index=True)
    low_fixed = int(sum(FIXED[p] for p in low_tickets))
    all_fixed = int(sum(FIXED[p] for p in all_tickets))
    return {
        "draws": int(len(part)),
        "low_signal_draws": int(len(low)),
        "low_blue_top2_hits": int(low.blue_top2_hit.sum()),
        "low_blue_top2_rate": float(low.blue_top2_hit.mean()) if len(low) else None,
        "low_fixed_return_yuan": low_fixed,
        "fixed_return_yuan": all_fixed,
        "winning_draw_rate": float((part.best_prize != "未中奖").mean()) if len(part) else None,
        "low_winning_draw_rate": float((low.best_prize != "未中奖").mean()) if len(low) else None,
    }


def main() -> None:
    df = fb.load_dataframe(include_live106=True)
    red_pred, _, red_occ = v4.walk_forward_red(df)
    low_groups = fb._current_v43_groups(red_pred, red_occ)
    high_groups, _, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    signal_active, _ = fb._signal_mask(red_pred, red_occ)
    actual_blue = df["blue"].to_numpy(int)

    blue_models = v45.build_blue_models(df)
    base_blue = blue_models["base"]
    high_blue, _ = v45.fuse_blue_predictions(blue_models, high_groups, red_occ, actual_blue, START)
    low_fused, _ = v45.fuse_blue_predictions(blue_models, low_groups, red_occ, actual_blue, START)
    equal_blue = _equal_rank(blue_models)

    strategies = {
        "base": base_blue,
        "full750": blue_models["full750"],
        "freq1000": blue_models["freq1000"],
        "equal_rank": equal_blue,
        "v43_utility_fused": low_fused,
    }

    results = {}
    train_baseline = None
    eligible = []
    for name, low_blue in strategies.items():
        result = evaluate(df, red_occ, low_groups, high_groups, low_blue, high_blue, signal_active)
        train = summarize(result, *TRAIN)
        validation = {seg: summarize(result, *rng) for seg, rng in VALIDATION.items()}
        results[name] = {"train": train, "validation": validation}
        if name == "base":
            train_baseline = train

    if train_baseline is None:
        raise RuntimeError("缺少base蓝球基线")

    for name, data in results.items():
        if name == "base":
            continue
        train = data["train"]
        hit_ok = (
            train["low_blue_top2_rate"] is not None
            and train_baseline["low_blue_top2_rate"] is not None
            and train["low_blue_top2_rate"] >= train_baseline["low_blue_top2_rate"] - 1e-12
        )
        return_ok = train["low_fixed_return_yuan"] >= train_baseline["low_fixed_return_yuan"]
        data["train_hit_nonworse"] = bool(hit_ok)
        data["train_return_nonworse"] = bool(return_ok)
        if hit_ok and return_ok:
            score = (
                train["low_fixed_return_yuan"] - train_baseline["low_fixed_return_yuan"]
                + 100.0 * (train["low_blue_top2_rate"] - train_baseline["low_blue_top2_rate"])
            )
            eligible.append((score, name))

    eligible.sort(reverse=True)
    selected = eligible[0][1] if eligible else None
    promotion = {"selected": selected, "pass": False, "checks": {}}
    if selected is not None:
        selected_data = results[selected]
        for seg in ("validation_2", "forward55"):
            cur = selected_data["validation"][seg]
            base = results["base"]["validation"][seg]
            promotion["checks"][seg] = {
                "top2_nonworse": bool(cur["low_blue_top2_rate"] >= base["low_blue_top2_rate"] - 1e-12),
                "return_nonworse": bool(cur["low_fixed_return_yuan"] >= base["low_fixed_return_yuan"]),
                "top2_delta": float(cur["low_blue_top2_rate"] - base["low_blue_top2_rate"]),
                "return_delta_yuan": int(cur["low_fixed_return_yuan"] - base["low_fixed_return_yuan"]),
            }
        promotion["pass"] = all(
            check["top2_nonworse"] and check["return_nonworse"]
            for check in promotion["checks"].values()
        )

    report = {
        "experiment": "低信号蓝球链优化",
        "selection_train_segment": TRAIN,
        "selection_uses_only_train_segment": True,
        "live2_excluded_from_selection": True,
        "results": results,
        "promotion": promotion,
    }
    out = ROOT / "backtests" / "v4_6" / "low_signal_blue.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
