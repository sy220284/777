from __future__ import annotations

import csv
import gzip
from pathlib import Path


def load_history(path: str | Path = "data/ssq_history_2003_2026-05-03.csv.gz") -> list[dict[str, object]]:
    """读取仓库中的双色球历史快照。每行包含 seq、6个红球和1个蓝球。"""
    path = Path(path)
    rows: list[dict[str, object]] = []
    with gzip.open(path, "rt", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            rows.append(
                {
                    "seq": int(row["seq"]),
                    "red": [int(row[f"red{i}"]) for i in range(1, 7)],
                    "blue": int(row["blue"]),
                }
            )
    return rows


if __name__ == "__main__":
    history = load_history()
    print(f"draws={len(history)} first={history[0]} last={history[-1]}")
