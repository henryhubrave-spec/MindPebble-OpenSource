#!/usr/bin/env python3
"""Fetch pinned build/runtime dependencies; no private data or model calls."""
from pathlib import Path
import hashlib,json,urllib.request,zipfile
root=Path(__file__).resolve().parents[1]
deps=root/'work/deps';deps.mkdir(parents=True,exist_ok=True)
for item in json.loads((root/'tools/dependencies.lock.json').read_text()):
 target=deps/item['filename']
 if not target.exists() or hashlib.sha256(target.read_bytes()).hexdigest()!=item['sha256']:
  pending=target.with_suffix(target.suffix+'.download')
  try:
   urllib.request.urlretrieve(item['url'],pending)
   if hashlib.sha256(pending.read_bytes()).hexdigest()!=item['sha256']:raise RuntimeError('Dependency checksum mismatch: '+item['filename'])
   pending.replace(target)
  finally:
   if pending.exists():pending.unlink()
 print('Verified '+item['filename'])
for artifact,folder in [('webrtc-2.0.10.aar','webrtc'),('sherpa-onnx-1.13.8.aar','sherpa')]:
 with zipfile.ZipFile(deps/artifact) as archive:
  for name in archive.namelist():
   if name=='classes.jar' or name.startswith('jni/') and name.endswith('.so'):
    parts=Path(name).parts
    if '..' in parts or Path(name).is_absolute():raise RuntimeError('Unsafe dependency member')
    dest=deps/folder/name;dest.parent.mkdir(parents=True,exist_ok=True);dest.write_bytes(archive.read(name))
