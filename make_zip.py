#!/usr/bin/env python3
"""Packs pkg/TrunkDeck (made by assemble_pkg.sh) into the release files in dist/:
   TrunkDeck-<version>-windows.zip   (without the linux/ folder)
   TrunkDeck-<version>-linux.tar.gz  (without the windows/ folder; scripts executable)
Both contain the whole app/ folder. Timestamps are fixed so a rebuild of the same files gives the same bytes.
Shell scripts in the Linux archive are written as LF and checked again after the archive is built."""
import os, re, stat, tarfile, zipfile, gzip, io, hashlib
here = os.path.dirname(os.path.abspath(__file__)); root = os.path.join(here, 'pkg')
EPOCH = 1793332800  # 2026-10-30 00:00 UTC

def files(skip):
    for d, dirs, fs in os.walk(root):
        dirs.sort()
        for f in sorted(fs):
            p = os.path.join(d, f); arc = os.path.relpath(p, root).replace(os.sep, '/')
            if arc.split('/')[1] == skip: continue
            yield p, arc

def mode(arc): return 0o755 if arc.endswith('.sh') else 0o644

def linux_script(arc):
    """Files Linux runs or installs: shell scripts, desktop files, udev rules and conf."""
    low = arc.lower()
    return low.endswith(('.sh', '.desktop', '.rules')) or ('/linux/' in '/' + low and low.endswith('.conf'))

def normalize_linux(arc, data):
    """Turn CRLF or bare CR into LF. Windows .bat/.cmd are not passed here."""
    if linux_script(arc):
        data = data.replace(b'\r\n', b'\n').replace(b'\r', b'\n')
    return data

def require_lf(arc, data):
    if arc.endswith('.sh') and b'\r' in data:
        raise SystemExit(f'ERROR: {arc} contains CR line endings. Linux shell scripts must be LF.')

def verify_linux_archive(path):
    """Fails the build if a packed shell script has a CR or lost its executable bit."""
    with tarfile.open(path, 'r:gz') as t:
        found = False
        for m in t.getmembers():
            if not m.name.endswith('.sh'): continue
            found = True
            if m.mode & 0o111 == 0:
                raise SystemExit(f'ERROR: {m.name} is not executable in {os.path.basename(path)}')
            body = t.extractfile(m).read()
            if b'\r' in body:
                raise SystemExit(f'ERROR: {m.name} contains CR in {os.path.basename(path)}')
        if not found:
            raise SystemExit(f'ERROR: {os.path.basename(path)} contains no shell scripts')

def _self_test():
    raw = b'#!/bin/sh\r\nset -e\r\n'
    clean = normalize_linux('TrunkDeck/linux/run.sh', raw)
    if clean != b'#!/bin/sh\nset -e\n':
        raise SystemExit('ERROR: LF normalizer did not strip CR')
    try:
        require_lf('TrunkDeck/linux/run.sh', raw)
    except SystemExit:
        pass
    else:
        raise SystemExit('ERROR: CR check did not fail')
    require_lf('TrunkDeck/linux/run.sh', clean)
    if normalize_linux('TrunkDeck/windows/Install.bat', b'@echo off\r\n') != b'@echo off\r\n':
        raise SystemExit('ERROR: normalizer changed a .bat file')

def main():
    _self_test()
    ver = re.search(r'Implementation-Version: ([0-9.]+)', open(os.path.join(here, 'build.sh')).read()).group(1)
    dist = os.path.join(here, 'dist'); os.makedirs(dist, exist_ok=True)
    win = os.path.join(dist, f'TrunkDeck-{ver}-windows.zip')
    with zipfile.ZipFile(win, 'w', zipfile.ZIP_DEFLATED, compresslevel=9) as z:
        for p, arc in files('linux'):
            with open(p, 'rb') as fh: data = fh.read()
            zi = zipfile.ZipInfo(arc, date_time=(2026, 10, 30, 0, 0, 0)); zi.compress_type = zipfile.ZIP_DEFLATED
            zi.external_attr = (stat.S_IFREG | mode(arc)) << 16; zi.create_system = 3
            z.writestr(zi, data)
    lin = os.path.join(dist, f'TrunkDeck-{ver}-linux.tar.gz')
    buf = io.BytesIO()
    with tarfile.open(fileobj=buf, mode='w', format=tarfile.PAX_FORMAT) as t:
        for p, arc in files('windows'):
            with open(p, 'rb') as fh: data = fh.read()
            data = normalize_linux(arc, data)
            require_lf(arc, data)
            ti = tarfile.TarInfo(arc); ti.size = len(data); ti.mtime = EPOCH; ti.mode = mode(arc)
            ti.uid = ti.gid = 0; ti.uname = ti.gname = ''
            t.addfile(ti, io.BytesIO(data))
    with open(lin, 'wb') as out:
        with gzip.GzipFile(filename='', mode='wb', fileobj=out, mtime=EPOCH, compresslevel=9) as g: g.write(buf.getvalue())
    verify_linux_archive(lin)
    with open(os.path.join(dist, 'SHA256SUMS.txt'), 'w') as s:
        for f in (win, lin):
            h = hashlib.sha256(open(f, 'rb').read()).hexdigest(); s.write(f'{h}  {os.path.basename(f)}\n')
            print(f'{os.path.basename(f)}  {os.path.getsize(f)} bytes  sha256 {h}')

if __name__ == '__main__':
    main()
