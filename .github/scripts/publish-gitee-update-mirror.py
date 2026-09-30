#!/usr/bin/env python3
"""Publish 777 release assets to a public Gitee Release mirror.

The mirror uses gitee.com itself, so clients do not need a custom domain, CDN or ICP filing.
Version assets are immutable. update-manifest.json is uploaded last so clients never observe a
manifest before its APK/patch payloads are publicly downloadable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path

VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-777\.[0-9]+$")
SAFE_NAME_RE = re.compile(r"^[A-Za-z0-9._-]+$")
GITEE_MAX_RELEASE_ASSET_BYTES = 100_000_000
MAX_ATTEMPTS = 3
API_BASE = "https://gitee.com/api/v5"


class ApiError(RuntimeError):
    def __init__(self, status: int, body: str):
        super().__init__(f"Gitee API 请求失败：HTTP {status} {body[:1000]}")
        self.status = status
        self.body = body


def parse_mirror_base(value: str) -> tuple[str, str, str]:
    raw = value.strip().rstrip("/") + "/"
    parsed = urllib.parse.urlsplit(raw)
    if (
        parsed.scheme != "https"
        or parsed.hostname != "gitee.com"
        or parsed.port not in (None, 443)
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
    ):
        raise ValueError("UPDATE_MIRROR_BASE_URL 必须是 https://gitee.com/<owner>/<repo>/")
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) != 2 or any(not SAFE_NAME_RE.fullmatch(part) for part in parts):
        raise ValueError("UPDATE_MIRROR_BASE_URL 必须精确指向一个 Gitee 仓库根地址")
    owner, repo = parts
    return raw, owner, repo


def api_request(
    method: str,
    path: str,
    token: str,
    payload: dict | None = None,
    allow_404: bool = False,
):
    url = API_BASE + path
    data = None
    headers = {
        "Accept": "application/json",
        "Authorization": f"Bearer {token}",
        "User-Agent": "777-release-mirror/1",
    }
    if payload is not None:
        data = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        headers["Content-Type"] = "application/json"

    last_error: Exception | None = None
    for attempt in range(MAX_ATTEMPTS):
        try:
            request = urllib.request.Request(url, data=data, method=method, headers=headers)
            with urllib.request.urlopen(request, timeout=45) as response:
                body = response.read()
                return json.loads(body.decode("utf-8")) if body else None
        except urllib.error.HTTPError as error:
            body = error.read(4096).decode("utf-8", "replace")
            if allow_404 and error.code == 404:
                return None
            last_error = ApiError(error.code, body)
            retryable = error.code in (408, 429) or 500 <= error.code <= 599
            if not retryable or attempt + 1 >= MAX_ATTEMPTS:
                raise last_error
        except urllib.error.URLError as error:
            last_error = error

        if attempt + 1 < MAX_ATTEMPTS:
            time.sleep(attempt + 1)

    raise RuntimeError(f"Gitee API 连接失败：{last_error}")


def public_asset_url(base: str, tag: str, name: str) -> str:
    if not VERSION_RE.fullmatch(tag.removeprefix("v")) or not SAFE_NAME_RE.fullmatch(name):
        raise ValueError("Gitee Release 下载路径参数无效")
    return f"{base}releases/download/{tag}/{name}"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        while chunk := stream.read(1024 * 1024):
            digest.update(chunk)
    return digest.hexdigest()


def sha256_public(url: str) -> tuple[str, int]:
    digest = hashlib.sha256()
    total = 0
    request = urllib.request.Request(
        url,
        headers={"Cache-Control": "no-cache", "User-Agent": "777-release-mirror/1"},
    )
    last_error: Exception | None = None
    for attempt in range(MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=90) as response:
                while chunk := response.read(1024 * 1024):
                    total += len(chunk)
                    digest.update(chunk)
                return digest.hexdigest(), total
        except (urllib.error.HTTPError, urllib.error.URLError) as error:
            last_error = error
            if attempt + 1 < MAX_ATTEMPTS:
                time.sleep(attempt + 1)
    raise RuntimeError(f"Gitee Release 公开下载校验失败：{url}：{last_error}")


def upload_attachment(
    api_path: str,
    token: str,
    file_path: Path,
) -> dict:
    cmd = [
        "curl",
        "--fail-with-body",
        "--silent",
        "--show-error",
        "--location",
        "--retry",
        "2",
        "--retry-all-errors",
        "--connect-timeout",
        "20",
        "--max-time",
        "300",
        "--request",
        "POST",
        "--header",
        f"Authorization: Bearer {token}",
        "--header",
        "Accept: application/json",
        "--form",
        f"file=@{file_path}",
        API_BASE + api_path,
    ]
    completed = subprocess.run(cmd, check=False, capture_output=True, text=True)
    if completed.returncode != 0:
        message = completed.stderr.strip() or completed.stdout.strip()
        raise RuntimeError(f"上传 Gitee Release 附件失败：{file_path.name}：{message[:1500]}")
    try:
        return json.loads(completed.stdout)
    except json.JSONDecodeError as error:
        raise RuntimeError(
            f"Gitee Release 上传返回非 JSON：{completed.stdout[:1000]}"
        ) from error


def ensure_release(owner: str, repo: str, tag: str, token: str) -> dict:
    encoded_tag = urllib.parse.quote(tag, safe="")
    release = api_request(
        "GET",
        f"/repos/{owner}/{repo}/releases/tags/{encoded_tag}",
        token,
        allow_404=True,
    )
    if release is not None:
        return release

    project = api_request("GET", f"/repos/{owner}/{repo}", token)
    if not isinstance(project, dict):
        raise RuntimeError("无法读取 Gitee 镜像仓库信息")
    if project.get("private") is True:
        raise RuntimeError("Gitee 更新镜像仓库必须公开，否则客户端无法匿名下载")
    default_branch = str(project.get("default_branch") or "master")

    release = api_request(
        "POST",
        f"/repos/{owner}/{repo}/releases",
        token,
        {
            "tag_name": tag,
            "name": tag,
            "body": "777 国内更新镜像。正式发行源仍同步发布到 GitHub。",
            "target_commitish": default_branch,
            "prerelease": False,
        },
    )
    if not isinstance(release, dict) or not release.get("id"):
        raise RuntimeError("Gitee Release 创建成功但没有返回 release id")
    return release


def existing_attachments(owner: str, repo: str, release_id: int, token: str) -> dict[str, dict]:
    result = api_request(
        "GET",
        f"/repos/{owner}/{repo}/releases/{release_id}/attach_files?per_page=100",
        token,
    )
    if not isinstance(result, list):
        raise RuntimeError("Gitee Release 附件列表格式异常")
    attachments: dict[str, dict] = {}
    for item in result:
        if isinstance(item, dict):
            name = str(item.get("name") or "")
            if name:
                attachments[name] = item
    return attachments


def verify_or_upload(
    *,
    base: str,
    owner: str,
    repo: str,
    tag: str,
    release_id: int,
    token: str,
    file_path: Path,
    existing: dict[str, dict],
) -> None:
    if file_path.stat().st_size > GITEE_MAX_RELEASE_ASSET_BYTES:
        raise RuntimeError(
            f"{file_path.name} 为 {file_path.stat().st_size} 字节，超过 Gitee "
            f"Release 单附件 {GITEE_MAX_RELEASE_ASSET_BYTES} 字节镜像预算"
        )

    expected_sha = sha256_file(file_path)
    expected_size = file_path.stat().st_size
    public_url = public_asset_url(base, tag, file_path.name)

    if file_path.name in existing:
        actual_sha, actual_size = sha256_public(public_url)
        if actual_size != expected_size or actual_sha != expected_sha:
            raise RuntimeError(
                f"Gitee 已存在同版本同名附件但内容不同：{file_path.name}；"
                "版本资产必须保持不可变，请处理镜像状态后重新发布新版本"
            )
        print(f"已存在并验证：{file_path.name}")
        return

    upload_attachment(
        f"/repos/{owner}/{repo}/releases/{release_id}/attach_files",
        token,
        file_path,
    )
    actual_sha, actual_size = sha256_public(public_url)
    if actual_size != expected_size or actual_sha != expected_sha:
        raise RuntimeError(
            f"Gitee 附件公开回读校验失败：{file_path.name} "
            f"size={actual_size}/{expected_size} sha256={actual_sha}/{expected_sha}"
        )
    print(f"已上传并验证：{file_path.name} ({expected_size} bytes)")


def publish(asset_dir: Path, version: str) -> None:
    base = os.environ.get("UPDATE_MIRROR_BASE_URL", "").strip()
    token = os.environ.get("GITEE_MIRROR_ACCESS_TOKEN", "").strip()

    if not base and not token:
        print("未配置 Gitee 更新镜像，保留原 GitHub Release 更新链。")
        return
    if not base or not token:
        raise RuntimeError(
            "Gitee 更新镜像配置不完整：需要 UPDATE_MIRROR_BASE_URL 与 "
            "GITEE_MIRROR_ACCESS_TOKEN"
        )
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f"发布版本格式无效：{version}")

    base, owner, repo = parse_mirror_base(base)
    tag = "v" + version

    if not asset_dir.is_dir():
        raise FileNotFoundError(f"Release 资产目录不存在：{asset_dir}")
    files = sorted(path for path in asset_dir.iterdir() if path.is_file())
    manifest = asset_dir / "update-manifest.json"
    if manifest not in files:
        raise FileNotFoundError("Release 资产缺少 update-manifest.json")

    manifest_data = json.loads(manifest.read_text(encoding="utf-8"))
    if manifest_data.get("schema") != 1 or manifest_data.get("targetVersion") != version:
        raise RuntimeError("update-manifest.json 与当前发布版本不一致")

    release = ensure_release(owner, repo, tag, token)
    release_id = int(release["id"])
    existing = existing_attachments(owner, repo, release_id, token)

    # Keep the manifest last. Until it becomes public, clients treat an incomplete Gitee release
    # as unavailable and safely fall back to GitHub.
    ordered = [path for path in files if path.name != manifest.name] + [manifest]
    for path in ordered:
        if not SAFE_NAME_RE.fullmatch(path.name):
            raise RuntimeError(f"Release 资产文件名不安全：{path.name}")
        verify_or_upload(
            base=base,
            owner=owner,
            repo=repo,
            tag=tag,
            release_id=release_id,
            token=token,
            file_path=path,
            existing=existing,
        )

    latest = api_request("GET", f"/repos/{owner}/{repo}/releases/latest", token)
    if not isinstance(latest, dict) or latest.get("tag_name") != tag:
        raise RuntimeError(
            f"Gitee 最新正式版未指向本次发布：期望 {tag}，实际 "
            f"{latest.get('tag_name') if isinstance(latest, dict) else latest}"
        )
    print(f"Gitee 国内更新镜像发布完成：{base}releases/tag/{tag}")


def self_test() -> None:
    base, owner, repo = parse_mirror_base("https://gitee.com/example/777-mirror/")
    assert base == "https://gitee.com/example/777-mirror/"
    assert owner == "example"
    assert repo == "777-mirror"
    assert public_asset_url(base, "v0.12.0-777.50", "app-release.apk") == (
        "https://gitee.com/example/777-mirror/releases/download/"
        "v0.12.0-777.50/app-release.apk"
    )

    invalid = [
        "http://gitee.com/example/777-mirror/",
        "https://example.com/example/777-mirror/",
        "https://gitee.com/example/777-mirror/extra/",
        "https://user@gitee.com/example/777-mirror/",
    ]
    for value in invalid:
        try:
            parse_mirror_base(value)
        except ValueError:
            pass
        else:
            raise AssertionError(f"不安全镜像地址被接受：{value}")

    try:
        public_asset_url(base, "v0.12.0-777.50", "../app.apk")
    except ValueError:
        pass
    else:
        raise AssertionError("路径穿越文件名不应被接受")

    print("Gitee 更新镜像发布脚本自检通过。")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-dir", type=Path, default=Path(".release-assets"))
    parser.add_argument("--version", default=os.environ.get("DSH_VERSION_NAME", ""))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return

    try:
        publish(args.asset_dir, args.version)
    except Exception as error:
        print(str(error), file=sys.stderr)
        raise


if __name__ == "__main__":
    main()
