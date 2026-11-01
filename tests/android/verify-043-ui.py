"""Public fixture UI checks; emulator only. Deletion dialogs are canceled."""
import os,shutil,subprocess,time,re,xml.etree.ElementTree as ET
from pathlib import Path
adb=os.environ.get('ADB') or shutil.which('adb') or str(Path(os.environ.get('ANDROID_HOME',str(Path.home()/'Library/Android/sdk')))/'platform-tools/adb')
base=[adb,'-s','emulator-5554'];out=Path('outputs/mindpebble-0.4.3');out.mkdir(exist_ok=True)
def run(*args):return subprocess.run(base+list(args),check=True,capture_output=True).stdout
assert run('shell','getprop','ro.hardware').strip()==b'ranchu'
def snapshot(name):
 run('shell','uiautomator','dump','/sdcard/ui043.xml');xml=run('exec-out','cat','/sdcard/ui043.xml');(out/(name+'.xml')).write_bytes(xml);(out/(name+'.png')).write_bytes(run('exec-out','screencap','-p'));return ET.fromstring(xml)
def tap(label,name):
 for _ in range(8):
  tree=snapshot(name)
  for n in tree.iter('node'):
   if n.get('text')==label:
    x1,y1,x2,y2=map(int,re.findall(r'\d+',n.get('bounds')))
    if x2>x1 and y2>y1:
     run('shell','input','tap',str((x1+x2)//2),str((y1+y2)//2));time.sleep(.3);return
  run('shell','input','swipe','540','1700','540','650','250')
 raise AssertionError(label)
def expect(label,name):
 tree=snapshot(name);assert any(n.get('text')==label for n in tree.iter('node')),label
def launch():
 run('shell','am','force-stop','org.diy.liferecorder');run('shell','am','start','-n','org.diy.liferecorder/.MainActivity','--es','page','settings');time.sleep(.5)
result=run('shell','am','instrument','-w','-e','phase','ui043','org.diy.liferecorder.integration/org.diy.liferecorder.IntegrationTests');assert b'INSTRUMENTATION_CODE: -1' in result,result
launch();tap('原始音频管理','settings-zh');tap('待清理','manager-zh');
expect('这段录音可能可以清理','cleanup-zh')
tap('删除与存储位置','cleanup-zh');expect('仅删除手机音频，保留转写与待办','delete-options-zh');run('shell','input','keyevent','4')
tap('手机','manager-zh');tap('回看记录','phone-records-zh')
# Scroll to the speaker controls, confirming no upload or transcription is implied.
tap('重新匹配声纹（保留文字）','speaker-zh');expect('不上传资料。只重新匹配声纹，保留文字、时间和已确认的说话人。\n只在本机执行，不产生 API 请求。','rematch-dialog-zh');run('shell','input','keyevent','4')
tap('用本句补充本人声纹','speaker-sample-zh');expect('确认这句只有本人说话？','supplement-dialog-zh');run('shell','input','keyevent','4')
result=run('shell','am','instrument','-w','-e','phase','ui043','-e','language','en','org.diy.liferecorder.integration/org.diy.liferecorder.IntegrationTests');assert b'INSTRUMENTATION_CODE: -1' in result,result
launch();tap('Audio storage','settings-en');tap('Cleanup suggestions','manager-en');
expect('This recording may be ready to remove','cleanup-en');expect('No speech was detected locally. Listen first, then decide whether to delete.','cleanup-en')
tap('Phone','manager-en');tap('Review record','phone-records-en')
tap('Add this phrase to my voice profile','speaker-en');expect('Is this phrase only your voice?','supplement-dialog-en');run('shell','input','keyevent','4')
print('Chinese and English cleanup filter, deletion choices, speaker-only confirmation and voice supplement confirmation passed')
