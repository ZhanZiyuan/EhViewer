package verification

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeDomainTest {
    // A minimal structural Mach-O fixture, not a claimed compiled or executed program.
    private fun header(cpu: Int = 0x0100000c, platform: Int = 7): ByteArray = ByteBuffer.allocate(56)
        .order(ByteOrder.LITTLE_ENDIAN).apply {
            putInt(0, 0xfeedfacf.toInt())
            putInt(4, cpu)
            putInt(12, 2)
            putInt(16, 1)
            putInt(20, 24)
            putInt(32, 0x32)
            putInt(36, 24)
            putInt(40, platform)
            putInt(44, 15 shl 16)
            putInt(48, (26 shl 16) or (4 shl 8))
        }.array()

    @Test fun simulatorFormatIsIndependentOfTheFileExtension() {
        assertEquals(
            mapOf("architecture" to "arm64", "platform" to "IOSSIMULATOR", "declared_minimum_ios" to "15.0.0", "sdk" to "26.4.0"),
            verifyDomainSimulatorMachO(header()),
        )
    }

    @Test fun wrongCpuDevicePlatformAndMalformedLoadCommandsFail() {
        val variants = listOf(
            header(cpu = 0x01000007),
            header(platform = 2),
            header(platform = 1),
            header().copyOf(40),
            header().also { it[0] = 0 },
            header().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(36, Int.MAX_VALUE) },
            header().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(16, 2) },
            header().also { ByteBuffer.wrap(it).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 0x19) },
        )
        variants.forEach { bytes -> assertThrows(IllegalArgumentException::class.java) { verifyDomainSimulatorMachO(bytes) } }
    }

    private fun manifestOnly(target: String, compiler: String): ByteArray = ByteArrayOutputStream().also { bytes ->
        ZipOutputStream(bytes).use { zip ->
            zip.putNextEntry(ZipEntry("default/manifest"))
            zip.write(
                (
                    "builtins_platform=NATIVE\nnative_targets=$target\ncompiler_version=$compiler\n" +
                        "short_name=domain\nunique_name=EhViewer.core\\:domain\ndepends=org.jetbrains.kotlinx\\:kotlinx-serialization-cbor org.jetbrains.kotlinx\\:kotlinx-serialization-core stdlib\n"
                    ).toByteArray(),
            )
            zip.closeEntry()
        }
    }.toByteArray()

    @Test fun declarationOnlyMissingIrWrongTargetAndCompilerCannotPassAsNativeLibrary() {
        for ((target, compiler) in listOf("ios_arm64" to "2.4.10", "ios_simulator_arm64" to "2.4.10", "ios_arm64" to "0.0.0")) {
            assertThrows(IllegalArgumentException::class.java) {
                domainNativeKlibEvidence(manifestOnly(target, compiler), "ios_arm64", "2.4.10")
            }
        }
        assertThrows(IllegalArgumentException::class.java) { domainNativeKlibEvidence(byteArrayOf(), "ios_arm64", "2.4.10") }
    }

    @Test fun missingPublicNativeApiCannotBeCertifiedByADump() {
        for (text in listOf("", "public final fun selectGalleryTitle", "public abstract interface ArchiveCatalog")) {
            assertThrows(IllegalArgumentException::class.java) { verifyDomainNativeApiDump(text) }
        }
    }

    @Test fun missingDuplicateOrUnreviewedNativeRuntimeDependencyFails() {
        val valid = domainNativeDependencies.joinToString(" ")
        verifyDomainNativeDependencies(valid)
        for (value in listOf(null, "stdlib", "$valid stdlib", "$valid unreviewed:runtime", valid.replace("kotlinx-serialization-cbor", "unreviewed"))) {
            assertThrows(IllegalArgumentException::class.java) { verifyDomainNativeDependencies(value) }
        }
    }

    @Test fun titleOnlyOrMissingModelTestReceiptCannotCertifyDomain() {
        val titlesOnly = mapOf(
            "status" to "PASS",
            "executed" to true,
            "source_files" to 1,
            "tests" to 3,
            "suites" to mapOf("iosSimulatorArm64Test.com.ehviewer.core.domain.GalleryDomainTitlesTest" to 3),
        )
        assertThrows(IllegalArgumentException::class.java) { verifyDomainTests(titlesOnly, native = true) }
        val wrongSuite = titlesOnly + mapOf("source_files" to 2, "tests" to 8)
        assertThrows(IllegalArgumentException::class.java) { verifyDomainTests(wrongSuite, native = true) }
    }
}
