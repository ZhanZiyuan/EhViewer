package com.hippo.ehviewer.jni;
import java.io.*;import java.lang.reflect.*;import java.nio.*;import java.nio.file.*;import java.security.*;import java.util.*;
/** Stable JNI digest oracle, used unchanged with C and Rust libraries. */
public class NativeArchiveProbe {
 static int fd(FileDescriptor d)throws Exception {Field f=FileDescriptor.class.getDeclaredField("fd");f.setAccessible(true);return f.getInt(d);}
 public static void main(String[]args)throws Exception {
  System.load(args[0]);Path path=Path.of(args[1]);
  try(FileInputStream input=new FileInputStream(path.toFile())) {
   try {
    int count=ArchiveKt.openArchive(fd(input.getFD()),Files.size(path),true);
    boolean encrypted=ArchiveKt.needPassword();
    if(encrypted && (args.length<3 || !ArchiveKt.providePassword(args[2])))throw new AssertionError("password");
    StringJoiner pages=new StringJoiner(",");
    for(int i=0;i<count;i++) {
     ByteBuffer data=ArchiveKt.extractToByteBuffer(i);if(data==null)throw new AssertionError("extract "+i);
     try {MessageDigest sha=MessageDigest.getInstance("SHA-256");MessageDigest sha1=MessageDigest.getInstance("SHA-1");int size=data.capacity();sha1.update(data.duplicate());sha.update(data);
      pages.add("{\"size\":"+size+",\"extension\":\""+ArchiveKt.getExtension(i)+"\",\"sha256\":\""+HexFormat.of().formatHex(sha.digest())+"\",\"sha1\":\""+HexFormat.of().formatHex(sha1.digest())+"\"}");
     }finally{ArchiveKt.releaseByteBuffer(data);}
    }
    System.out.println("{\"count\":"+count+",\"encrypted\":"+encrypted+",\"pages\":["+pages+"]}");
   } finally {ArchiveKt.closeArchive();}
  }
 }
}
