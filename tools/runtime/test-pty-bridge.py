"""Exercise the shipped bridge against real controlling terminals; standard library only."""
import json
import os
from pathlib import Path
import select
import signal
import struct
import subprocess
import sys
import tempfile
import time
import unittest

BRIDGE = Path(__file__).resolve().parents[2] / (
    "harness-runtime-android/src/main/resources/com/labteto/dshmobile/runtime/pty_bridge.py"
)


class Bridge:
    def __init__(self, script, start=True):
        self.process = subprocess.Popen(
            [sys.executable, "-u", str(BRIDGE),
             json.dumps([sys.executable, "-u", "-c", script]), "24", "80"],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
            start_new_session=True,
        )
        self.output = bytearray()
        line = self.process.stdout.readline()
        if not line.startswith(b"DSH_PTY_V1:"):
            raise AssertionError(line)
        self.child = int(line.split(b":")[1])
        if start:
            self.frame("S")

    def frame(self, op, payload=b"", fragmented=False):
        data = op.encode() + struct.pack("!I", len(payload)) + payload
        if fragmented:
            for byte in data:
                self.process.stdin.write(bytes([byte]))
                self.process.stdin.flush()
        else:
            self.process.stdin.write(data)
            self.process.stdin.flush()

    def until(self, marker, timeout=5):
        deadline = time.monotonic() + timeout
        while marker not in self.output and time.monotonic() < deadline:
            ready, _, _ = select.select([self.process.stdout], [], [], 0.05)
            if ready:
                data = os.read(self.process.stdout.fileno(), 65536)
                if not data:
                    break
                self.output.extend(data)
        if marker not in self.output:
            raise AssertionError((marker, bytes(self.output[-200:]), self.process.poll()))
        return bytes(self.output)

    def finish(self):
        output, error = self.process.communicate(timeout=5)
        self.output.extend(output)
        if error:
            raise AssertionError(error)
        return self.process.returncode, bytes(self.output)

    def close(self):
        if self.process.poll() is None:
            try:
                self.frame("C")
                self.process.wait(timeout=3)
            except (BrokenPipeError, subprocess.TimeoutExpired):
                os.killpg(self.child, signal.SIGKILL)
                self.process.kill()
                self.process.wait(timeout=3)
        for stream in (self.process.stdin, self.process.stdout, self.process.stderr):
            stream.close()


class PtyBridgeTest(unittest.TestCase):
    def bridge(self, script, **kwargs):
        bridge = Bridge(script, **kwargs)
        self.addCleanup(bridge.close)
        return bridge

    def test_real_tty_dimensions_resize_and_fragmented_input(self):
        bridge = self.bridge("""
import os, signal, struct, fcntl, termios
def size(*_):
    h, w, _, _ = struct.unpack('HHHH', fcntl.ioctl(0, termios.TIOCGWINSZ, b'\\0'*8))
    print('SIZE:%d:%d' % (h, w), flush=True)
signal.signal(signal.SIGWINCH, size)
print('TTY:' + str(all(os.isatty(fd) for fd in (0, 1, 2))), flush=True)
size()
print('INPUT:' + input(), flush=True)
""")
        bridge.until(b"SIZE:24:80")
        self.assertIn(b"TTY:True", bridge.output)
        bridge.frame("R", struct.pack("!II", 43, 132), fragmented=True)
        bridge.until(b"SIZE:43:132")
        bridge.frame("D", "你好😀\n".encode(), fragmented=True)
        bridge.until("INPUT:你好😀".encode())
        # Keep stdin open until the child exits; EOF explicitly means disconnect.
        bridge.process.wait(timeout=5)
        code, output = bridge.finish()
        self.assertEqual(0, code)
        self.assertIn("INPUT:你好😀".encode(), output)

    def test_control_c_reaches_foreground_group(self):
        bridge = self.bridge("""
import signal, time
signal.signal(signal.SIGINT, lambda *_: exit(42))
print('READY', flush=True)
while True: time.sleep(1)
""")
        bridge.until(b"READY")
        bridge.frame("D", b"\x03")
        bridge.process.wait(timeout=5)
        code, _ = bridge.finish()
        self.assertEqual(42, code)

    def test_start_gate_blocks_side_effects_and_disconnect_reaps_child(self):
        with tempfile.TemporaryDirectory() as directory:
            target = Path(directory) / "executed"
            bridge = self.bridge(f"open({str(target)!r}, 'w').write('executed')", start=False)
            self.assertFalse(target.exists())
            bridge.process.stdin.close()
            bridge.process.wait(timeout=5)
            self.assertFalse(target.exists())
            with self.assertRaises(ProcessLookupError):
                os.kill(bridge.child, 0)

    def test_final_unicode_output_and_nonzero_exit_are_preserved(self):
        bridge = self.bridge("import os; os.write(1, '尾部😀'.encode()); exit(7)")
        bridge.process.wait(timeout=5)
        code, output = bridge.finish()
        self.assertEqual(7, code)
        self.assertEqual("尾部😀", output.decode())

    def test_large_output_drains_while_stdin_is_pending(self):
        bridge = self.bridge("""
import os, tty
tty.setraw(0)
print('READY', flush=True)
for _ in range(128):
    os.write(1, b'x' * 4096)
    assert os.read(0, 1) == b'y'
os.write(1, b'DONE')
""")
        bridge.until(b"READY")
        bridge.frame("D", b"y" * 128)
        bridge.until(b"DONE")
        bridge.process.wait(timeout=5)
        code, output = bridge.finish()
        self.assertEqual(0, code)
        self.assertEqual(128 * 4096, output.count(b"x"))

    def test_close_kills_job_in_different_process_group(self):
        bridge = self.bridge("""
import os, time
child = os.fork()
if not child:
    os.setpgid(0, 0)
    print('JOB:' + str(os.getpid()), flush=True)
    while True: time.sleep(1)
while True: time.sleep(1)
""")
        bridge.until(b"JOB:")
        bridge.until(b"\n")
        job = int(bytes(bridge.output).split(b"JOB:")[1].split()[0])
        bridge.frame("C")
        bridge.process.wait(timeout=5)
        deadline = time.monotonic() + 3
        while time.monotonic() < deadline:
            try:
                os.kill(job, 0)
                state = None
                for entry in Path('/proc').iterdir():
                    if not entry.name.isdecimal():
                        continue
                    try:
                        status = dict(line.split(':', 1) for line in (entry / 'status').read_text().splitlines() if ':' in line)
                        if int(status.get('NSpid', status['Pid']).split()[-1]) == job:
                            state = (entry / 'stat').read_text().rsplit(')', 1)[1].split()[0]
                            break
                    except (FileNotFoundError, ProcessLookupError):
                        continue
            except (FileNotFoundError, ProcessLookupError):
                break
            if state == "Z":  # Reaping a reparented job belongs to init.
                break
            time.sleep(0.01)
        else:
            self.fail("PTY background job survived session close")


if __name__ == "__main__":
    unittest.main()
