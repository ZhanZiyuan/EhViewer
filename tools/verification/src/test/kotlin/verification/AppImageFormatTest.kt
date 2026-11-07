package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

/** Structural fixtures deliberately do not claim to be runnable application packages. */
class AppImageFormatTest {
    private fun header(runtime: AppImageRuntime): ByteArray = ByteBuffer.allocate(64).order(ByteOrder.LITTLE_ENDIAN).apply {
        putInt(0, 0x464c457f)
        put(4, 2)
        put(5, 1)
        put(6, 1)
        put(8, 'A'.code.toByte())
        put(9, 'I'.code.toByte())
        put(10, 2)
        putShort(16, 3)
        putShort(18, runtime.machine.toShort())
        putInt(20, 1)
        putLong(24, 4096)
    }.array()

    @Test fun oppositeCpuOrdinaryElfWrongMarkerAndTruncationAreRejected() {
        for (runtime in appImageRuntimes.values) {
            val valid = header(runtime)
            verifyAppImageHeader(valid, runtime)
            for (other in appImageRuntimes.values.filter { it != runtime }) assertTrue(runCatching { verifyAppImageHeader(valid, other) }.isFailure)
            for (invalid in listOf(valid.copyOf(32), valid.copyOf().also { it[8] = 0 }, valid.copyOf().also { it[10] = 1 }, valid.copyOf().also { it[5] = 2 })) assertTrue(runCatching { verifyAppImageHeader(invalid, runtime) }.isFailure)
        }
    }

    @Test fun plausibleCpuAndTypeMarkerCannotReplacePinnedRuntimeAndFilesystem() {
        temporary { directory ->
            for (runtime in appImageRuntimes.values) {
                val file = directory.resolve(runtime.architecture)
                Files.write(file, header(runtime))
                assertTrue(runCatching { inspectLinuxAppImage(file, runtime.architecture) }.isFailure)
                val forged = ByteArray((runtime.size + 96).toInt())
                header(runtime).copyInto(forged)
                Files.write(file, forged)
                assertTrue(runCatching { inspectLinuxAppImage(file, runtime.architecture) }.exceptionOrNull()?.message?.contains("pinned upstream") == true)
            }
        }
    }
}
