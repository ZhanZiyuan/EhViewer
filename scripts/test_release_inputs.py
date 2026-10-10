#!/usr/bin/env python3
"""Optional integration tests requiring built release APKs and Android SDK tools.

Run explicitly after assembleRelease; these are not dependency-free unit tests.
"""
import argparse
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import zipfile

from prepare_release import prepare


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--sdk', type=Path, required=True)
    args = parser.parse_args()
    root = Path(__file__).resolve().parent.parent
    original = root/'app/build/outputs/apk/release'
    metadata = json.loads((original/'output-metadata.json').read_text())
    version = metadata['elements'][0]['versionName']
    rows = []
    for scenario in ['version-mismatch', 'missing-apk', 'extra-apk', 'tampered-signature']:
        with tempfile.TemporaryDirectory() as name:
            directory = Path(name)
            apks = directory/'inputs'
            shutil.copytree(original, apks)
            tag = 'v'+version
            first = apks/metadata['elements'][0]['outputFile']
            if scenario == 'version-mismatch':
                tag = 'v999.999.999'
            elif scenario == 'missing-apk':
                first.unlink()
            elif scenario == 'extra-apk':
                shutil.copy2(first, apks/'unexpected.apk')
            else:
                with zipfile.ZipFile(first, 'a') as apk:
                    apk.writestr('tampered.txt', 'signature regression probe')
            try:
                prepare(apks, args.sdk, directory/'payload', directory/'private', tag)
            except (ValueError, subprocess.CalledProcessError, FileNotFoundError) as error:
                if scenario == 'tampered-signature':
                    if not isinstance(error, subprocess.CalledProcessError) or 'apksigner' not in str(error.cmd):
                        raise AssertionError('Tampering must fail signature verification') from error
                if (directory/'payload').exists():
                    raise AssertionError('Failed input produced a public payload')
                rows.append({'scenario': scenario, 'rejected': True,
                             'error_type': type(error).__name__, 'public_payload_created': False})
            else:
                raise AssertionError('Unsafe release input accepted: '+scenario)
    print(json.dumps(rows, indent=2))


if __name__ == '__main__':
    main()
