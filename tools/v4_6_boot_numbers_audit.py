from __future__ import annotations

import json
import re
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]

# 中彩网在开奖前发布当期“开机号”，历史文章同时保留往期条目。
# 选取跨时间锚点，合并后检查覆盖连续性，不依赖单篇文章。
ZHCW_URLS = [
    "https://www.zhcw.com/c/2025-04-14/883354.shtml",
    "https://www.zhcw.com/c/2025-11-26/883354.shtml",
    "https://www.zhcw.com/h5/c/2025-12-26/883354.shtml",
    "https://www.zhcw.com/h5/c/2025-12-31/883354.shtml",
    "https://www.zhcw.com/c/2026-01-05/883354.shtml",
    "https://www.zhcw.com/c/2026-06-19/883354.shtml",
    "https://www.zhcw.com/c/2026-09-11/883354.shtml",
]

SECONDARY_TEMPLATE = "https://m.33633.cn/kaijiang/ssq/{issue}.html"
UA = "Mozilla/5.0 (777-v46-boot-audit/1.0)"
PATTERN = re.compile(
    r"(20\d{5})双色球开机号码[：:]\s*红球[：:]\s*"
    r"(\d{2})\s*[,，]\s*(\d{2})\s*[,，]\s*(\d{2})\s*[,，]\s*"
    r"(\d{2})\s*[,，]\s*(\d{2})\s*[,，]\s*(\d{2})\s*"
    r"蓝球[：:]\s*(\d{2})"
)
SECONDARY_PATTERN = re.compile(
    r"开机号[：:]?\s*([0-9]{2})\s*[,，]\s*([0-9]{2})\s*[,，]\s*([0-9]{2})\s*[,，]\s*"
    r"([0-9]{2})\s*[,，]\s*([0-9]{2})\s*[,，]\s*([0-9]{2})\s*\+\s*([0-9]{2})"
)


def fetch(url: str) -> str:
    req = urllib.request.Request(url, headers={"User-Agent": UA})
    with urllib.request.urlopen(req, timeout=30) as response:
        raw = response.read()
    for enc in ("utf-8", "utf-8-sig", "gb18030"):
        try:
            return raw.decode(enc)
        except UnicodeDecodeError:
            pass
    raise RuntimeError(f"无法解码: {url}")


def parse_primary() -> tuple[dict[int, dict], dict[int, list[str]]]:
    merged: dict[int, dict] = {}
    provenance: dict[int, list[str]] = {}
    conflicts = []
    for url in ZHCW_URLS:
        text = fetch(url)
        matches = list(PATTERN.finditer(text))
        if not matches:
            raise RuntimeError(f"未从中彩网页面解析出开机号: {url}")
        for m in matches:
            issue = int(m.group(1))
            reds = [int(m.group(i)) for i in range(2, 8)]
            blue = int(m.group(8))
            item = {"reds": reds, "blue": blue}
            old = merged.get(issue)
            if old is not None and old != item:
                conflicts.append({"issue": issue, "old": old, "new": item, "url": url})
            merged[issue] = item
            provenance.setdefault(issue, []).append(url)
    if conflicts:
        raise RuntimeError(f"中彩网历史汇总内部冲突: {conflicts[:10]}")
    return merged, provenance


def expected_issues() -> list[int]:
    out = []
    for n in range(1, 152):
        out.append(2025000 + n)
    for n in range(1, 107):
        out.append(2026000 + n)
    return out


def choose_secondary_sample(issues: list[int]) -> list[int]:
    # 固定抽样，避免看结果后挑期：首尾 + 每13期取1期。
    picked = {issues[0], issues[-1]}
    picked.update(issues[::13])
    return sorted(picked)


def parse_secondary(issue: int) -> dict | None:
    url = SECONDARY_TEMPLATE.format(issue=issue)
    try:
        text = fetch(url)
    except Exception:
        return None
    m = SECONDARY_PATTERN.search(text)
    if not m:
        return None
    return {"reds": [int(m.group(i)) for i in range(1, 7)], "blue": int(m.group(7)), "url": url}


def main() -> None:
    primary, provenance = parse_primary()
    expected = expected_issues()
    covered = [i for i in expected if i in primary]
    missing = [i for i in expected if i not in primary]

    # 只对主源已经覆盖的期次做固定抽样交叉核验。
    sample = choose_secondary_sample(covered) if covered else []
    checks = []
    agree = 0
    available = 0
    for issue in sample:
        sec = parse_secondary(issue)
        if sec is None:
            checks.append({"issue": issue, "secondary_available": False})
            continue
        available += 1
        same = primary[issue]["reds"] == sec["reds"] and primary[issue]["blue"] == sec["blue"]
        agree += int(same)
        checks.append({
            "issue": issue,
            "secondary_available": True,
            "same": bool(same),
            "primary": primary[issue],
            "secondary": sec,
        })

    # 寻找最长连续覆盖段（同一年期号递增）。
    longest: list[int] = []
    current: list[int] = []
    prev = None
    for issue in covered:
        same_year_next = prev is not None and issue // 1000 == prev // 1000 and issue == prev + 1
        year_roll = prev == 2025151 and issue == 2026001
        if prev is None or same_year_next or year_roll:
            current.append(issue)
        else:
            if len(current) > len(longest):
                longest = current
            current = [issue]
        prev = issue
    if len(current) > len(longest):
        longest = current

    report = {
        "experiment": "V4.6开奖前开机号数据源审计",
        "primary": "中彩网多篇开奖前文章历史汇总",
        "secondary": "牛票票逐期开奖页",
        "expected_range": [2025001, 2026106],
        "expected_draws": len(expected),
        "covered_draws": len(covered),
        "coverage_rate": len(covered) / len(expected),
        "missing_issues": missing,
        "longest_contiguous": {
            "start": longest[0] if longest else None,
            "end": longest[-1] if longest else None,
            "draws": len(longest),
        },
        "secondary_sample_requested": len(sample),
        "secondary_sample_available": available,
        "secondary_agree": agree,
        "secondary_agree_rate": agree / available if available else 0.0,
        "checks": checks,
        "source_redundancy": {
            str(issue): len(set(provenance.get(issue, []))) for issue in covered
        },
        "usable_for_experiment": bool(
            len(longest) >= 180
            and available >= 10
            and agree == available
        ),
    }

    out = ROOT / "backtests" / "v4_6" / "boot_numbers_audit.json"
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
