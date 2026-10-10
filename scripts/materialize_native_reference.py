#!/usr/bin/env python3
"""Materialize the audited P0 C implementation outside this checkout for differential tests."""
import argparse
from pathlib import Path
import subprocess

REFERENCE = 'c5156a2de34e08fe9d11acd0f7552f6b8189a5db'
ap = argparse.ArgumentParser()
ap.add_argument('--output', type=Path, required=True)
opts = ap.parse_args()
repo = Path(__file__).resolve().parent.parent
out = opts.output.resolve()
if out == repo or repo in out.parents:
    ap.error('Reference must be outside the source repository')
for name in ['archive.c', 'hash.c', 'gifutils.c', 'ehviewer.h', 'natsort/strnatcmp.c', 'natsort/strnatcmp.h']:
    path = 'app/src/main/cpp/'+name
    content = subprocess.check_output(['git', 'show', REFERENCE+':'+path], cwd=repo)
    destination = out/path
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists() and destination.read_bytes() != content:
        raise RuntimeError('Refusing to overwrite differing reference: '+str(destination))
    destination.write_bytes(content)
print(REFERENCE)
