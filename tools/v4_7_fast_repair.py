from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd

import v4_7_research as r
import v4_7_research_strict as s

ROOT = Path(__file__).resolve().parents[1]
FAST_REFIT_BLOCK = 50
FAST_PERF_WINDOW = 250
FAST_GATE_WINDOW = 100
FAST_GATE_MARGIN = 0.05
FAST_GATE_MIN_HISTORY = 50
EXPECTED_DEV_FIXED_RETURN = 4760


def walk_fast_red(df: pd.DataFrame):
    """V4同构快模型：仅把重新拟合周期250期缩短到50期，专家权重仍看过去250期。"""
    x, y, t_index, occ = r.v4.build_red_features(df)
    n = len(df)
    model_defs = {
        "all": None,
        "w1000": 1000,
        "w500": 500,
    }
    raw = {name: np.full((n, r.v4.RED_COUNT), np.nan) for name in model_defs}

    for bstart in range(r.MODEL_START, n, FAST_REFIT_BLOCK):
        bend = min(n, bstart + FAST_REFIT_BLOCK)
        test = (t_index >= bstart) & (t_index < bend)
        for name, window in model_defs.items():
            train = t_index < bstart
            if window is not None:
                train &= t_index >= max(200, bstart - window)
            model = r.v4._logistic()
            model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
            p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
            raw[name][t_index[test]] = p.reshape(-1, r.v4.RED_COUNT)

    names = list(model_defs)
    hit_cs: dict[str, np.ndarray] = {}
    for name in names:
        hits = np.zeros(n, dtype=float)
        for t in range(r.MODEL_START, n):
            if np.isfinite(raw[name][t]).all():
                hits[t] = r.v4._topk_hits(raw[name][t], occ[t], 10)
        hit_cs[name] = np.concatenate([[0.0], np.cumsum(hits)])

    ensemble = np.full((n, r.v4.RED_COUNT), np.nan)
    for t in range(r.START, n):
        left = max(r.MODEL_START, t - FAST_PERF_WINDOW)
        width = max(1, t - left)
        perf = np.asarray([
            (hit_cs[name][t] - hit_cs[name][left]) / width
            for name in names
        ])
        baseline = r.v4.RED_PICK * 10 / r.v4.RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        w = np.exp(z - z.max())
        w /= w.sum()
        ensemble[t] = sum(
            w[i] * r.v4._rank_score(raw[name][t])
            for i, name in enumerate(names)
        )
    return ensemble


def _topk_hits_series(pred: np.ndarray, occ: np.ndarray, k: int = 10) -> np.ndarray:
    hits = np.full(len(pred), np.nan)
    for t in range(r.START, len(pred)):
        if np.isfinite(pred[t]).all():
            hits[t] = r.v4._topk_hits(pred[t], occ[t], k)
    return hits


def fast_gate(stable: np.ndarray, fast: np.ndarray, occ: np.ndarray) -> np.ndarray:
    stable_hits = _topk_hits_series(stable, occ, 10)
    fast_hits = _topk_hits_series(fast, occ, 10)
    active = np.zeros(len(stable), dtype=bool)
    for t in range(r.START, len(stable)):
        left = max(r.START, t - FAST_GATE_WINDOW)
        a = stable_hits[left:t]
        b = fast_hits[left:t]
        valid = np.isfinite(a) & np.isfinite(b)
        if int(valid.sum()) < FAST_GATE_MIN_HISTORY:
            continue
        active[t] = float(b[valid].mean() - a[valid].mean()) >= FAST_GATE_MARGIN
    return active


def repair_pool(
    stable: np.ndarray,
    fast: np.ndarray,
    active: np.ndarray,
    slots: int,
) -> np.ndarray:
    """只替换稳定前12的最末slots个位置，其他排名和分值尽量保持稳定模型原样。"""
    out = stable.copy()
    for t in range(r.START, len(stable)):
        if not active[t]:
            continue
        s_order = np.argsort(stable[t])[::-1]
        f_order = np.argsort(fast[t])[::-1]
        keep = list(s_order[: 12 - slots])
        additions = [int(x) for x in f_order if int(x) not in keep][:slots]
        if len(additions) < slots:
            additions.extend(
                int(x) for x in s_order[12 - slots : 12]
                if int(x) not in additions
            )
            additions = additions[:slots]

        removed = [int(x) for x in s_order[12 - slots : 12]]
        rank13_score = float(stable[t, s_order[12]])
        for j, n0 in enumerate(removed):
            if n0 not in additions:
                out[t, n0] = rank13_score - 1e-5 * (j + 1)
        for j, n0 in enumerate(additions):
            target_pos = 12 - slots + j
            target_score = float(stable[t, s_order[target_pos]])
            out[t, n0] = target_score + 1e-5 * (slots - j)
    return out


def _baseline_blue_scores(
    df: pd.DataFrame,
    stable_red: np.ndarray,
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
):
    groups, v45_groups, signal = s._groups_strict(stable_red, red_occ, "standard")
    actual_blue = df["blue"].to_numpy(int)
    fused, _ = r.v45.fuse_blue_predictions(
        blue_models, v45_groups, red_occ, actual_blue, r.START
    )
    score = np.full_like(blue_models["base"], np.nan)
    for t in range(r.START, len(df)):
        score[t] = fused[t] if signal[t] else blue_models["base"][t]
    picks = r._blue_choices(score, actual_blue, False)
    return groups, picks


def evaluate_defense_injection(
    df: pd.DataFrame,
    stable_red: np.ndarray,
    fast_red: np.ndarray,
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
    active: np.ndarray,
    slots: int,
) -> pd.DataFrame:
    """第一张票完全保持V4.5.1，快模型只替换第二张票最低分的1/2个号码。"""
    groups, blue_picks = _baseline_blue_scores(df, stable_red, red_occ, blue_models)
    actual_blue = df["blue"].to_numpy(int)
    rows = []
    for t in range(r.START, len(df)):
        a, b = groups[t]
        a = np.asarray(a, dtype=int).copy()
        b = np.asarray(b, dtype=int).copy()

        if active[t]:
            fast_order = np.argsort(fast_red[t])[::-1]
            existing = set(int(x) for x in a) | set(int(x) for x in b)
            additions = [int(x) for x in fast_order if int(x) not in existing][:slots]
            if additions:
                b_ranked_low = sorted(
                    (int(x) for x in b),
                    key=lambda x: float(stable_red[t, x]),
                )
                replace = b_ranked_low[: len(additions)]
                new_b = [int(x) for x in b if int(x) not in replace] + additions
                b = np.asarray(new_b, dtype=int)

        b1, b2 = int(blue_picks[t, 0]), int(blue_picks[t, 1])
        h1, h2 = int(red_occ[t, a].sum()), int(red_occ[t, b].sum())
        p1 = r.v4.prize_level(h1, b1 == actual_blue[t])
        p2 = r.v4.prize_level(h2, b2 == actual_blue[t])
        best = p1 if r.PRIZE_RANK[p1] <= r.PRIZE_RANK[p2] else p2
        rows.append({
            "t": t,
            "seq": int(df.iloc[t]["seq"]),
            "hit1": h1,
            "hit2": h2,
            "max_red_hit": max(h1, h2),
            "blue_hit": int(actual_blue[t] in (b1, b2)),
            "prize1": p1,
            "prize2": p2,
            "best_prize": best,
        })
    return pd.DataFrame(rows)


def _select_pool_variant(
    baseline: np.ndarray,
    variants: dict[str, np.ndarray],
    occ: np.ndarray,
    dev_end: int,
) -> str:
    base = r._red_pool_metrics(baseline, occ, r.START, dev_end)
    eligible = ["baseline"]
    for name, pred in variants.items():
        m = r._red_pool_metrics(pred, occ, r.START, dev_end)
        if (
            m["top12_4plus"] >= base["top12_4plus"]
            and m["top12_5plus"] >= base["top12_5plus"]
            and m["top12_mean"] >= base["top12_mean"] - 0.005
        ):
            eligible.append(name)
    all_pred = {"baseline": baseline, **variants}
    return max(
        eligible,
        key=lambda name: (
            r._red_pool_metrics(all_pred[name], occ, r.START, dev_end)["top12_5plus"],
            r._red_pool_metrics(all_pred[name], occ, r.START, dev_end)["top12_4plus"],
            r._red_pool_metrics(all_pred[name], occ, r.START, dev_end)["top12_mean"],
        ),
    )


def _choose_portfolio(
    baseline_result: pd.DataFrame,
    candidates: dict[str, pd.DataFrame],
    dev_end: int,
) -> str:
    base = r._summary(baseline_result, r.START, dev_end)
    eligible = ["baseline"]
    for name, result in candidates.items():
        m = r._summary(result, r.START, dev_end)
        if (
            m["fixed_return"] > base["fixed_return"]
            and m["max_red_4plus"] >= base["max_red_4plus"]
            and m["max_red_5plus"] >= base["max_red_5plus"]
        ):
            eligible.append(name)
    all_results = {"baseline": baseline_result, **candidates}
    return max(
        eligible,
        key=lambda name: (
            r._summary(all_results[name], r.START, dev_end)["max_red_5plus"],
            r._summary(all_results[name], r.START, dev_end)["max_red_4plus"],
            r._summary(all_results[name], r.START, dev_end)["fixed_return"],
        ),
    )


def main() -> None:
    df = r.load_current_dataframe()
    manifest = r.history_tool.load_manifest()
    dev_end = int(manifest["frozen_base_draws"])

    stable_red, _, red_occ = r.v4.walk_forward_red(df)
    fast_red = walk_fast_red(df)
    active = fast_gate(stable_red, fast_red, red_occ)

    pool_variants = {
        "repair1": repair_pool(stable_red, fast_red, active, 1),
        "repair2": repair_pool(stable_red, fast_red, active, 2),
    }
    chosen_pool = _select_pool_variant(stable_red, pool_variants, red_occ, dev_end)

    blue_models = r.v45.build_blue_models(df)
    baseline_result = s._evaluate_strict(
        df, stable_red, red_occ, blue_models, "standard", False
    )
    if r._summary(baseline_result, r.START, dev_end)["fixed_return"] != EXPECTED_DEV_FIXED_RETURN:
        raise RuntimeError("V4.5.1基线复现失败")

    pool_results = {
        name: s._evaluate_strict(df, pred, red_occ, blue_models, "standard", False)
        for name, pred in pool_variants.items()
    }
    defense_results = {
        "defense1": evaluate_defense_injection(
            df, stable_red, fast_red, red_occ, blue_models, active, 1
        ),
        "defense2": evaluate_defense_injection(
            df, stable_red, fast_red, red_occ, blue_models, active, 2
        ),
    }
    portfolio_candidates = {**pool_results, **defense_results}
    chosen_portfolio = _choose_portfolio(baseline_result, portfolio_candidates, dev_end)
    chosen_result = {"baseline": baseline_result, **portfolio_candidates}[chosen_portfolio]

    pool_report = {
        "baseline": {
            "development": r._red_pool_metrics(stable_red, red_occ, r.START, dev_end),
            "forward": r._red_pool_metrics(stable_red, red_occ, dev_end, len(df)),
        }
    }
    for name, pred in pool_variants.items():
        pool_report[name] = {
            "development": r._red_pool_metrics(pred, red_occ, r.START, dev_end),
            "forward": r._red_pool_metrics(pred, red_occ, dev_end, len(df)),
        }

    results_report = {
        "baseline": {
            "development": r._summary(baseline_result, r.START, dev_end),
            "quarters": r._quarters(baseline_result, r.START, dev_end),
            "forward": r._summary(baseline_result, dev_end, len(df)),
        }
    }
    for name, result in portfolio_candidates.items():
        results_report[name] = {
            "development": r._summary(result, r.START, dev_end),
            "quarters": r._quarters(result, r.START, dev_end),
            "forward": r._summary(result, dev_end, len(df)),
        }

    base = results_report["baseline"]
    chosen = results_report[chosen_portfolio]
    quarter_ok = sum(
        int(cq["fixed_return"] >= bq["fixed_return"])
        for cq, bq in zip(chosen["quarters"], base["quarters"])
    )
    dev_ok = (
        chosen["development"]["fixed_return"] > base["development"]["fixed_return"]
        and chosen["development"]["max_red_4plus"] >= base["development"]["max_red_4plus"]
        and chosen["development"]["max_red_5plus"] >= base["development"]["max_red_5plus"]
        and quarter_ok >= 3
    )
    forward_ok = (
        chosen["forward"]["fixed_return"] >= base["forward"]["fixed_return"]
        and chosen["forward"]["max_red_4plus"] >= base["forward"]["max_red_4plus"]
    )

    report = {
        "status": "research_complete",
        "principle": "快模型只做因果补漏；所有候选选择只看冻结开发段，后56期仅在锁定后验收",
        "fast_model": {
            "refit_block": FAST_REFIT_BLOCK,
            "performance_window": FAST_PERF_WINDOW,
            "gate_window": FAST_GATE_WINDOW,
            "gate_margin": FAST_GATE_MARGIN,
            "gate_active_rate_development": float(active[r.START:dev_end].mean()),
            "gate_active_rate_forward": float(active[dev_end:].mean()),
        },
        "pool_metrics": pool_report,
        "portfolio_results": results_report,
        "selection": {
            "pool": chosen_pool,
            "portfolio": chosen_portfolio,
        },
        "promotion_gate": {
            "development_pass": bool(dev_ok),
            "development_quarters_not_worse": int(quarter_ok),
            "forward_pass": bool(forward_ok),
            "promote": bool(dev_ok and forward_ok and chosen_portfolio != "baseline"),
        },
    }

    out = ROOT / "backtests" / "v4_7" / "fast_repair_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
