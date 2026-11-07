package verification

import java.security.KeyStore
import java.security.PrivateKey
import java.security.Signature
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleaseDryRunTest {
    private val environment = mapOf(
        "GITHUB_ACTIONS" to "true",
        "GITHUB_REPOSITORY" to "ZhanZiyuan/EhViewer",
        "GITHUB_EVENT_NAME" to "workflow_dispatch",
        "GITHUB_REF" to "refs/heads/feature/kmp",
        "GITHUB_WORKFLOW_REF" to "ZhanZiyuan/EhViewer/.github/workflows/releases.yml@refs/heads/feature/kmp",
        "RELEASE_PUBLISH" to "false",
        "PUBLIC_TEST_DRY_RUN" to "true",
    )

    @Test fun publicSigningRejectsEveryProductionOrForeignContext() {
        requirePublicReleaseDryRun(environment)
        requirePublicReleaseDryRun(environment + mapOf("GITHUB_REF" to "refs/heads/main", "GITHUB_WORKFLOW_REF" to "ZhanZiyuan/EhViewer/.github/workflows/releases.yml@refs/heads/main"))
        listOf(
            "GITHUB_REPOSITORY" to "untrusted/fork",
            "GITHUB_EVENT_NAME" to "pull_request",
            "GITHUB_EVENT_NAME" to "workflow_run",
            "GITHUB_REF" to "refs/heads/other",
            "GITHUB_REF" to "refs/tags/v1.16.0",
            "GITHUB_WORKFLOW_REF" to "ZhanZiyuan/EhViewer/.github/workflows/ci.yml@refs/heads/feature/kmp",
            "RELEASE_PUBLISH" to "true",
            "PUBLIC_TEST_DRY_RUN" to "false",
        ).forEach { changed -> assertThrows(changed.toString(), IllegalArgumentException::class.java) { requirePublicReleaseDryRun(environment + changed) } }
        environment.keys.forEach { missing -> assertThrows(missing, IllegalArgumentException::class.java) { requirePublicReleaseDryRun(environment - missing) } }
        assertThrows(IllegalArgumentException::class.java) { requirePublicReleaseDryRun(environment, localComponentTest = true) }
        assertThrows(IllegalArgumentException::class.java) { requirePublicReleaseDryRun(emptyMap()) }
        requirePublicReleaseDryRun(emptyMap(), localComponentTest = true)
        assertThrows(IllegalArgumentException::class.java) { requirePublicReleaseDryRun(mapOf("RELEASE_PUBLISH" to "true"), localComponentTest = true) }
    }

    @Test fun generatedPublicFixtureActuallySignsAndIsNeverTheProductionIdentity() {
        val (fixture, bytes) = publicSigningFixture()
        val password = fixture.str("password").toCharArray()
        val store = KeyStore.getInstance("JKS").apply { load(bytes.inputStream(), password) }
        val challenge = "EhViewer public test fixture has no production authority".toByteArray()
        val signed = Signature.getInstance("SHA256withRSA").apply {
            initSign(store.getKey(fixture.str("alias"), password) as PrivateKey)
            update(challenge)
        }.sign()
        val verifier = Signature.getInstance("SHA256withRSA").apply { initVerify(store.getCertificate(fixture.str("alias"))) }
        verifier.update(challenge)
        assertEquals(true, verifier.verify(signed))
        verifier.update(challenge + byteArrayOf(1))
        assertEquals(false, verifier.verify(signed))
        assertNotEquals(CERTIFICATE, PUBLIC_RELEASE_TEST_CERTIFICATE)
        requirePublicReleaseCertificate(List(4) { mapOf("certificate" to listOf(PUBLIC_RELEASE_TEST_CERTIFICATE)) })
        for (certificates in listOf(emptyList(), listOf(CERTIFICATE), listOf(PUBLIC_RELEASE_TEST_CERTIFICATE, CERTIFICATE), listOf("0".repeat(64)))) {
            assertThrows(IllegalArgumentException::class.java) { requirePublicReleaseCertificate(listOf(mapOf("certificate" to certificates))) }
        }
        assertThrows(IllegalArgumentException::class.java) { requirePublicReleaseCertificate(emptyList()) }
    }

    @Test fun matrixSigningModesCannotBeMixedOrPromoted() {
        val source = "1".repeat(40)
        val parts = (abis.keys + "universal").associateWith { abi ->
            mapOf(
                "mode" to "verified-public-test-dry-run", "public_test_dry_run" to true, "android_certificate_sha256" to PUBLIC_RELEASE_TEST_CERTIFICATE,
                "tag" to "v1.15.10", "version" to "1.15.10", "versionCode" to 180074,
                "source_commit" to source, "source_dirty" to false, "source_status" to emptyList<String>(), "diagnostics" to mapOf("retention_days" to 90),
                "attachments" to listOf(mapOf("abi" to abi, "name" to "EhViewer-1.15.10-$abi.apk", "sha256" to "a".repeat(64))),
            )
        }
        assertEquals(4, releasePartsPlan(parts, "v1.15.10", source, publicTestDryRun = true).size)
        assertThrows(IllegalArgumentException::class.java) { releasePartsPlan(parts, "v1.15.10", source) }
        for (changed in listOf("public_test_dry_run" to false, "mode" to "verified-payload", "android_certificate_sha256" to CERTIFICATE)) {
            assertThrows(IllegalArgumentException::class.java) {
                releasePartsPlan(parts + ("arm64-v8a" to (parts.getValue("arm64-v8a") + changed)), "v1.15.10", source, publicTestDryRun = true)
            }
        }
        val options = Options(listOf("--public-test-dry-run", "--local-component-test", "--tag", "v1.15.10"))
        assertEquals("v1.15.10", options.get("--tag"))
        assertEquals(true, options.has("--public-test-dry-run") && options.has("--local-component-test"))
    }
}
