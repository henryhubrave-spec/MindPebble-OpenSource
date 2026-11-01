"""Read-only UI assertions and screenshots on a dedicated emulator."""
import argparse,subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--adb',default='adb');p.add_argument('--serial',default='emulator-5554');p.add_argument('--output',default='outputs/mindpebble-0.4.1');a=p.parse_args();base=[a.adb,'-s',a.serial];out=Path(a.output);out.mkdir(parents=True,exist_ok=True)
def run(*args):return subprocess.run(base+list(args),check=True,capture_output=True).stdout
if not a.serial.startswith('emulator-') or run('shell','getprop','ro.hardware').strip()!=b'ranchu':raise SystemExit('Dedicated emulator only')
def snapshot(name):
 run('shell','uiautomator','dump','/sdcard/tasks-ui.xml');xml=run('exec-out','cat','/sdcard/tasks-ui.xml');(out/(name+'.xml')).write_bytes(xml);(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'));return ET.fromstring(xml)
def tap_text(label,name):
 for attempt in range(7):
  tree=snapshot(name)
  for n in tree.iter('node'):
   if n.get('text')==label or n.get('content-desc')==label:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    if x2>x1 and y2>y1:run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.3);return
  run('shell','input','swipe','550','1450','550','800','250')
 raise AssertionError('Missing '+label)
def launch(page='settings'):
 run('shell','am','force-stop','org.diy.liferecorder');run('shell','am','start','-n','org.diy.liferecorder/.MainActivity','--es','page',page);time.sleep(.5)
launch();t=snapshot('settings');assert any(n.get('text')=='设备与采集' for n in t.iter('node'));assert not any(n.get('text')=='API 密钥 (Token)' for n in t.iter('node'));tap_text('手表连接与文件接收','settings-entry');t=snapshot('settings-device');assert any(n.get('text')=='‹ 返回高级设置' for n in t.iter('node'));tap_text('解除 App 配对','settings-device');t=snapshot('flat-dialog');assert any(n.get('text')=='取消' for n in t.iter('node'));tap_text('取消','flat-dialog');tap_text('‹ 返回高级设置','settings-device');tap_text('本人声音与声纹','settings-voice-entry');t=snapshot('voice-settings');assert any(n.get('text')=='直接录制本人声音' for n in t.iter('node'));tap_text('直接录制本人声音','voice-settings');t=snapshot('voice-recording');assert any(n.get('text')=='开始录制本人声音' for n in t.iter('node'));run('shell','input','keyevent','4');tap_text('任务中心','voice-settings');t=snapshot('task-center');assert any(n.get('text')=='立即处理等待中的离线任务' for n in t.iter('node'));print('Grouped settings, direct enrollment, task center and flat dialog passed',flush=True)
