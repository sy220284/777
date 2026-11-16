"""Capture complete Android UI journeys, automatically rolling over screenrecord clips."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import subprocess
import threading
import time
import uuid


def _utc_now():
    return datetime.now(timezone.utc).isoformat(timespec="seconds")


class DeviceVideoRecorder:
    """One recording owner per emulator. All clips are preserved, including failed tests."""

    def __init__(self, directory: Path, name: str, *, adb="adb", seconds=150, bit_rate=6000000):
        self.directory = Path(directory)
        self.name = name
        self.adb = adb
        self.seconds = seconds  # Android screenrecord stops at 180 s; roll over before then.
        self.bit_rate = bit_rate
        self._stop = threading.Event()
        self._started = threading.Event()
        self._thread = None
        self._process = None
        self._clips = []
        self._remotes = []
        self._errors = []
        self._token = uuid.uuid4().hex[:12]

    def _adb(self, *args, timeout=90):
        proc = subprocess.run([self.adb, *args], capture_output=True, text=True, timeout=timeout)
        if proc.returncode:
            raise RuntimeError(f'adb {args[0]}: {(proc.stderr or proc.stdout)[-1000:]}')
        return proc.stdout

    def start(self):
        self.directory.mkdir(parents=True, exist_ok=True)
        self._adb("shell", "-n", "mkdir", "-p", "/sdcard/Download", timeout=20)
        self._thread = threading.Thread(target=self._capture, name="android-ui-video", daemon=True)
        self._thread.start()
        if not self._started.wait(12):
            raise RuntimeError(f"录屏进程未启动：{self.name}")
        # Wait for the remote recorder, not just the adb subprocess, before clicking.
        for _ in range(30):
            if self._errors:
                raise RuntimeError("录屏启动失败：" + "; ".join(self._errors))
            proc = subprocess.run([self.adb, "shell", "-n", "pidof", "screenrecord"],
                                  capture_output=True, text=True, timeout=10)
            if proc.returncode == 0 and proc.stdout.strip():
                time.sleep(.35)  # Allow the encoder to initialize before UI actions.
                return
            time.sleep(.2)
        raise RuntimeError("Android screenrecord 未进入录制状态")

    def _capture(self):
        index = 0
        while not self._stop.is_set():
            index += 1
            remote = f"/sdcard/Download/ui-audit-{self._token}-{index:03d}.mp4"
            began = _utc_now()
            start = time.monotonic()
            try:
                self._process = subprocess.Popen(
                    [self.adb, "shell", "-n", "screenrecord", "--time-limit", str(self.seconds),
                     "--bit-rate", str(self.bit_rate), remote],
                    stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                    text=True,
                )
                self._started.set()
                stdout, stderr = self._process.communicate(timeout=self.seconds + 35)
                if self._process.returncode:
                    raise RuntimeError(f"screenrecord 退出 {self._process.returncode}: {(stderr or stdout)[-800:]}")
                self._remotes.append({"remote": remote, "file": f"part-{index:03d}.mp4",
                                      "started_at": began, "ended_at": _utc_now(),
                                      "elapsed_seconds": round(time.monotonic()-start, 2)})
            except Exception as error:
                self._errors.append(f"{remote}: {error}")
                break
            finally:
                self._process = None
        self._started.set()

    def stop(self):
        self._stop.set()
        if self._thread:
            if self._thread.is_alive():
                # SIGINT asks Android's screenrecord to finalize its MP4, unlike killing adb.
                pids = subprocess.run([self.adb, "shell", "-n", "pidof", "screenrecord"],
                                      capture_output=True, text=True, timeout=10)
                if pids.returncode == 0:
                    for pid in pids.stdout.split():
                        if pid.isdigit():
                            signal_result = subprocess.run(
                                [self.adb, "shell", "-n", "kill", "-2", pid],
                                capture_output=True, text=True, timeout=15)
                            if signal_result.returncode and self._process and self._process.poll() is None:
                                self._errors.append("无法结束录像进程：" + signal_result.stderr[-600:])
            self._thread.join(timeout=45)
            if self._thread.is_alive():
                self._errors.append("录屏进程未能正常结束并回收")
        for item in self._remotes:
            remote = item["remote"]
            local = self.directory / item["file"]
            try:
                self._adb("pull", remote, str(local), timeout=180)
                with local.open("rb") as video:
                    header = video.read(12)
                if local.stat().st_size < 128 or header[4:8] != b"ftyp":
                    raise RuntimeError(f"视频文件无效：{local.name}")
                digest = hashlib.sha256()
                with local.open("rb") as video:
                    for chunk in iter(lambda: video.read(1024 * 1024), b""):
                        digest.update(chunk)
                self._clips.append({**item, "file": local.name,
                                    "bytes": local.stat().st_size, "sha256": digest.hexdigest()})
                self._clips[-1].pop("remote")
            except Exception as error:
                self._errors.append(f"无法保存 {item['file']}: {error}")
            finally:
                try:
                    self._adb("shell", "-n", "rm", "-f", remote, timeout=20)
                except Exception as cleanup_error:
                    self._errors.append(f"清理设备录像文件失败：{cleanup_error}")
        receipt = {"name": self.name, "clips": self._clips, "errors": self._errors}
        (self.directory / "manifest.json").write_text(
            json.dumps(receipt, ensure_ascii=False, indent=2), encoding="utf-8")
        if self._errors or not self._clips:
            raise RuntimeError("；".join(self._errors) or "没有录到可播放的 MP4 视频")
        return receipt


def main():
    """Record any other emulator test command, retaining video even when it fails."""
    import argparse
    import sys
    parser = argparse.ArgumentParser(description="录制 Android 模拟器测试全过程")
    parser.add_argument("--name", required=True)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--adb", default="adb")
    parser.add_argument("command", nargs=argparse.REMAINDER)
    args = parser.parse_args()
    command = args.command[1:] if args.command[:1] == ["--"] else args.command
    if not command:
        parser.error("需要提供要执行的测试命令")
    recorder = DeviceVideoRecorder(args.output / args.name, args.name, adb=args.adb)
    status = 1
    video_error = False
    try:
        recorder.start()
        status = subprocess.run(command).returncode
    finally:
        try:
            receipt = recorder.stop()
            print(f"UI 视频录制完成：{args.name}，{len(receipt['clips'])} 段", flush=True)
        except Exception as error:
            video_error = True
            print(f"UI 视频未完整保存：{error}", file=sys.stderr, flush=True)
    return status or int(video_error)


if __name__ == "__main__":
    raise SystemExit(main())
