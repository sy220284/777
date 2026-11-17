#!/usr/bin/env python3
"""Install a real APK, traverse its real phone navigation, retain native pixels and XML."""
import argparse,hashlib,json,re,subprocess,time,xml.etree.ElementTree as ET
from pathlib import Path

P=argparse.ArgumentParser()
P.add_argument('--apk',required=True);P.add_argument('--package',required=True)
P.add_argument('--output',required=True);P.add_argument('--expected-sha256')
a=P.parse_args();out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
apk=Path(a.apk);digest=hashlib.sha256(apk.read_bytes()).hexdigest()
if a.expected_sha256 and digest!=a.expected_sha256:raise SystemExit('APK SHA256 mismatch')
strings={e.attrib['name']:''.join(e.itertext()) for e in ET.parse('app/src/main/res/values/strings.xml').getroot() if e.tag=='string'}
visited=[]
def adb(*args,timeout=60):
 p=subprocess.run(['adb',*args],capture_output=True,timeout=timeout)
 if p.returncode:raise RuntimeError(f'adb {args}: {p.stdout.decode(errors="replace")}\n{p.stderr.decode(errors="replace")}')
 return p.stdout

def tree():
 # UIAutomator may return an empty or transiently incomplete dump while the
 # application is attaching its first window. Require a real hierarchy.
 last_error=None
 for attempt in range(10):
  try:
   adb('shell','-n','uiautomator','dump','/sdcard/ui-audit.xml')
   xml=adb('exec-out','cat','/sdcard/ui-audit.xml')
   root=ET.fromstring(xml)
   if root.tag!='hierarchy':raise RuntimeError('Unexpected UI hierarchy root: '+root.tag)
   return root
  except (ET.ParseError,RuntimeError) as failure:
   last_error=failure
   time.sleep(.4)
 raise RuntimeError(f'UIAutomator did not produce a valid hierarchy after 10 reads: {last_error}')

def candidates(t,label):
 found={}
 for n in t.iter('node'):
  if label not in (n.get('text'),n.get('content-desc')):continue
  nums=list(map(int,re.findall(r'\d+',n.get('bounds',''))))
  if len(nums)==4 and nums[2]>nums[0] and nums[3]>nums[1]:found[tuple(nums)]=n
 return list(found)

def tap(label):
 deadline=time.monotonic()+15
 swipes=0
 while time.monotonic()<deadline:
  t=tree();matches=candidates(t,label)
  if len(matches)==1:
   x1,y1,x2,y2=matches[0];adb('shell','-n','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
  if len(matches)>1:raise RuntimeError(f'Ambiguous navigation label: {label}')
  scrollers=[n for n in t.iter('node') if n.get('scrollable')=='true']
  if scrollers and swipes<6:
   bounds=max((list(map(int,re.findall(r'\d+',n.get('bounds','')))) for n in scrollers),key=lambda b:(b[2]-b[0])*(b[3]-b[1]))
   x1,y1,x2,y2=bounds;x=(x1+x2)//2
   adb('shell','-n','input','swipe',str(x),str(y2-24),str(x),str(y1+24),'350')
   swipes+=1
  else:
   time.sleep(.35)
 raise RuntimeError(f'Navigation target missing after waiting for UI: {label}')

def snapshot(name):
 t=tree();ET.ElementTree(t).write(out/(name+'.xml'),encoding='utf-8')
 (out/(name+'.png')).write_bytes(adb('exec-out','screencap','-p'))
 visited.append(name);print('Captured',name,flush=True)
 (out/'coverage.json').write_text(json.dumps({'apk_sha256':digest,'package':a.package,'captured':visited},ensure_ascii=False,indent=2))

def drawer_visible(t):
 return all(candidates(t,strings[key]) for key in ['local_usage_chat','local_usage_work','settings_title'])

def drawer():
 # A successful input tap is asynchronous; verify the drawer is visible
 # before attempting to interact with its destinations.
 deadline=time.monotonic()+15
 opened=False
 while time.monotonic()<deadline:
  t=tree()
  if drawer_visible(t):return
  if candidates(t,strings['app_open_navigation']) and not opened:
   tap(strings['app_open_navigation'])
   opened=True
  time.sleep(.3)
 raise RuntimeError('Sidebar did not open after tapping the navigation control')

def home():
 for _ in range(4):
  t=tree()
  if not drawer_visible(t) and candidates(t,strings['app_open_navigation']):return
  adb('shell','-n','input','keyevent','4')
 raise RuntimeError('Could not return to conversation')

def page(resource,name):
 drawer();tap(strings[resource]);snapshot(name);home()

adb('install','--no-streaming','-r',str(apk),timeout=180)
package=adb('shell','-n','dumpsys','package',a.package).decode()
if 'versionName=' not in package:raise RuntimeError('Installed package version could not be verified')
metadata={'apk_sha256':digest,'package':a.package,'version':re.search(r'versionName=(\S+)',package).group(1),
 'api':adb('shell','-n','getprop','ro.build.version.sdk').decode().strip(),
 'abis':adb('shell','-n','getprop','ro.product.cpu.abilist').decode().strip()}
(out/'installed.json').write_text(json.dumps(metadata,indent=2))
adb('shell','-n','pm','grant',a.package,'android.permission.POST_NOTIFICATIONS')
# Launch as PackageManager.getLaunchIntentForPackage does: resolve the launcher
# without MATCH_DEFAULT_ONLY, then use an explicit component with its matching action/category.
user=adb('shell','-n','am','get-current-user').decode().strip()
(out/'package-state.txt').write_text(package)
resolved=adb('shell','-n','cmd','package','resolve-activity','--brief','--query-flags','0',
 '--user',user,'-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER','-p',a.package).decode().strip()
(out/'launcher-resolution.txt').write_text(resolved)
component=resolved.splitlines()[-1]
if not component.startswith(a.package+'/'):raise RuntimeError('Installed launcher could not be resolved: '+resolved)
adb('shell','-n','am','start','-W','--user',user,'-n',component,
 '-a','android.intent.action.MAIN','-c','android.intent.category.LAUNCHER')
end=time.monotonic()+45
while True:
 t=tree()
 if candidates(t,strings['shenyan_onboarding_skip']):
  snapshot('first-launch');tap(strings['shenyan_onboarding_skip']);continue
 if candidates(t,strings['app_open_navigation']):break
 if time.monotonic()>end:raise RuntimeError('Conversation shell did not become ready')
 time.sleep(.5)
snapshot('home-initial');drawer();tap(strings['local_usage_work']);home();snapshot('home-work')
drawer();snapshot('sidebar-work');home()
page('chatlist_workspace_files','workspace');page('tasks_title','tasks-work');page('tools_title','tools');page('settings_title','settings-root')
drawer();tap(strings['local_usage_chat']);home();snapshot('home-chat')
drawer();snapshot('sidebar-chat');home()
page('persona_gallery_title','personas');page('chat_diary_title','diary');page('tasks_chat_title','tasks-chat')
adb('shell','-n','am','force-stop',a.package)
print(json.dumps({'installed':metadata,'pages':visited},ensure_ascii=False),flush=True)
