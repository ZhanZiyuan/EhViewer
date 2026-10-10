#!/usr/bin/env python3
"""Verify produced Android Java/Kotlin class files, independently of the build JDK."""
import json
import struct
from pathlib import Path

roots = {
    'app': [Path('app/build/intermediates') / kind / variant
            for kind in ('javac', 'built_in_kotlinc') for variant in ('debug', 'release')],
    'benchmark': [Path('benchmark/build/intermediates/built_in_kotlinc/nonMinifiedRelease')],
    **{name: [Path('core') / name / 'build/classes/kotlin/android/main']
       for name in ('common', 'data', 'i18n', 'ui')},
}
counts = {}
for name, directories in roots.items():
    files = [path for directory in directories for path in directory.rglob('*.class')]
    if not files:
        raise RuntimeError('No compiled Android classes for ' + name)
    for path in files:
        data = path.read_bytes()
        if data[:4] != b'\xca\xfe\xba\xbe' or struct.unpack('>H', data[6:8])[0] != 61:
            raise RuntimeError('Expected Java 17 class: ' + str(path))
    counts[name] = len(files)
print(json.dumps({'androidClassMajor': 61, 'classes': counts}, indent=2))
