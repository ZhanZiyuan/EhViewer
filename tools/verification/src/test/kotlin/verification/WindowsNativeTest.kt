package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertTrue
import org.junit.Test

class WindowsNativeTest {
    @Test fun rejectsWrongArchitectureObjectFilesAndInvalidPeOffsets() {
        val pe = ByteBuffer.allocate(256).order(ByteOrder.LITTLE_ENDIAN).apply {
            putShort(0, 0x5a4d)
            putInt(60, 128)
            putInt(128, 0x4550)
            putShort(132, 0xaa64.toShort())
            putShort(150, 2)
            putShort(152, 0x20b)
        }.array()
        verifyWindowsArm64Pe(pe)
        for (invalid in listOf(
            pe.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(60, Int.MAX_VALUE) },
            pe.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(60, -1) },
            pe.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(132, 0x8664.toShort()) },
            pe.copyOf().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putShort(132, 0xa641.toShort()) },
            pe.copyOf().also { it[0] = 0 },
            pe.copyOf().also { it[128] = 0 },
            pe.copyOf().also { it[150] = 0 },
            pe.copyOf().also { it[153] = 1 },
            pe.copyOf(153),
            pe.copyOf(63),
        )) {
            assertTrue("Invalid PE was accepted", runCatching { verifyWindowsArm64Pe(invalid) }.isFailure)
        }
    }
}
