#!/usr/bin/env python3
"""Compact bundled runtime assets by storing identical shared libraries once."""

from __future__ import annotations

import hashlib
import shutil
import sys
import tempfile
from pathlib import Path

RUNTIMES = ("node", "python", "git")
SUPPORTED_ABIS = {"arm64-v8a", "x86_64"}
SCHEMA_VERSION = "1"


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def copy_non_library_assets(source: Path, destination: Path) -> None:
    for path in sorted(source.rglob("*")):
        relative = path.relative_to(source)
        parts = relative.parts
        if len(parts) >= 2 and parts[0] in SUPPORTED_ABIS and parts[1] == "lib":
            continue
        target = destination / relative
        if path.is_dir():
            target.mkdir(parents=True, exist_ok=True)
        elif path.is_file():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(path, target)


def compact_runtime_assets(output_root: Path, inputs: dict[str, Path]) -> tuple[int, int, int]:
    if output_root.exists():
        shutil.rmtree(output_root)
    assets_root = output_root / "assets"
    runtime_output = assets_root / "runtime"
    runtime_output.mkdir(parents=True, exist_ok=True)

    logical_bytes = 0
    unique_bytes = 0
    unique_blobs: set[tuple[str, str]] = set()

    for runtime in RUNTIMES:
        input_assets = inputs[runtime]
        source = input_assets / "runtime" / runtime
        if not source.is_dir():
            raise SystemExit(f"缺少 {runtime} 运行时资源目录：{source}")

        destination = runtime_output / runtime
        copy_non_library_assets(source, destination)

        abi_dirs = [
            child for child in source.iterdir()
            if child.is_dir() and child.name in SUPPORTED_ABIS
        ]
        if not abi_dirs:
            raise SystemExit(f"{runtime} 未生成任何受支持 ABI")

        for abi_dir in sorted(abi_dirs):
            abi = abi_dir.name
            library_dir = abi_dir / "lib"
            if not library_dir.is_dir():
                raise SystemExit(f"{runtime}/{abi} 缺少运行库目录")

            entries: list[tuple[str, str, int]] = []
            for library in sorted(library_dir.iterdir()):
                if not library.is_file():
                    raise SystemExit(f"运行库必须为扁平文件：{library}")
                size = library.stat().st_size
                if size <= 0:
                    raise SystemExit(f"运行库为空：{library}")
                digest = sha256_file(library)
                logical_bytes += size
                key = (abi, digest)
                blob = runtime_output / "shared" / abi / "lib" / digest
                if key not in unique_blobs:
                    blob.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copy2(library, blob)
                    if blob.stat().st_size != size or sha256_file(blob) != digest:
                        raise SystemExit(f"共享运行库写入校验失败：{library}")
                    unique_blobs.add(key)
                    unique_bytes += size
                entries.append((library.name, digest, size))

            if not entries:
                raise SystemExit(f"{runtime}/{abi} 没有可打包运行库")

            manifest = destination / abi / "libraries.tsv"
            manifest.parent.mkdir(parents=True, exist_ok=True)
            manifest.write_text(
                "".join(f"{name}\t{digest}\t{size}\n" for name, digest, size in entries),
                encoding="utf-8",
            )

    (runtime_output / "shared" / "schema-version.txt").write_text(
        SCHEMA_VERSION + "\n",
        encoding="utf-8",
    )
    return logical_bytes, unique_bytes, logical_bytes - unique_bytes


def self_test() -> None:
    with tempfile.TemporaryDirectory(prefix="runtime-compact-test-") as raw:
        root = Path(raw)
        inputs: dict[str, Path] = {}
        shared = b"same-library-content" * 100
        different = b"different-library-content" * 80

        for runtime in RUNTIMES:
            assets = root / f"{runtime}-assets"
            inputs[runtime] = assets
            runtime_root = assets / "runtime" / runtime
            lib = runtime_root / "arm64-v8a" / "lib"
            lib.mkdir(parents=True)
            (runtime_root / f"{runtime}-version.txt").write_text("test\n", encoding="utf-8")

        node_lib = inputs["node"] / "runtime/node/arm64-v8a/lib"
        (node_lib / "libsame.so").write_bytes(shared)
        (node_lib / "libsame.so.1").write_bytes(shared)
        python_lib = inputs["python"] / "runtime/python/arm64-v8a/lib"
        (python_lib / "libsame.so.1").write_bytes(shared)
        git_lib = inputs["git"] / "runtime/git/arm64-v8a/lib"
        (git_lib / "libdifferent.so").write_bytes(different)

        output = root / "output"
        logical, unique, saved = compact_runtime_assets(output, inputs)
        shared_dir = output / "assets/runtime/shared/arm64-v8a/lib"
        blobs = sorted(path.name for path in shared_dir.iterdir() if path.is_file())
        assert len(blobs) == 2, blobs
        assert logical == len(shared) * 3 + len(different)
        assert unique == len(shared) + len(different)
        assert saved == len(shared) * 2
        assert not (output / "assets/runtime/node/arm64-v8a/lib").exists()
        node_manifest = (
            output / "assets/runtime/node/arm64-v8a/libraries.tsv"
        ).read_text(encoding="utf-8")
        assert "libsame.so\t" in node_manifest
        assert "libsame.so.1\t" in node_manifest
        print("runtime compactor self-test passed")


def main(argv: list[str]) -> int:
    if argv == ["--self-test"]:
        self_test()
        return 0
    if len(argv) != 4:
        raise SystemExit(
            "usage: compact-runtime-assets.py <output-root> <node-assets> <python-assets> <git-assets>"
        )
    output = Path(argv[0])
    inputs = dict(zip(RUNTIMES, map(Path, argv[1:])))
    logical, unique, saved = compact_runtime_assets(output, inputs)
    print(
        "Runtime assets compacted: "
        f"logical={logical} bytes unique={unique} bytes saved={saved} bytes"
    )
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
