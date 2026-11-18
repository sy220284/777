#!/usr/bin/env python3
"""每个 CI 媒体与指定测试报告系列仅保留最新的一份 Artifact。"""
import argparse
from collections import defaultdict
import json
import os
import re
import sys
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

API = "https://api.github.com"
MEDIA_NAME = re.compile(
    r"(?:^|[-_\s])(?:videos?|screenshots?|screen[-_]?record(?:ings?)?|"
    r"screen[-_]?captures?|ui[-_]?audit)(?:$|[-_\s])",
    re.IGNORECASE,
)
# 精确匹配现行上传契约。工具链、发行包、其他测试报告不参与清理。
REPORT_SERIES = frozenset((
    "session-storage-v7-android-tests",
    "architecture3-full-callgraph",
))
RUN_SUFFIX = re.compile(
    r"(?:[-_]run[-_]?\d{6,}|[-_]sha[-_]?[0-9a-f]{12,40}|"
    r"[-_][0-9a-f]{16,40}|[-_]20\d{6}(?:T\d{6}Z)?)$",
    re.IGNORECASE,
)


def artifact_series(name):
    if not isinstance(name, str) or name.startswith("777-toolchain-"):
        return None
    series = RUN_SUFFIX.sub("", name)
    if series in REPORT_SERIES:
        return series
    if not MEDIA_NAME.search(name):
        return None
    if series == "777-ui-videos-and-results-37":
        series += ".0"
    return series


def plan_cleanup(artifacts):
    """分组选择创建时间最新的一份，返回 {系列:保留产物}、待删除产物。"""
    grouped = defaultdict(list)
    for artifact in artifacts:
        series = artifact_series(artifact.get("name"))
        if series is None or artifact.get("expired") is True:
            continue
        if not isinstance(artifact.get("id"), int) or not artifact.get("created_at"):
            raise ValueError(f"Artifact 元数据不完整: {artifact!r}")
        grouped[series].append(artifact)
    kept, outdated = {}, []
    for series, items in sorted(grouped.items()):
        items.sort(key=lambda x: (x["created_at"], x["id"]), reverse=True)
        kept[series] = items[0]
        outdated.extend(items[1:])
    return kept, outdated


class GithubArtifacts:
    def __init__(self, repository, token):
        if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
            raise ValueError("GITHUB_REPOSITORY 格式无效")
        if not token:
            raise ValueError("缺少 GITHUB_TOKEN")
        self.repository = repository
        self.token = token

    def request(self, method, path):
        req = Request(
            API + path,
            headers={
                "Authorization": f"Bearer {self.token}",
                "Accept": "application/vnd.github+json",
                "X-GitHub-Api-Version": "2022-11-28",
                "User-Agent": "777-ci-media-cleanup",
            },
            method=method,
        )
        try:
            with urlopen(req, timeout=45) as response:
                data = response.read()
                return json.loads(data) if data else None
        except HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace")[:1000]
            raise RuntimeError(f"GitHub API {method} {path}: HTTP {exc.code}: {body}") from exc
        except URLError as exc:
            raise RuntimeError(f"GitHub API {method} {path}: {exc}") from exc

    def list_all(self):
        found = []
        page = 1
        while True:
            data = self.request("GET", f"/repos/{self.repository}/actions/artifacts?per_page=100&page={page}")
            if not isinstance(data, dict) or not isinstance(data.get("artifacts"), list):
                raise RuntimeError(f"Artifact 列表格式异常，第 {page} 页")
            items = data["artifacts"]
            found.extend(items)
            print(f"扫描第 {page} 页：{len(items)} 个 Artifact", flush=True)
            if len(items) < 100:
                return found
            page += 1

    def delete(self, artifact_id):
        self.request("DELETE", f"/repos/{self.repository}/actions/artifacts/{artifact_id}")


def main(argv=None, api=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="实际删除；默认为预览")
    args = parser.parse_args(argv)
    client = api or GithubArtifacts(
        os.environ.get("GITHUB_REPOSITORY", ""),
        os.environ.get("GITHUB_TOKEN", ""),
    )
    existing = client.list_all()
    kept, outdated = plan_cleanup(existing)
    freed = 0
    for item in outdated:
        if args.apply:
            client.delete(item["id"])
            freed += int(item.get("size_in_bytes") or 0)
        print(f"{'删除' if args.apply else '预览删除'} {item['name']} id={item['id']}", flush=True)
    report = (
        "## 777 CI 截图、录屏与报告 Artifact 清理\n\n"
        f"- 模式：{'实际删除' if args.apply else '仅预览'}\n"
        f"- 总 Artifact：{len(existing)}\n"
        f"- 最新保留：{len(kept)} 份（每个受管证据系列一份）\n"
        f"- 旧版待/已删除：{len(outdated)} 份\n"
        f"- 实际释放：{freed:,} 字节\n\n"
        + "\n".join(f"- 保留 {name}: ID {a['id']}" for name, a in kept.items()) + "\n"
    )
    if os.environ.get("GITHUB_STEP_SUMMARY"):
        with open(os.environ["GITHUB_STEP_SUMMARY"], "a", encoding="utf-8") as summary:
            summary.write(report)
    print(report)
    return 0


if __name__ == "__main__":
    sys.exit(main())
