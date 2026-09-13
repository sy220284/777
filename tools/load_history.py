from __future__ import annotations

import argparse
import csv
import gzip
import hashlib
from pathlib import Path

BASE_SNAPSHOT = Path("data/ssq_history_2003_2026-05-03.csv.gz")
CURRENT_INCREMENT = Path("data/increment_2026050_2026105.csv")
CURRENT_DRAWS = 3502
CURRENT_LAST_SEQ = 3502
CURRENT_LAST_ISSUE = 2026105
FROZEN_BASE_GIT_BLOB_SHA1 = "c80e59cf0ffbfbc58746d804e02bae7f34a8d51e"
CURRENT_INCREMENT_SHA256 = "cfba2f2d51197cd7a3a6949dac76a81b657c9f883d47818b0781a90959cf5718"


def _sha256(path: str | Path) -> str:
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def _git_blob_sha1(path: str | Path) -> str:
    data = Path(path).read_bytes()
    header = f"blob {len(data)}\0".encode("ascii")
    return hashlib.sha1(header + data).hexdigest()


def verify_current_sources(
    base_path: str | Path = BASE_SNAPSHOT,
    increment_path: str | Path = CURRENT_INCREMENT,
) -> None:
    base_blob = _git_blob_sha1(base_path)
    if base_blob != FROZEN_BASE_GIT_BLOB_SHA1:
        raise ValueError(
            f"冻结基础快照已变化: expected={FROZEN_BASE_GIT_BLOB_SHA1} actual={base_blob}"
        )
    increment_sha = _sha256(increment_path)
    if increment_sha != CURRENT_INCREMENT_SHA256:
        raise ValueError(
            f"当前增量SHA256不一致: expected={CURRENT_INCREMENT_SHA256} actual={increment_sha}"
        )


def _read_base(path: str | Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with gzip.open(Path(path), "rt", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            rows.append({
                "seq": int(row["seq"]),
                "red": [int(row[f"red{i}"]) for i in range(1, 7)],
                "blue": int(row["blue"]),
            })
    return rows


def _read_increment(path: str | Path) -> list[dict[str, object]]:
    rows: list[dict[str, object]] = []
    with Path(path).open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            rows.append({
                "issue": int(row["issue"]),
                "seq": int(row["seq"]),
                "red": [int(row[f"red{i}"]) for i in range(1, 7)],
                "blue": int(row["blue"]),
            })
    if rows:
        issues = [int(r["issue"]) for r in rows]
        if issues != sorted(issues) or len(set(issues)) != len(issues):
            raise ValueError("增量期号存在乱序或重复")
    return rows


def validate_history(rows: list[dict[str, object]]) -> None:
    if not rows:
        raise ValueError("历史数据为空")
    seqs = [int(r["seq"]) for r in rows]
    expected = list(range(seqs[0], seqs[-1] + 1))
    if seqs != expected:
        raise ValueError("seq 不连续或存在乱序/重复")
    for row in rows:
        reds = [int(x) for x in row["red"]]
        blue = int(row["blue"])
        if len(reds) != 6 or len(set(reds)) != 6 or reds != sorted(reds):
            raise ValueError(f"红球格式非法: seq={row['seq']}")
        if any(x < 1 or x > 33 for x in reds) or not 1 <= blue <= 16:
            raise ValueError(f"号码越界: seq={row['seq']}")


def load_history(
    base_path: str | Path = BASE_SNAPSHOT,
    increment_path: str | Path | None = CURRENT_INCREMENT,
) -> list[dict[str, object]]:
    """读取双色球历史。

    默认由冻结基础快照（截至2026-05-03）与当前增量重建完整历史。
    将 increment_path 设为 None 可只读取冻结基础快照，供旧版本复现。
    """
    rows = _read_base(base_path)
    if increment_path is not None and Path(increment_path).exists():
        rows.extend(_read_increment(increment_path))
    validate_history(rows)
    return rows


def write_current_csv(
    out_path: str | Path,
    base_path: str | Path = BASE_SNAPSHOT,
    increment_path: str | Path = CURRENT_INCREMENT,
) -> str:
    rows = load_history(base_path, increment_path)
    out = Path(out_path)
    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("w", encoding="utf-8", newline="") as f:
        w = csv.writer(f, lineterminator="\n")
        w.writerow(["seq", "red1", "red2", "red3", "red4", "red5", "red6", "blue"])
        for row in rows:
            w.writerow([row["seq"], *row["red"], row["blue"]])
    return _sha256(out)


def main() -> None:
    parser = argparse.ArgumentParser(description="读取/重建双色球当前历史数据")
    parser.add_argument("--base", default=str(BASE_SNAPSHOT))
    parser.add_argument("--increment", default=str(CURRENT_INCREMENT))
    parser.add_argument("--out", default=None, help="可选：重建当前合并CSV到指定路径")
    parser.add_argument("--skip-source-hash", action="store_true", help="自定义数据源时跳过仓库冻结哈希检查")
    args = parser.parse_args()

    if not args.skip_source_hash:
        verify_current_sources(args.base, args.increment)
    rows = load_history(args.base, args.increment)
    print(f"draws={len(rows)} first={rows[0]} last={rows[-1]}")
    if len(rows) != CURRENT_DRAWS or int(rows[-1]["seq"]) != CURRENT_LAST_SEQ:
        raise SystemExit("当前历史期数/末序号与清单不一致")
    increment_rows = _read_increment(args.increment)
    if not increment_rows or int(increment_rows[-1]["issue"]) != CURRENT_LAST_ISSUE:
        raise SystemExit("当前增量末期号与清单不一致")
    if args.out:
        digest = write_current_csv(args.out, args.base, args.increment)
        print(f"merged_sha256={digest}")


if __name__ == "__main__":
    main()
