from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


transition = _load("v46_median_transition", ROOT / "tools" / "v4_6_transition_features.py")
v4 = transition.v4
v45 = transition.v45


def median_rank_ensemble(raw: dict[str, np.ndarray]) -> np.ndarray:
    names = ("all", "w1000", "w500")
    n = len(raw[names[0]])
    out = np.full_like(raw[names[0]], np.nan, dtype=float)
    for t in range(n):
        rows = []
        valid = True
        for name in names:
            p = raw[name][t]
            if not np.isfinite(p).all():
                valid = False
                break
            rows.append(v4._rank_score(p))
        if valid:
            out[t] = np.median(np.stack(rows), axis=0)
    return out


def main() -> None:
    df = transition.load_dataframe()
    seqs = df["seq"].to_numpy(int)
    base, raw, occ = v4.walk_forward_red(df)
    median = median_rank_ensemble(raw)

    blue_base, _ = v4.walk_forward_blue(df)
    blue_models = v45.build_blue_models(df)
    configs = {"base": base, "rank_median": median}
    report = {
        "experiment": "V4.6三时间尺度排名中位数共识",
        "rule": {
            "models": ["全历史", "最近1000期", "最近500期"],
            "aggregation": "逐号排名分位取三模型中位数",
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

    bcfg = report["configs"]["base"]
    ccfg = report["configs"]["rank_median"]
    checks = {}
    ok = True
    positive = 0
    for seg in ("validation_1", "validation_2", "forward55"):
        b = bcfg[seg]
        c = ccfg[seg]
        d12 = c["candidate"]["top12_mean"] - b["candidate"]["top12_mean"]
        d5 = c["candidate"]["top12_5plus"] - b["candidate"]["top12_5plus"]
        dret = c["portfolio"]["fixed_return"] - b["portfolio"]["fixed_return"]
        pr5 = c["portfolio"]["red_5plus"] - b["portfolio"]["red_5plus"]
        nw = d12 >= -1e-12 and d5 >= 0 and dret >= 0 and pr5 >= 0
        imp = d12 > 1e-12 or d5 > 0 or dret > 0 or pr5 > 0
        ok &= nw
        positive += int(imp)
        checks[seg] = {
            "top12_mean_delta": float(d12),
            "top12_5plus_delta": int(d5),
            "fixed_return_delta": int(dret),
            "portfolio_red5_delta": int(pr5),
            "nonworse": bool(nw),
            "improved": bool(imp),
        }
    report["promotion"] = {
        "pass": bool(ok and positive >= 2),
        "minimum_positive_segments": 2,
        "positive_segments": int(positive),
        "checks": checks,
    }

    out = ROOT / "backtests" / "v4_6" / "rank_median_ensemble.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
