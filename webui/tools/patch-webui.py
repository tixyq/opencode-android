#!/usr/bin/env python3
"""Apply our deterministic patches onto a pristine upstream mirror.

1. index.html: inject mobile tags after the webmanifest link (assert once).
2. <changelog-bundle>.js: point the changelog fetch at our local
   oc-mobile/changelog-empty-01.json (assert single occurrence
   tree-wide; upstream moves this URL between bundles on redeploys).

Usage: patch-webui.py <webui-dir>
"""
import glob
import re
import sys

ROOT = sys.argv[1]


def patch_index():
    p = ROOT + '/index.html'
    s = open(p, encoding='utf-8').read()
    # Upstream adds attributes (e.g. crossorigin) on redeploys; anchor on
    # the stable prefix only, but require the full tag to end on this line.
    m = re.search(r'<link rel="manifest" href="/site\.webmanifest"[^>]*>', s)
    assert m, 'index.html manifest link gone upstream!'
    anchor = m.group(0)
    assert s.count(anchor) == 1, 'index.html anchor ambiguous!'
    inject = (anchor
              + '\n    <link rel="stylesheet" href="/mobile.css" />'
              + '\n    <script src="/enter-newline.js"></script>'
              + '\n    <script src="/mobile.js"></script>')
    open(p, 'w').write(s.replace(anchor, inject))
    print('patched index.html')


def patch_changelog():
    # Minified var name changes every deploy; match the URL itself, in
    # whatever bundle upstream puts it (currently whats-new-*.js).
    hits = []
    for p in glob.glob(ROOT + '/_assets/*.js'):
        s = open(p, encoding='utf-8', errors='replace').read()
        if 'https://opencode.ai/changelog.json' in s:
            hits.append(p)
    assert len(hits) == 1, f'changelog fetch in {len(hits)} bundles: {hits}'
    p = hits[0]
    s = open(p, encoding='utf-8', errors='replace').read()
    old = '`https://opencode.ai/changelog.json`'
    assert s.count(old) == 1, 'changelog URL occurrences != 1'
    s = s.replace(old, '`/oc-mobile/changelog-empty-01.json`')
    open(p, 'w').write(s)
    print('patched', p.split('/')[-1])


patch_index()
patch_changelog()
print('OK')
