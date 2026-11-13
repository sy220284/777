#!/usr/bin/env python3
"""Verify the compact bundled-runtime layout inside a built APK."""

from __future__ import annotations

import hashlib
import re
import sys
import zipfile
from pathlib import Path

RUNTIMES = ("node", "python", "git")
SHA256 = re.compile(r"^[0-9a-f]{64}$")
NAME = re.compile(r"^[A-Za-z0-9._+\\-]+$")


def parse_manifest(raw: bytes, label: str) -> list[tuple[str, str, int]]:
    entries: list[tuple[str, str, int]] = []
    names: set[str] = set()
    for raw_line in raw.decode("utf-8").splitlines():
        line = raw_line.strip()
        if not line:
            continue
        fields = line.split("\t")
        if len(fields) != 3:
            raise SystemExit(f"{label} 清单字段数量无效：{line}")
        name, digest, size_text = fields
        if not NAME.fullmatch(name):
            raise SystemExit(f"{label} 清单库名无效：{name}")
        if name in names:
            raise SystemExit(f"{label} 清单库名重复：{name}")
        names.add(name)
        if not SHA256.fullmatch(digest):
            raise SystemExit(f"{label} 清单摘要无效：{digest}")
        try:
            size = int(size_text)
        except ValueError as error:
            raise SystemExit(f"{label} 清单大小无效：{size_text}") from error
        if size <= 0:
            raise SystemExit(f"{label} 清单大小必须大于零：{name}")
        entries.append((name, digest, size))
    if not entries:
        raise SystemExit(f"{label} 清单为空")
    return entries


def main(argv: list[str]) -> int:
    if len(argv) not in (1, 2):
        raise SystemExit("usage: check-apk-runtime-layout.py <apk> [expected-abi]")
    apk = Path(argv[0])
    abi = argv[1] if len(argv) == 2 else "arm64-v8a"
    if abi not in {"arm64-v8a", "x86_64"}:
        raise SystemExit(f"不支持的 ABI：{abi}")

    with zipfile.ZipFile(apk) as archive:
        infos = {info.filename: info for info in archive.infolist() if not info.is_dir()}
        referenced_blobs: set[str] = set()
        logical_bytes = 0
        alias_count = 0

        for runtime in RUNTIMES:
            legacy_prefix = f"assets/runtime/{runtime}/{abi}/lib/"
            legacy = [name for name in infos if name.startswith(legacy_prefix)]
            if legacy:
                raise SystemExit(f"{runtime} 仍包含旧式重复运行库：{legacy[0]}")

            manifest_name = f"assets/runtime/{runtime}/{abi}/libraries.tsv"
            if manifest_name not in infos:
                raise SystemExit(f"APK 缺少运行库清单：{manifest_name}")
            entries = parse_manifest(archive.read(manifest_name), runtime)
            alias_count += len(entries)
            for name, digest, size in entries:
                blob = f"assets/runtime/shared/{abi}/lib/{digest}"
                info = infos.get(blob)
                if info is None:
                    raise SystemExit(f"{runtime}/{name} 引用的共享库不存在：{digest}")
                if info.file_size != size:
                    raise SystemExit(
                        f"{runtime}/{name} 大小不匹配：manifest={size}, apk={info.file_size}"
                    )
                referenced_blobs.add(blob)
                logical_bytes += size

        actual_blobs = {
            name for name in infos
            if name.startswith(f"assets/runtime/shared/{abi}/lib/")
        }
        if actual_blobs != referenced_blobs:
            extra = sorted(actual_blobs - referenced_blobs)
            missing = sorted(referenced_blobs - actual_blobs)
            raise SystemExit(
                "共享运行库引用集合不一致："
                f"extra={extra[:3]} missing={missing[:3]}"
            )

        unique_bytes = 0
        for blob in sorted(referenced_blobs):
            payload = archive.read(blob)
            digest = Path(blob).name
            if hashlib.sha256(payload).hexdigest() != digest:
                raise SystemExit(f"共享运行库摘要校验失败：{blob}")
            unique_bytes += len(payload)

    saved = logical_bytes - unique_bytes
    print(
        "Runtime APK layout OK: "
        f"aliases={alias_count}, blobs={len(referenced_blobs)}, "
        f"logical={logical_bytes}, unique={unique_bytes}, saved={saved}"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
