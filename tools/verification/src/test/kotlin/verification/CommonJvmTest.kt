package verification

import org.junit.Assert.assertThrows
import org.junit.Test

class CommonJvmTest {
    @Test fun wrongDirtyMissingOrOldProducerIdentityCannotCertifyTheInput() {
        val source = "1".repeat(40)
        // An identity-only fixture never supplies compiler bytes or executes an application.
        val identity = mapOf(
            "source_commit" to source,
            "source_dirty" to false,
            "schema_version" to SHARED_SCHEMA,
            "verification_scope" to SHARED_SCOPE,
            "shared_library_artifacts_verified" to true,
            "portable_common_targets" to portableCommonTargets,
        )
        for (changed in listOf(emptyMap(), identity + ("source_commit" to "2".repeat(40)), identity + ("source_dirty" to true), identity + ("schema_version" to 3), identity + ("shared_library_artifacts_verified" to false), identity + ("portable_common_targets" to listOf("android")))) {
            assertThrows(IllegalArgumentException::class.java) { verifyCommonJvmProducerIdentity(changed, source) }
        }
    }

    @Test fun originalTitlesAloneOrDeclarationOnlyTestsCannotPassAsFilesystemExecution() {
        for (receipt in listOf(
            emptyMap(),
            mapOf("status" to "NO_TEST_SOURCES", "executed" to false, "source_files" to 0, "tests" to 0),
            mapOf("status" to "PASS", "executed" to true, "source_files" to 1, "tests" to 3, "suites" to mapOf("GalleryTitlesTest[desktop]" to 3)),
            mapOf("status" to "PASS", "executed" to false, "source_files" to 2, "tests" to 8, "suites" to mapOf("GalleryTitlesTest[desktop]" to 3, "FilesystemTest[desktop]" to 5)),
        )) {
            assertThrows(IllegalArgumentException::class.java) { verifyCommonJvmTests(receipt) }
        }
    }
}
