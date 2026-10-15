#!/usr/bin/env python3
"""Repack the official Termux bootstrap zip into bootstrap-clean.zip.

Only transformation: drop per-entry `extra' fields. Old Android
ZipInputStream chokes on some of them; everything else (names, order,
methods, bytes, unix modes) is preserved bit-for-bit (verified).
Usage: repack-bootstrap.py <raw.zip> <clean.zip>
"""
import sys
import zipfile


def main():
    src, dst = sys.argv[1], sys.argv[2]
    with zipfile.ZipFile(src, 'r') as zin:
        infos = zin.infolist()
        blobs = [(i, zin.read(i.filename)) for i in infos]
    with zipfile.ZipFile(dst, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as zout:
        for info, data in blobs:
            ni = zipfile.ZipInfo(info.filename, date_time=info.date_time)
            ni.compress_type = info.compress_type
            ni.external_attr = info.external_attr
            ni.create_system = info.create_system
            zout.writestr(ni, data)
    print('repacked %d entries -> %s' % (len(blobs), dst))


if __name__ == '__main__':
    main()
