#!/usr/bin/env python3
"""Publish verified release assets to a public Alibaba Cloud OSS endpoint.

The client never receives OSS credentials. GitHub Actions uploads immutable versioned
objects first, verifies that they are publicly readable, and updates latest.json last.
"""

from __future__ import annotations

import argparse
import base64
import hashlib
import hmac
import json
import mimetypes
import os
import re
import time
import urllib.error
import urllib.request
from dataclasses import dataclass
from email.utils import formatdate
from pathlib import Path
from urllib.parse import urlsplit

VERSION_RE = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+-777\.[0-9]+$")
SHA256_RE = re.compile(r"^[0-9a-fA-F]{64}$")
SAFE_SEGMENT_RE = re.compile(r"^[A-Za-z0-9._-]+$")
MAX_ERROR_BODY = 4096
MAX_ATTEMPTS = 3


@dataclass(frozen=True)
class MirrorTarget:
    base_url: str
    bucket: str
    host: str
    prefix: str

    def object_key(self, relative: str) -> str:
        return f"{self.prefix}/{relative}" if self.prefix else relative

    def public_url(self, relative: str) -> str:
        return self.base_url + relative


def _safe_path(value: str) -> str:
    value = value.strip().strip("/")
    if not value:
        return ""
    segments = value.split("/")
    if any(not SAFE_SEGMENT_RE.fullmatch(segment) for segment in segments):
        raise ValueError(f"对象路径包含不安全片段：{value}")
    return "/".join(segments)


def parse_target(value: str) -> MirrorTarget:
    raw = value.strip()
    parsed = urlsplit(raw)
    if (
        parsed.scheme != "https"
        or not parsed.hostname
        or parsed.username
        or parsed.password
        or parsed.query
        or parsed.fragment
        or parsed.port not in (None, 443)
    ):
        raise ValueError("UPDATE_MIRROR_BASE_URL 必须是标准 HTTPS 公网地址")

    host = parsed.hostname.lower()
    if "." not in host:
        raise ValueError("UPDATE_MIRROR_BASE_URL 缺少 OSS Bucket 主机名")
    bucket, endpoint = host.split(".", 1)
    if (
        not SAFE_SEGMENT_RE.fullmatch(bucket)
        or not endpoint.startswith("oss-")
        or not endpoint.endswith(".aliyuncs.com")
        or "-internal." in endpoint
    ):
        raise ValueError("UPDATE_MIRROR_BASE_URL 必须使用阿里云 OSS 公网 Bucket Endpoint")

    prefix = _safe_path(parsed.path)
    base_path = f"/{prefix}/" if prefix else "/"
    return MirrorTarget(
        base_url=f"https://{host}{base_path}",
        bucket=bucket,
        host=host,
        prefix=prefix,
    )


def _authorization(
    *,
    access_key_id: str,
    access_key_secret: str,
    method: str,
    content_md5: str,
    content_type: str,
    date: str,
    bucket: str,
    object_key: str,
) -> str:
    canonical_resource = f"/{bucket}/{object_key}"
    string_to_sign = "\n".join(
        [method, content_md5, content_type, date, canonical_resource]
    )
    signature = base64.b64encode(
        hmac.new(
            access_key_secret.encode("utf-8"),
            string_to_sign.encode("utf-8"),
            hashlib.sha1,
        ).digest()
    ).decode("ascii")
    return f"OSS {access_key_id}:{signature}"


def _request_with_retry(request: urllib.request.Request) -> bytes:
    last_error: Exception | None = None
    for attempt in range(MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=45) as response:
                return response.read()
        except urllib.error.HTTPError as error:
            last_error = error
            retryable = error.code in (408, 429) or 500 <= error.code <= 599
            if not retryable or attempt + 1 >= MAX_ATTEMPTS:
                body = error.read(MAX_ERROR_BODY).decode("utf-8", "replace")
                raise RuntimeError(
                    f"OSS 请求失败：HTTP {error.code} {body[:MAX_ERROR_BODY]}"
                ) from error
        except urllib.error.URLError as error:
            last_error = error

        if attempt + 1 < MAX_ATTEMPTS:
            time.sleep(1.0 * (attempt + 1))

    raise RuntimeError(f"OSS 请求失败：{last_error}")


def put_object(
    target: MirrorTarget,
    *,
    access_key_id: str,
    access_key_secret: str,
    relative: str,
    data: bytes,
    content_type: str,
    cache_control: str,
) -> None:
    relative = _safe_path(relative)
    object_key = target.object_key(relative)
    date = formatdate(usegmt=True)
    content_md5 = base64.b64encode(hashlib.md5(data).digest()).decode("ascii")
    authorization = _authorization(
        access_key_id=access_key_id,
        access_key_secret=access_key_secret,
        method="PUT",
        content_md5=content_md5,
        content_type=content_type,
        date=date,
        bucket=target.bucket,
        object_key=object_key,
    )
    request = urllib.request.Request(
        target.public_url(relative),
        data=data,
        method="PUT",
        headers={
            "Authorization": authorization,
            "Cache-Control": cache_control,
            "Content-MD5": content_md5,
            "Content-Type": content_type,
            "Date": date,
            "User-Agent": "777-release-mirror/1",
        },
    )
    _request_with_retry(request)


def verify_public_size(target: MirrorTarget, relative: str, expected: int) -> None:
    request = urllib.request.Request(
        target.public_url(relative),
        method="HEAD",
        headers={"Cache-Control": "no-cache", "User-Agent": "777-release-mirror/1"},
    )
    for attempt in range(MAX_ATTEMPTS):
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                declared = response.headers.get("Content-Length")
                if declared is None or int(declared) != expected:
                    raise RuntimeError(
                        f"公开对象长度异常：{relative}，期望 {expected}，实际 {declared}"
                    )
                return
        except (urllib.error.HTTPError, urllib.error.URLError) as error:
            if attempt + 1 >= MAX_ATTEMPTS:
                raise RuntimeError(f"公开对象无法读取：{relative}：{error}") from error
            time.sleep(1.0 * (attempt + 1))


def build_latest(manifest: dict, version: str) -> dict:
    if manifest.get("schema") != 1 or manifest.get("targetVersion") != version:
        raise ValueError("update-manifest.json 与当前发布版本不一致")

    apk = manifest.get("targetApk") or {}
    apk_name = apk.get("name", "")
    apk_size = apk.get("size")
    apk_sha = apk.get("sha256", "")
    if (
        not SAFE_SEGMENT_RE.fullmatch(apk_name)
        or not apk_name.endswith(".apk")
        or not isinstance(apk_size, int)
        or apk_size <= 0
        or not SHA256_RE.fullmatch(apk_sha)
    ):
        raise ValueError("update-manifest.json 的 APK 元数据无效")

    release_dir = f"releases/v{version}"
    patches = []
    for patch in manifest.get("patches", []):
        asset = patch.get("asset", "")
        if (
            not SAFE_SEGMENT_RE.fullmatch(asset)
            or patch.get("toVersion") != version
            or patch.get("algorithm") != "hdiffpatch-window-zstd-v1"
            or not isinstance(patch.get("size"), int)
            or patch["size"] <= 0
            or not SHA256_RE.fullmatch(patch.get("sha256", ""))
            or not SHA256_RE.fullmatch(patch.get("sourceSha256", ""))
            or not SHA256_RE.fullmatch(patch.get("targetSha256", ""))
            or patch.get("targetSha256", "").lower() != apk_sha.lower()
            or not VERSION_RE.fullmatch(patch.get("fromVersion", ""))
        ):
            raise ValueError("update-manifest.json 的增量包元数据无效")
        patches.append(
            {
                "fromVersion": patch["fromVersion"],
                "toVersion": version,
                "algorithm": patch["algorithm"],
                "path": f"{release_dir}/{asset}",
                "size": patch["size"],
                "sha256": patch["sha256"].lower(),
                "sourceSha256": patch["sourceSha256"].lower(),
                "targetSha256": patch["targetSha256"].lower(),
            }
        )

    return {
        "schema": 1,
        "version": version,
        "apk": {
            "name": apk_name,
            "path": f"{release_dir}/{apk_name}",
            "size": apk_size,
            "sha256": apk_sha.lower(),
        },
        "patches": patches,
    }


def content_type(path: Path) -> str:
    if path.suffix == ".apk":
        return "application/vnd.android.package-archive"
    if path.suffix == ".json":
        return "application/json"
    if path.suffix == ".txt":
        return "text/plain; charset=utf-8"
    return mimetypes.guess_type(path.name)[0] or "application/octet-stream"


def publish(asset_dir: Path, version: str) -> None:
    base_url = os.environ.get("UPDATE_MIRROR_BASE_URL", "").strip()
    access_key_id = os.environ.get("UPDATE_OSS_ACCESS_KEY_ID", "").strip()
    access_key_secret = os.environ.get("UPDATE_OSS_ACCESS_KEY_SECRET", "").strip()

    configured = [bool(base_url), bool(access_key_id), bool(access_key_secret)]
    if not any(configured):
        print("未配置 OSS 更新镜像，跳过国内镜像同步。")
        return
    if not all(configured):
        raise RuntimeError(
            "OSS 更新镜像配置不完整：需要 UPDATE_MIRROR_BASE_URL、"
            "UPDATE_OSS_ACCESS_KEY_ID、UPDATE_OSS_ACCESS_KEY_SECRET"
        )
    if not VERSION_RE.fullmatch(version):
        raise ValueError(f"发布版本格式无效：{version}")

    target = parse_target(base_url)
    manifest_path = asset_dir / "update-manifest.json"
    if not manifest_path.is_file():
        raise FileNotFoundError(f"缺少 {manifest_path}")
    manifest = json.loads(manifest_path.read_text(encoding="utf-8"))
    latest = build_latest(manifest, version)

    assets = sorted(path for path in asset_dir.iterdir() if path.is_file())
    if not assets:
        raise RuntimeError("没有可同步的 Release 资产")

    release_prefix = f"releases/v{version}"
    uploaded: list[tuple[str, int]] = []
    for path in assets:
        if not SAFE_SEGMENT_RE.fullmatch(path.name):
            raise ValueError(f"Release 资产文件名不安全：{path.name}")
        data = path.read_bytes()
        relative = f"{release_prefix}/{path.name}"
        put_object(
            target,
            access_key_id=access_key_id,
            access_key_secret=access_key_secret,
            relative=relative,
            data=data,
            content_type=content_type(path),
            cache_control="public, max-age=31536000, immutable",
        )
        uploaded.append((relative, len(data)))
        print(f"已上传：{relative} ({len(data)} bytes)")

    for relative, size in uploaded:
        verify_public_size(target, relative, size)

    latest_bytes = (
        json.dumps(latest, ensure_ascii=False, sort_keys=True, separators=(",", ":"))
        + "\n"
    ).encode("utf-8")
    put_object(
        target,
        access_key_id=access_key_id,
        access_key_secret=access_key_secret,
        relative="latest.json",
        data=latest_bytes,
        content_type="application/json",
        cache_control="no-store, max-age=0",
    )

    latest_request = urllib.request.Request(
        target.public_url("latest.json"),
        headers={"Cache-Control": "no-cache", "User-Agent": "777-release-mirror/1"},
    )
    actual_latest = _request_with_retry(latest_request)
    if actual_latest != latest_bytes:
        raise RuntimeError("latest.json 回读内容与本次发布不一致")

    print(f"OSS 国内更新镜像发布完成：{target.public_url('latest.json')}")


def self_test() -> None:
    target = parse_target(
        "https://example-update.oss-cn-hangzhou.aliyuncs.com/777/"
    )
    assert target.bucket == "example-update"
    assert target.prefix == "777"
    assert target.public_url("latest.json").endswith("/777/latest.json")

    try:
        parse_target("https://example-update.oss-cn-hangzhou-internal.aliyuncs.com/777/")
    except ValueError:
        pass
    else:
        raise AssertionError("内部网 Endpoint 不应被接受")

    version = "0.12.0-777.50"
    manifest = {
        "schema": 1,
        "targetVersion": version,
        "targetApk": {
            "name": "app-release.apk",
            "size": 100,
            "sha256": "a" * 64,
        },
        "patches": [
            {
                "fromVersion": "0.12.0-777.49",
                "toVersion": version,
                "algorithm": "hdiffpatch-window-zstd-v1",
                "asset": "delta-49-to-50.hpatch",
                "size": 10,
                "sha256": "b" * 64,
                "sourceSha256": "c" * 64,
                "targetSha256": "a" * 64,
            }
        ],
    }
    latest = build_latest(manifest, version)
    assert latest["apk"]["path"] == f"releases/v{version}/app-release.apk"
    assert latest["patches"][0]["path"].endswith("delta-49-to-50.hpatch")

    authorization = _authorization(
        access_key_id="id",
        access_key_secret="secret",
        method="PUT",
        content_md5="md5",
        content_type="application/json",
        date="Wed, 30 Sep 2026 00:00:00 GMT",
        bucket="bucket",
        object_key="777/latest.json",
    )
    assert authorization.startswith("OSS id:")
    print("OSS 更新镜像发布脚本自检通过。")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--asset-dir", type=Path, default=Path(".release-assets"))
    parser.add_argument("--version", default=os.environ.get("DSH_VERSION_NAME", ""))
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        self_test()
        return
    publish(args.asset_dir, args.version)


if __name__ == "__main__":
    main()
