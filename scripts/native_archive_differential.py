#!/usr/bin/env python3
"""Compare unchanged JDK25 JNI clients against caller-supplied C and Rust host libraries."""
import argparse
import json
import os
from pathlib import Path
import subprocess


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--c-library', type=Path, required=True)
    ap.add_argument('--rust-library', type=Path, required=True)
    ap.add_argument('--output', type=Path, required=True)
    opts = ap.parse_args()
    repo = Path(__file__).resolve().parent.parent
    out = opts.output.resolve()
    package = out/'java/com/hippo/ehviewer/jni'
    package.mkdir(parents=True, exist_ok=True)
    (package/'ArchiveKt.java').write_text('''package com.hippo.ehviewer.jni; import java.nio.*;
public class ArchiveKt {
 public static native int openArchive(int fd,long size,boolean sort);
 public static native ByteBuffer extractToByteBuffer(int index);
 public static native void releaseByteBuffer(ByteBuffer buffer);
 public static native boolean extractToFd(int index,int fd);
 public static native String getExtension(int index);
 public static native boolean needPassword();
 public static native boolean providePassword(String password);
 public static native void closeArchive();
 public static native void archiveFdBatch(int[] fds,String[] names,int output,int size);
}''')
    (package/'HashKt.java').write_text('package com.hippo.ehviewer.jni; public class HashKt { public static native String sha1(int fd); }')
    jdk = Path(os.environ['JAVA_HOME'])/'bin'
    classes = out/'classes'
    subprocess.run([str(jdk/'javac'), '-d', str(classes), *map(str, package.glob('*.java')), str(repo/'docs/modernization/p5-p7/NativeArchiveProbe.java'), str(repo/'docs/modernization/p5-p7/NativeBenchmark.java')], check=True)
    results = {}
    cases = [(repo/'docs/modernization/baseline/fixtures'/n, 'baseline' if n in ['password.zip','zipcrypto.zip'] else None) for n in ['stored.zip','deflated.zip','pages.tar','pages.7z','password.zip','zipcrypto.zip']]
    cases += [(repo/'docs/modernization/p5-p7/fixtures'/n, None) for n in ['stored.rar','compressed.rar','solid.rar','multiple_files.rar','pages.tgz','pages.txz','large-deflated.zip']]
    for path, password in cases:
        values = []
        for library in [opts.c_library.resolve(), opts.rust_library.resolve()]:
            command = [str(jdk/'java'), '-Xcheck:jni', '--enable-native-access=ALL-UNNAMED', '--add-opens=java.base/java.io=ALL-UNNAMED', '-cp', str(classes), 'com.hippo.ehviewer.jni.NativeArchiveProbe', str(library), str(path)]
            if password:
                command.append(password)
            p = subprocess.run(command, text=True, stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=True)
            values.append(json.loads(p.stdout))
        assert values[0] == values[1] and values[1]['count'] > 0, path.name
        results[path.name] = values[1]
        print('PASS C/Rust JNI digest equivalence:', path.name)
    (out/'results.json').write_text(json.dumps(results, indent=2)+'\n')


if __name__ == '__main__':
    main()
