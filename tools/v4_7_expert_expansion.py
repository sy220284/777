from __future__ import annotations

import json
from pathlib import Path

import numpy as np
import pandas as pd
from sklearn.ensemble import HistGradientBoostingClassifier

import v4_7_research as r
import v4_7_research_strict as s

ROOT = Path(__file__).resolve().parents[1]
BLOCK = 250
PERF_WINDOW = 250
EXPECTED_DEV_FIXED_RETURN = 4760


def _fit_logistic_expert(
    x: np.ndarray,
    y: np.ndarray,
    t_index: np.ndarray,
    n: int,
    window: int,
) -> np.ndarray:
    raw = np.full((n, r.v4.RED_COUNT), np.nan)
    for bstart in range(r.MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        train = (t_index < bstart) & (t_index >= max(200, bstart - window))
        test = (t_index >= bstart) & (t_index < bend)
        model = r.v4._logistic()
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        raw[t_index[test]] = p.reshape(-1, r.v4.RED_COUNT)
    return raw


def _fit_tree_expert(
    x: np.ndarray,
    y: np.ndarray,
    t_index: np.ndarray,
    n: int,
    window: int = 1000,
) -> np.ndarray:
    raw = np.full((n, r.v4.RED_COUNT), np.nan)
    for bstart in range(r.MODEL_START, n, BLOCK):
        bend = min(n, bstart + BLOCK)
        train = (t_index < bstart) & (t_index >= max(200, bstart - window))
        test = (t_index >= bstart) & (t_index < bend)
        model = HistGradientBoostingClassifier(
            learning_rate=0.05,
            max_iter=120,
            max_depth=3,
            min_samples_leaf=80,
            l2_regularization=1.0,
            random_state=0,
        )
        model.fit(x[train].reshape(-1, x.shape[-1]), y[train].reshape(-1))
        p = model.predict_proba(x[test].reshape(-1, x.shape[-1]))[:, 1]
        raw[t_index[test]] = p.reshape(-1, r.v4.RED_COUNT)
    return raw


def _ensemble(raw: dict[str, np.ndarray], occ: np.ndarray) -> np.ndarray:
    names = list(raw)
    hit_cs = r._expert_hits(raw, occ, 10)
    out = np.full((len(occ), r.v4.RED_COUNT), np.nan)
    for t in range(r.START, len(occ)):
        left = max(r.MODEL_START, t - PERF_WINDOW)
        width = max(1, t - left)
        perf = np.asarray([
            (hit_cs[name][t] - hit_cs[name][left]) / width
            for name in names
        ])
        baseline = r.v4.RED_PICK * 10 / r.v4.RED_COUNT
        z = np.clip((perf - baseline) / 0.08, -3.0, 3.0)
        w = np.exp(z - z.max())
        w /= w.sum()
        out[t] = sum(
            w[i] * r.v4._rank_score(raw[name][t])
            for i, name in enumerate(names)
        )
    return out


def _select_candidate(
    candidates: dict[str, np.ndarray],
    occ: np.ndarray,
    dev_end: int,
) -> str:
    base = r._red_pool_metrics(candidates["baseline"], occ, r.START, dev_end)
    eligible = ["baseline"]
    for name in ("recent250", "nonlinear_tree"):
        m = r._red_pool_metrics(candidates[name], occ, r.START, dev_end)
        if (
            m["top12_4plus"] >= base["top12_4plus"]
            and m["top12_5plus"] >= base["top12_5plus"]
            and m["top12_mean"] >= base["top12_mean"] - 0.005
        ):
            eligible.append(name)
    return max(
        eligible,
        key=lambda name: (
            r._red_pool_metrics(candidates[name], occ, r.START, dev_end)["top12_5plus"],
            r._red_pool_metrics(candidates[name], occ, r.START, dev_end)["top12_4plus"],
            r._red_pool_metrics(candidates[name], occ, r.START, dev_end)["top12_mean"],
        ),
    )


def main() -> None:
    df = r.load_current_dataframe()
    dev_end = int(r.history_tool.load_manifest()["frozen_base_draws"])
    baseline, base_raw, occ = r.v4.walk_forward_red(df)
    x, y, t_index, _ = r.v4.build_red_features(df)
    n = len(df)

    raw_recent = dict(base_raw)
    raw_recent["w250"] = _fit_logistic_expert(x, y, t_index, n, 250)
    pred_recent = _ensemble(raw_recent, occ)

    raw_tree = dict(base_raw)
    raw_tree["tree1000"] = _fit_tree_expert(x, y, t_index, n, 1000)
    pred_tree = _ensemble(raw_tree, occ)

    candidates = {
        "baseline": baseline,
        "recent250": pred_recent,
        "nonlinear_tree": pred_tree,
    }
    chosen = _select_candidate(candidates, occ, dev_end)

    blue_models = r.v45.build_blue_models(df)
    baseline_result = s._evaluate_strict(df, baseline, occ, blue_models, "standard", False)
    if r._summary(baseline_result, r.START, dev_end)["fixed_return"] != EXPECTED_DEV_FIXED_RETURN:
        raise RuntimeError("V4.5.1基线复现失败")

    result_map = {
        name: s._evaluate_strict(df, pred, occ, blue_models, "standard", False)
        for name, pred in candidates.items()
    }

    pool = {
        name: {
            "development": r._red_pool_metrics(pred, occ, r.START, dev_end),
            "forward": r._red_pool_metrics(pred, occ, dev_end, n),
        }
        for name, pred in candidates.items()
    }
    results = {
        name: {
            "development": r._summary(result, r.START, dev_end),
            "quarters": r._quarters(result, r.START, dev_end),
            "forward": r._summary(result, dev_end, n),
        }
        for name, result in result_map.items()
    }

    base = results["baseline"]
    cand = results[chosen]
    quarter_ok = sum(
        int(c["fixed_return"] >= b["fixed_return"])
        for c, b in zip(cand["quarters"], base["quarters"])
    )
    dev_ok = chosen != "baseline" and (
        cand["development"]["fixed_return"] > base["development"]["fixed_return"]
        and cand["development"]["max_red_4plus"] >= base["development"]["max_red_4plus"]
        and cand["development"]["max_red_5plus"] >= base["development"]["max_red_5plus"]
        and quarter_ok >= 3
    )
    forward_ok = (
        cand["forward"]["fixed_return"] >= base["forward"]["fixed_return"]
        and cand["forward"]["max_red_4plus"] >= base["forward"]["max_red_4plus"]
    )

    report = {
        "status": "research_complete",
        "principle": "保留V4三个冻结专家，只分别增加一个短窗线性专家或一个非线性树专家；两条路线独立比较，不做组合调参",
        "pool_metrics": pool,
        "portfolio_results": results,
        "selection": chosen,
        "promotion_gate": {
            "development_pass": bool(dev_ok),
            "development_quarters_not_worse": int(quarter_ok),
            "forward_pass": bool(forward_ok),
            "promote": bool(dev_ok and forward_ok),
        },
    }

    out = ROOT / "backtests" / "v4_7" / "expert_expansion_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
