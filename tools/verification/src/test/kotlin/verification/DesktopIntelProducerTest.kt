package verification

import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopIntelProducerTest {
    @Test fun armPackageRequiresCleanSameSourceNativeArmExecutionAndExactBytes() {
        val source = "a".repeat(40)
        val digest = "b".repeat(64)
        val receipt = mapOf(
            "source_commit" to source, "source_dirty" to false, "result" to "PASS",
            "physical_host" to "arm64", "artifact_build_host" to "arm64", "arm_hardware_probe" to "1",
            "rosetta_probe" to "0", "target" to "aarch64-apple-darwin", "execution_mode" to "native-arm64", "dmg_sha256" to digest,
        )
        verifyArm64Producer(receipt, source, digest)
        verifyArm64Producer(receipt + ("rosetta_probe" to ""), source, digest)
        for ((key, invalid) in listOf(
            "source_commit" to "c".repeat(40), "source_dirty" to true, "result" to "FAIL",
            "physical_host" to "x86_64", "artifact_build_host" to "x86_64", "arm_hardware_probe" to "0",
            "rosetta_probe" to "1", "target" to "x86_64-apple-darwin", "execution_mode" to "rosetta", "dmg_sha256" to "d".repeat(64),
        )) {
            assertTrue("Accepted invalid ARM producer field: $key", runCatching { verifyArm64Producer(receipt + (key to invalid), source, digest) }.isFailure)
        }
    }

    @Test fun receiptMustBindSuccessfulCleanArmExecutionToExactSourceAndBytes() {
        val source = "a".repeat(40)
        val digest = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        val receipt = mapOf(
            "source_commit" to source,
            "source_dirty" to false,
            "result" to "PASS",
            "physical_host" to "arm64",
            "rosetta_probe" to "1",
            "target" to "x86_64-apple-darwin",
            "dmg_sha256" to digest,
        )
        verifyIntelProducer(receipt, source, digest)
        // Each looks like a successful artifact receipt but violates a required provenance boundary.
        for ((key, invalid) in listOf(
            "source_commit" to "b".repeat(40),
            "source_dirty" to true,
            "result" to "FAIL",
            "physical_host" to "x86_64",
            "rosetta_probe" to "0",
            "target" to "aarch64-apple-darwin",
            "dmg_sha256" to "0".repeat(64),
        )) {
            assertTrue("Accepted invalid producer field: $key", runCatching { verifyIntelProducer(receipt + (key to invalid), source, digest) }.isFailure)
        }
    }
}
