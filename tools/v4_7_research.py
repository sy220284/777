from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
START = 750
MODEL_START = 500
PERF_WINDOW = 250
BLUE_RANK_WINDOW = 800
BLUE_RANK_PRIOR = 64.0
BLUE_RANK_TOPN = 4


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v4 = _load("v47_v4", ROOT / "models" / "v4" / "predictor.py")
v43 = _load("v47_v43", ROOT / "models" / "v4_3" / "predictor.py")
v45 = _load("v47_v45", ROOT / "models" / "v4_5" / "predictor.py")
gate = _load("v47_gate", ROOT / "models" / "v4_5_1" / "signal_gate.py")
history_tool = _load("v47_history", ROOT / "tools" / "load_history.py")

PRIZE_RANK = v43.PRIZE_RANK
FIXED_PRIZE = v43.FIXED_PRIZE


def load_current_dataframe() -> pd.DataFrame:
    rows = history_tool.load_history()
    return pd.DataFrame([
        {
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        }
        for row in rows
    ])


def _softmax(values: np.ndarray) -> np.ndarray:
    values = values - np.max(values)
    e = np.exp(np.clip(values, -20.0, 20.0))
    return e / e.sum()


def _expert_hits(raw: dict[str, np.ndarray], occ: np.ndarray, k: int) -> dict[str, np.ndarray]:
    out: dict[str, np.ndarray] = {}
    for name, pred in raw.items():
        hits = np.zeros(len(pred), dtype=float)
        for t in range(MODEL_START, len(pred)):
            if np.isfinite(pred[t]).all():
                hits[t] = v4._topk_hits(pred[t], occ[t], k)
        out[name] = np.concatenate([[0.0], np.cumsum(hits)])
    return out


def _objective_aligned_ensemble(
    raw: dict[str, np.ndarray],
    occ: np.ndarray,
    mode: str,
) -> np.ndarray:
    """把在线专家权重与最终前12候选目标对齐。

    V4原版用前10命中给三个专家加权，但最终组合实际消费前12候选。
    本研究只改变在线专家评分口径，不改底层特征和各子模型训练。
    """
    names = list(raw)
    c6 = _expert_hits(raw, occ, 6)
    c12 = _expert_hits(raw, occ, 12)
    n = len(occ)
    result = np.full((n, v4.RED_COUNT), np.nan)

    for t in range(START, n):
        left = max(MODEL_START, t - PERF_WINDOW)
        width = max(1, t - left)
        p6 = np.asarray([(c6[name][t] - c6[name][left]) / width for name in names])
        p12 = np.asarray([(c12[name][t] - c12[name][left]) / width for name in names])
        z6 = np.clip((p6 - v4.RED_PICK * 6 / v4.RED_COUNT) / 0.08, -3.0, 3.0)
        z12 = np.clip((p12 - v4.RED_PICK * 12 / v4.RED_COUNT) / 0.08, -3.0, 3.0)
        if mode == "top12":
            z = z12
        elif mode == "dual":
            z = 0.35 * z6 + 0.65 * z12
        else:
            raise ValueError(mode)
        w = _softmax(z)
        result[t] = sum(w[i] * v4._rank_score(raw[name][t]) for i, name in enumerate(names))
    return result


def _signal_array(red_pred: np.ndarray, red_occ: np.ndarray) -> np.ndarray:
    n = len(red_pred)
    hits = np.full(n, np.nan)
    for t in range(START, n):
        top12 = np.argsort(red_pred[t])[::-1][:12]
        hits[t] = red_occ[t, top12].sum()
    signal = np.zeros(n, dtype=bool)
    for t in range(START, n):
        left = max(START, t - gate.WINDOW)
        past = hits[left:t]
        past = past[np.isfinite(past)]
        signal[t] = len(past) >= gate.MIN_HISTORY and float(np.mean(past)) >= gate.RANDOM_TOP12_MEAN
    return signal


def _build_groups(
    red_pred: np.ndarray,
    red_occ: np.ndarray,
    mode: str,
):
    baseline, _, _ = v45._baseline_red_groups(red_pred, red_occ, START)
    concentrated, active, _ = v45.build_red_portfolio(red_pred, red_occ, START)
    signal = _signal_array(red_pred, red_occ)
    groups: dict[int, tuple[np.ndarray, np.ndarray]] = {}

    for t in range(START, len(red_pred)):
        if not signal[t]:
            groups[t] = baseline[t]
            continue
        if mode == "standard":
            groups[t] = concentrated[t]
            continue
        if mode != "attack_defense":
            raise ValueError(mode)
        if not active[t]:
            groups[t] = baseline[t]
            continue

        ca, cb = concentrated[t]
        primary = ca if red_pred[t, ca].sum() >= red_pred[t, cb].sum() else cb
        ba, bb = baseline[t]
        candidates = (ba, bb)
        overlaps = [len(set(primary.tolist()) & set(x.tolist())) for x in candidates]
        best_overlap = min(overlaps)
        options = [x for x, ov in zip(candidates, overlaps) if ov == best_overlap]
        secondary = max(options, key=lambda x: float(red_pred[t, x].sum()))
        groups[t] = (primary, secondary)
    return groups, signal


def _fused_blue_for_variant(
    blue_models: dict[str, np.ndarray],
    red_groups: dict[int, tuple[np.ndarray, np.ndarray]],
    red_occ: np.ndarray,
    actual_blue: np.ndarray,
    signal: np.ndarray,
):
    fused, _ = v45.fuse_blue_predictions(blue_models, red_groups, red_occ, actual_blue, START)
    base = blue_models["base"]
    final = np.full_like(base, np.nan)
    for t in range(START, len(base)):
        final[t] = fused[t] if signal[t] else base[t]
    return final


def _blue_choices(score: np.ndarray, actual_blue: np.ndarray, calibrated: bool) -> np.ndarray:
    n = len(score)
    picks = np.zeros((n, 2), dtype=int)
    if not calibrated:
        for t in range(START, n):
            order = np.argsort(score[t])[::-1]
            picks[t] = order[:2] + 1
        return picks

    rank_hit = np.zeros((n, v4.BLUE_COUNT), dtype=float)
    for t in range(START, n):
        order = np.argsort(score[t])[::-1]
        pos = int(np.where(order == actual_blue[t] - 1)[0][0])
        rank_hit[t, pos] = 1.0
    cs = np.concatenate([np.zeros((1, v4.BLUE_COUNT)), np.cumsum(rank_hit, axis=0)], axis=0)

    for t in range(START, n):
        order = np.argsort(score[t])[::-1]
        left = max(START, t - BLUE_RANK_WINDOW)
        width = max(0, t - left)
        if width < 100:
            picks[t] = order[:2] + 1
            continue
        counts = cs[t] - cs[left]
        posterior = (counts + BLUE_RANK_PRIOR / v4.BLUE_COUNT) / (width + BLUE_RANK_PRIOR)
        candidate_positions = np.arange(BLUE_RANK_TOPN)
        # 只允许前4名内部重排，历史命中率并列时优先当前模型排名更高者。
        chosen_pos = sorted(candidate_positions, key=lambda r: (-posterior[r], r))[:2]
        picks[t] = [int(order[r] + 1) for r in chosen_pos]
    return picks


def _red_pool_metrics(red_pred: np.ndarray, red_occ: np.ndarray, start: int, end: int) -> dict:
    hit12, hit6 = [], []
    for t in range(start, end):
        order = np.argsort(red_pred[t])[::-1]
        hit6.append(int(red_occ[t, order[:6]].sum()))
        hit12.append(int(red_occ[t, order[:12]].sum()))
    a = np.asarray(hit12)
    b = np.asarray(hit6)
    return {
        "draws": int(len(a)),
        "top6_mean": float(b.mean()),
        "top12_mean": float(a.mean()),
        "top12_4plus": int((a >= 4).sum()),
        "top12_5plus": int((a >= 5).sum()),
        "top12_6": int((a >= 6).sum()),
    }


def _evaluate(
    df: pd.DataFrame,
    red_pred: np.ndarray,
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
    grouping_mode: str,
    blue_calibrated: bool,
) -> pd.DataFrame:
    groups, signal = _build_groups(red_pred, red_occ, grouping_mode)
    actual_blue = df["blue"].to_numpy(int)
    blue_score = _fused_blue_for_variant(blue_models, groups, red_occ, actual_blue, signal)
    blue_pick = _blue_choices(blue_score, actual_blue, blue_calibrated)

    rows = []
    for t in range(START, len(df)):
        a, b = groups[t]
        # 第一蓝球给红球边际分更高的一注。
        if red_pred[t, a].sum() < red_pred[t, b].sum():
            a, b = b, a
        b1, b2 = (int(blue_pick[t, 0]), int(blue_pick[t, 1]))
        h1 = int(red_occ[t, a].sum())
        h2 = int(red_occ[t, b].sum())
        p1 = v4.prize_level(h1, b1 == actual_blue[t])
        p2 = v4.prize_level(h2, b2 == actual_blue[t])
        best = p1 if PRIZE_RANK[p1] <= PRIZE_RANK[p2] else p2
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


def _summary(result: pd.DataFrame, start_t: int, end_t: int) -> dict:
    x = result[(result["t"] >= start_t) & (result["t"] < end_t)]
    prizes = pd.concat([x["prize1"], x["prize2"]], ignore_index=True)
    best = x["best_prize"].value_counts().to_dict()
    ticket = prizes.value_counts().to_dict()
    return {
        "draws": int(len(x)),
        "best_prize": {k: int(best.get(k, 0)) for k in PRIZE_RANK},
        "ticket_prize": {k: int(ticket.get(k, 0)) for k in PRIZE_RANK},
        "fixed_return": int(sum(FIXED_PRIZE[p] for p in prizes)),
        "winning_draw_rate": float((x["best_prize"] != "未中奖").mean()),
        "blue_top2_rate": float(x["blue_hit"].mean()),
        "max_red_4plus": int((x["max_red_hit"] >= 4).sum()),
        "max_red_5plus": int((x["max_red_hit"] >= 5).sum()),
        "max_red_6": int((x["max_red_hit"] >= 6).sum()),
    }


def _quarters(result: pd.DataFrame, start_t: int, end_t: int) -> list[dict]:
    edges = np.linspace(start_t, end_t, 5, dtype=int)
    return [_summary(result, int(edges[i]), int(edges[i + 1])) for i in range(4)]


def _choose_red_ensemble(candidates: dict[str, np.ndarray], occ: np.ndarray, dev_end: int) -> str:
    # 仅用冻结基线开发段选择；前向56期不参与选择。
    metrics = {name: _red_pool_metrics(pred, occ, START, dev_end) for name, pred in candidates.items()}
    base = metrics["baseline"]
    eligible = ["baseline"]
    for name in ("top12", "dual"):
        m = metrics[name]
        if (
            m["top12_4plus"] >= base["top12_4plus"]
            and m["top12_5plus"] >= base["top12_5plus"]
            and m["top12_mean"] >= base["top12_mean"] - 0.005
        ):
            eligible.append(name)
    return max(
        eligible,
        key=lambda name: (
            metrics[name]["top12_5plus"],
            metrics[name]["top12_4plus"],
            metrics[name]["top12_mean"],
        ),
    )


def _choose_grouping(standard: pd.DataFrame, attack: pd.DataFrame, dev_end: int) -> str:
    a = _summary(standard, START, dev_end)
    b = _summary(attack, START, dev_end)
    if (
        b["fixed_return"] > a["fixed_return"]
        and b["max_red_4plus"] >= a["max_red_4plus"]
        and b["max_red_5plus"] >= a["max_red_5plus"]
    ):
        return "attack_defense"
    return "standard"


def _choose_blue(plain: pd.DataFrame, calibrated: pd.DataFrame, dev_end: int) -> bool:
    a = _summary(plain, START, dev_end)
    b = _summary(calibrated, START, dev_end)
    return bool(
        b["blue_top2_rate"] > a["blue_top2_rate"]
        and b["fixed_return"] >= a["fixed_return"]
    )


def main() -> None:
    df = load_current_dataframe()
    manifest = history_tool.load_manifest()
    dev_end = int(manifest.get("frozen_base_draws", len(df)))
    if not START < dev_end < len(df):
        raise RuntimeError(f"开发/前向边界异常: dev_end={dev_end} n={len(df)}")

    baseline_red, raw, red_occ = v4.walk_forward_red(df)
    red_candidates = {
        "baseline": baseline_red,
        "top12": _objective_aligned_ensemble(raw, red_occ, "top12"),
        "dual": _objective_aligned_ensemble(raw, red_occ, "dual"),
    }
    chosen_red = _choose_red_ensemble(red_candidates, red_occ, dev_end)
    red_pred = red_candidates[chosen_red]

    blue_models = v45.build_blue_models(df)
    standard_plain = _evaluate(df, red_pred, red_occ, blue_models, "standard", False)
    attack_plain = _evaluate(df, red_pred, red_occ, blue_models, "attack_defense", False)
    grouping = _choose_grouping(standard_plain, attack_plain, dev_end)

    chosen_plain = standard_plain if grouping == "standard" else attack_plain
    chosen_cal = _evaluate(df, red_pred, red_occ, blue_models, grouping, True)
    blue_calibrated = _choose_blue(chosen_plain, chosen_cal, dev_end)
    locked = chosen_cal if blue_calibrated else chosen_plain

    baseline_full = _evaluate(df, baseline_red, red_occ, blue_models, "standard", False)

    report = {
        "status": "research_complete",
        "method": "所有选择只使用冻结基线开发段；2026-05-03之后56期只在锁定方案后做一次前向报告",
        "dataset_draws": int(len(df)),
        "development_end_t": dev_end,
        "forward_draws": int(len(df) - dev_end),
        "red_pool_development": {
            name: _red_pool_metrics(pred, red_occ, START, dev_end)
            for name, pred in red_candidates.items()
        },
        "red_pool_forward": {
            name: _red_pool_metrics(pred, red_occ, dev_end, len(df))
            for name, pred in red_candidates.items()
        },
        "selection": {
            "red_ensemble": chosen_red,
            "grouping": grouping,
            "blue_rank_calibration": blue_calibrated,
        },
        "baseline": {
            "development": _summary(baseline_full, START, dev_end),
            "development_quarters": _quarters(baseline_full, START, dev_end),
            "forward": _summary(baseline_full, dev_end, len(df)),
        },
        "locked_candidate": {
            "development": _summary(locked, START, dev_end),
            "development_quarters": _quarters(locked, START, dev_end),
            "forward": _summary(locked, dev_end, len(df)),
        },
        "component_development": {
            "standard_plain": _summary(standard_plain, START, dev_end),
            "attack_defense_plain": _summary(attack_plain, START, dev_end),
            "chosen_blue_calibrated": _summary(chosen_cal, START, dev_end),
        },
    }

    b = report["baseline"]
    c = report["locked_candidate"]
    q_ok = sum(
        int(cq["fixed_return"] >= bq["fixed_return"])
        for cq, bq in zip(c["development_quarters"], b["development_quarters"])
    )
    forward_ok = (
        c["forward"]["fixed_return"] >= b["forward"]["fixed_return"]
        and c["forward"]["max_red_4plus"] >= b["forward"]["max_red_4plus"]
    )
    dev_ok = (
        c["development"]["fixed_return"] > b["development"]["fixed_return"]
        and c["development"]["max_red_4plus"] >= b["development"]["max_red_4plus"]
        and c["development"]["max_red_5plus"] >= b["development"]["max_red_5plus"]
        and q_ok >= 3
    )
    report["promotion_gate"] = {
        "development_pass": bool(dev_ok),
        "development_quarters_not_worse": int(q_ok),
        "forward_pass": bool(forward_ok),
        "promote": bool(dev_ok and forward_ok),
    }

    out = ROOT / "backtests" / "v4_7" / "research_summary.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
