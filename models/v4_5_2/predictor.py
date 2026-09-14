from __future__ import annotations

import argparse
import importlib.util
import json
from pathlib import Path

import numpy as np
import pandas as pd

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[1]


def _load_module(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


v451 = _load_module("ssq_v451_v452_prod", ROOT / "models" / "v4_5_1" / "predictor.py")
blue_expert = _load_module("ssq_v452_blue_expert", HERE / "blue_expert.py")
history_tool = _load_module("ssq_history_v452_prod", ROOT / "tools" / "load_history.py")


def load_current_dataframe(
    base_path: str | Path = history_tool.BASE_SNAPSHOT,
    increment_path: str | Path | None = history_tool.CURRENT_INCREMENT,
) -> pd.DataFrame:
    return v451.load_current_dataframe(base_path, increment_path)


def predict_next(df: pd.DataFrame) -> dict:
    """V4.5.2：沿用V4.5.1红球门控，仅在低红球信号状态切换蓝球专家。"""
    result = v451.predict_next(df)
    signal_active = bool(result["signal"]["active"])

    if signal_active:
        blue_source = "V4.5奖级效用融合"
        low_signal_top4: list[int] | None = None
    else:
        low_score = blue_expert.predict_next(df)
        order = np.argsort(low_score)[::-1]
        result["tickets"][0]["blue"] = int(order[0] + 1)
        result["tickets"][1]["blue"] = int(order[1] + 1)
        low_signal_top4 = [int(x + 1) for x in order[:4]]
        blue_source = "V4.5.2低信号时序蓝球专家"

    result["model"] = "V4.5.2 红球状态驱动蓝球专家门控"
    result["diagnostics"]["blue_source"] = blue_source
    result["diagnostics"]["low_signal_blue_top4"] = low_signal_top4
    result["diagnostics"]["v452_rule"] = (
        "红球信号开启时沿用V4.5奖级效用蓝球融合；"
        "红球信号关闭时切换因果时序蓝球专家（指数热度+条件转移+距离分布+遗漏风险率）"
    )
    return result


def main() -> None:
    parser = argparse.ArgumentParser(description="双色球 V4.5.2 最终研究版下一期执行器")
    parser.add_argument("--base", default=str(history_tool.BASE_SNAPSHOT))
    parser.add_argument("--increment", default=str(history_tool.CURRENT_INCREMENT))
    parser.add_argument("--out", default="predictions/latest.json")
    args = parser.parse_args()

    df = load_current_dataframe(args.base, args.increment)
    result = predict_next(df)
    out = Path(args.out)
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
