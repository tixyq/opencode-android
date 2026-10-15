#!/usr/bin/env python3
"""Apply our deterministic patches onto a pristine upstream mirror.

1. index.html: inject mobile tags after the webmanifest link (assert once).
2. _assets/index-DjQvpak_.js: point the changelog fetch at our local
   oc-mobile/changelog-empty-01.json (assert single occurrence).

Usage: patch-webui.py <webui-dir>
"""
import sys

ROOT = sys.argv[1]


def patch_index():
    p = ROOT + '/index.html'
    s = open(p, encoding='utf-8').read()
    anchor = '<link rel="manifest" href="/site.webmanifest" />'
    assert s.count(anchor) == 1, 'index.html anchor changed upstream!'
    inject = (anchor
              + '\n    <link rel="stylesheet" href="/mobile.css" />'
              + '\n    <script src="/enter-newline.js"></script>'
              + '\n    <script src="/mobile.js"></script>')
    open(p, 'w').write(s.replace(anchor, inject))
    print('patched index.html')


def patch_changelog():
    import glob
    cands = glob.glob(ROOT + '/_assets/index-*.js')
    assert len(cands) == 1, 'expected exactly one index-*.js bundle'
    p = cands[0]
    s = open(p, encoding='utf-8', errors='replace').read()
    old = 'var Zg=`https://opencode.ai/changelog.json`'
    assert s.count(old) == 1, 'changelog fetch changed upstream!'
    s = s.replace(old, 'var Zg=`/oc-mobile/changelog-empty-01.json`')
    open(p, 'w').write(s)
    print('patched', p.split('/')[-1])


patch_index()
patch_changelog()
print('OK')
