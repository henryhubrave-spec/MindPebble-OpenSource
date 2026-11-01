#!/usr/bin/env python3
"""Export an audited source snapshot, never Git history or local data."""
import argparse,hashlib,json,re,shutil,zipfile
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
TOP={'.gitignore','README.md','LICENSE','CONTRIBUTING.md','SECURITY.md','THIRD_PARTY_ASSETS.md','requirements-dev.txt'}
DIRS={'.github','apps','config','docs','experiments','licenses','packages','services','tests','tools'}
EXT={'.md','.txt','.java','.xml','.json','.py','.sh','.yml','.yaml','.csv','.html','.ttf'}
DENY_PARTS={'.git','work','outputs','build','.gradle','__pycache__','node_modules','recordings','private-data','runtime-data','logs'}
PATTERNS=[re.compile(x,re.I) for x in [r'/'+r'Users/[^/\s]+/',r'/'+r'home/[^/\s]+/',r'-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----',r'\b(?:ghp_|github_pat_|sk-(?:proj-)?)[A-Za-z0-9_-]{20,}',r'\bAKIA[A-Z0-9]{16}\b']]
EXCLUDED={'tools/render-social-cards.py','apps/phone/app/src/main/assets/design/logo.png','apps/phone/app/src/main/assets/design/morning.jpg'}

def export(dest):
    dest=dest.resolve()
    if dest.exists(): raise SystemExit('Destination already exists; choose a new empty path')
    files=[]
    for p in sorted(ROOT.rglob('*')):
        rel=p.relative_to(ROOT)
        if any(x in DENY_PARTS for x in rel.parts): continue
        if p.is_symlink(): raise SystemExit('Symlink refused: '+str(rel))
        if not p.is_file() or str(rel) in EXCLUDED: continue
        if not (str(rel) in TOP or rel.parts[0] in DIRS): continue
        image=rel.parts[:2]==('social','xiaohongshu') and p.suffix in {'.png','.jpg'}
        if p.suffix not in EXT and str(rel) not in TOP and p.name not in {'.gitignore','LICENSE'} and not image: raise SystemExit('Unreviewed file type: '+str(rel))
        if p.suffix not in {'.png','.jpg','.ttf'}:
            content=p.read_text(encoding='utf-8')
            for pattern in PATTERNS:
                if pattern.search(content): raise SystemExit('Sensitive content pattern in '+str(rel))
        files.append((p,rel))
    dest.mkdir(parents=True)
    manifest=[]
    for p,rel in files:
        out=dest/rel;out.parent.mkdir(parents=True,exist_ok=True);shutil.copy2(p,out)
        manifest.append({'path':rel.as_posix(),'sha256':hashlib.sha256(out.read_bytes()).hexdigest(),'bytes':out.stat().st_size})
    (dest/'SOURCE_MANIFEST.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
    archive=dest.with_suffix('.zip')
    if archive.exists(): raise SystemExit('Archive already exists')
    with zipfile.ZipFile(archive,'w',zipfile.ZIP_DEFLATED) as z:
        for p in sorted(dest.rglob('*')):
            if p.is_file(): z.write(p,dest.name+'/'+p.relative_to(dest).as_posix())
    digest=hashlib.sha256(archive.read_bytes()).hexdigest()
    archive.with_suffix('.zip.sha256').write_text(digest+'  '+archive.name+'\n')
    print(json.dumps({'files':len(files),'source':str(dest),'archive':str(archive),'sha256':digest},ensure_ascii=False))

if __name__=='__main__':
    ap=argparse.ArgumentParser(description=__doc__);ap.add_argument('--dest',type=Path,required=True);export(ap.parse_args().dest)
