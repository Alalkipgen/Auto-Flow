#!/usr/bin/env python3
"""Check production restore bytecode against the *actual* minSdk android.jar.

No source-pattern guesses, desugaring assumptions or third-party dependencies.
This is an API-linkage gate, NOT an Android runtime test.
"""
import io
import struct
import sys
import zipfile
from pathlib import Path


def parse(data):
    f = io.BytesIO(data)
    def u2(): return struct.unpack('>H', f.read(2))[0]
    def skip_attributes():
        for _ in range(u2()):
            u2()
            f.seek(struct.unpack('>I', f.read(4))[0], 1)
    assert f.read(4) == b'\xca\xfe\xba\xbe', 'Not a class file'
    f.read(4)
    cp = [None] * u2()
    i = 1
    while i < len(cp):
        tag = f.read(1)[0]
        if tag == 1: value = f.read(u2()).decode('utf-8', errors='replace')
        elif tag in (3, 4): value = f.read(4)
        elif tag in (5, 6): value = f.read(8)
        elif tag in (7, 8, 16, 19, 20): value = u2()
        elif tag in (9, 10, 11, 12, 17, 18): value = (u2(), u2())
        elif tag == 15: value = (f.read(1)[0], u2())
        else: raise ValueError(f'Unknown constant-pool tag {tag}')
        cp[i] = (tag, value)
        i += 2 if tag in (5, 6) else 1
    def text(index): return cp[index][1]
    def cls(index): return text(text(index)) if index else None
    u2(); u2(); parent = cls(u2())
    interfaces = [cls(u2()) for _ in range(u2())]
    for _ in range(u2()):
        f.read(6); skip_attributes()
    methods = set()
    for _ in range(u2()):
        u2(); methods.add((text(u2()), text(u2()))); skip_attributes()
    refs = set()
    for entry in cp:
        if entry and entry[0] in (10, 11):
            owner, name_type = entry[1]
            name, descriptor = text(name_type)
            refs.add((cls(owner), text(name), text(descriptor)))
    return methods, [x for x in [parent, *interfaces] if x], refs


def main():
    jar_path, classes = map(Path, sys.argv[1:])
    # Scan only restore and its generated helpers, not the desktop-only recovery CLI.
    compiled = list(classes.rglob('ProjectZip*.class'))
    assert compiled, f'Compile :platform:compileKotlinDesktop first: {classes}'
    checked_owners = {'java/nio/file/Files', 'java/nio/file/Path', 'java/io/ByteArrayOutputStream',
                      'java/io/InputStream', 'java/io/OutputStream', 'java/util/stream/Stream'}
    with zipfile.ZipFile(jar_path) as jar:
        cache = {}
        def available(owner, name, descriptor):
            if owner not in cache: cache[owner] = parse(jar.read(owner + '.class'))[:2]
            methods, parents = cache[owner]
            return (name, descriptor) in methods or any(available(p, name, descriptor) for p in parents)
        refs = set().union(*(parse(p.read_bytes())[2] for p in compiled))
        refs = sorted(r for r in refs if r[0] in checked_owners)
        missing = [r for r in refs if not available(*r)]
        for owner, name, descriptor in missing: print(f'UNAVAILABLE: {owner}.{name}{descriptor}')
        if missing: raise SystemExit(1)
        print(f'PASS: {len(refs)} restore I/O method references resolve in {jar_path}; NOT runtime verification')


if __name__ == '__main__': main()
