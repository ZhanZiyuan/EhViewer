#!/usr/bin/env python3
"""Prepare exactly four APKs and owner-only local diagnostics; never publish."""
import argparse
from datetime import datetime, timedelta, timezone
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import stat
import subprocess
import tempfile

from verify_apks import verify
from verify_native_diagnostics import verify as verify_diagnostics

ABIS = {'arm64-v8a', 'armeabi-v7a', 'universal', 'x86_64'}
TAG = re.compile(r'v?(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*))?\Z')


def version_from_tag(tag):
    if not TAG.fullmatch(tag) or any(x in tag.lower() for x in ['snapshot', 'default', 'marshmallow']):
        raise ValueError('Expected a version tag such as v1.15.2; snapshot/flavor names are forbidden')
    return tag.removeprefix('v')


def attachment_plan(metadata, tag):
    version = version_from_tag(tag)
    if metadata.get('artifactType', {}).get('type') != 'APK' or metadata.get('applicationId') != 'moe.tarsin.ehviewer':
        raise ValueError('Unexpected release artifact/application ID')
    plan, seen, filenames, codes = [], set(), set(), set()
    for element in metadata['elements']:
        filters = element['filters']
        if filters and (len(filters) != 1 or filters[0]['filterType'] != 'ABI'):
            raise ValueError('Unexpected APK filters')
        abi = filters[0]['value'] if filters else 'universal'
        filename = element['outputFile']
        if abi not in ABIS or abi in seen or filename in filenames:
            raise ValueError('Unexpected/duplicate ABI or input filename')
        if Path(filename).name != filename or '/' in filename or '\\' in filename or not filename.endswith('.apk'):
            raise ValueError('Unsafe APK filename')
        if element['versionName'] != version:
            raise ValueError('Release tag and packaged versionName differ')
        code = element['versionCode']
        if not isinstance(code, int) or isinstance(code, bool) or code <= 0:
            raise ValueError('Invalid versionCode')
        codes.add(code)
        seen.add(abi)
        filenames.add(filename)
        plan.append({'abi': abi, 'input': filename, 'name': f'EhViewer-{version}-{abi}.apk'})
    if seen != ABIS or len(plan) != 4 or len(codes) != 1:
        raise ValueError('Exactly three ABI splits and one universal APK with one versionCode are required')
    return sorted(plan, key=lambda row: row['name'])


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(block)
    return value.hexdigest()


def private_directory(path):
    if path.is_symlink():
        raise ValueError('Private diagnostics directory must not be a symlink')
    path.mkdir(parents=True, exist_ok=True, mode=0o700)
    mode = stat.S_IMODE(path.stat().st_mode)
    if mode & 0o077 or path.stat().st_uid != os.getuid():
        raise ValueError('Private diagnostics directory must be owner-only (0700) and owned by this user')


def prepare(apk_dir, sdk, output, private_root, tag, retention_days=180):
    if not 1 <= retention_days <= 3650:
        raise ValueError('Retention must be between 1 and 3650 days')
    version = version_from_tag(tag)
    metadata = json.loads((apk_dir/'output-metadata.json').read_text())
    plan = attachment_plan(metadata, tag)
    verify(apk_dir, sdk, '29.0.14206865', 'moe.tarsin.ehviewer', True)
    root = Path(__file__).resolve().parent.parent
    source = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=root, text=True).strip()
    diagnostics = {
        'mapping.txt': root/'app/build/outputs/mapping/release/mapping.txt',
        'native-debug-symbols.zip': root/'app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip',
    }
    for path in [*(apk_dir/row['input'] for row in plan), *diagnostics.values()]:
        if not path.is_file() or path.is_symlink() or path.stat().st_size == 0:
            raise ValueError('Missing/empty/non-regular release input: '+str(path))
    verify_diagnostics(diagnostics['native-debug-symbols.zip'], apk_dir, sdk)
    if output.is_symlink() or (output.exists() and (not output.is_dir() or any(output.iterdir()))):
        raise ValueError('Refusing to overwrite a populated public payload directory')
    private_directory(private_root)
    private = private_root/(version+'-'+source)
    private_directory(private)
    now = datetime.now(timezone.utc)
    manifest = {
        'mode': 'dry-run-only', 'tag': tag, 'version': version,
        'versionCode': metadata['elements'][0]['versionCode'], 'source_commit': source,
        'source_dirty': bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=root)),
        'created_at': now.isoformat(), 'publication_enabled': False,
        'attachments': [], 'github_automatic_sources': ['Source code (zip)', 'Source code (tar.gz)'],
        'diagnostics': {'access': 'local owner only; no public upload', 'retention_days': retention_days,
                        'retain_until': (now+timedelta(days=retention_days)).isoformat(), 'files': {}},
    }
    for name, path in diagnostics.items():
        destination = private/name
        if destination.exists():
            if destination.is_symlink() or digest(destination) != digest(path):
                raise ValueError('Refusing to overwrite different retained diagnostics: '+name)
        else:
            with destination.open('xb') as stream:
                os.chmod(destination, 0o600)
                with path.open('rb') as original:
                    shutil.copyfileobj(original, stream)
        if stat.S_IMODE(destination.stat().st_mode) != 0o600:
            raise ValueError('Retained diagnostics must be owner-only (0600)')
        manifest['diagnostics']['files'][name] = {'sha256': digest(destination), 'bytes': destination.stat().st_size}
    output.parent.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='.release-', dir=output.parent) as temporary:
        staging = Path(temporary)/'apks'
        staging.mkdir()
        for row in plan:
            shutil.copyfile(apk_dir/row['input'], staging/row['name'])
            manifest['attachments'].append({'name': row['name'], 'abi': row['abi'], 'sha256': digest(staging/row['name'])})
        if {p.name for p in staging.iterdir()} != {row['name'] for row in plan}:
            raise ValueError('Public payload is not exactly the four planned APKs')
        if output.exists():
            output.rmdir()  # Only the checked empty output directory may be replaced.
        staging.rename(output)
    manifest_path = private/'manifest.json'
    with manifest_path.open('w') as stream:
        os.chmod(manifest_path, 0o600)
        json.dump(manifest, stream, indent=2)
        stream.write('\n')
    # The public report contains checksums and policy only, never mapping/symbol contents.
    (output.parent/'release-manifest.json').write_text(json.dumps(manifest, indent=2)+'\n')
    return manifest


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apk-dir', type=Path, default=Path('app/build/outputs/apk/release'))
    parser.add_argument('--sdk', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--private-dir', type=Path, required=True)
    parser.add_argument('--retention-days', type=int, default=180)
    args = parser.parse_args()
    print(json.dumps(prepare(args.apk_dir, args.sdk, args.output, args.private_dir, args.tag, args.retention_days), indent=2))
