#!/usr/bin/env python3
"""Generate concise Chinese release notes from merged PR descriptions.

Release notes are limited to first-parent commits between published release and
current release commit. Every PR is resolved through GitHub's commit-to-PR API,
so rebases, squash merges and normal merge commits share one path.
"""

import argparse
import json
import re
import subprocess
from pathlib import Path

RELEASE_PATH = re.compile(
    r"^(?:app/|core/|harness-|ui-|reference-validation/|tools/runtime/|"
    r"gradle/|build-logic/|upstream/|settings\.gradle|build\.gradle|"
    r"gradle\.properties|AndroidManifest)"
)
HEADING = re.compile(r"^#{2,4}\s*(.+?)\s*$")
BULLET = re.compile(r"^\s*(?:[-*+]\s+|\d+[.)]\s+)(.+?)\s*$")
PREFERRED = ("发布说明", "更新说明", "用户可见更新", "本次更新")
DETAIL_SECTIONS = (
    "改动", "实现内容", "主要改动", "已实现", "完成内容", "调整内容",
    "更新内容", "功能变化", "界面重构", "优化内容", "变化", "主要变化",
    "本次调整", "实际改动", "修复内容", "体验优化",
)
SKIP_SECTIONS = ("验证", "测试", "背景", "目标", "风险", "说明", "注意", "关联", "检查", "问题", "计划", "后续")


def run(*args: str) -> str:
    return subprocess.check_output(args, text=True, stderr=subprocess.PIPE).strip()


def clean(s: str) -> str:
    s = re.sub(r"!\[([^]]*)\]\([^)]+\)", r"\1", s)
    s = re.sub(r"\[([^]]+)\]\([^)]+\)", r"\1", s)
    s = re.sub(r"[\x60*_~]", "", s)
    s = re.sub(r"<[^>]+>", "", s)
    s = re.sub(r"\s+", " ", s).strip()
    return s


def shorten(s: str, limit: int = 85) -> str:
    s = clean(s)
    if len(s) <= limit:
        return s
    for delim in ("。", "；", ";", "，", ","):
        pos = s.find(delim)
        if 12 <= pos < limit:
            return s[:pos + 1]
    return s[: limit - 1].rstrip("，、；,;：: ") + "…"


def sections(body: str) -> dict[str, list[str]]:
    blocks: dict[str, list[str]] = {"": []}
    active = ""
    for line in body.splitlines():
        match = HEADING.match(line)
        if match:
            active = clean(match.group(1)).strip("：: ")
            blocks.setdefault(active, [])
        else:
            blocks[active].append(line)
    return blocks


def summary(body: str, title: str) -> str:
    blocks = sections(body)
    headers = list(blocks)
    preferred = [h for h in headers if any(h.startswith(x) for x in PREFERRED)]
    useful = [h for h in headers if any(h.startswith(x) for x in DETAIL_SECTIONS)]
    other = [h for h in headers if h and not any(h.startswith(x) for x in SKIP_SECTIONS)]
    for heading in preferred + useful + other + [""]:
        lines = blocks.get(heading, [])
        for raw in lines:
            m = BULLET.match(raw)
            if not m:
                continue
            candidate = clean(m.group(1))
            if len(candidate) < 8 or candidate.startswith(("验证", "测试", "CI ", "通过", "运行 ")):
                continue
            return shorten(candidate)
        if heading in preferred:
            for raw in lines:
                candidate = clean(raw)
                if len(candidate) >= 8 and not candidate.startswith(("<!--", "#", "|")):
                    return shorten(candidate)
    return ""


def is_client_change(commit: str) -> bool:
    paths = run("git", "diff", "--name-only", f"{commit}^1", commit).splitlines()
    return any(RELEASE_PATH.match(p) for p in paths)


def pull_requests(repo: str, commit: str) -> list[dict]:
    result = run("gh", "api", f"repos/{repo}/commits/{commit}/pulls")
    records = json.loads(result)
    if not isinstance(records, list):
        raise RuntimeError(f"GitHub PR association returned an invalid response for {commit}")
    return [p for p in records if p.get("merged_at") and p.get("base", {}).get("ref") == "main"]


def generate(repo: str, base: str, head: str) -> str:
    if not base:
        return "## 本次更新\n\n- 首次发布 Android 安装包。\n"
    run("git", "rev-parse", "--verify", f"{base}^{{commit}}")
    run("git", "merge-base", "--is-ancestor", base, head)
    shas = run("git", "rev-list", "--first-parent", "--reverse", f"{base}..{head}").splitlines()
    entries: list[str] = []
    seen: set[int] = set()
    for sha in shas:
        if not is_client_change(sha):
            continue
        prs = pull_requests(repo, sha)
        if prs:
            for pr in prs:
                number = pr["number"]
                if number in seen:
                    continue
                seen.add(number)
                title = shorten(pr["title"], 55)
                detail = summary(pr.get("body") or "", title)
                if detail and detail.rstrip("。") != title.rstrip("。"):
                    entries.append(f"- {title}：{detail}")
                else:
                    entries.append(f"- {title}")
        else:
            subject = run("git", "log", "-1", "--format=%s", sha)
            subject = re.sub(r"^(?:feat|fix|refactor|perf)(?:\([^)]*\))?:\s*", "", subject, flags=re.I)
            if subject and not re.search(r"\[(?:skip ci|ci skip)\]", subject, re.I):
                entries.append(f"- {shorten(subject)}")
    if not entries:
        raise RuntimeError("Version contains client changes but no usable release notes")
    return "## 本次更新\n\n" + "\n".join(entries) + "\n"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--repo", required=True)
    parser.add_argument("--base", required=True)
    parser.add_argument("--head", required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    notes = generate(args.repo, args.base, args.head)
    args.output.write_text(notes, encoding="utf-8")
    print(notes)


if __name__ == "__main__":
    main()
