package com.hippo.ehviewer.jni;
import java.io.*;import java.lang.reflect.*;import java.nio.*;import java.nio.file.*;import java.util.*;
/** Same JNI workload for the original C and replacement Rust libraries. */
public class NativeBenchmark {
 static int fd(FileDescriptor d)throws Exception { Field f=FileDescriptor.class.getDeclaredField("fd");f.setAccessible(true);return f.getInt(d); }
 static volatile long checksum;
 public static void main(String[]args)throws Exception {
  System.load(args[0]);Path input=Path.of(args[1]);int loops=Integer.parseInt(args[2]);
  try(FileInputStream in=new FileInputStream(input.toFile())) {
   long start=System.nanoTime();int count=ArchiveKt.openArchive(fd(in.getFD()),Files.size(input),true);
   if(count!=3)throw new AssertionError("count "+count);long opened=System.nanoTime();
   long[]times=new long[loops];
   for(int n=0;n<loops;n++) {
    long t=System.nanoTime();ByteBuffer buffer=ArchiveKt.extractToByteBuffer((n*17)%3);
    if(buffer==null)throw new AssertionError("extract");
    // Touch each page of memory to compare committed memory rather than virtual mappings.
    for(int i=0;i<buffer.capacity();i+=4096)checksum+=buffer.get(i);
    ArchiveKt.releaseByteBuffer(buffer);times[n]=System.nanoTime()-t;
   }
   ArchiveKt.closeArchive();Arrays.sort(times);
   System.out.printf(Locale.ROOT,"{\"open_ms\":%.4f,\"median_ms\":%.4f,\"p95_ms\":%.4f,\"checksum\":%d}%n",(opened-start)/1e6,times[loops/2]/1e6,times[loops*95/100]/1e6,checksum);
  }
 }
}
