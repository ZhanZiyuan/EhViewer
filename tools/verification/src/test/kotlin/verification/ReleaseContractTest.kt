package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReleaseContractTest {
    private val source = "1234567890abcdef1234567890abcdef12345678"
    private val settings = "val releaseVersion = \"1.15.10\"\n    versionCode = 180074\n"
    private val names = listOf(
        "EhViewer-1.15.10-arm64-v8a.apk", "EhViewer-1.15.10-armeabi-v7a.apk",
        "EhViewer-1.15.10-universal.apk", "EhViewer-1.15.10-x86_64.apk",
        "EhViewer-1.15.10-arm64-unsigned.ipa", "EhViewer-1.15.10-macos-x86_64.dmg",
        "EhViewer-1.15.10-macos-arm64.dmg", "EhViewer-1.15.10-linux-x86_64.AppImage",
        "EhViewer-1.15.10-linux-aarch64.AppImage", "EhViewer-1.15.10-windows-x86_64.zip",
        "EhViewer-1.15.10-windows-arm64.zip",
    )

    @Test fun exactDeliveryContractDoesNotClaimRealArtifactsOrPublication() {
        val contract = releaseContract(settings, source)
        val targets = contract["targets"].array().map { it.obj() }
        assertEquals(names.toSet(), targets.map { it.str("file") }.toSet())
        assertEquals(11, targets.size)
        assertEquals(source, contract["source_commit"])
        assertEquals(180074, contract["android_version_code"])
        assertEquals(180074, releaseContract(settings.replace("    versionCode", "\tversionCode").replace("\n", "\r\n"), source)["android_version_code"])
        assertEquals("CONTRACT_ONLY", contract["verification_scope"])
        assertEquals(false, contract["artifacts_verified"])
        assertEquals(false, contract["publish_enabled"])
        verifyReleaseAssetNames("1.15.10", names.reversed())
        val runners = targets.associate { it.str("file") to "${it.str("runner")}/${it.str("runner_architecture")}" }
        names.take(4).forEach { assertEquals("ubuntu-latest/X64", runners[it]) }
        names.slice(4..6).forEach { assertEquals("macos-latest/ARM64", runners[it]) }
        assertEquals("ubuntu-latest/X64", runners[names[7]])
        assertEquals("ubuntu-24.04-arm/ARM64", runners[names[8]])
        assertEquals("windows-latest/X64", runners[names[9]])
        assertEquals("windows-11-arm/ARM64", runners[names[10]])
        assertEquals(listOf("macos-x64"), targets.filter { it["rosetta_required"] == true }.map { it.str("id") })
        assertEquals("UNSIGNED_NOT_DIRECTLY_INSTALLABLE", targets.single { it["platform"] == "ios" }["signing_required"])
        assertTrue(targets.filter { it["platform"] == "android" }.all { it["signing_required"] == "ORIGINAL_ANDROID_CERTIFICATE" })
    }

    @Test fun everyMissingTargetAndDuplicateIsRejected() {
        names.indices.forEach { index ->
            assertThrows(IllegalArgumentException::class.java) { verifyReleaseAssetNames("1.15.10", names.filterIndexed { i, _ -> i != index }) }
            val duplicate = names.toMutableList().apply { this[index] = names[(index + 1) % names.size] }
            assertThrows(IllegalArgumentException::class.java) { verifyReleaseAssetNames("1.15.10", duplicate) }
        }
    }

    @Test fun extraNestedRenamedWrongCaseAndMixedVersionNamesAreRejected() {
        for (unexpected in listOf("diagnostics.zip", "sub/${names[0]}", "sub\\${names[0]}", "EhViewer-1.15.10-linux-arm64.AppImage", "EhViewer-1.15.10-windows-aarch64.zip", "EhViewer-1.15.10-arm64.ipa", "EhViewer-1.16.0-arm64-v8a.apk", names[0].uppercase())) {
            assertThrows(IllegalArgumentException::class.java) { verifyReleaseAssetNames("1.15.10", names + unexpected) }
            assertThrows(IllegalArgumentException::class.java) { verifyReleaseAssetNames("1.15.10", names.drop(1) + unexpected) }
        }
    }

    @Test fun requestedVersionCannotAdvanceTheAuthoritativeSource() {
        assertEquals("v1.15.10", releaseContract(settings, source, "1.15.10")["planned_tag"])
        for (tag in listOf("v1.16.0", "v1.15.10-SNAPSHOT", "../1.15.10", "v01.15.10", "v1.15.10\n")) {
            assertThrows(IllegalArgumentException::class.java) { releaseContract(settings, source, tag) }
        }
        assertThrows(IllegalArgumentException::class.java) { verifyReleaseAssetNames("v1.15.10", names) }
        // A name-only validator never checks ZIP magic, actual runtime architecture or a package hash.
        assertFalse(releaseContract(settings, source)["artifacts_verified"] as Boolean)
    }

    @Test fun invalidOrAmbiguousSourceIdentityIsRejected() {
        for (sha in listOf("", source.take(12), source.uppercase(), "0".repeat(40), source + "\n")) {
            assertThrows(IllegalArgumentException::class.java) { releaseContract(settings, sha) }
        }
        for (invalid in listOf("", settings + settings, settings.replace("180074", "0"), settings.replace("180074", "2100000001"), settings.replace("180074", "999999999999"), settings.replace("1.15.10", "1.15.10-SNAPSHOT"))) {
            assertThrows(IllegalArgumentException::class.java) { releaseContract(invalid, source) }
        }
    }
}
