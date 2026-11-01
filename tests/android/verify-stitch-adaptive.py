from pathlib import Path
import subprocess,time,xml.etree.ElementTree as ET,re
import argparse
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',default='outputs/stitch-preview');args=p.parse_args();base=[args.adb,'-s',args.serial];out=Path(args.output);out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run(base+list(args),capture_output=True,check=True).stdout
if not args.serial.startswith('emulator-') or run('shell','getprop','ro.hardware').strip()!=b'ranchu':raise SystemExit('Dedicated ranchu emulator only')
def tree(name):
 run('shell','uiautomator','dump','/sdcard/adaptive-stitch.xml');xml=run('exec-out','cat','/sdcard/adaptive-stitch.xml');(out/(name+'.xml')).write_bytes(xml);(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'));return ET.fromstring(xml)
def open_page(page):run('shell','am','force-stop','org.diy.liferecorder');run('shell','am','start','-n','org.diy.liferecorder/.MainActivity','--es','page',page);time.sleep(.8)
def tap(t,name):
 for n in t.iter('node'):
  if n.get('text')==name or n.get('content-desc')==name:
   b=list(map(int,re.findall(r'\d+',n.get('bounds'))));run('shell','input','tap',str((b[0]+b[2])//2),str((b[1]+b[3])//2));return True
 return False
try:
 run('shell','settings','put','system','font_scale','1.3');open_page('today');t=tree('large-font');assert all(any(n.get('text')==label for n in t.iter('node')) for label in ['今日时光','语音回看','生活待办','高级设置']);print('Large font navigation passed',flush=True)
 run('shell','settings','put','system','font_scale','1.0');run('shell','wm','size','1800x2200');open_page('memory');t=tree('fold-memory');assert any(n.get('text')=='打开原文与回放' for n in t.iter('node'));print('Fold list and preview passed',flush=True)
 run('shell','wm','size','reset');open_page('today')
 for i in range(5):
  t=tree('breathing-entry')
  if tap(t,'开始 ▷'):break
  run('shell','input','swipe','550','1450','550','850','250')
 else:raise Exception('Breathing entry missing')
 time.sleep(1);t=tree('breathing');assert any('轻轻吸气' in n.get('text','') or '缓缓呼气' in n.get('text','') for n in t.iter('node'));run('shell','input','keyevent','4');print('Breathing countdown passed',flush=True)
 open_page('memory')
 for i in range(5):
  t=tree('memory-final')
  if tap(t,'查看情境复盘  ›'):break
  run('shell','input','swipe','550','1450','550','850','250')
 else:raise Exception('Record link missing')
 time.sleep(.8)
 for i in range(6):
  t=tree('record-tab-entry')
  if tap(t,'AI 浓缩要点'):break
  run('shell','input','swipe','550','1450','550','850','250')
 else:raise Exception('Record tabs missing')
 time.sleep(.3);t=tree('record-summary');assert any(n.get('text')=='AI 分析这段原文' for n in t.iter('node')),'tab selection or scroll lost';assert tap(t,'情绪脉络');time.sleep(.3);t=tree('record-context');assert any(n.get('text')=='情绪脉络 · 环境观察' for n in t.iter('node'));print('Detail tabs preserve scroll and show selected content',flush=True)
finally:
 run('shell','settings','put','system','font_scale','1.0');run('shell','wm','size','reset');open_page('today')
