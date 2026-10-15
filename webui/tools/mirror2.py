import re, os, subprocess, time

BASE = 'https://beta.opencode.ai'
import sys
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/oc-web')
UA = 'Mozilla/5.0 (Linux; Android 16; CPH2727) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
REL = re.compile(r'import\([\'"`]\./([^\'"`]+)[\'"`]\)')
LOG = sys.stdout

seen = set(os.listdir(ROOT + '/_assets'))
queue = []
for f in os.listdir(ROOT + '/_assets'):
    if f.endswith('.js'):
        queue += ['/_assets/' + m for m in
                  REL.findall(open(ROOT + '/_assets/' + f, encoding='utf-8', errors='ignore').read())]

total, count = 0, 0
while queue:
    p = queue.pop(0)
    fn = p.split('/')[-1]
    if fn in seen:
        continue
    seen.add(fn)
    r = subprocess.run(['curl', '-s', '-m', '30', '-A', UA, '-e', BASE + '/',
                        BASE + p, '-o', ROOT + '/_assets/' + fn,
                        '-w', '%{http_code} %{size_download}'],
                       capture_output=True, text=True)
    code, _, size = r.stdout.partition(' ')
    if code != '200':
        print('MISS', p, r.stdout.strip(), flush=True)
        LOG.write('MISS %s %s\n' % (p, r.stdout.strip()))
        continue
    total += int(size or 0)
    count += 1
    if fn.endswith('.js'):
        queue += ['/_assets/' + m for m in
                  REL.findall(open(ROOT + '/_assets/' + fn, encoding='utf-8', errors='ignore').read())]
    time.sleep(0.15)

LOG.write('DONE extra files: %d bytes: %d\n' % (count, total))
print('DONE extra files:', count, 'bytes:', total, flush=True)
