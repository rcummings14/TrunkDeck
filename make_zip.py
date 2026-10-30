#!/usr/bin/env python3
"""Packs pkg/TrunkDeck (made by assemble_pkg.sh) into the release files in dist/:
   TrunkDeck-<version>-windows.zip   (without the linux/ folder)
   TrunkDeck-<version>-linux.tar.gz  (without the windows/ folder; scripts executable)
Both contain the whole app/ folder. Timestamps are fixed so a rebuild of the same files gives the same bytes."""
import os, re, stat, tarfile, zipfile, gzip, io, hashlib
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, 'pkg')
ver = re.search(r'Implementation-Version: ([0-9.]+)', open(os.path.join(here, 'build.sh')).read()).group(1)
dist = os.path.join(here, 'dist'); os.makedirs(dist, exist_ok=True)
EPOCH = 1793332800  # 2026-10-30 00:00 UTC
def files(skip):
    for d, dirs, fs in os.walk(root):
        dirs.sort()
        for f in sorted(fs):
            p = os.path.join(d, f); arc = os.path.relpath(p, root).replace(os.sep, '/')
            if arc.split('/')[1] == skip: continue
            yield p, arc
def mode(arc): return 0o755 if arc.endswith('.sh') else 0o644
win = os.path.join(dist, f'TrunkDeck-{ver}-windows.zip')
with zipfile.ZipFile(win, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
    for p, arc in files('linux'):
        zi = zipfile.ZipInfo(arc, date_time=(2026, 10, 30, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
        zi.external_attr = (stat.S_IFREG | mode(arc)) << 16; zi.create_system = 3
        with open(p, 'rb') as fh: z.writestr(zi, fh.read())
lin = os.path.join(dist, f'TrunkDeck-{ver}-linux.tar.gz')
buf = io.BytesIO()
with tarfile.open(fileobj=buf, mode='w', format=tarfile.PAX_FORMAT) as t:
    for p, arc in files('windows'):
        ti = tarfile.TarInfo(arc); ti.size = os.path.getsize(p); ti.mtime = EPOCH; ti.mode = mode(arc)
        ti.uid = ti.gid = 0; ti.uname = ti.gname = ''
        with open(p, 'rb') as fh: t.addfile(ti, fh)
with open(lin, 'wb') as out:
    with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=EPOCH, compresslevel=9) as g: g.write(buf.getvalue())
with open(os.path.join(dist, 'SHA256SUMS.txt'), 'w') as s:
    for f in (win, lin):
        h = hashlib.sha256(open(f, 'rb').read()).hexdigest(); s.write(f'{h}  {os.path.basename(f)}\n')
        print(f'{os.path.basename(f)}  {os.path.getsize(f)} bytes  sha256 {h}')
