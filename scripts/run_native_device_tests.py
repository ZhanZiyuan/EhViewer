#!/usr/bin/env python3
"""Run final packaged JNI/platform regressions on an already booted dedicated device."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--serial', required=True)
    ap.add_argument('--output', type=Path, required=True)
    ap.add_argument('--sdk', type=Path, default=Path(os.environ.get('ANDROID_HOME', '')))
    ap.add_argument('--tests', type=int, default=16)
    opts = ap.parse_args()
    root = Path(__file__).resolve().parent.parent
    opts.output.mkdir(parents=True, exist_ok=True)
    adb = [str(opts.sdk/'platform-tools/adb'), '-s', opts.serial]
    def run(args, name, timeout=180):
        p = subprocess.run(adb+args, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True, timeout=timeout)
        (opts.output/name).write_text(p.stdout)
        if p.returncode:
            raise RuntimeError(name + ': ' + p.stdout)
        return p.stdout
    api = int(run(['shell', 'getprop', 'ro.build.version.sdk'], 'api.txt').strip())
    p = subprocess.run(adb+['shell', 'getconf', 'PAGE_SIZE'], stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
    if p.returncode == 0 and p.stdout.strip().isdigit():
        page_size = int(p.stdout.strip())
    else:
        text = run(['shell', 'cat', '/proc/self/smaps'], 'smaps.txt')
        page_size = int(next(line.split()[1] for line in text.splitlines() if line.startswith('KernelPageSize:'))) * 1024
    (opts.output/'page-size.txt').write_text(str(page_size)+'\n')
    apks = [root/'app/build/outputs/apk/debug/app-universal-debug.apk', root/'app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk']
    digests = {p.name: hashlib.sha256(p.read_bytes()).hexdigest() for p in apks}
    for i, apk in enumerate(apks):
        run(['install', '-r', str(apk)], 'install-%d.log' % i)
    text = run(['shell', 'am', 'instrument', '-w', '-r', 'moe.tarsin.ehviewer.debug.test/androidx.test.runner.AndroidJUnitRunner'], 'instrumentation.log', 300)
    run(['logcat', '-d', '-s', 'AndroidRuntime:E', 'libc:F', 'DEBUG:F'], 'native-errors.log')
    assert 'OK (%d tests)' % opts.tests in text, 'JUnit did not report expected success'
    assert 'FAILURES!!!' not in text and 'INSTRUMENTATION_FAILED' not in text
    result = {'serial': opts.serial, 'api': api, 'page_size': page_size, 'tests': opts.tests, 'apks': digests, 'result': 'PASS'}
    (opts.output/'result.json').write_text(json.dumps(result, indent=2)+'\n')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
