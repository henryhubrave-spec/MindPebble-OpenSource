"""Verify 0.4.2 screens against public emulator fixtures; never run on a physical device."""
import argparse,subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',default='outputs/mindpebble-0.4.2');a=p.parse_args();base=[a.adb,'-s',a.serial];out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run(base+list(args),check=True,capture_output=True).stdout
assert a.serial.startswith('emulator-') and run('shell','getprop','ro.hardware').strip()==b'ranchu'
def snapshot(name):
 result=run('shell','uiautomator','dump','/sdcard/ui042.xml');assert b'dumped to' in result,result.decode();xml=run('exec-out','cat','/sdcard/ui042.xml');(out/(name+'.xml')).write_bytes(xml);(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'));return ET.fromstring(xml)
def expect(label,name,prefix=False):
 for attempt in range(4):
  tree=snapshot(name)
  if any((n.get('text','').startswith(label) if prefix else n.get('text')==label) for n in tree.iter('node')):return
  time.sleep(.8)
 raise AssertionError('Missing settled text: '+label)
def tap(label,name,scroll=True):
 for i in range(7):
  tree=snapshot(name)
  for n in tree.iter('node'):
   if n.get('text')==label or n.get('content-desc')==label:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    if x2>x1 and y2>y1:run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.4);return
  if not scroll:break
  run('shell','input','swipe','550','1500','550','650','250')
 raise AssertionError('Missing '+label)
def launch(setting='',page='settings'):
 run('shell','am','force-stop','org.diy.liferecorder');run('shell','am','start','-n','org.diy.liferecorder/.MainActivity','--es','page',page,*(['--es','setting',setting] if setting else []));time.sleep(.5)
launch(page='memory');tap('连续生活片段 · 2 段','archive-zh');time.sleep(1);expect('连续回看','continuous-zh')
tap('任务中心','continuous-zh',False);tap('已完成','tasks-zh');expect('Public UI 042 ·','completed-filter-zh',True)
launch();tap('原始音频管理','settings-zh');expect('删除与存储位置','audio-storage-zh')
launch('language');tap('English','language-zh');expect('Language / 语言','language-en')
run('shell','input','keyevent','4');expect('Settings','settings-en')
tap('Audio storage','settings-en');expect('Delete & storage','audio-storage-en')
tap('Delete & storage','audio-storage-en');expect('Delete watch original only','delete-options-en');run('shell','input','keyevent','4')
launch('language');tap('Chinese','language-en');print('Continuous review, completed filter, phone storage, English UI and deletion choices passed')
