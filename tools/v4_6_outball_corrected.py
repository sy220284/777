from __future__ import annotations

import importlib.util
from pathlib import Path

import numpy as np

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / "tools" / "v4_6_outball_features.py"

spec = importlib.util.spec_from_file_location("v46_outball_base", SOURCE)
if spec is None or spec.loader is None:
    raise RuntimeError(f"无法加载出球顺序实验: {SOURCE}")
exp = importlib.util.module_from_spec(spec)
spec.loader.exec_module(exp)

# 第三方历史出球顺序表在以下6期与两个独立全量开奖号源不一致。
# 纠错值来自2025全年出球顺序表，并对关键期用乐彩网/500彩票网等开奖页再次核验。
# 这里只修实验外部顺序元数据，不修改777主历史开奖号码。
VERIFIED_CORRECTIONS = {
    2025055: {"order": [22, 33, 5, 27, 29, 2], "blue": 12},
    2025061: {"order": [10, 32, 9, 7, 11, 6], "blue": 9},
    2025063: {"order": [2, 21, 28, 30, 19, 22], "blue": 1},
    2025067: {"order": [17, 10, 5, 22, 20, 1], "blue": 5},
    2025068: {"order": [7, 8, 19, 31, 20, 5], "blue": 7},
    2025069: {"order": [4, 27, 30, 23, 19, 2], "blue": 5},
}

_original_fetch = exp._fetch_legacy_orders
_original_load_order_matrix = exp.load_order_matrix


def _fetch_corrected_orders() -> list[dict]:
    rows = _original_fetch()
    found = set()
    for row in rows:
        issue = int(row["issue"])
        correction = VERIFIED_CORRECTIONS.get(issue)
        if correction is None:
            continue
        order = [int(x) for x in correction["order"]]
        row["order"] = order
        row["red"] = sorted(order)
        row["blue"] = int(correction["blue"])
        found.add(issue)
    missing = sorted(set(VERIFIED_CORRECTIONS) - found)
    if missing:
        raise RuntimeError(f"纠错期未在外部顺序源中找到: {missing}")
    return rows


def _load_order_matrix_blind_target(df):
    """允许最后一行是完全留出的目标期；其顺序绝不进入目标特征。"""
    order = _original_load_order_matrix(df)
    if len(df) == len(order):
        return order
    if len(df) != len(order) + 1:
        raise RuntimeError(f"顺序历史与目标边界异常: df={len(df)} order={len(order)}")
    # build_order_features 在构造 t 时只读取 [:t] 和 t-1；当前行在特征完成后才写入状态。
    # 因此追加任意合法占位顺序只用于保持矩阵维度，不会泄漏目标期真实顺序。
    placeholder = np.arange(1, 7, dtype=int).reshape(1, 6)
    return np.vstack([order, placeholder])


exp._fetch_legacy_orders = _fetch_corrected_orders
exp.load_order_matrix = _load_order_matrix_blind_target


if __name__ == "__main__":
    exp.main()
