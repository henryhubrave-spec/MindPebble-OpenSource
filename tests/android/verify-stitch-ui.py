from pathlib import Path
import subprocess,time,xml.etree.ElementTree as ET
import argparse
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',default='outputs/stitch-preview');args=p.parse_args();base=[args.adb,'-s',args.serial];out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout
if not args.serial.startswith('emulator-') or run('shell','getprop','ro.hardware').strip()!=b'ranchu':raise SystemExit('Dedicated ranchu emulator only')
def snap(name):
 run('shell','uiautomator','dump','/sdcard/stitch.xml');xml=run('exec-out','cat','/sdcard/stitch.xml');(out/(name+'.xml')).write_bytes(xml);(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'));return ET.fromstring(xml)
def tap_text(tree,text):
 for node in tree.iter('node'):
  if node.get('text')==text or node.get('content-desc')==text:
   import re
   x1,y1,x2,y2=map(int,re.findall(r'\d+',node.get('bounds')));run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));return
 raise Exception('Missing '+text)
for page in ['today','memory','tasks','settings']:
 run('shell','am','force-stop','org.diy.liferecorder');run('shell','am','start','-n','org.diy.liferecorder/.MainActivity','--es','page',page);time.sleep(.8);tree=snap(page);texts=[n.get('text') or n.get('content-desc') for n in tree.iter('node')];assert all(v in texts for v in ['今日时光','语音回看','生活待办','高级设置']);print('Visible navigation:',page,flush=True)
 if page=='memory':
  for attempt in range(5):
   try:tap_text(tree,'查看情境复盘  ›');break
   except Exception:run('shell','input','swipe','550','1450','550','850','250');tree=snap('memory-scroll')
  else:raise Exception('Record detail entry missing')
  time.sleep(1);tree=snap('record');assert any(n.get('text')=='身心节律复盘' or '身心节律复盘' in n.get('text','') for n in tree.iter('node'));print('Record details open',flush=True)
 if page=='tasks':
  tap_text(tree,'配置规则');time.sleep(.4);tree=snap('reminder-rules');assert any(n.get('text')=='情境提醒规则' for n in tree.iter('node'));run('shell','input','keyevent','4');print('Editable reminder rules open',flush=True);run('shell','input','swipe','550','1450','550','750','250');time.sleep(.3);snap('tasks-list')
print('UI smoke passed',flush=True)
