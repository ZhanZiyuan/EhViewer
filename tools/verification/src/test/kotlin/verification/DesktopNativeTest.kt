package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopNativeTest {
    private fun header(mach: Boolean): ByteArray = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
        if (mach) {
            putInt(0, 0xfeedfacf.toInt())
            putInt(4, 0x100000c)
            putInt(12, 6)
        } else {
            putInt(0, 0x464c457f)
            put(4, 2)
            put(5, 1)
            putShort(16, 3)
            putShort(18, 62)
        }
    }.array()

    @Test fun validatesHostFormatAndArchitectureTogether() {
        verifyDesktopLibrary(header(true), "aarch64-apple-darwin")
        verifyDesktopLibrary(header(false), "x86_64-unknown-linux-gnu")
        assertTrue(runCatching { verifyDesktopLibrary(header(true), "x86_64-unknown-linux-gnu") }.isFailure)
        assertTrue(runCatching { verifyDesktopLibrary(header(false), "aarch64-apple-darwin") }.isFailure)
    }

    @Test fun rejectsWrongCpuAndTruncatedOrUnsupportedLibraries() {
        val wrongCpu = header(true).also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 0x1000007) }
        assertTrue(runCatching { verifyDesktopLibrary(wrongCpu, "aarch64-apple-darwin") }.isFailure)
        assertTrue(runCatching { verifyDesktopLibrary(ByteArray(20), "x86_64-unknown-linux-gnu") }.isFailure)
        assertTrue(runCatching { verifyDesktopLibrary(header(false), "aarch64-pc-windows-msvc") }.isFailure)
    }
}
