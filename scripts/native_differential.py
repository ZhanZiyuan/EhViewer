#!/usr/bin/env python3
"""Repeatable P5 differential against git HEAD C, with no C sources shipped in the app.
Requires clang, Cargo and installed host libarchive. Writes only to --output.
"""
import argparse
import ctypes
import json
import os
from pathlib import Path
import random
import subprocess


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--output', type=Path, required=True)
    opts = ap.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = opts.output.resolve()
    out.mkdir(parents=True, exist_ok=True)
    def original(name):
        return subprocess.check_output(['git', 'show', 'c5156a2de34e08fe9d11acd0f7552f6b8189a5db:app/src/main/cpp/' + name], cwd=repo)
    (out/'strnatcmp.c').write_bytes(original('natsort/strnatcmp.c'))
    (out/'strnatcmp.h').write_bytes(original('natsort/strnatcmp.h'))
    # Only the pure original GIF function is compiled; no JNI or platform shims required.
    gif = original('gifutils.c').decode()
    gif = gif[gif.index('#define GIF_HEADER_87A'):gif.index('JNIEXPORT')]
    (out/'gif.c').write_text('#include <stdbool.h>\n#include <string.h>\n' + gif + '\nvoid original_rewrite(void *p, size_t n) { doRewrite(p,n); }\n')
    rng = random.Random(20261010)
    names = ['', '01', '001', '1', '2', '10', ' 2', '2 ', 'A', 'a', '页2.png', '页10.png', '9'*1000, '1'+'0'*1000]
    names += [''.join(rng.choice('aAzZ0123456789 \t页😀') for _ in range(rng.randrange(1, 60))) for _ in range(150)]
    gifs = [b'', b'GIF87a', b'GIF89a', b'not-a-gif']
    # Never run the known undefined 7-byte GIF through old C. Rust covers every short length.
    for _ in range(2000):
        data = bytearray(rng.choice([b'GIF87a', b'GIF89a', b'NOTGIF']))
        data += rng.randbytes(rng.randrange(3, 128)) if hasattr(rng, 'randbytes') else bytes(rng.randrange(256) for _ in range(rng.randrange(3, 128)))
        if len(data) > 15:
            pos = rng.randrange(6, len(data)-8)
            data[pos:pos+9] = bytes([0, 33, 249, 4, 0, rng.randrange(256), rng.randrange(256), 0, 0])
        gifs.append(bytes(data))
    for signed in [True, False]:
        library = out/('old-signed.dylib' if signed else 'old-unsigned.dylib')
        subprocess.run(['clang', '-O2', '-dynamiclib', '-fsigned-char' if signed else '-funsigned-char', str(out/'strnatcmp.c'), str(out/'gif.c'), '-o', str(library)], check=True)
        old = ctypes.CDLL(str(library))
        old.strnatcmp.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
        old.strnatcmp.restype = ctypes.c_int
        old.original_rewrite.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
        signs = [[(lambda n: (n > 0)-(n < 0))(old.strnatcmp(a.encode(), b.encode())) for b in names] for a in names]
        expected = []
        for gif in gifs:
            buffer = ctypes.create_string_buffer(gif)
            old.original_rewrite(buffer, len(gif))
            expected.append(list(buffer.raw[:len(gif)]))
        request = json.dumps({'names': names, 'signed': signed, 'gifs': [list(g) for g in gifs]})
        proc = subprocess.run(['cargo', 'run', '--locked', '--quiet', '--manifest-path', str(repo/'native/rust/Cargo.toml'), '-p', 'ehviewer_core', '--example', 'native_probe'], input=request, text=True, stdout=subprocess.PIPE, check=True, env=os.environ)
        actual = json.loads(proc.stdout)
        assert actual['signs'] == signs, 'natural sort differs'
        assert actual['gifs'] == expected, 'GIF rewrite differs'
        print(json.dumps({'signed_char': signed, 'sort_pairs': len(names)**2, 'gif_vectors': len(gifs), 'result': 'PASS'}))


if __name__ == '__main__':
    main()
