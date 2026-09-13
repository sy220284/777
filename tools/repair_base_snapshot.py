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
SOURCE_URLS = [
    "https://raw.githubusercontent.com/yangxb919/lottery-data/main/data/ssq.json",
    "https://raw.githubusercontent.com/Justdoitfor/my-lottery-data/main/data/ssq.json",
]
BASE_DRAWS = 3446
CURRENT_DRAWS = 3502
CURRENT_LAST_ISSUE = 2026105
LEGACY_BLOB_SHA1 = "c80e59cf0ffbfbc58746d804e02bae7f34a8d51e"


def _sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def _valid_numbers(reds: list[int], blue: int) -> bool:
    return (
        len(reds) == 6
        and len(set(reds)) == 6
        and reds == sorted(reds)
        and all(1 <= x <= 33 for x in reds)
        and 1 <= blue <= 16
    )


def _parse_legacy_prefix(path: Path) -> list[tuple[int, list[int], int]]:
    """只保留旧损坏文件从开头起连续且结构合法的可信前缀。"""
    raw = path.read_bytes()
    decoder = zlib.decompressobj(16 + zlib.MAX_WBITS)
    recovered = decoder.decompress(raw) + decoder.flush()
    text = recovered.decode("utf-8-sig")
    rows: list[tuple[int, list[int], int]] = []
    expected_seq = 1
    for row in csv.DictReader(io.StringIO(text, newline="")):
        try:
            seq = int(row["seq"])
            reds = [int(row[f"red{i}"]) for i in range(1, 7)]
            blue = int(row["blue"])
        except (TypeError, ValueError, KeyError):
            break
        if seq != expected_seq or not _valid_numbers(reds, blue):
            break
        rows.append((seq, reds, blue))
        expected_seq += 1
    if len(rows) < 100:
        raise RuntimeError(f"旧冻结快照可信前缀过短: {len(rows)}")
    return rows


def _download_source(url: str) -> list[dict]:
    request = urllib.request.Request(url, headers={"User-Agent": "777-base-repair/1.1"})
    with urllib.request.urlopen(request, timeout=30) as response:
        payload = response.read()
    data = json.loads(payload.decode("utf-8"))
    if not isinstance(data, list):
        raise RuntimeError(f"外部历史源格式异常: {url}")

    rows = []
    for item in data:
        raw_issue = str(item["issue"])
        issue = int("20" + raw_issue) if len(raw_issue) == 5 else int(raw_issue)
        if issue > CURRENT_LAST_ISSUE:
            continue
        reds = sorted(int(x) for x in item["red"])
        blue_raw = item["blue"]
        blue = int(blue_raw[0] if isinstance(blue_raw, list) else blue_raw)
        if not _valid_numbers(reds, blue):
            raise RuntimeError(f"外部历史源号码非法: {url} issue={issue}")
        rows.append({
            "issue": issue,
            "date": str(item.get("date", "")),
            "red": reds,
            "blue": blue,
        })
    rows.sort(key=lambda row: row["issue"])
    if len(rows) != CURRENT_DRAWS:
        raise RuntimeError(f"外部历史源期数异常: {url} expected={CURRENT_DRAWS} actual={len(rows)}")
    return rows


def _same_draw(a: dict, b: dict) -> bool:
    return a["issue"] == b["issue"] and a["red"] == b["red"] and a["blue"] == b["blue"]


def _load_cross_checked_remote() -> list[dict]:
    sources = [_download_source(url) for url in SOURCE_URLS]
    primary = sources[0]
    for source_index, other in enumerate(sources[1:], start=2):
        for seq, (a, b) in enumerate(zip(primary, other), start=1):
            if not _same_draw(a, b):
                raise RuntimeError(
                    f"两个全量历史源不一致: source={source_index} seq={seq} "
                    f"a={a['issue']}:{a['red']}+{a['blue']} b={b['issue']}:{b['red']}+{b['blue']}"
                )
    return primary


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
                f"双源历史与旧快照可信前缀不一致: seq={seq} remote={candidate['red']}+{candidate['blue']} legacy={reds}+{blue}"
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
            raise RuntimeError(f"双源历史与当前增量不一致: seq={row['seq']} issue={row['issue']}")

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
    remote = _load_cross_checked_remote()
    increment = _load_increment()
    _validate_remote(remote, legacy, increment)

    csv_bytes = _build_base_csv(remote)
    repaired = gzip.compress(csv_bytes, compresslevel=9, mtime=0)
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
        "sources": SOURCE_URLS,
        "full_history_sources_agree_draws": CURRENT_DRAWS,
        "legacy_valid_prefix_rows_verified": len(legacy),
        "current_increment_rows_verified": len(increment),
        "method": "两个全量历史源3502期逐期一致，且同时匹配旧损坏快照连续合法前缀与仓库56期当前增量后，重建冻结基线",
    }
    MANIFEST.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    print(json.dumps({
        "status": "ok",
        "full_history_cross_checked": CURRENT_DRAWS,
        "legacy_valid_prefix_rows_verified": len(legacy),
        "increment_rows_verified": len(increment),
        "base_draws": BASE_DRAWS,
        "base_sha256": integrity["frozen_base_sha256"],
    }, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
