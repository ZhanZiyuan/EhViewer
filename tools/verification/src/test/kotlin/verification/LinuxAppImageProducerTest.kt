package verification

import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxAppImageProducerTest {
    @Test fun bothCpusRequireExactCleanSourceVersionBytesAndBothNativeEntryModes() {
        val source = "a".repeat(40)
        val digest = "b".repeat(64)
        val version = "1.15.10"
        for (runtime in appImageRuntimes.values) {
            val architecture = runtime.architecture
            val target = "$architecture-unknown-linux-gnu"
            val embedded = mapOf("source_commit" to source, "source_dirty" to false, "version" to version, "target" to target, "format" to "APPIMAGE_TYPE_2", "runtime_version" to "20251108", "runtime_sha256" to runtime.sha256)
            val proof = mapOf("result" to "PASS", "source_commit" to source, "source_dirty" to false, "version" to version, "target" to target, "file" to "EhViewer-$version-linux-$architecture.AppImage", "sha256" to digest, "execution_mode" to "NATIVE_APPIMAGE_EXTRACT_AND_RUN_AND_FUSE", "normal_fuse_mount" to "PASS", "publish_enabled" to false, "package_source" to embedded)
            verifyLinuxAppImageProducer(proof, source, version, architecture, digest)
            for ((key, invalid) in listOf("result" to "FAIL", "source_commit" to "c".repeat(40), "source_dirty" to true, "version" to "1.16.0", "target" to "wrong-linux-gnu", "file" to "renamed.AppImage", "sha256" to "d".repeat(64), "execution_mode" to "EXTRACT_ONLY", "normal_fuse_mount" to "NOT_VERIFIED", "publish_enabled" to true)) {
                assertTrue("Accepted changed $architecture producer $key", runCatching { verifyLinuxAppImageProducer(proof + (key to invalid), source, version, architecture, digest) }.isFailure)
            }
            for ((key, invalid) in listOf("source_dirty" to true, "source_commit" to "e".repeat(40), "runtime_sha256" to "f".repeat(64))) {
                assertTrue(runCatching { verifyLinuxAppImageProducer(proof + ("package_source" to (embedded + (key to invalid))), source, version, architecture, digest) }.isFailure)
            }
        }
    }
}
