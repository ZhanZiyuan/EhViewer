@file:JvmName("ArchiveKt")

package com.hippo.ehviewer.jni

import java.io.FileDescriptor
import java.io.FileInputStream
import java.lang.management.ManagementFactory
import java.nio.ByteBuffer
import java.nio.file.Files
import java.util.Random
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.outputStream
import verification.Options

// Match the application JNI class without compiling Android application code.
internal external fun openArchive(fd: Int, size: Long, sort: Boolean): Int
internal external fun closeArchive()
internal external fun extractToByteBuffer(index: Int): ByteBuffer?
internal external fun extractForReading(index: Int, rewriteGif: Boolean): ByteBuffer?
internal external fun releaseByteBuffer(buffer: ByteBuffer)
internal external fun getExtension(index: Int): String
internal external fun needPassword(): Boolean
internal external fun providePassword(password: String): Boolean
internal external fun openArchiveSession(fd: Int, size: Long, sort: Boolean): Long
internal external fun closeArchiveSession(handle: Long)
internal external fun archiveSessionPageCount(handle: Long): Int
internal external fun archiveSessionNeedsPassword(handle: Long): Boolean
internal external fun archiveSessionProvidePassword(handle: Long, password: String): Boolean
internal external fun archiveSessionRead(handle: Long, index: Int, rewriteGif: Boolean): ByteBuffer?

private var readonly = false
private var sessionHandle = 0L
private fun extract(index: Int) = when {
    sessionHandle != 0L -> archiveSessionRead(sessionHandle, index, false)
    readonly -> extractForReading(index, false)
    else -> extractToByteBuffer(index)
}

private class Worker {
    // Preserve the original volatile 4-KiB touch workload, without sharing a cache line across workers.
    @Volatile var checksum = 0L
    var extractNs = 0L
    var touchNs = 0L
    var releaseNs = 0L
}

private class Samples(loops: Int, cpu: Boolean, phases: Boolean) {
    val times = LongArray(loops)
    val cpuTimes = LongArray(if (cpu) loops else 0)
    val starts = LongArray(if (cpu) loops else 0)
    val extracts = LongArray(if (phases) loops else 0)
    val touches = LongArray(if (phases) loops else 0)
    val releases = LongArray(if (phases) loops else 0)
    var checksum = 0L
}

private fun touchPage(buffer: ByteBuffer, worker: Worker) {
    var index = 0
    while (index < buffer.capacity()) {
        worker.checksum += buffer.get(index)
        index += 4096
    }
}

private fun consume(buffer: ByteBuffer, mutation: Int, worker: Worker) {
    touchPage(buffer, worker)
    if (mutation >= 0 && buffer.capacity() > 0) {
        val changed = ((mutation * 104729L) % buffer.capacity()).toInt()
        buffer.put(changed, (buffer.get(changed).toInt() xor 127).toByte())
    }
}

// Keep optional statistics out of the timed method and the hot page-touch loop.
private fun measurePage(index: Int, mutation: Int, worker: Worker): Long {
    val before = System.nanoTime()
    val buffer = checkNotNull(extract(index))
    try {
        consume(buffer, mutation, worker)
    } finally {
        releaseByteBuffer(buffer)
    }
    return System.nanoTime() - before
}

// Separate diagnostic workload; do not mix these instrumented times into acceptance trials.
private fun measureParts(index: Int, mutation: Int, worker: Worker): Long {
    val before = System.nanoTime()
    val buffer = checkNotNull(extract(index))
    val extracted = System.nanoTime()
    var touched = extracted
    try {
        consume(buffer, mutation, worker)
        touched = System.nanoTime()
    } finally {
        releaseByteBuffer(buffer)
    }
    val released = System.nanoTime()
    worker.extractNs = extracted - before
    worker.touchNs = touched - extracted
    worker.releaseNs = released - touched
    return released - before
}

fun benchmarkArchives(options: Options): Map<String, Any> {
    val output = options.path("--output")
    val size = options.get("--page-bytes", "${4 shl 20}").toInt()
    val count = options.get("--pages", "3").toInt()
    val pattern = options.get("--pattern", "constant")
    require(size in 1..(64 shl 20) && count in 1..128 && size.toLong() * count <= (512L shl 20))
    require(pattern in setOf("constant", "mixed"))
    Files.createDirectories(output)
    val result = mutableListOf<String>()
    for (stored in listOf(true, false)) {
        val path = output.resolve(if (stored) "large-stored.zip" else "large-deflated.zip")
        ZipOutputStream(path.outputStream()).use { zip ->
            for (page in if (count == 3) listOf(10, 2, 1) else (count downTo 1).toList()) {
                val bytes = ByteArray(size) { page.toByte() }
                if (pattern == "mixed") Random(page.toLong()).nextBytes(bytes)
                val entry = ZipEntry("page$page.jpg").apply {
                    time = 0
                    if (stored) {
                        method = ZipEntry.STORED
                        this.size = bytes.size.toLong()
                        compressedSize = this.size
                        crc = CRC32().apply { update(bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(bytes)
                zip.closeEntry()
            }
        }
        result += path.toString()
    }
    return mapOf("archives" to result, "pages" to count, "page_bytes" to size, "pattern" to pattern)
}

fun nativeArchiveBenchmark(options: Options): Map<String, Any> {
    val library = options.path("--library")
    val archive = options.path("--archive")
    val loops = options.get("--loops", "300").toInt()
    val warmup = options.get("--warmup", "0").toInt()
    val retained = options.get("--retained", "0").toInt()
    val threads = options.get("--threads", "1").toInt()
    val order = options.get("--order", "legacy")
    val pause = options.get("--pause-ms", "0").toLong()
    val cpuSamples = options.has("--cpu-samples")
    val phases = options.has("--phase-samples")
    val samples = options.has("--samples") || cpuSamples || phases
    val mutate = options.has("--mutate")
    readonly = options.has("--readonly")
    val explicitSession = options.has("--session")
    require(!explicitSession || readonly) { "The explicit session uses the production read-only API" }
    require(!readonly || !mutate) { "Read-only output cannot be mutated" }
    require(loops in 1..100_000 && warmup in 0..100_000 && retained in 0..64 && threads in 1..8)
    require(order in setOf("legacy", "forward", "reverse", "random", "repeat"))
    require(pause in 0..60_000)
    val collectors = if (samples) ManagementFactory.getGarbageCollectorMXBeans() else emptyList()
    val threadBean = if (cpuSamples) {
        ManagementFactory.getThreadMXBean().also {
            require(it.isCurrentThreadCpuTimeSupported && it.isThreadCpuTimeEnabled) { "Thread CPU timing is unavailable" }
        }
    } else {
        null
    }
    System.load(library.toString())
    if (options.has("--pid-file")) Files.writeString(options.path("--pid-file"), ProcessHandle.current().pid().toString())
    FileInputStream(archive.toFile()).use { input ->
        val field = FileDescriptor::class.java.getDeclaredField("fd").apply { isAccessible = true }
        val start = System.nanoTime()
        val fd = field.getInt(input.fd)
        val size = Files.size(archive)
        val pages = if (explicitSession) {
            sessionHandle = openArchiveSession(fd, size, true)
            archiveSessionPageCount(sessionHandle)
        } else {
            openArchive(fd, size, true)
        }
        require(pages > 0) { "Unable to open benchmark archive" }
        val opened = System.nanoTime() - start
        val held = mutableListOf<ByteBuffer>()
        val executor = Executors.newFixedThreadPool(threads)
        var closed = 0L
        var heldRelease = 0L
        val result = try {
            if (if (explicitSession) archiveSessionNeedsPassword(sessionHandle) else needPassword()) {
                val password = options.get("--password", "")
                require(password.isNotEmpty() && if (explicitSession) archiveSessionProvidePassword(sessionHandle, password) else providePassword(password)) {
                    "Benchmark archive requires a valid password"
                }
            }
            val firstStart = System.nanoTime()
            releaseByteBuffer(checkNotNull(extract(0)))
            val firstPage = System.nanoTime() - firstStart
            repeat(retained) { held += checkNotNull(extract(it % pages)) }
            val ready = CountDownLatch(threads)
            val go = CountDownLatch(1)
            var measuredStart = 0L
            val records = List(threads) { Samples(loops, cpuSamples, phases) }
            val futures = (0 until threads).map { workerIndex ->
                executor.submit {
                    val worker = Worker()
                    val random = Random(0xE471L + workerIndex)
                    val indices = IntArray(warmup + loops) { iteration ->
                        when (order) {
                            "forward" -> iteration % pages
                            "reverse" -> pages - 1 - iteration % pages
                            "random" -> random.nextInt(pages)
                            "repeat" -> 0
                            else -> (iteration * 17) % pages
                        }
                    }
                    val record = records[workerIndex]
                    val warmed = runCatching { repeat(warmup) { measurePage(indices[it], if (mutate) it else -1, worker) } }
                    ready.countDown()
                    go.await()
                    warmed.getOrThrow()
                    repeat(loops) { iteration ->
                        val cpuBefore = threadBean?.currentThreadCpuTime ?: 0L
                        val before = if (cpuSamples) System.nanoTime() else 0L
                        val mutation = if (mutate) warmup + iteration else -1
                        record.times[iteration] = if (phases) measureParts(indices[warmup + iteration], mutation, worker) else measurePage(indices[warmup + iteration], mutation, worker)
                        if (cpuSamples) {
                            record.cpuTimes[iteration] = threadBean!!.currentThreadCpuTime - cpuBefore
                            record.starts[iteration] = before - measuredStart
                        }
                        if (phases) {
                            record.extracts[iteration] = worker.extractNs
                            record.touches[iteration] = worker.touchNs
                            record.releases[iteration] = worker.releaseNs
                        }
                    }
                    record.checksum = worker.checksum
                }
            }
            ready.await()
            val gcBefore = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            val gcTimeBefore = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }
            val uptime = ManagementFactory.getRuntimeMXBean().uptime
            measuredStart = System.nanoTime()
            go.countDown()
            futures.forEach { it.get() }
            val wall = System.nanoTime() - measuredStart
            val gcAfter = collectors.sumOf { it.collectionCount.coerceAtLeast(0) }
            val gcTimeAfter = collectors.sumOf { it.collectionTime.coerceAtLeast(0) }

            // Box samples only after every worker has stopped measuring and GC is snapshotted.
            fun flatten(get: (Samples) -> LongArray) = records.flatMap { get(it).toList() }
            val original = flatten { it.times }
            val times = original.sorted()
            val measured = if (samples) {
                mapOf(
                    "samples_ns" to original,
                    "gc_count" to gcAfter - gcBefore,
                    "gc_ms" to gcTimeAfter - gcTimeBefore,
                    "gc_supported" to collectors.all { it.collectionCount >= 0 && it.collectionTime >= 0 },
                    "gc_collectors" to collectors.map { it.name },
                )
            } else {
                emptyMap()
            }
            val cpu = if (cpuSamples) mapOf("thread_cpu_ns" to flatten { it.cpuTimes }, "starts_ns" to flatten { it.starts }, "measurement_uptime_ms" to uptime) else emptyMap()
            val parts = if (phases) mapOf("extract_ns" to flatten { it.extracts }, "touch_ns" to flatten { it.touches }, "release_ns" to flatten { it.releases }) else emptyMap()
            val result = mapOf(
                "archive" to archive.fileName.toString(), "library" to library.toString(),
                "loops" to loops, "warmup" to warmup, "retained" to retained, "threads" to threads, "order" to order,
                "mutate" to mutate, "readonly" to readonly, "instrumented" to (cpuSamples || phases || pause > 0),
                "session" to explicitSession, "open_ms" to opened / 1e6, "first_page_ms" to firstPage / 1e6, "median_ms" to times[times.size / 2] / 1e6,
                "p95_ms" to times[(times.size * 95 / 100).coerceAtMost(times.lastIndex)] / 1e6,
                "p99_ms" to times[(times.size * 99 / 100).coerceAtMost(times.lastIndex)] / 1e6,
                "wall_ms" to wall / 1e6, "pages_per_second" to (loops.toDouble() * threads * 1e9 / wall),
                "checksum" to records.sumOf { it.checksum },
            ) + measured + cpu + parts
            // Diagnostic-only pause with the archive and retained leases still alive. It is outside
            // all operation/throughput timers, so vmmap/footprint can inspect the actual mappings.
            if (pause > 0) Thread.sleep(pause)
            result
        } finally {
            executor.shutdownNow()
            val beforeRelease = System.nanoTime()
            held.forEach(::releaseByteBuffer)
            heldRelease = System.nanoTime() - beforeRelease
            val beforeClose = System.nanoTime()
            if (explicitSession) {
                closeArchiveSession(sessionHandle)
                sessionHandle = 0
            } else {
                closeArchive()
            }
            closed = System.nanoTime() - beforeClose
        }
        return result + mapOf("held_release_ms" to heldRelease / 1e6, "close_ms" to closed / 1e6)
    }
}
