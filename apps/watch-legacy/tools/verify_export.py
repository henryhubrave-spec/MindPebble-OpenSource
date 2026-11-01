#!/usr/bin/env python3
"""Validate an exported recordings folder. Never edits or uploads recordings."""
import json
import shutil
import subprocess
import sys
from pathlib import Path

def main():
    if len(sys.argv) != 2:
        print('Usage: python3 tools/verify_export.py /path/to/recordings')
        return 2
    folder = Path(sys.argv[1])
    if not folder.is_dir():
        print('ERROR: recordings directory does not exist')
        return 2
    ffprobe = shutil.which('ffprobe')
    errors, seen = [], set()
    files = sorted(folder.glob('*.m4a'))
    if not files:
        errors.append('No completed .m4a files')
    for audio in files:
        try:
            meta = json.loads(audio.with_name(audio.name + '.json').read_text(encoding='utf-8'))
            key = (meta['session_id'], meta['sequence'])
            if key in seen:
                raise ValueError('Duplicate session/sequence')
            seen.add(key)
            if meta['schema_version'] != 1 or meta['audio_file'] != audio.name:
                raise ValueError('Schema or filename mismatch')
            if meta['bytes'] != audio.stat().st_size or meta['bytes'] <= 0:
                raise ValueError('Audio byte count mismatch')
            if meta['capture_elapsed_ms'] < 0:
                raise ValueError('Negative monotonic capture duration')
            if ffprobe:
                p = subprocess.run([ffprobe, '-v', 'error', '-show_streams', '-show_format', '-of', 'json', str(audio)], capture_output=True, text=True, check=True)
                probe = json.loads(p.stdout)
                streams = [s for s in probe['streams'] if s['codec_type'] == 'audio']
                if len(streams) != 1 or streams[0]['codec_name'] != 'aac' or streams[0]['channels'] != 1:
                    raise ValueError('Expected one mono AAC stream')
                duration = float(probe['format']['duration'])
                if duration <= 0 or abs(duration - meta['capture_elapsed_ms'] / 1000) > 5:
                    raise ValueError('Media duration differs from capture timer by more than 5 seconds')
                if meta['reason'] == 'segment' and abs(duration - 300) > 10:
                    raise ValueError('Full segment duration differs from 300 seconds by more than 10 seconds')
                print(f"OK {audio.name}: {duration:.3f}s, AAC {streams[0]['sample_rate']}Hz mono, reason={meta['reason']}")
            else:
                print(f'METADATA OK {audio.name}; media not inspected (ffprobe missing)')
        except Exception as error:
            errors.append(f'{audio.name}: {error}')
    for partial in sorted(folder.glob('*.part')):
        print('WARNING incomplete recording retained:', partial.name)
    for temp in sorted(folder.glob('*.tmp')):
        print('WARNING temporary metadata retained:', temp.name)
    for error in errors:
        print('ERROR', error)
    if errors:
        return 1
    if not ffprobe:
        print('INCOMPLETE: install FFmpeg/ffprobe to check actual media codec and duration')
        return 2
    print(f'PASS: {len(files)} completed files and metadata inspected. Listening, device stability and battery life still require real-device tests.')
    return 0

if __name__ == '__main__':
    sys.exit(main())
