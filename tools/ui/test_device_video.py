"""Isolated fake-adb checks for video finalization and automatic rollover."""
import json
import os
import subprocess
import sys
from pathlib import Path
import tempfile
import time
import unittest
from device_video import DeviceVideoRecorder

ADB_STUB = r'''#!/usr/bin/env python3
import os,sys,time,signal,shutil
from pathlib import Path
store=Path(os.environ['FAKE_ADB_STORE']);store.mkdir(exist_ok=True)
args=sys.argv[1:]
if args[:2]==['shell','-n']:args=args[2:]
if args[0]=='mkdir':sys.exit(0)
if args[0]=='pidof':
 f=store/'pid'
 if f.exists():
  try:os.kill(int(f.read_text()),0);print(f.read_text());sys.exit(0)
  except ProcessLookupError:pass
 sys.exit(1)
if args[0]=='kill':
 try:os.kill(int(args[-1]),signal.SIGINT)
 except ProcessLookupError:pass
 sys.exit(0)
if args[0]=='screenrecord':
 if not args[-1].startswith('/data/local/tmp/'):raise Exception('Use device shell-writable storage')
 dest=store/Path(args[-1]).name
 quitting=False
 def stop(sig,frame):
  global quitting
  quitting=True
 signal.signal(signal.SIGINT,stop)
 sequence=store/'sequence'
 index=int(sequence.read_text())+1 if sequence.exists() else 1
 sequence.write_text(str(index))
 slow=os.environ.get('FAKE_ADB_DELAY_CLIP')==str(index)
 if slow:
  (store/'delayed-clip-started').write_text(str(index))
  time.sleep(.45)
 (store/'pid').write_text(str(os.getpid()))
 end=time.monotonic()+(4 if slow else float(args[2])*.01)
 while not quitting and time.monotonic()<end:time.sleep(.025)
 dest.write_bytes(b'\0\0\0\x18ftypisom'+b'0'*1024)
 (store/'pid').unlink(missing_ok=True)
 sys.exit(0)
if args[0]=='pull':
 if os.environ.get('FAKE_ADB_FAIL_PULL')=='1':
  sys.stderr.write('simulated interrupted transfer')
  sys.exit(23)
 shutil.copyfile(store/Path(args[1]).name, args[2]);sys.exit(0)
if args[0]=='rm':(store/Path(args[-1]).name).unlink(missing_ok=True);sys.exit(0)
raise Exception(args)
'''

class VideoTest(unittest.TestCase):
    def setup_recorder(self, root, **kwargs):
        adb=root/'adb'
        adb.write_text(ADB_STUB)
        adb.chmod(0o755)
        os.environ['FAKE_ADB_STORE']=str(root/'remote')
        return DeviceVideoRecorder(root/'videos','main-tour',adb=str(adb),**kwargs)

    def test_early_stop_finalizes_mp4(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            rec=self.setup_recorder(root)
            rec.start()
            time.sleep(.1)
            receipt=rec.stop()
            self.assertEqual(len(receipt['clips']),1)
            self.assertTrue((root/'videos'/'part-001.mp4').exists())

    def test_long_journey_rolls_over_and_keeps_all_parts(self):
        with tempfile.TemporaryDirectory() as d:
            root=Path(d)
            rec=self.setup_recorder(root,seconds=30)
            rec.start()
            time.sleep(1.2)
            receipt=rec.stop()
            self.assertGreaterEqual(len(receipt['clips']),2)
            self.assertTrue(all((root/'videos'/x['file']).exists() for x in receipt['clips']))
            self.assertEqual(json.loads((root/'videos'/'manifest.json').read_text())['name'],'main-tour')


    def test_stopping_during_rollover_waits_for_remote_recorder(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            rec = self.setup_recorder(root, seconds=30)
            os.environ['FAKE_ADB_DELAY_CLIP'] = '2'
            try:
                rec.start()
                deadline = time.monotonic() + 5
                marker = root / 'remote' / 'delayed-clip-started'
                while not marker.exists() and time.monotonic() < deadline:
                    time.sleep(.025)
                self.assertTrue(marker.exists(), 'Second clip must have begun launching')
                started = time.monotonic()
                receipt = rec.stop()
                self.assertLess(time.monotonic() - started, 2)
                self.assertGreaterEqual(len(receipt['clips']), 2)
                self.assertEqual(receipt['errors'], [])
            finally:
                os.environ.pop('FAKE_ADB_DELAY_CLIP', None)

    def test_failed_transfer_never_deletes_only_recording(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            rec = self.setup_recorder(root)
            rec.start()
            time.sleep(.1)
            os.environ["FAKE_ADB_FAIL_PULL"] = "1"
            try:
                with self.assertRaisesRegex(RuntimeError, "无法保存"):
                    rec.stop()
            finally:
                os.environ.pop("FAKE_ADB_FAIL_PULL", None)
            receipt = json.loads((root / "videos" / "manifest.json").read_text())
            self.assertEqual(len(receipt["unrecovered_device_clips"]), 1)
            self.assertTrue(list((root / "remote").glob("*.mp4")))
            self.assertEqual(receipt["clips"], [])

    def test_cli_preserves_video_when_wrapped_test_fails(self):
        with tempfile.TemporaryDirectory() as d:
            root = Path(d)
            adb = root / 'adb'
            adb.write_text(ADB_STUB)
            adb.chmod(0o755)
            env = dict(os.environ, FAKE_ADB_STORE=str(root / 'remote'))
            command = [sys.executable, str(Path(__file__).with_name('device_video.py')),
                       '--name', 'extension-market', '--output', str(root / 'output'),
                       '--adb', str(adb), '--', sys.executable, '-c', 'import sys;sys.exit(7)']
            result = subprocess.run(command, env=env, capture_output=True, text=True, timeout=35)
            self.assertEqual(result.returncode, 7, result.stderr)
            self.assertTrue((root / 'output' / 'extension-market' / 'part-001.mp4').exists())

if __name__=='__main__':
    unittest.main()
