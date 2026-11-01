#!/usr/bin/env python3
"""Create an OFFLINE speech-retention preview and reversible source time map.

Requires ffmpeg on PATH, and the optional requirements.txt. No network calls.
Sources are never removed or overwritten. This does not validate missed speech.
"""
import argparse,hashlib,json,shutil,subprocess,sys
from pathlib import Path
from retention import retained_intervals

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('sources',nargs='+',type=Path)
    parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--mode',type=int,choices=range(4),default=0)
    args=parser.parse_args()
    import webrtcvad
    ffmpeg=shutil.which('ffmpeg')
    if not ffmpeg:raise SystemExit('ffmpeg missing')
    if not all(p.is_file() for p in args.sources):raise SystemExit('Source file missing')
    # A fresh destination prevents overwriting an earlier preview or a source file.
    args.output.mkdir(parents=True,exist_ok=False)
    rate,frame_ms=16000,20
    bytes_per_frame=rate*frame_ms//1000*2
    dest=args.output/'speech-only-preview.m4a'
    raw=args.output/'preview-temporary.pcm'
    output_samples=0;input_samples=0;mapping=[];per_source=[]
    try:
        with raw.open('wb') as writer:
            for source in args.sources:
                pcm=subprocess.run([ffmpeg,'-nostdin','-v','error','-i',str(source),'-ar',str(rate),'-ac','1','-f','s16le','pipe:1'],capture_output=True,check=True).stdout
                count=len(pcm)//2;input_samples+=count
                vad=webrtcvad.Vad(args.mode);flags=[]
                for offset in range(0,len(pcm),bytes_per_frame):
                    frame=pcm[offset:offset+bytes_per_frame]
                    frame=frame+b'\0'*(bytes_per_frame-len(frame))
                    flags.append(vad.is_speech(frame,rate))
                spans=retained_intervals(flags,total_ms=count*1000/rate)
                kept=0
                for start_ms,end_ms in spans:
                    a=max(0,int(start_ms*rate/1000));b=min(count,int(round(end_ms*rate/1000)))
                    if b<=a:continue
                    writer.write(pcm[a*2:b*2]);n=b-a
                    mapping.append({'source_file':source.name,'source_start_sample':a,'source_end_sample':b,'output_start_sample':output_samples,'output_end_sample':output_samples+n,'source_start_ms':a*1000/rate,'source_end_ms':b*1000/rate})
                    output_samples+=n;kept+=n
                per_source.append({'file':source.name,'sha256':hashlib.sha256(source.read_bytes()).hexdigest(),'decoded_seconds':count/rate,'retained_seconds':kept/rate,'intervals':len(spans)})
        if output_samples:
            subprocess.run([ffmpeg,'-nostdin','-v','error','-f','s16le','-ar',str(rate),'-ac','1','-i',str(raw),'-c:a','aac','-b:a','32k',str(dest)],check=True)
            subprocess.run([ffmpeg,'-nostdin','-v','error','-i',str(dest),'-f','null','-'],check=True)
        source_bytes=sum(p.stat().st_size for p in args.sources)
        result={'schema_version':1,'experiment_only':True,'detector':'webrtcvad-wheels','detector_version':'2.0.14','mode':args.mode,'frame_ms':frame_ms,'pre_ms':500,'post_ms':800,'merge_ms':700,'sample_rate_hz':rate,'source_seconds':input_samples/rate,'retained_seconds':output_samples/rate,'retained_intervals':len(mapping),'source_audio_bytes':source_bytes,'preview_audio_bytes':dest.stat().st_size if dest.exists() else 0,'removed_duration_percent':100*(1-output_samples/input_samples) if input_samples else 0,'byte_reduction_percent':100*(1-(dest.stat().st_size if dest.exists() else 0)/source_bytes) if source_bytes else 0,'sources':per_source,'mapping':mapping,'limitations':['No speech-recall measurement; listen against source.','Android integration and battery use not measured.','Removed intervals are not recoverable from preview; source files remain unchanged.','This is not a trained personal-speaker filter.']}
        (args.output/'time-map.json').write_text(json.dumps(result,ensure_ascii=False,indent=2)+'\n')
        summary={k:v for k,v in result.items() if k not in ('mapping','sources')}
        print(json.dumps(summary,ensure_ascii=False,indent=2))
    finally:
        if raw.exists():raw.unlink() # Only our own disposable, derived PCM scratch.

if __name__=='__main__':main()
