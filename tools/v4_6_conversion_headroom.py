from __future__ import annotations

import importlib.util
import json
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
START = 750
SEGMENTS = {
    "training": (751, 2446),
    "validation_1": (2447, 2946),
    "validation_2": (2947, 3446),
    "forward55": (3447, 3501),
    "live2": (3502, 3503),
}


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


rankpat = _load("v46_headroom_rankpat", ROOT / "tools" / "v4_6_rank_pattern_portfolio.py")
v4 = rankpat.v4
v45 = rankpat.v45


def main() -> None:
    df = rankpat.load_dataframe()
    pred, _, occ = v4.walk_forward_red(df)
    signal = rankpat._signal_mask(pred, occ)
    base_low, _, _ = v45._baseline_red_groups(pred, occ, START)
    seqs = df["seq"].to_numpy(int)

    rows = []
    for t in range(START, len(df)):
        order = np.argsort(pred[t])[::-1]
        top12_hits = int(occ[t, order[:12]].sum())
        a, b = base_low[t]
        actual_max = max(int(occ[t, a].sum()), int(occ[t, b].sum()))
        rows.append((int(seqs[t]), bool(signal[t]), top12_hits, actual_max))

    report = {"experiment": "V4.5.1低信号前12到两票奖级转换上限诊断", "segments": {}}
    for name, (lo, hi) in SEGMENTS.items():
        part = [r for r in rows if lo <= r[0] <= hi and not r[1]]
        if not part:
            report["segments"][name] = {"low_signal_draws": 0}
            continue
        top4 = sum(r[2] >= 4 for r in part)
        top5 = sum(r[2] >= 5 for r in part)
        act4 = sum(r[3] >= 4 for r in part)
        act5 = sum(r[3] >= 5 for r in part)
        report["segments"][name] = {
            "low_signal_draws": len(part),
            "top12_4plus_oracle_draws": int(top4),
            "top12_5plus_oracle_draws": int(top5),
            "v43_converted_4plus": int(act4),
            "v43_converted_5plus": int(act5),
            "conversion_efficiency_4plus": float(act4 / top4) if top4 else None,
            "conversion_efficiency_5plus": float(act5 / top5) if top5 else None,
            "missed_convertible_4plus": int(top4 - act4),
            "missed_convertible_5plus": int(top5 - act5),
        }

    out = ROOT / "backtests" / "v4_6" / "conversion_headroom.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
