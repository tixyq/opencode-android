#!/usr/bin/env python3
"""Recursive mirror crawler for the upstream web UI (beta.opencode.ai).

Walks index.html + discovered /_assets and /icons references, same as the
historical mirror.py this was adapted from. Usage: mirror.py <destdir>
Only network input; verification against MANIFEST.sha256 happens in
fetch-webui.sh afterwards.
"""
import os
import re
import subprocess
import sys
import time

BASE = 'https://beta.opencode.ai'
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/oc-web')
UA = 'Mozilla/5.0 (Linux; Android 16; CPH2727) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
REF = re.compile(r'["\'(](/(?:_assets|icons)/[A-Za-z0-9_@.\-/]+)')

seen = set()
queue = []
html = open(ROOT + '/index.html', 'rb').read().decode()
queue += REF.findall(html)
queue += ['/site.webmanifest', '/social-share.png']

total = 0
count = 0
while queue:
    path = queue.pop(0)
    if path in seen:
        continue
    seen.add(path)
    local = ROOT + path
    os.makedirs(os.path.dirname(local), exist_ok=True)
    r = subprocess.run(['curl', '-s', '-m', '30', '-A', UA, '-e', BASE + '/',
                        BASE + path, '-o', local, '-w', '%{http_code} %{size_download}'],
                       capture_output=True, text=True)
    code, _, size = r.stdout.partition(' ')
    if code != '200':
        print('MISS', path, r.stdout.strip())
        continue
    n = int(size or 0)
    total += n
    count += 1
    if path.endswith('.js') or path.endswith('.webmanifest'):
        try:
            queue += REF.findall(open(local, 'rb').read().decode())
        except Exception:
            pass
    time.sleep(0.2)

print('files:', count, 'bytes:', total)
