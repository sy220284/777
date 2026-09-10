from __future__ import annotations

import argparse
import importlib.util
import itertools
import json
import math
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
V45_PATH = ROOT / "models" / "v4_5" / "predictor.py"
LOAD_PATH = ROOT / "tools" / "load_history.py"
START = 750
CORE_SIZES = tuple(range(0, 6))
PRIZE_ORDER = {"一等奖": 1, "二等奖": 2, "三等奖": 3, "四等奖": 4, "五等奖": 5, "六等奖": 6, "未中奖": 99}
FIXED_PRIZE = {"一等奖": 0, "二等奖": 0, "三等奖": 3000, "四等奖": 200, "五等奖": 10, "六等奖": 5, "未中奖": 0}


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v45 = _load_module("ssq_v45_core_fringe", V45_PATH)
loader = _load_module("ssq_history_loader_core_fringe", LOAD_PATH)
v4 = v45.v4
v43 = v45.v43


def _current_dataframe() -> pd.DataFrame:
    rows = loader.load_history()
    records = []
    for row in rows:
        records.append({
            "seq": int(row["seq"]),
            **{f"red{i}": int(row["red"][i - 1]) for i in range(1, 7)},
            "blue": int(row["blue"]),
        })
    return pd.DataFrame(records)


def _rank01(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def _robust_red_score(raw: dict[str, np.ndarray], t: int) -> tuple[np.ndarray, np.ndarray]:
    """跨训练窗口共识分数。

    只使用各V4子模型在目标期之前训练得到的预测。均值表示共识，标准差表示模型分歧；
    不在这里引入人工拟合的惩罚系数，核心排序按“共识优先、分歧次优”做字典序稳定化。
    """
    ranks = np.stack([_rank01(pred[t]) for pred in raw.values()], axis=0)
    return ranks.mean(axis=0), ranks.std(axis=0)


def _ticket_components(ticket: np.ndarray, red_score: np.ndarray, affinity: np.ndarray) -> tuple[float, float]:
    marginal = float(red_score[ticket].sum())
    pair = 0.0
    for i, j in itertools.combinations(ticket, 2):
        pair += float(affinity[int(i), int(j)])
    return marginal, pair


def _z(values: np.ndarray) -> np.ndarray:
    return (values - values.mean()) / (values.std() + 1e-9)


def _core_fringe_pair(
    t: int,
    core_size: int,
    red_pred: np.ndarray,
    raw: dict[str, np.ndarray],
    affinity: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, dict[str, float]]:
    consensus, disagreement = _robust_red_score(raw, t)
    # 核心号优先选择三个训练窗口都稳定靠前的号码：先共识均值，再用较低分歧打破近似并列。
    stable_order = sorted(
        range(v4.RED_COUNT),
        key=lambda n: (float(consensus[n]), -float(disagreement[n])),
        reverse=True,
    )
    core = np.asarray(stable_order[:core_size], dtype=int)
    core_set = set(int(x) for x in core)

    # 两票共享core后，边缘位保持完全分叉；总独立号码数固定为12-core_size。
    need = 2 * (6 - core_size)
    ensemble_order = np.argsort(red_pred[t])[::-1]
    fringe = [int(n) for n in ensemble_order if int(n) not in core_set][:need]
    if len(fringe) != need:
        raise RuntimeError("边缘候选不足")
    fringe = np.asarray(fringe, dtype=int)
    r = 6 - core_size

    # 穷举边缘分区。两张票视为无序，因此强制第一个边缘号进入A，避免重复计算互补分区。
    partitions: list[tuple[np.ndarray, np.ndarray]] = []
    marg_a: list[float] = []
    marg_b: list[float] = []
    pair_a: list[float] = []
    pair_b: list[float] = []
    for choice in itertools.combinations(range(need), r):
        if need > 0 and 0 not in choice:
            continue
        mask = np.zeros(need, dtype=bool)
        mask[list(choice)] = True
        a = np.concatenate([core, fringe[mask]])
        b = np.concatenate([core, fringe[~mask]])
        ma, pa = _ticket_components(a, red_pred[t], affinity)
        mb, pb = _ticket_components(b, red_pred[t], affinity)
        partitions.append((a, b))
        marg_a.append(ma)
        marg_b.append(mb)
        pair_a.append(pa)
        pair_b.append(pb)

    ma = np.asarray(marg_a)
    mb = np.asarray(marg_b)
    pa = np.asarray(pair_a)
    pb = np.asarray(pair_b)
    # 继承V4.5已验证的边际/两两关系比例，但对两票使用“强票+弱票”对称目标；
    # 不针对当前实验重新调权重。
    sa = v45.RED_MARGINAL_WEIGHT * _z(ma) + v45.RED_PRIMARY_PAIR_WEIGHT * _z(pa)
    sb = v45.RED_MARGINAL_WEIGHT * _z(mb) + v45.RED_PRIMARY_PAIR_WEIGHT * _z(pb)
    objective = np.maximum(sa, sb) + 0.5 * np.minimum(sa, sb)
    best = int(np.argmax(objective))
    a, b = partitions[best]
    if red_pred[t, a].sum() < red_pred[t, b].sum():
        a, b = b, a
    meta = {
        "core_size": float(core_size),
        "core_consensus_mean": float(consensus[core].mean()) if len(core) else 0.0,
        "core_disagreement_mean": float(disagreement[core].mean()) if len(core) else 0.0,
        "portfolio_objective": float(objective[best]),
    }
    return a, b, meta


def _red_reward(max_hit: int) -> float:
    """平滑偏向高红球命中的奖励，不使用奖金，避免被单次蓝球偶然性支配。"""
    return float(math.comb(max_hit, 3)) if max_hit >= 3 else 0.0


def _best_prize(p1: str, p2: str) -> str:
    return p1 if PRIZE_ORDER[p1] <= PRIZE_ORDER[p2] else p2


def _evaluate_groups(
    df: pd.DataFrame,
    groups: dict[int, tuple[np.ndarray, np.ndarray]],
    red_occ: np.ndarray,
    blue_models: dict[str, np.ndarray],
    start: int,
) -> pd.DataFrame:
    actual_blue = df["blue"].to_numpy(int)
    blue_fused, _ = v45.fuse_blue_predictions(blue_models, groups, red_occ, actual_blue, start)
    rows = []
    for t in range(start, len(df)):
        a, b = groups[t]
        blue_order = np.argsort(blue_fused[t])[::-1]
        blue1, blue2 = int(blue_order[0] + 1), int(blue_order[1] + 1)
        hit1 = int(red_occ[t, a].sum())
        hit2 = int(red_occ[t, b].sum())
        p1 = v4.prize_level(hit1, blue1 == actual_blue[t])
        p2 = v4.prize_level(hit2, blue2 == actual_blue[t])
        rows.append({
            "t": t,
            "seq": int(df.iloc[t]["seq"]),
            "hit1": hit1,
            "hit2": hit2,
            "max_red_hit": max(hit1, hit2),
            "prize1": p1,
            "prize2": p2,
            "best_prize": _best_prize(p1, p2),
            "ticket1": " ".join(f"{int(x)+1:02d}" for x in a),
            "ticket2": " ".join(f"{int(x)+1:02d}" for x in b),
            "blue1": blue1,
            "blue2": blue2,
        })
    return pd.DataFrame(rows)


def _adaptive_groups(
    fixed_groups: dict[int, dict[int, tuple[np.ndarray, np.ndarray]]],
    red_occ: np.ndarray,
    start: int,
    n: int,
) -> tuple[dict[int, tuple[np.ndarray, np.ndarray]], np.ndarray]:
    """只按过去已开奖的4+红球事件率选择core_size。

    Beta(1,1)后验均值给稀有事件做收缩；5+只作为极小的同分辅助，避免三次历史事件把策略拉偏。
    """
    success4 = {k: 0 for k in CORE_SIZES}
    success5 = {k: 0 for k in CORE_SIZES}
    seen = 0
    out: dict[int, tuple[np.ndarray, np.ndarray]] = {}
    chosen = np.full(n, -1, dtype=int)

    for t in range(start, n):
        scores = {}
        for k in CORE_SIZES:
            p4 = (success4[k] + 1.0) / (seen + 2.0)
            p5 = (success5[k] + 1.0) / (seen + 2.0)
            # 主要追踪相对稳定的4+事件，5+只占很小权重。
            scores[k] = p4 + 0.10 * p5
        best_score = max(scores.values())
        # 完全并列时选择较小共享核心，优先保留覆盖面。
        k_now = min(k for k, score in scores.items() if abs(score - best_score) < 1e-15)
        out[t] = fixed_groups[k_now][t]
        chosen[t] = k_now

        # 当前期开奖结果只能在完成当前期选择以后更新，供下一期使用。
        for k in CORE_SIZES:
            a, b = fixed_groups[k][t]
            h = max(int(red_occ[t, a].sum()), int(red_occ[t, b].sum()))
            success4[k] += int(h >= 4)
            success5[k] += int(h >= 5)
        seen += 1
    return out, chosen


def _segment_summary(result: pd.DataFrame) -> dict:
    ticket_prizes = pd.concat([result["prize1"], result["prize2"]], ignore_index=True)
    best = result["best_prize"].value_counts().to_dict()
    ticket = ticket_prizes.value_counts().to_dict()
    fixed_return = int(sum(FIXED_PRIZE[p] for p in ticket_prizes))
    maxhit = result["max_red_hit"].value_counts().to_dict()
    return {
        "draws": int(len(result)),
        "tickets": int(len(result) * 2),
        "best_prize_counts": {k: int(best.get(k, 0)) for k in PRIZE_ORDER},
        "ticket_prize_counts": {k: int(ticket.get(k, 0)) for k in PRIZE_ORDER},
        "highest_prize": min((p for p, c in best.items() if c > 0), key=lambda p: PRIZE_ORDER[p], default="未中奖"),
        "fixed_return_yuan": fixed_return,
        "fixed_return_ratio": float(fixed_return / max(1, len(result) * 4)),
        "red_4plus_draws": int((result["max_red_hit"] >= 4).sum()),
        "red_5plus_draws": int((result["max_red_hit"] >= 5).sum()),
        "red_6_draws": int((result["max_red_hit"] >= 6).sum()),
        "mean_max_red_hit": float(result["max_red_hit"].mean()),
        "red_max_hit_distribution": {str(k): int(v) for k, v in sorted(maxhit.items())},
    }


def _four_blocks(result: pd.DataFrame) -> list[dict]:
    idx = np.array_split(np.arange(len(result)), 4)
    blocks = []
    for i, ids in enumerate(idx, 1):
        part = result.iloc[ids]
        s = _segment_summary(part)
        s["block"] = i
        s["seq_first"] = int(part.iloc[0]["seq"])
        s["seq_last"] = int(part.iloc[-1]["seq"])
        blocks.append(s)
    return blocks


def main() -> None:
    p = argparse.ArgumentParser(description="V5研究：因果核心号共享/边缘号分叉里程碑实验")
    p.add_argument("--summary", default="backtests/v5_research/core_fringe_summary.json")
    p.add_argument("--details", default="backtests/v5_research/core_fringe_details.csv")
    args = p.parse_args()

    df = _current_dataframe()
    if len(df) != loader.CURRENT_DRAWS:
        raise SystemExit(f"数据期数异常: {len(df)}")

    red_pred, raw, red_occ = v4.walk_forward_red(df)
    pair_cs, marg_cs = v43._pair_cumulative(red_occ)
    blue_models = v45.build_blue_models(df)

    fixed_groups: dict[int, dict[int, tuple[np.ndarray, np.ndarray]]] = {k: {} for k in CORE_SIZES}
    metas: dict[int, dict[int, dict[str, float]]] = {k: {} for k in CORE_SIZES}
    for t in range(START, len(df)):
        affinity = v43._pair_affinity(t, pair_cs, marg_cs)
        for k in CORE_SIZES:
            a, b, meta = _core_fringe_pair(t, k, red_pred, raw, affinity)
            fixed_groups[k][t] = (a, b)
            metas[k][t] = meta

    results: dict[str, pd.DataFrame] = {}
    for k in CORE_SIZES:
        results[f"core_{k}"] = _evaluate_groups(df, fixed_groups[k], red_occ, blue_models, START)

    adaptive, chosen = _adaptive_groups(fixed_groups, red_occ, START, len(df))
    results["adaptive"] = _evaluate_groups(df, adaptive, red_occ, blue_models, START)
    results["adaptive"]["chosen_core_size"] = chosen[START:]

    # 原V4.5作为同数据、同起点基准。
    baseline = v45.backtest(df, start=START)
    results["v4_5_baseline"] = baseline.assign(t=np.arange(START, len(df)))

    summary = {
        "experiment": "V5 causal core-fringe milestone search",
        "dataset": {
            "draws": int(len(df)),
            "first_seq": int(df.iloc[0]["seq"]),
            "last_seq": int(df.iloc[-1]["seq"]),
            "blind_start_seq": int(df.iloc[START]["seq"]),
        },
        "rules": {
            "tickets_per_draw": 2,
            "causal": True,
            "core_sizes": list(CORE_SIZES),
            "core_logic": "V4三个训练窗口的排名共识优先、模型分歧次优",
            "fringe_logic": "V4集成排序取边缘池；V4.5既有边际权重+V4.3两两关系优化分区",
            "adaptive_logic": "仅用此前开奖的4+事件Beta收缩后验选择核心规模；5+只作0.10辅助；当前期结果在选号后才更新",
            "promotion_guard": "不因单个高奖直接晋级；必须检查相邻核心规模、四时间块和新增55期",
        },
        "strategies": {},
    }

    for name, result in results.items():
        full = _segment_summary(result)
        legacy = _segment_summary(result[result["seq"] <= 3446])
        forward = _segment_summary(result[result["seq"] >= 3447])
        summary["strategies"][name] = {
            "full": full,
            "legacy_seq_le_3446": legacy,
            "new_forward_seq_ge_3447": forward,
            "four_blocks": _four_blocks(result),
        }
        if name == "adaptive":
            counts = result["chosen_core_size"].value_counts().sort_index().to_dict()
            summary["strategies"][name]["chosen_core_distribution"] = {str(int(k)): int(v) for k, v in counts.items()}

    # 明细只保留每个策略每期核心结果，便于定位高奖而不把大量中间矩阵落盘。
    detail_frames = []
    for name, result in results.items():
        cols = [c for c in ["seq", "ticket1", "blue1", "ticket2", "blue2", "hit1", "hit2", "max_red_hit", "prize1", "prize2", "best_prize", "chosen_core_size"] if c in result.columns]
        part = result[cols].copy()
        part.insert(0, "strategy", name)
        detail_frames.append(part)
    details = pd.concat(detail_frames, ignore_index=True)

    out = Path(args.summary)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(summary, ensure_ascii=False, indent=2), encoding="utf-8")
    detail_path = Path(args.details)
    detail_path.parent.mkdir(parents=True, exist_ok=True)
    details.to_csv(detail_path, index=False, encoding="utf-8-sig")

    print(json.dumps(summary, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
