from __future__ import annotations

import argparse
import csv
import hashlib
import json
from datetime import date
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
MANIFEST_PATH = ROOT / "data" / "current_manifest.json"
FIELDS = ["issue", "seq", "red1", "red2", "red3", "red4", "red5", "red6", "blue"]


def _sha256(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _load_manifest() -> dict:
    return json.loads(MANIFEST_PATH.read_text(encoding="utf-8"))


def _validate_numbers(reds: list[int], blue: int) -> None:
    if len(reds) != 6 or len(set(reds)) != 6 or reds != sorted(reds):
        raise ValueError("红球必须是6个不重复的升序号码")
    if any(n < 1 or n > 33 for n in reds):
        raise ValueError("红球范围必须是1-33")
    if not 1 <= blue <= 16:
        raise ValueError("蓝球范围必须是1-16")


def append_draw(
    *,
    issue: int,
    draw_date: str,
    reds: list[int],
    blue: int,
    verified_against: list[str] | None = None,
) -> dict:
    # 同时校验日期格式，避免清单进入不可解析状态。
    date.fromisoformat(draw_date)
    reds = [int(x) for x in reds]
    blue = int(blue)
    _validate_numbers(reds, blue)

    manifest = _load_manifest()
    current_issue = int(manifest["current_last_issue"])
    current_seq = int(manifest["current_last_seq"])
    if issue <= current_issue:
        raise ValueError(f"新期号必须大于当前期号 {current_issue}")

    increment = ROOT / manifest["storage"]["current_increment"]
    with increment.open("r", encoding="utf-8-sig", newline="") as f:
        rows = list(csv.DictReader(f))
    if any(int(row["issue"]) == issue for row in rows):
        raise ValueError(f"期号 {issue} 已存在")

    seq = current_seq + 1
    rows.append({
        "issue": issue,
        "seq": seq,
        "red1": reds[0],
        "red2": reds[1],
        "red3": reds[2],
        "red4": reds[3],
        "red5": reds[4],
        "red6": reds[5],
        "blue": blue,
    })

    temp = increment.with_suffix(increment.suffix + ".tmp")
    with temp.open("w", encoding="utf-8", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=FIELDS, lineterminator="\n")
        writer.writeheader()
        writer.writerows(rows)
    temp.replace(increment)

    manifest["current_cutoff"] = draw_date
    manifest["current_last_issue"] = issue
    manifest["current_last_seq"] = seq
    manifest["current_draws"] = int(manifest["current_draws"]) + 1
    manifest["integrity"]["current_increment_sha256"] = _sha256(increment)
    manifest["latest_draw_source"] = {
        "issue": issue,
        "date": draw_date,
        "red": reds,
        "blue": blue,
        "verified_against": verified_against or [],
    }
    MANIFEST_PATH.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    return {
        "issue": issue,
        "seq": seq,
        "red": reds,
        "blue": blue,
        "increment_sha256": manifest["integrity"]["current_increment_sha256"],
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="安全追加一期双色球开奖并同步当前数据清单")
    parser.add_argument("--issue", type=int, required=True)
    parser.add_argument("--date", required=True, help="开奖日期，YYYY-MM-DD")
    parser.add_argument("--red", type=int, nargs=6, required=True, metavar=("R1", "R2", "R3", "R4", "R5", "R6"))
    parser.add_argument("--blue", type=int, required=True)
    parser.add_argument("--verified-against", action="append", default=[], help="可重复填写核验来源")
    args = parser.parse_args()

    result = append_draw(
        issue=args.issue,
        draw_date=args.date,
        reds=args.red,
        blue=args.blue,
        verified_against=args.verified_against,
    )
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
