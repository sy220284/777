from __future__ import annotations

import csv
import gzip
import hashlib
import io
import json
import urllib.request
import zlib
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / "data" / "ssq_history_2003_2026-05-03.csv.gz"
INCREMENT = ROOT / "data" / "increment_current.csv"
MANIFEST = ROOT / "data" / "current_manifest.json"
SOURCE_URL = "https://raw.githubusercontent.com/yangxb919/lottery-data/main/data/ssq.json"
BASE_DRAWS = 3446
CURRENT_DRAWS = 3502
CURRENT_LAST_ISSUE = 2026105
LEGACY_BLOB_SHA1 = "c80e59cf0ffbfbc58746d804e02bae7f34a8d51e"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _parse_legacy_prefix(path: Path) -> list[tuple[int, list[int], int]]:
    raw = path.read_bytes()
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    recovered = decoder.decompress(raw) + decoder.flush()
    text = recovered.decode("utf-8-sig")
    rows: list[tuple[int, list[int], int]] = []
    for row in csv.DictReader(io.StringIO(text, newline="")):
        try:
            seq = int(row["seq"])
            reds = [int(row[f"red{i}"]) for i in range(1, 7)]
            blue = int(row["blue"])
        except (TypeError, ValueError, KeyError):
            break
        rows.append((seq, reds, blue))
    if not rows:
        raise RuntimeError("旧冻结快照没有恢复出任何完整记录")
    return rows


def _load_remote() -> list[dict]:
    request = urllib.request.Request(
        SOURCE_URL,
        headers={"User-Agent": "777-base-repair/1.0"},
    )
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read()
    data = json.loads(payload.decode("utf-8"))
    if not isinstance(data, list):
        raise RuntimeError("外部历史源格式异常")

    rows = []
    for item in data:
        raw_issue = str(item["issue"])
        issue = int("20" + raw_issue) if len(raw_issue) == 5 else int(raw_issue)
        if issue > CURRENT_LAST_ISSUE:
            continue
        reds = sorted(int(x) for x in item["red"])
        blue_raw = item["blue"]
        blue = int(blue_raw[0] if isinstance(blue_raw, list) else blue_raw)
        rows.append({
            "issue": issue,
            "date": str(item.get("date", "")),
            "red": reds,
            "blue": blue,
        })
    rows.sort(key=lambda row: row["issue"])
    if len(rows) != CURRENT_DRAWS:
        raise RuntimeError(f"外部历史源期数异常: expected={CURRENT_DRAWS} actual={len(rows)}")
    return rows


def _load_increment() -> list[dict]:
    rows = []
    with INCREMENT.open("r", encoding="utf-8-sig", newline="") as f:
        for row in csv.DictReader(f):
            rows.append({
                "issue": int(row["issue"]),
                "seq": int(row["seq"]),
                "red": [int(row[f"red{i}"]) for i in range(1, 7)],
                "blue": int(row["blue"]),
            })
    return rows


def _validate_remote(remote: list[dict], legacy: list[tuple[int, list[int], int]], increment: list[dict]) -> None:
    for seq, reds, blue in legacy:
        candidate = remote[seq - 1]
        if candidate["red"] != reds or candidate["blue"] != blue:
            raise RuntimeError(
                f"外部历史源与旧快照恢复前缀不一致: seq={seq} remote={candidate['red']}+{candidate['blue']} legacy={reds}+{blue}"
            )

    if len(increment) != CURRENT_DRAWS - BASE_DRAWS:
        raise RuntimeError(f"当前增量期数异常: {len(increment)}")
    for row in increment:
        candidate = remote[row["seq"] - 1]
        if (
            candidate["issue"] != row["issue"]
            or candidate["red"] != row["red"]
            or candidate["blue"] != row["blue"]
        ):
            raise RuntimeError(f"外部历史源与当前增量不一致: seq={row['seq']} issue={row['issue']}")

    if increment[0]["seq"] != BASE_DRAWS + 1 or increment[-1]["seq"] != CURRENT_DRAWS:
        raise RuntimeError("当前增量 seq 边界异常")


def _build_base_csv(remote: list[dict]) -> bytes:
    out = io.StringIO(newline="")
    writer = csv.writer(out, lineterminator="\n")
    writer.writerow(["seq", "red1", "red2", "red3", "red4", "red5", "red6", "blue"])
    for seq, row in enumerate(remote[:BASE_DRAWS], start=1):
        writer.writerow([seq, *row["red"], row["blue"]])
    return out.getvalue().encode("utf-8")


def main() -> None:
    legacy = _parse_legacy_prefix(BASE)
    remote = _load_remote()
    increment = _load_increment()
    _validate_remote(remote, legacy, increment)

    csv_bytes = _build_base_csv(remote)
    repaired = gzip.compress(csv_bytes, compresslevel=9, mtime=0)
    # 生成后立即严格解压，避免再次提交不完整 gzip。
    if gzip.decompress(repaired) != csv_bytes:
        raise RuntimeError("重建 gzip 自检失败")
    BASE.write_bytes(repaired)

    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    integrity = manifest["integrity"]
    integrity.pop("frozen_base_git_blob_sha1", None)
    integrity["frozen_base_sha256"] = _sha256(repaired)
    integrity["legacy_corrupt_base_git_blob_sha1"] = LEGACY_BLOB_SHA1
    manifest["frozen_base_draws"] = BASE_DRAWS
    manifest["base_repair"] = {
        "status": "repaired",
        "source": SOURCE_URL,
        "legacy_prefix_rows_verified": len(legacy),
        "current_increment_rows_verified": len(increment),
        "method": "外部全量历史必须同时与旧损坏快照可恢复前缀、仓库当前增量逐期一致后才允许重建",
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({
        "status": "ok",
        "legacy_prefix_rows_verified": len(legacy),
        "increment_rows_verified": len(increment),
        "base_draws": BASE_DRAWS,
        "base_sha256": integrity["frozen_base_sha256"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
