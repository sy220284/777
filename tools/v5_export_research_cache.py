from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

ROOT = Path(__file__).resolve().parents[1]
V45_PATH = ROOT / "models" / "v4_5" / "predictor.py"
LOAD_PATH = ROOT / "tools" / "load_history.py"
START = 750


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v45 = _load_module("ssq_v45_cache", V45_PATH)
loader = _load_module("ssq_history_loader_cache", LOAD_PATH)
v4 = v45.v4


def _df() -> pd.DataFrame:
    rows = loader.load_history()
    return pd.DataFrame([
        {
            "seq": int(r["seq"]),
            **{f"red{i}": int(r["red"][i - 1]) for i in range(1, 7)},
            "blue": int(r["blue"]),
        }
        for r in rows
    ])


def main() -> None:
    p = argparse.ArgumentParser(description="导出V5研究用冻结因果预测缓存")
    p.add_argument("--out", default="backtests/v5_research/v4_causal_cache.npz")
    p.add_argument("--meta", default="backtests/v5_research/v4_causal_cache_meta.json")
    args = p.parse_args()

    df = _df()
    red_pred, raw, red_occ = v4.walk_forward_red(df)
    blue_models = v45.build_blue_models(df)
    actual_red = df[[f"red{i}" for i in range(1, 7)]].to_numpy(np.int16)
    actual_blue = df["blue"].to_numpy(np.int16)
    seq = df["seq"].to_numpy(np.int32)

    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    payload = {
        "seq": seq,
        "actual_red": actual_red,
        "actual_blue": actual_blue,
        "red_occ": red_occ.astype(np.uint8),
        "red_pred": red_pred.astype(np.float32),
    }
    for name, arr in raw.items():
        payload[f"red_raw_{name}"] = arr.astype(np.float32)
    for name, arr in blue_models.items():
        payload[f"blue_{name}"] = arr.astype(np.float32)
    np.savez_compressed(out, **payload)

    meta = {
        "dataset_draws": int(len(df)),
        "first_seq": int(seq[0]),
        "last_seq": int(seq[-1]),
        "blind_start_seq": int(seq[START]),
        "cache_rule": "仅缓存现有V4/V4.5逐期因果输出，不包含未来信息；供后续固定底座组合实验复用",
        "red_raw_models": sorted(raw),
        "blue_models": sorted(blue_models),
        "arrays": {k: list(v.shape) for k, v in payload.items() if hasattr(v, "shape")},
    }
    meta_path = Path(args.meta)
    meta_path.parent.mkdir(parents=True, exist_ok=True)
    meta_path.write_text(json.dumps(meta, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(meta, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
