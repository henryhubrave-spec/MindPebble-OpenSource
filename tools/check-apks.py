#!/usr/bin/env python3
"""Verify APK signatures and write a local checksum manifest, without private content."""
from pathlib import Path
import hashlib,json,os,subprocess
root=Path(__file__).resolve().parents[1]
signer=Path(os.environ['BUILD_TOOLS'])/'apksigner'
rows=[]
for name in ['watch2-recorder-0.4.5-debug.apk','life-recorder-phone-0.5.4-debug.apk']:
 path=root/'outputs'/name
 output=subprocess.check_output([str(signer),'verify','--print-certs',str(path)],text=True)
 cert=next(line.split(': ',1)[1] for line in output.splitlines() if 'certificate SHA-256 digest:' in line)
 rows.append({'apk':name,'sha256':hashlib.sha256(path.read_bytes()).hexdigest(),'certificate_sha256':cert})
(root/'outputs/apk-verification.json').write_text(json.dumps(rows,indent=2)+'\n')
(root/'outputs/SHA256SUMS').write_text(''.join(row['sha256']+'  '+row['apk']+'\n' for row in rows))
print(json.dumps(rows,indent=2))
