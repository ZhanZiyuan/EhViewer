#!/usr/bin/env python3
"""P0 host characterization of unchanged C/JNI. No Android build is implied.

Requires macOS, JDK (JAVA_HOME), clang, Homebrew libarchive/nettle, and 7zz.
Writes only to --output. Source files are compiled directly from --repo.
"""
import argparse
import ctypes
import functools
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tarfile
import zipfile


def run(args, cwd=None, expected=0):
    proc = subprocess.run([str(x) for x in args], cwd=cwd, text=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=120)
    print("COMMAND", " ".join(str(x) for x in args), "EXIT", proc.returncode)
    print(proc.stdout)
    if expected is not None and proc.returncode != expected:
        raise RuntimeError("Unexpected command exit")
    return proc


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--repo", type=Path, required=True)
    ap.add_argument("--output", type=Path, required=True)
    ap.add_argument("--jni-header", type=Path, help="NDK jni.h, for JDK distributions without JNI headers")
    ap.add_argument("--fixture-seed", type=Path, help="Reuse the recorded 7z/encrypted ZIP bytes for differential testing")
    opts = ap.parse_args()
    repo, out = opts.repo.resolve(), opts.output.resolve()
    if out == repo or repo in out.parents:
        ap.error("output must be outside the source repository")
    out.mkdir(parents=True, exist_ok=True)
    cpp = repo / "app/src/main/cpp"
    jdk = Path(os.environ["JAVA_HOME"])
    if opts.jni_header:
        (out/"include").mkdir(exist_ok=True)
        (out/"include/jni.h").write_bytes(opts.jni_header.read_bytes())
    shim = out / "include/android"
    shim.mkdir(parents=True, exist_ok=True)
    (shim / "log.h").write_text("""#pragma once
#define ANDROID_LOG_VERBOSE 2
#define ANDROID_LOG_DEBUG 3
#define ANDROID_LOG_INFO 4
#define ANDROID_LOG_WARN 5
#define ANDROID_LOG_ERROR 6
#define ANDROID_LOG_FATAL 7
static inline int __android_log_print(int p, const char *t, const char *f, ...) { return 0; }
""")
    helper = out / "helper.c"
    helper.write_text('#include "gifutils.c"\nvoid p0_rewrite(void *p, size_t n) { doRewrite(p,n); }\n')
    flags = ["clang", "-g", "-O0", "-I"+str(cpp), "-I"+str(out/"include"),
             "-I"+str(jdk/"include"), "-I"+str(jdk/"include/darwin"),
             "-I/opt/homebrew/opt/libarchive/include", "-I/opt/homebrew/opt/nettle/include",
             "-Dstat64=stat", "-Dfstat64=fstat", "-Dmmap64=mmap", "-include", "unistd.h"]
    lib = out / "libp0.dylib"
    run(flags + ["-dynamiclib", cpp/"archive.c", cpp/"hash.c", helper,
                 cpp/"natsort/strnatcmp.c", "-L/opt/homebrew/opt/libarchive/lib",
                 "-L/opt/homebrew/opt/nettle/lib", "-larchive", "-lnettle", "-o", lib])
    native = ctypes.CDLL(str(lib))
    native.strnatcmp.argtypes = [ctypes.c_char_p, ctypes.c_char_p]
    native.strnatcmp.restype = ctypes.c_int
    names = ["", "1", "01", "001", "2", "10", "a", "A", "a1", "a01", "a2", "a10",
             "image2.jpg", "image10.jpg", " 2", "2 ", "页2.png", "页10.png",
             "9"*160, "1"+"0"*160, "x2.JPG", "x2.jpg"]
    def compare(a, b):
        n = native.strnatcmp(a.encode(), b.encode())
        return (n > 0) - (n < 0)
    matrix = [[compare(a, b) for b in names] for a in names]
    for i in range(len(names)):
        assert matrix[i][i] == 0
        for j in range(len(names)):
            assert matrix[i][j] == -matrix[j][i]
            for k in range(len(names)):
                if matrix[i][j] <= 0 and matrix[j][k] <= 0:
                    assert matrix[i][k] <= 0
    assert compare("image2.jpg", "image10.jpg") < 0
    assert compare("9"*160, "1"+"0"*160) < 0
    (out/"sort-oracle.json").write_text(json.dumps({"inputs": names, "signs": matrix,
        "order": sorted(names, key=functools.cmp_to_key(compare))}, ensure_ascii=False, indent=2)+"\n")
    print("PASS natural sort: 22 inputs, 484 pairs, antisymmetry and transitivity")
    native.p0_rewrite.argtypes = [ctypes.c_void_p, ctypes.c_size_t]
    for original, expected in [
        (b"", b""), (b"GIF89a", b"GIF89a"), (b"not-a-gif", b"not-a-gif"),
        (b"GIF89a"+bytes.fromhex("0021f9040001000000"),
         b"GIF89a"+bytes.fromhex("0021f904000a000000")),
        (b"GIF87a"+bytes.fromhex("0021f9040002000000"),
         b"GIF87a"+bytes.fromhex("0021f9040002000000")),
    ]:
        buf = ctypes.create_string_buffer(original)
        native.p0_rewrite(buf, len(original))
        assert buf.raw[:len(original)] == expected
    print("PASS GIF: 5 safe byte vectors")
    sanitizer = out / "gif_short.c"
    sanitizer.write_text('#include "gifutils.c"\n#include <stdlib.h>\nint main(void) { '
        'char *p=malloc(7); memcpy(p,"GIF89a!",7); doRewrite((byte*)p,7); free(p); return 0; }\n')
    run(flags + ["-fsanitize=address,undefined", sanitizer, "-o", out/"gif_short"])
    crash = run([out/"gif_short"], expected=None)
    assert crash.returncode != 0 and "heap-buffer-overflow" in crash.stdout
    (out/"gif-short-asan.log").write_text(crash.stdout)
    print("CONFIRMED existing defect: seven-byte GIF heap-buffer-overflow (ASan)")

    fixtures = out / "fixtures"
    fixtures.mkdir(exist_ok=True)
    if opts.fixture_seed:
        for name in ["pages.7z", "password.zip", "zipcrypto.zip"]:
            shutil.copyfile(opts.fixture_seed/name, fixtures/name)
    pages = {"page10.png": b"ten", "page2.jpg": b"two", "page1.gif": b"one",
             "ignored.txt": b"not an image", "UPPER.JPG": b"case-sensitive extension"}
    for name, data in pages.items():
        (fixtures/name).write_bytes(data)
    for name, compression in [("stored.zip", zipfile.ZIP_STORED), ("deflated.zip", zipfile.ZIP_DEFLATED)]:
        with zipfile.ZipFile(fixtures/name, "w", compression=compression) as archive:
            for path, data in pages.items():
                info = zipfile.ZipInfo(path, (2020, 1, 1, 0, 0, 0))
                info.compress_type = compression
                archive.writestr(info, data)
    with tarfile.open(fixtures/"pages.tar", "w") as archive:
        for path, data in pages.items():
            info = tarfile.TarInfo(path)
            info.size = len(data)
            archive.addfile(info, io.BytesIO(data))
    for name, flags7 in [("pages.7z", []), ("password.zip", ["-tzip", "-mem=AES256", "-pbaseline"]),
                         ("zipcrypto.zip", ["-tzip", "-mem=ZipCrypto", "-pbaseline"])]:
        if not (fixtures/name).exists():
            run(["7zz", "a", name]+flags7+list(pages), cwd=fixtures)
    (fixtures/"broken.zip").write_bytes(b"PK\x03\x04broken")
    for name, data in [("empty.bin", b""), ("abc.bin", b"abc"),
                       ("large.bin", bytes(range(256))*65), ("offset.bin", b"xxxabc")]:
        (fixtures/name).write_bytes(data)
    (out/"fixture-sha256.json").write_text(json.dumps({p.name: hashlib.sha256(p.read_bytes()).hexdigest()
        for p in sorted(fixtures.iterdir()) if p.is_file()}, indent=2)+"\n")
    package = out/"java/com/hippo/ehviewer/jni"
    package.mkdir(parents=True, exist_ok=True)
    (package/"HashKt.java").write_text('package com.hippo.ehviewer.jni; public class HashKt { public static native String sha1(int fd); }')
    (package/"GifUtilsKt.java").write_text('package com.hippo.ehviewer.jni; import java.nio.*; public class GifUtilsKt { public static native boolean isGif(int fd); public static native void rewriteGifSource(ByteBuffer b); public static native ByteBuffer mmap(int fd); public static native void munmap(ByteBuffer b); }')
    (package/"ArchiveKt.java").write_text('''package com.hippo.ehviewer.jni; import java.nio.*;
public class ArchiveKt {
 public static native int openArchive(int fd, long size, boolean sort);
 public static native ByteBuffer extractToByteBuffer(int index);
 public static native void releaseByteBuffer(ByteBuffer buffer);
 public static native boolean extractToFd(int index, int fd);
 public static native String getExtension(int index);
 public static native boolean needPassword();
 public static native boolean providePassword(String password);
 public static native void closeArchive();
 public static native void archiveFdBatch(int[] fds, String[] names, int output, int size);
}''')
    (package/"Baseline.java").write_text(r'''package com.hippo.ehviewer.jni;
import java.io.*; import java.nio.*; import java.nio.file.*; import java.lang.reflect.*; import java.security.*; import java.util.*;
public class Baseline {
 static int checks;
 static void check(boolean value,String msg) { checks++; if(!value)throw new AssertionError(msg); }
 static int fd(FileDescriptor d)throws Exception { Field f=FileDescriptor.class.getDeclaredField("fd");f.setAccessible(true);return f.getInt(d); }
 static byte[] bytes(ByteBuffer b) { byte[] x=new byte[b.capacity()];b.get(x);return x; }
 static void archive(Path p, boolean encrypted, boolean sort)throws Exception {
  try(FileInputStream in=new FileInputStream(p.toFile())) {
   try {
    check(ArchiveKt.openArchive(fd(in.getFD()),Files.size(p),sort)==3,"entry count "+p);
    check(ArchiveKt.needPassword()==encrypted,"encryption "+p);
    if(encrypted) { check(!ArchiveKt.providePassword("wrong"),"wrong password");check(ArchiveKt.providePassword("baseline"),"correct password"); }
    String[] contents=sort?new String[]{"one","two","ten"}:new String[]{"ten","two","one"};
    String[] extensions=sort?new String[]{"gif","jpg","png"}:new String[]{"png","jpg","gif"};
    for(int i:new int[]{2,0,1,2,0}) {
     ByteBuffer b=ArchiveKt.extractToByteBuffer(i);check(b!=null && b.isDirect(),"direct buffer");
     check(Arrays.equals(bytes(b),contents[i].getBytes()),"data "+p+" "+i);ArchiveKt.releaseByteBuffer(b);
     check(ArchiveKt.getExtension(i).equals(extensions[i]),"extension");
     Path target=p.resolveSibling("extracted.bin");
     try(FileOutputStream output=new FileOutputStream(target.toFile())) { check(ArchiveKt.extractToFd(i,fd(output.getFD())),"extract fd"); }
     check(Arrays.equals(Files.readAllBytes(target),contents[i].getBytes()),"fd data");
    }
   } finally { ArchiveKt.closeArchive(); }
  }
 }
 public static void main(String[] args)throws Exception {
  System.load(args[0]);Path root=Path.of(args[1]);
  if(args.length>2) {
   try(FileInputStream in=new FileInputStream(root.resolve("password.zip").toFile())) {
    try { check(ArchiveKt.openArchive(fd(in.getFD()),in.getChannel().size(),true)==3,"open unicode");
     check(ArchiveKt.providePassword("密码"),"unicode password");
    } finally { ArchiveKt.closeArchive(); }
   } return;
  }
  for(String name:new String[]{"empty.bin","abc.bin","large.bin","offset.bin"}) {
   byte[] data=Files.readAllBytes(root.resolve(name));
   try(FileInputStream in=new FileInputStream(root.resolve(name).toFile())) {
    int offset=name.equals("offset.bin")?3:0;in.getChannel().position(offset);
    String expected=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Arrays.copyOfRange(data,offset,data.length)));
    check(HashKt.sha1(fd(in.getFD())).equals(expected),"SHA1 "+name);
    check(in.getChannel().position()==data.length,"fd position");
   }
  }
  for(String name:new String[]{"stored.zip","deflated.zip","pages.tar","pages.7z","password.zip","zipcrypto.zip"}) archive(root.resolve(name),name.contains("password")||name.contains("zipcrypto"),true);
  archive(root.resolve("stored.zip"),false,false);
  try(FileInputStream in=new FileInputStream(root.resolve("broken.zip").toFile())) {
   try { check(ArchiveKt.openArchive(fd(in.getFD()),in.getChannel().size(),true)==0,"broken archive"); }
   finally { ArchiveKt.closeArchive(); }
  }
  for(String header:new String[]{"GIF87a!","GIF89a!","NOTGIF!","GIF"}) {
   Path p=root.resolve("header.gif");Files.write(p,header.getBytes());
   try(FileInputStream in=new FileInputStream(p.toFile())) {check(GifUtilsKt.isGif(fd(in.getFD()))==header.startsWith("GIF8"),"GIF fd");}
  }
  Path gif=root.resolve("mapped.gif");Files.write(gif,HexFormat.of().parseHex("4749463839610021f9040001000000"));
  try(FileInputStream in=new FileInputStream(gif.toFile())) {
   ByteBuffer b=GifUtilsKt.mmap(fd(in.getFD()));check(b!=null && b.isDirect(),"mmap");
   GifUtilsKt.rewriteGifSource(b);check(b.get(11)==10,"GIF JNI rewrite");GifUtilsKt.munmap(b);
  }
  Path batch=root.resolve("batch.zip");
  try(FileInputStream one=new FileInputStream(root.resolve("abc.bin").toFile()); FileOutputStream dest=new FileOutputStream(batch.toFile())) {ArchiveKt.archiveFdBatch(new int[]{fd(one.getFD())},new String[]{"batch.png"},fd(dest.getFD()),1);}
  try(FileInputStream in=new FileInputStream(batch.toFile())) {
   try {check(ArchiveKt.openArchive(fd(in.getFD()),in.getChannel().size(),true)==1,"batch reopen");ByteBuffer b=ArchiveKt.extractToByteBuffer(0);check(Arrays.equals(bytes(b),"abc".getBytes()),"batch data");ArchiveKt.releaseByteBuffer(b);}
   finally {ArchiveKt.closeArchive();}
  }
  System.out.println("PASS host JNI: "+checks+" checks");
 }
}''')
    run([jdk/"bin/javac", "-d", out/"classes"]+sorted(package.glob("*.java")))
    command = [jdk/"bin/java", "-Xcheck:jni", "--add-opens=java.base/java.io=ALL-UNNAMED",
               "-cp", out/"classes", "com.hippo.ehviewer.jni.Baseline", lib, fixtures]
    run(command)
    unicode = run(command+["unicode"], expected=None)
    assert unicode.returncode != 0 and "StringIndexOutOfBoundsException" in unicode.stdout
    (out/"unicode-password.log").write_text(unicode.stdout)
    print("CONFIRMED existing defect: UTF-8 byte count used as UTF-16 region length")
    print("Host libraries differ from Android CMake pins; Android ABI/16KB/RAR5/concurrency remain unverified.")


if __name__ == "__main__":
    main()
