from __future__ import annotations

import csv
import importlib.util
import io
import json
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCES = [
    "https://raw.githubusercontent.com/yangxb919/lottery-data/main/data/ssq.json",
    "https://raw.githubusercontent.com/Justdoitfor/my-lottery-data/main/data/ssq.json",
]
OUTBALL_SOURCE = "https://raw.githubusercontent.com/fentouxungui/Double-Color-Ball-Data/main/lottery_data.csv"
CUTOFF = 2026105


def _load(name: str, path: Path):
    spec = importlib.util.spec_from_file_location(name, path)
    if spec is None or spec.loader is None:
        raise RuntimeError(f"无法加载模块: {path}")
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


history = _load("v46audit_history", ROOT / "tools" / "load_history.py")


def _download(url: str) -> bytes:
    req = urllib.request.Request(url, headers={"User-Agent": "777-history-audit/1.0"})
    with urllib.request.urlopen(req, timeout=30) as response:
        return response.read()


def _history_source(url: str) -> list[dict]:
    data = json.loads(_download(url).decode("utf-8"))
    rows = []
    for item in data:
        raw_issue = str(item["issue"])
        issue = int("20" + raw_issue) if len(raw_issue) == 5 else int(raw_issue)
        if issue > CUTOFF:
            continue
        blue_raw = item["blue"]
        rows.append({
            "issue": issue,
            "red": sorted(int(x) for x in item["red"]),
            "blue": int(blue_raw[0] if isinstance(blue_raw, list) else blue_raw),
        })
    rows.sort(key=lambda r: r["issue"])
    return rows


def _outball_source() -> list[dict]:
    text = _download(OUTBALL_SOURCE).decode("utf-8-sig")
    rows = []
    for row in csv.DictReader(io.StringIO(text)):
        issue = int(row["期号"])
        if issue > 2026091:
            continue
        rows.append({
            "issue": issue,
            "red": sorted(int(x) for x in row["排序红球"].split()),
            "blue": int(row["排序蓝球"]),
            "order": [int(x) for x in row["出球顺序"].split()],
        })
    rows.sort(key=lambda r: r["issue"])
    return rows


def _diff(a: list[dict], b: list[dict], name_a: str, name_b: str) -> list[dict]:
    out = []
    for seq, (ra, rb) in enumerate(zip(a, b), start=1):
        if (ra.get("issue"), ra["red"], ra["blue"]) != (rb.get("issue"), rb["red"], rb["blue"]):
            out.append({"seq": seq, name_a: ra, name_b: rb})
    if len(a) != len(b):
        out.append({"length_mismatch": {name_a: len(a), name_b: len(b)}})
    return out


def main() -> None:
    repo_rows = history.load_history(history.BASE_SNAPSHOT, history.CURRENT_INCREMENT)
    repo = [{"issue": None, "red": [int(x) for x in r["red"]], "blue": int(r["blue"])} for r in repo_rows]
    s1 = _history_source(SOURCES[0])
    s2 = _history_source(SOURCES[1])
    outball = _outball_source()

    # repo无旧期号字段，所以按同一seq只比较号码；增量期号已由manifest工具另行检查。
    repo_vs_s1 = []
    for seq, (rr, sr) in enumerate(zip(repo, s1), start=1):
        if rr["red"] != sr["red"] or rr["blue"] != sr["blue"]:
            repo_vs_s1.append({"seq": seq, "issue": sr["issue"], "repo": rr, "source": sr})

    s1_vs_s2 = _diff(s1, s2, "source1", "source2")
    outball_vs_s1 = []
    for seq, (orow, srow) in enumerate(zip(outball, s1), start=1):
        if (orow["issue"], orow["red"], orow["blue"]) != (srow["issue"], srow["red"], srow["blue"]):
            outball_vs_s1.append({"seq": seq, "outball": orow, "source": srow})

    report = {
        "repo_draws": len(repo),
        "source_draws": [len(s1), len(s2)],
        "outball_draws": len(outball),
        "source1_source2_mismatches": len(s1_vs_s2),
        "repo_source1_mismatches": len(repo_vs_s1),
        "outball_source1_mismatches": len(outball_vs_s1),
        "repo_source1_examples": repo_vs_s1[:50],
        "source1_source2_examples": s1_vs_s2[:50],
        "outball_source1_examples": outball_vs_s1[:50],
        "repo_source1_all_mismatch_seqs": [x["seq"] for x in repo_vs_s1],
        "outball_source1_all_mismatch_seqs": [x["seq"] for x in outball_vs_s1],
    }
    out = ROOT / "backtests" / "v4_6" / "history_audit.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
