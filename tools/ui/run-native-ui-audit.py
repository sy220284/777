#!/usr/bin/env python3
"""One owner for native UI evidence: run each declared class and verify its real receipt."""
from pathlib import Path
import hashlib,json,re,subprocess,sys,urllib.request

ROOT=Path(__file__).resolve().parents[2]
OUT=ROOT/'app/build/outputs/ui-audit';OUT.mkdir(parents=True,exist_ok=True)
CLASSES=[
 'com.shenyan.designsystem.components.MobileActionReadabilityTest',
 'com.labteto.dshmobile.ui.screens.local.WorkspaceBrowseLayoutTest',
 'com.labteto.dshmobile.ui.audit.SettingsPageAuditTest',
 'com.labteto.dshmobile.ui.screens.local.LocalConversationComposerRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.LocalAgentTeamUiRegressionTest',
 'com.labteto.dshmobile.ui.screens.local.WorkBackgroundReplyAndroidTest',
 'com.labteto.dshmobile.ui.screens.settings.PermissionSetupFlowAndroidTest',
 'com.labteto.dshmobile.notify.NotificationRoutingAndroidTest',
]
summary=[]
def phase(name,fn):
 print('UI audit phase:',name,flush=True)
 try:
  result=fn();summary.append({'phase':name,'passed':True,'result':result})
 except Exception as exc:
  summary.append({'phase':name,'passed':False,'error':str(exc)})
  print('UI audit failure:',name,str(exc),flush=True)
 (OUT/'execution.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2))

def checked(argv,timeout=180):
 p=subprocess.run(argv,cwd=ROOT,text=True,capture_output=True,timeout=timeout)
 if p.returncode:raise RuntimeError(f'{argv}: {p.stdout[-2000:]}\n{p.stderr[-2000:]}')
 return p.stdout

# Build failures stop here: no stale APK can satisfy this head's verification.
subprocess.run(['./gradlew','--max-workers=2',':app:assembleDebug',':app:assembleDebugAndroidTest','--stacktrace','--console=plain'],cwd=ROOT,check=True)
debug=ROOT/'app/build/outputs/apk/debug/app-debug.apk'
test=ROOT/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk'
for apk in [debug,test]:checked(['adb','install','--no-streaming','-r',str(apk)])

for name in CLASSES:
 def run_class(name=name):
  p=subprocess.run(['adb','shell','-n','am','instrument','-w','-r','-e','class',name,
    'com.sy220284.dshmobile.debug.test/androidx.test.runner.AndroidJUnitRunner'],cwd=ROOT,text=True,capture_output=True,timeout=600)
  text=p.stdout+'\n'+p.stderr
  (OUT/(name.rsplit('.',1)[1]+'.txt')).write_text(text)
  match=re.search(r'OK \((\d+) tests?\)',text)
  if p.returncode or not match or int(match.group(1))==0 or ('INSTRUMENTATION_STATUS: class='+name) not in text:
   raise RuntimeError('Native class did not produce passing non-empty results: '+text[-5000:])
  return {'class':name,'tests':int(match.group(1))}
 phase(name,run_class)

def export_components():
 return checked(['adb','pull','/sdcard/Android/data/com.sy220284.dshmobile.debug/files/ui-audit/.',str(OUT/'components')])
phase('export-native-component-images',export_components)

release=OUT/'777.394.apk'
def baseline():
 url='https://github.com/sy220284/777/releases/download/v0.12.0-777.394/app-release.apk'
 urllib.request.urlretrieve(url,release)
 digest='1383c41f03e20eef38d4b3369c21f7cae0740822a3b06ccde80547cadc06e734'
 if hashlib.sha256(release.read_bytes()).hexdigest()!=digest:raise RuntimeError('Official release checksum mismatch')
 try:
  return checked(['python3','tools/ui/native-page-audit.py','--apk',str(release),'--package','com.sy220284.dshmobile','--expected-sha256',digest,'--output',str(OUT/'baseline')],timeout=420)
 finally:
  release.unlink(missing_ok=True)
phase('installed-release-page-tour',baseline)
phase('candidate-page-tour',lambda:checked(['python3','tools/ui/native-page-audit.py','--apk',str(debug),'--package','com.sy220284.dshmobile.debug','--output',str(OUT/'candidate')],timeout=420))
print(json.dumps(summary,ensure_ascii=False,indent=2),flush=True)
sys.exit(0 if all(item['passed'] for item in summary) else 1)
