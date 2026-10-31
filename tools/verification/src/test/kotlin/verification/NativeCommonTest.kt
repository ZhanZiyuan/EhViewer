package verification

import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCommonTest {
    @Test fun titleOnlySkippedOrDomainTestsCannotCertifyNativeFilesystem() {
        val valid = mapOf(
            "status" to "PASS",
            "executed" to true,
            "source_files" to 2,
            "tests" to 8,
            "suites" to mapOf(
                "iosSimulatorArm64Test.com.ehviewer.core.domain.GalleryTitlesTest" to 3,
                "iosSimulatorArm64Test.com.ehviewer.core.files.FilesystemTest" to 5,
            ),
        )
        for (receipt in listOf(
            emptyMap(),
            valid + ("executed" to false),
            valid + ("tests" to 3),
            valid + ("source_files" to 1),
            valid + ("suites" to mapOf("GalleryTitlesTest[desktop]" to 3, "FilesystemTest[desktop]" to 5)),
            valid + (
                "suites" to mapOf(
                    "iosSimulatorArm64Test.com.ehviewer.core.domain.GalleryDomainTitlesTest" to 3,
                    "iosSimulatorArm64Test.com.ehviewer.core.model.GalleryModelWireTest" to 5,
                )
                ),
        )) {
            assertThrows(IllegalArgumentException::class.java) { verifyCommonNativeTests(receipt) }
        }
    }

    @Test fun wrongPlatformOrMissingCompilerBytesCannotCertifyCommon() {
        assertThrows(IllegalArgumentException::class.java) {
            nativeLibraryKlibEvidence(byteArrayOf(), "ios_arm64", "2.4.10", commonNativeSpec)
        }
        for (target in listOf("jvm", "ios_x64", "macos_arm64")) {
            assertThrows(IllegalArgumentException::class.java) {
                nativeLibraryKlibEvidence(byteArrayOf(), target, "2.4.10", commonNativeSpec)
            }
        }
    }

    @Test fun publicFilesystemApiCannotBeReplacedByTitleOrDeclarationOnlyDump() {
        for (text in listOf("", "public final val SystemFileSystem", "public final fun selectGalleryTitle")) {
            assertThrows(IllegalArgumentException::class.java) { verifyCommonNativeApiDump(text) }
        }
    }
}
