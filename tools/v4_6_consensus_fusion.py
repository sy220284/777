from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
CORRECTION_WEIGHT = 0.25


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


corrected = _load("v46_consensus_outball_corrected", ROOT / "tools" / "v4_6_outball_corrected.py")
order_exp = corrected.exp
transition = _load("v46_consensus_transition", ROOT / "tools" / "v4_6_transition_features.py")
v4 = transition.v4
v45 = transition.v45


def _rank(row: np.ndarray) -> np.ndarray:
    order = np.argsort(row)
    ranks = np.empty(len(row), dtype=float)
    ranks[order] = np.arange(len(row), dtype=float)
    return ranks / max(1, len(row) - 1)


def consensus(base: np.ndarray, outball: np.ndarray, trans: np.ndarray) -> np.ndarray:
    """只有两个独立新信号相对V4同向时才做保守修正；冲突时完全保留V4。"""
    result = np.full_like(base, np.nan, dtype=float)
    for t in range(len(base)):
        if not (
            np.isfinite(base[t]).all()
            and np.isfinite(outball[t]).all()
            and np.isfinite(trans[t]).all()
        ):
            continue
        b = _rank(base[t])
        o = _rank(outball[t])
        tr = _rank(trans[t])
        d1 = o - b
        d2 = tr - b
        same_direction = d1 * d2 > 0
        # 取两类信号中较弱的一侧作为共同证据，避免单一模型主导。
        shared = np.sign(d1) * np.minimum(np.abs(d1), np.abs(d2))
        result[t] = b + CORRECTION_WEIGHT * np.where(same_direction, shared, 0.0)
    return result


def main() -> None:
    df = transition.load_dataframe()
    seqs = df["seq"].to_numpy(int)

    base, _, occ = v4.walk_forward_red(df)

    order = order_exp.load_order_matrix(df)
    outball, _, occ_order = order_exp.walk_forward_augmented(df, order)
    trans, occ_trans = transition.walk_forward_augmented(df)
    if not np.array_equal(occ, occ_order) or not np.array_equal(occ, occ_trans):
        raise RuntimeError("红球发生矩阵不一致")

    fused = consensus(base, outball, trans)
    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)

    configs = {"base": base, "consensus": fused}
    report = {
        "experiment": "V4.6出球顺序×跨期转移共识融合",
        "rule": {
            "correction_weight": CORRECTION_WEIGHT,
            "agreement": "两类新信号相对V4排名变化同号同向",
            "correction": "仅使用两类变化绝对值的较小者，冲突时保持V4",
            "parameter_search": False,
        },
        "configs": {},
        "promotion": {},
    }

    for name, pred in configs.items():
        port = transition.portfolio(df, pred, occ, blue_base, blue_models)
        report["configs"][name] = {
            seg: {
                "candidate": transition.candidate(pred, occ, seqs, *rng),
                "portfolio": transition.psum(port, *rng),
            }
            for seg, rng in transition.SEGMENTS.items()
        }

    checks = {}
    ok = True
    positive = 0
    base_cfg = report["configs"]["base"]
    cand_cfg = report["configs"]["consensus"]
    for seg in ("validation_1", "validation_2", "forward55"):
        b = base_cfg[seg]
        c = cand_cfg[seg]
        d12 = c["candidate"]["top12_mean"] - b["candidate"]["top12_mean"]
        d5 = c["candidate"]["top12_5plus"] - b["candidate"]["top12_5plus"]
        dret = c["portfolio"]["fixed_return"] - b["portfolio"]["fixed_return"]
        pr5 = c["portfolio"]["red_5plus"] - b["portfolio"]["red_5plus"]
        nonworse = d12 >= -1e-12 and d5 >= 0 and dret >= 0 and pr5 >= 0
        improved = d12 > 1e-12 or d5 > 0 or dret > 0 or pr5 > 0
        ok &= nonworse
        positive += int(improved)
        checks[seg] = {
            "top12_mean_delta": float(d12),
            "top12_5plus_delta": int(d5),
            "fixed_return_delta": int(dret),
            "portfolio_red5_delta": int(pr5),
            "nonworse": bool(nonworse),
            "improved": bool(improved),
        }

    report["promotion"] = {
        "pass": bool(ok and positive >= 2),
        "minimum_positive_segments": 2,
        "positive_segments": int(positive),
        "checks": checks,
    }

    out = ROOT / "backtests" / "v4_6" / "consensus_fusion.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
