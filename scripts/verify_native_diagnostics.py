#!/usr/bin/env python3
"""Require matching, complete Rust symbols while keeping APK libraries stripped."""
import argparse
import json
from pathlib import Path
import re
import subprocess
import sys
import tempfile
import zipfile

ABIS = ['arm64-v8a', 'armeabi-v7a', 'x86_64']


def verify(symbols, apk_dir, sdk, ndk='29.0.14206865'):
    host = 'darwin-x86_64' if sys.platform == 'darwin' else 'linux-x86_64'
    tools = sdk/'ndk'/ndk/'toolchains/llvm/prebuilt'/host/'bin'

    def run(tool, *args):
        return subprocess.check_output([str(tools/tool), *map(str, args)], stderr=subprocess.STDOUT)

    def build_id(path):
        match = re.search(rb'Build ID: ([0-9a-f]+)', run('llvm-readelf', '--notes', path))
        if not match:
            raise ValueError('Native build ID missing')
        return match[1].decode()

    rows = []
    with zipfile.ZipFile(symbols) as diagnostics, tempfile.TemporaryDirectory() as directory:
        temp = Path(directory)  # TemporaryDirectory is owner-only.
        expected = {abi+'/libehviewer.so.dbg' for abi in ABIS}
        if {name for name in diagnostics.namelist() if not name.endswith('/')} != expected:
            raise ValueError('Expected precisely three libehviewer diagnostic ELFs')
        for abi in ABIS:
            debug = temp/(abi+'.dbg')
            debug.write_bytes(diagnostics.read(abi+'/libehviewer.so.dbg'))
            debug.chmod(0o600)
            lines = run('llvm-dwarfdump', '--debug-line', debug)
            if b'archive.rs' not in lines or b'native.rs' not in lines:
                raise ValueError('Rust Core/JNI source line information missing: '+abi)
            packaged = temp/(abi+'.so')
            with zipfile.ZipFile(apk_dir/('app-'+abi+'-release.apk')) as apk:
                packaged.write_bytes(apk.read('lib/'+abi+'/libehviewer.so'))
            if b'.debug_info' in run('llvm-readelf', '--sections', packaged):
                raise ValueError('Debug information must not be shipped in the APK: '+abi)
            identifier = build_id(debug)
            if build_id(packaged) != identifier:
                raise ValueError('Diagnostic build ID differs from APK: '+abi)
            rows.append({'abi': abi, 'build_id': identifier, 'rust_core_lines': True,
                         'rust_jni_lines': True, 'apk_debug_info': False})
    return rows


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--symbols', type=Path, default=Path('app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip'))
    parser.add_argument('--apk-dir', type=Path, default=Path('app/build/outputs/apk/release'))
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    print(json.dumps(verify(args.symbols, args.apk_dir, args.sdk), indent=2))
