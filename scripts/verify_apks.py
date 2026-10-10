#!/usr/bin/env python3
"""Verify real AGP APK metadata, identity, signing, ABI/JNI and 16KB alignment."""
import argparse
import hashlib
import json
import re
import struct
import subprocess
import tempfile
import zipfile
from pathlib import Path

ABIS = {'arm64-v8a': 183, 'armeabi-v7a': 40, 'x86_64': 62}
CERT = '5d91dff13b6489a9cfadbb197b3e795eefc8c39f849c4543f32c160720fb40a6'
JNI = {'JNI_OnLoad', 'Java_com_hippo_ehviewer_jni_HashKt_sha1',
       *{'Java_com_hippo_ehviewer_jni_ArchiveKt_' + n for n in (
           'openArchive', 'closeArchive', 'extractToByteBuffer', 'extractToFd',
           'releaseByteBuffer', 'getExtension', 'needPassword', 'providePassword', 'archiveFdBatch')},
       *{'Java_com_hippo_ehviewer_jni_GifUtilsKt_' + n for n in ('isGif', 'rewriteGifSource', 'mmap', 'munmap')}}


def elf(data, abi):
    if data[:4] != b'\x7fELF' or data[5] != 1:
        raise ValueError('Expected little-endian ELF')
    is64 = data[4] == 2
    if data[4] not in (1, 2) or is64 != (abi != 'armeabi-v7a'):
        raise ValueError('Incorrect ELF class')
    if struct.unpack_from('<H', data, 18)[0] != ABIS[abi]:
        raise ValueError('Incorrect ELF machine')
    phoff = struct.unpack_from('<Q' if is64 else '<I', data, 32 if is64 else 28)[0]
    entsize, count = struct.unpack_from('<HH', data, 54 if is64 else 42)
    loads = []
    for i in range(count):
        off = phoff + i * entsize
        fields = struct.unpack_from('<IIQQQQQQ' if is64 else '<IIIIIIII', data, off)
        if fields[0] == 1:
            offset, vaddr, align = (fields[2], fields[3], fields[7]) if is64 else (fields[1], fields[2], fields[7])
            if align < 4096 or align & (align - 1) or (offset - vaddr) % align:
                raise ValueError('Invalid LOAD alignment')
            if is64 and align < 16384:
                raise ValueError('64-bit LOAD not 16KB aligned')
            loads.append(align)
    if not loads:
        raise ValueError('ELF has no LOAD segments')
    return loads


def run(*args):
    return subprocess.check_output([str(a) for a in args], text=True, stderr=subprocess.STDOUT)


def verify(directory, sdk, ndk, application_id, release, version_code=None):
    metadata = json.loads((directory / 'output-metadata.json').read_text())
    if metadata['artifactType']['type'] != 'APK':
        raise ValueError('Expected APK metadata')
    if metadata['applicationId'] != application_id:
        raise ValueError('Metadata applicationId changed')
    expected_code = metadata['elements'][0]['versionCode']
    expected_name = metadata['elements'][0]['versionName']
    if expected_code <= 0 or (version_code is not None and expected_code != version_code):
        raise ValueError('Unexpected versionCode')
    seen, reports = set(), []
    host = 'darwin-x86_64' if __import__('sys').platform == 'darwin' else 'linux-x86_64'
    nm = sdk / 'ndk' / ndk / 'toolchains/llvm/prebuilt' / host / 'bin/llvm-nm'
    tools = sdk / 'build-tools/37.0.0'
    for element in metadata['elements']:
        filters = element['filters']
        if filters and (len(filters) != 1 or filters[0]['filterType'] != 'ABI'):
            raise ValueError('Unexpected APK filters')
        abi = filters[0]['value'] if filters else 'universal'
        if abi in seen or abi not in {*ABIS, 'universal'}:
            raise ValueError('Unexpected/duplicate APK ABI')
        seen.add(abi)
        apk = directory / element['outputFile']
        if apk.resolve().parent != directory.resolve():
            raise ValueError('APK escapes output directory')
        if element['versionCode'] != expected_code or element['versionName'] != expected_name:
            raise ValueError('Inconsistent APK versions')
        badging = run(tools / 'aapt2', 'dump', 'badging', apk)
        if f"package: name='{application_id}'" not in badging or "minSdkVersion:'26'" not in badging or "targetSdkVersion:'37'" not in badging:
            raise ValueError('Packaged manifest identity/SDK mismatch')
        if f"versionCode='{expected_code}'" not in badging or f"versionName='{expected_name}'" not in badging:
            raise ValueError('Packaged version differs from metadata')
        signing = run(tools / 'apksigner', 'verify', '--verbose', '--print-certs', apk)
        certs = re.findall(r'certificate SHA-256 digest: ([0-9a-f]+)', signing)
        if not certs or (release and certs != [CERT]):
            raise ValueError('APK signature/certificate mismatch')
        run(tools / 'zipalign', '-c', '-P', '16', '-v', '4', apk)
        libraries, actual_abis = {}, set()
        with zipfile.ZipFile(apk) as archive, tempfile.TemporaryDirectory() as temp:
            for info in archive.infolist():
                if not info.filename.startswith('lib/') or not info.filename.endswith('.so'):
                    continue
                _, libabi, name = info.filename.split('/')
                actual_abis.add(libabi)
                if libabi not in ABIS:
                    raise ValueError('Unexpected packaged ABI')
                data = archive.read(info)
                alignment = elf(data, libabi)
                libraries[info.filename] = alignment
                if name == 'libehviewer.so':
                    path = Path(temp) / (libabi + '.so')
                    path.write_bytes(data)
                    exports = [line.split()[-1] for line in run(nm, '--dynamic', '--defined-only', path).splitlines() if line.split()]
                    if len(exports) != len(set(exports)) or not JNI.issubset(exports):
                        raise ValueError('Missing/duplicate JNI symbols')
                    if not any('ParserKt_' in x for x in exports):
                        raise ValueError('Rust JNI parser exports missing')
            expected = set(ABIS) if abi == 'universal' else {abi}
            if actual_abis != expected or not all('lib/' + a + '/libehviewer.so' in libraries for a in expected):
                raise ValueError('Packaged native ABI set mismatch')
        reports.append({'file': apk.name, 'abi': abi, 'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
                        'versionCode': expected_code, 'versionName': expected_name, 'certificate': certs, 'native': libraries})
    if seen != {*ABIS, 'universal'}:
        raise ValueError('Expected three splits and universal APK')
    if {p.name for p in directory.glob('*.apk')} != {e['outputFile'] for e in metadata['elements']}:
        raise ValueError('Unexpected/stale APK in output directory')
    return reports


if __name__ == '__main__':
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('directory', type=Path)
    p.add_argument('--sdk', type=Path, required=True)
    p.add_argument('--ndk', default='29.0.14206865')
    p.add_argument('--application-id', default='moe.tarsin.ehviewer')
    p.add_argument('--debug', action='store_true')
    p.add_argument('--version-code', type=int, help='Optional migration baseline versionCode')
    a = p.parse_args()
    print(json.dumps(verify(a.directory, a.sdk, a.ndk, a.application_id, not a.debug, a.version_code), indent=2))
