#!/usr/bin/env python3
"""Fetch the pinned upstream KWS files; models stay in ignored work/ and are hash checked."""
import hashlib,json,urllib.request,concurrent.futures
from pathlib import Path
root=Path(__file__).resolve().parents[1];target=root/'work/watch-wakeword-assets/wakeword';target.mkdir(parents=True,exist_ok=True)
def fetch(row):
 path=(root/'work/watch-wakeword-tests'/row['name'] if row['name'].startswith('test_wavs/') else target/row['name']);path.parent.mkdir(parents=True,exist_ok=True)
 if path.exists() and hashlib.sha256(path.read_bytes()).hexdigest()==row['sha256']:return
 temp=Path(str(path)+'.partial')
 with urllib.request.urlopen(row['url'],timeout=40) as response,temp.open('wb') as out:
  while True:
   b=response.read(65536)
   if not b:break
   out.write(b)
 if temp.stat().st_size!=row['bytes'] or hashlib.sha256(temp.read_bytes()).hexdigest()!=row['sha256']:raise RuntimeError('Model verification failed: '+row['name'])
 temp.replace(path);print('Verified '+row['name'],flush=True)
with concurrent.futures.ThreadPoolExecutor(max_workers=3) as pool:list(pool.map(fetch,json.loads((root/'tools/watch-wakeword-model.lock.json').read_text())['files']))
