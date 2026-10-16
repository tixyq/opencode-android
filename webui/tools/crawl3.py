import re, os, subprocess

BASE = 'https://beta.opencode.ai'
import sys
ROOT = sys.argv[1] if len(sys.argv) > 1 else os.path.expanduser('~/oc-web')
UA = 'Mozilla/5.0 (Linux; Android 16; CPH2727) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36'
# bare hashed filenames: name-HASH8.ext
TOK = re.compile(r'([A-Za-z0-9][A-Za-z0-9_@.\-]*-[A-Za-z0-9_\-]{6,}\.(?:js|css|woff2?|ttf|png|svg|ico))')
# absolute paths to other static dirs
ABS = re.compile(r'["\'(](/(?:fonts|images|icons|_assets|media)/[A-Za-z0-9_@.\-/]+)')
LOG = sys.stdout

have = set()
for dirpath, _, files in os.walk(ROOT):
    for f in files:
        have.add(f)

def fetch(url, local):
    os.makedirs(os.path.dirname(local), exist_ok=True)
    r = subprocess.run(['curl', '-sL', '-m', '30', '-A', UA, '-e', BASE + '/',
                        url, '-o', local, '-w', '%{http_code}'],
                       capture_output=True, text=True)
    return r.stdout.strip() == '200'

new_total = 0
for rnd in range(4):
    want = {}
    for dirpath, _, files in os.walk(ROOT + '/_assets'):
        for f in files:
            if not f.endswith('.js'):
                continue
            try:
                txt = open(os.path.join(dirpath, f), encoding='utf-8', errors='ignore').read()
            except Exception:
                continue
            for t in TOK.findall(txt):
                if t not in have:
                    want.setdefault('/_assets/' + t, t)
            for a in ABS.findall(txt):
                fn = a.split('/')[-1]
                if fn not in have:
                    want.setdefault(a, fn)
    fresh = [(u, fn) for u, fn in want.items() if fn not in have]
    LOG.write('round %d candidates %d\n' % (rnd, len(fresh)))
    got = 0
    for url, fn in fresh:
        if url.startswith('/_assets/'):
            local = ROOT + '/_assets/' + fn
        else:
            local = ROOT + url
        if fetch(BASE + url, local):
            have.add(fn)
            got += 1
            new_total += 1
        else:
            LOG.write('MISS %s\n' % url)
    LOG.write('round %d downloaded %d\n' % (rnd, got))
    if got == 0:
        break

LOG.write('DONE new=%d\n' % new_total)
print('DONE new=%d' % new_total, flush=True)
