#!/usr/bin/env python3
"""Run native UI checks and preserve playable videos of every interaction journey."""
from html import escape
from pathlib import Path
import hashlib,json,os,re,shutil,subprocess,sys,urllib.request
from device_video import DeviceVideoRecorder

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/build/outputs/ui-audit';OUT.mkdir(parents=True,exist_ok=True)
VIDEOS=OUT/'videos';VIDEOS.mkdir(exist_ok=True)
CLASSES=[
 'com.shenyan.designsystem.components.ShenyanAtlasInteractionTest',
 'com.shenyan.designsystem.components.SyLiquidRefractionAndroidTest',
 'com.shenyan.designsystem.components.ShenyanAtlasVisualCaptureTest',
 'com.labteto.dshmobile.ui.screens.local.DeviceScrollFrameAndMemoryTest',
 'com.shenyan.designsystem.components.MobileActionReadabilityTest',
 'com.labteto.dshmobile.ui.screens.local.WorkspaceBrowseLayoutTest',
 'com.labteto.dshmobile.ui.screens.local.UiPolishInteractionRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.LocalWorkspaceShareSelectionUiTest',
 'com.labteto.dshmobile.ui.audit.SettingsPageAuditTest',
 'com.labteto.dshmobile.ui.screens.settings.SettingsSearchUiTest',
 'com.labteto.dshmobile.ui.screens.settings.SettingsNavigationStateRestorationTest',
 'com.labteto.dshmobile.ui.audit.MainPagesAuditTourTest',
 'com.labteto.dshmobile.ui.audit.ContentPagesAuditTest',
 'com.labteto.dshmobile.ui.screens.local.LocalSessionEntryRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.LocalDrawerEntryVisibilityRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.LocalConversationComposerRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.LocalAgentTeamUiRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.WorkBackgroundReplyAndroidTest',
 'com.labteto.dshmobile.ui.screens.local.LocalWorkToolDisclosureAndroidTest',
 'com.labteto.dshmobile.ui.screens.settings.PermissionSetupFlowAndroidTest',
 'com.labteto.dshmobile.notify.NotificationRoutingAndroidTest',
]
summary=[]

def write_index():
 rows=[]
 for entry in summary:
  clips=entry.get('video',{}).get('clips',[])
  links=' '.join(f'<a href="{escape(entry["phase"])}'+'/'+escape(clip['file'])+'">第 '+str(i)+' 段</a>' for i,clip in enumerate(clips,1))
  rows.append('<tr><td>'+escape(entry['phase'])+'</td><td>'+('通过' if entry['passed'] else '失败')+'</td><td>'+links+'</td></tr>')
 (VIDEOS/'index.html').write_text('''<!doctype html><html lang="zh-CN"><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>777 原生 UI 自动巡检录屏</title><style>body{font:16px system-ui;max-width:1100px;margin:36px auto;padding:0 18px}table{width:100%;border-collapse:collapse;word-break:break-word}td,th{padding:12px;border-bottom:1px solid #ddd;text-align:left}a{margin-right:16px}p{color:#555}</style><h1>777 模拟器 UI 巡检视频</h1><p>按真实测试用例或页面走查分组。时长超过 Android 单段录制上限时自动分段，按序播放即可。详细结果在上一级 execution.json；每组录像目录提供 manifest.json。</p><table><thead><tr><th>页面/交互流程</th><th>结果</th><th>视频</th></tr></thead><tbody>'''+''.join(rows)+'''</tbody></table></html>''',encoding='utf-8')

def phase(name,fn,record=False):
 print('UI audit phase:',name,flush=True)
 video=DeviceVideoRecorder(VIDEOS/name,name) if record else None
 result=None;failure=None;receipt=None
 try:
  if video:video.start()
  result=fn()
 except Exception as exc:
  failure=str(exc)
 finally:
  if video:
   try:receipt=video.stop()
   except Exception as error:
    manifest=VIDEOS/name/'manifest.json'
    if manifest.exists():receipt=json.loads(manifest.read_text(encoding='utf-8'))
    failure=(failure+'\n' if failure else '')+'录屏失败：'+str(error)
  entry={'phase':name,'passed':failure is None}
  if failure:entry['error']=failure;print('UI audit failure:',name,failure,flush=True)
  else:entry['result']=result
  if receipt:entry['video']=receipt
  summary.append(entry)
  (OUT/'execution.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
  write_index()

def checked(argv,timeout=180):
 p=subprocess.run(argv,cwd=ROOT,text=True,capture_output=True,timeout=timeout)
 if p.returncode:raise RuntimeError(f'{argv}: {p.stdout[-2000:]}\n{p.stderr[-2000:]}')
 return p.stdout

# Build failures stop here: no stale APK can satisfy this head's verification.
debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
test=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
if os.environ.get('DSH_NATIVE_UI_PREBUILT_APKS') == '1':
 manifest=ROOT/'app/build/outputs/apk/SHA256SUMS'
 if not manifest.is_file():raise RuntimeError('Prebuilt APK checksum manifest missing')
 expected={}
 for line in manifest.read_text(encoding='utf-8').splitlines():
  digest,separator,relpath=line.partition('  ')
  if not separator or not re.fullmatch(r'[0-9a-f]{64}',digest) or relpath in expected:
   raise RuntimeError('Invalid prebuilt APK checksum manifest entry: '+repr(line))
  expected[relpath]=digest
 required={str(apk.relative_to(ROOT)) for apk in (debug,test)}
 if set(expected)!=required:raise RuntimeError('Prebuilt APK manifest must list exactly the debug and test APKs')
 for apk in (debug,test):
  if not apk.is_file():raise RuntimeError('Required prebuilt APK missing: '+str(apk))
  digest=hashlib.sha256(apk.read_bytes()).hexdigest()
  if digest!=expected[str(apk.relative_to(ROOT))]:raise RuntimeError('Prebuilt APK checksum mismatch: '+str(apk))
 print('Verified Android device-test APKs against workflow build manifest',flush=True)
else:
 subprocess.run(['./gradlew','--max-workers=2',':app:assembleDebug',':app:assembleDebugAndroidTest','--stacktrace','--console=plain'],cwd=ROOT,check=True)
for apk in (debug,test):checked(['adb','install','--no-streaming','-r',str(apk)])

for name in CLASSES:
 def run_class(name=name):
  p=subprocess.run(['adb','shell','-n','am','instrument','-w','-r','-e','class',name,
    'com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner'],cwd=ROOT,text=True,capture_output=True,timeout=1800 if name.endswith('SettingsPageAuditTest') else 1200 if name.endswith('ContentPagesAuditTest') else 600)
  text=p.stdout+'\n'+p.stderr
  (OUT/(name.rsplit('.',1)[1]+'.txt')).write_text(text)
  match=re.search(r'OK \((\d+) tests?\)',text)
  if p.returncode or not match or int(match.group(1))==0 or ('INSTRUMENTATION_STATUS: class='+name) not in text:
   raise RuntimeError('Native class did not produce passing non-empty results: '+text[-5000:])
  return {'class':name,'tests':int(match.group(1))}
 phase(name.rsplit('.',1)[1],run_class,record=True)

def export_components():
 return checked(['adb','pull','/sdcard/Android/data/com.sy220284.dshmobile.debug/files/ui-audit/.',str(OUT/'components')])
phase('export-native-component-images',export_components)

# The repository retains only its latest three formal releases. Resolve and verify the
# published baseline at run time instead of pinning an asset that will be deleted.
release=OUT/'baseline-release.apk'
def baseline():
 api='https://api.github.com/repos/sy220284/777/releases/latest'
 headers={'Accept':'application/vnd.github+json','User-Agent':'777-native-ui-audit'}
 token=os.environ.get('GITHUB_TOKEN','').strip()
 if token:headers['Authorization']='Bearer '+token
 try:
  with urllib.request.urlopen(urllib.request.Request(api,headers=headers),timeout=30) as response:
   info=json.load(response)
  tag=info.get('tag_name','')
  if not re.fullmatch(r'v\d+\.\d+\.\d+-777\.\d+',tag):
   raise RuntimeError('Latest release has an unexpected tag: '+str(tag))
  assets=[asset for asset in info.get('assets',[]) if asset.get('name')=='app-release.apk']
  if len(assets)!=1:raise RuntimeError('Latest formal release must have exactly one release APK')
  asset=assets[0]
  url=asset.get('browser_download_url','')
  expected_url=f'https://github.com/sy220284/777/releases/download/{tag}/app-release.apk'
  if url!=expected_url:raise RuntimeError('Release APK URL does not match the verified repository/tag')
  published_digest=asset.get('digest','')
  if not isinstance(published_digest,str) or not re.fullmatch(r'sha256:[0-9a-f]{64}',published_digest):
   raise RuntimeError('Release APK has no valid published SHA-256 digest')
  size=asset.get('size')
  if not isinstance(size,int) or size<1000000:
   raise RuntimeError('Release APK metadata has an invalid file size')
  with urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'777-native-ui-audit'}),timeout=180) as response:
   with release.open('wb') as output:shutil.copyfileobj(response,output,length=1024*1024)
  if release.stat().st_size!=size:raise RuntimeError('Release APK size differs from published asset metadata')
  digest=published_digest.removeprefix('sha256:')
  if hashlib.sha256(release.read_bytes()).hexdigest()!=digest:
   raise RuntimeError('Release APK SHA-256 differs from published asset metadata')
  evidence={'tag':tag,'apk_sha256':digest,'size':size,'asset_url':url}
  (OUT/'baseline-release.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
  print('Verified published baseline:',json.dumps(evidence,ensure_ascii=False),flush=True)
  return checked(['python3','tools/ui/native-page-audit.py','--apk',str(release),
   '--package','com.sy220284.dshmobile','--expected-sha256',digest,
   '--output',str(OUT/'baseline')],timeout=420)
 finally:
  release.unlink(missing_ok=True)
phase('installed-release-page-tour',baseline,record=True)
phase('candidate-page-tour',lambda:checked(['python3','tools/ui/native-page-audit.py','--apk',str(debug),'--package','com.sy220284.dshmobile.debug','--output',str(OUT/'candidate')],timeout=420),record=True)
print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)
sys.exit(0 if all(item['passed'] for item in summary) else 1)
