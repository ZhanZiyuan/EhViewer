package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLinuxTest {
    @Test fun rejectsWrongCpuFormatEndianTypeAndTruncation() {
        val elf = ByteBuffer.allocate(32).order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0x464c457f)
            put(4, 2)
            put(5, 1)
            put(6, 1)
            putShort(16, 3)
            putShort(18, 183)
            putInt(20, 1)
        }.array()
        verifyLinuxArm64Elf(elf)
        for ((offset, value) in listOf(0 to 0, 4 to 1, 5 to 2, 6 to 0, 16 to 1, 18 to 62, 20 to 0)) {
            assertTrue("Invalid ELF byte at $offset", runCatching { verifyLinuxArm64Elf(elf.copyOf().also { it[offset] = value.toByte() }) }.isFailure)
        }
        assertTrue(runCatching { verifyLinuxArm64Elf(elf.copyOf(24)) }.isFailure)
    }
}
