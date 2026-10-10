#!/usr/bin/env python3
"""Same JNI access pattern and macOS process RSS for original C and Release Rust."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import zipfile


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--c-library', type=Path, required=True)
    ap.add_argument('--rust-library', type=Path, required=True)
    ap.add_argument('--classes', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--trials', type=int, default=3)
    ap.add_argument('--loops', type=int, default=300)
    opts = ap.parse_args()
    opts.output.mkdir(parents=True, exist_ok=True)
    repo = Path(__file__).resolve().parent.parent
    stored = opts.output/'large-stored.zip'
    with zipfile.ZipFile(stored, 'w') as archive:
        for i in [10, 2, 1]:
            archive.writestr('page%d.jpg' % i, bytes([i]) * (4 << 20))
    results = []
    for implementation, library in [('c', opts.c_library), ('rust', opts.rust_library)]:
        for archive in [stored, repo/'docs/modernization/p5-p7/fixtures/large-deflated.zip']:
            for trial in range(opts.trials):
                command = ['/usr/bin/time', '-l', str(Path(os.environ['JAVA_HOME'])/'bin/java'), '--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED', '-cp', str(opts.classes), 'com.hippo.ehviewer.jni.NativeBenchmark', str(library.resolve()), str(archive.resolve()), str(opts.loops)]
                p = subprocess.run(command, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True, check=True)
                row = json.loads(p.stdout)
                rss = re.search(r'(\d+)\s+maximum resident set size', p.stderr)
                assert rss, 'macOS time -l RSS unavailable'
                row.update(implementation=implementation, archive=archive.name, trial=trial, max_rss=int(rss[1]), exit=p.returncode)
                results.append(row)
    (opts.output/'results.json').write_text(json.dumps(results, indent=2)+'\n')
    print(json.dumps(results, indent=2))


if __name__ == '__main__':
    main()
