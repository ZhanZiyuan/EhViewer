package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class IosStartupTest {
    private fun runtime(version: String, available: Boolean = true, platform: String = "iOS") = mapOf(
        "identifier" to "com.apple.CoreSimulator.SimRuntime.$platform-${version.replace('.', '-')}",
        "version" to version,
        "name" to "$platform $version",
        "isAvailable" to available,
    )

    @Test fun selectsAnAvailableIosRuntimeNumerically() {
        val result = latestIosRuntime(listOf(runtime("26.5.1"), runtime("27.0"), runtime("28.0", false), runtime("30.0", platform = "tvOS")))
        assertEquals("27.0", result["version"])
    }

    @Test fun missingRuntimeFailsInsteadOfClaimingStartupSuccess() {
        assertTrue(runCatching { latestIosRuntime(listOf(runtime("27.0", false))) }.isFailure)
        assertTrue(runCatching { latestIosRuntime(emptyList()) }.isFailure)
    }

    @Test fun explicitRuntimeMustBeAvailableAndDoesNotSilentlyFallBack() {
        val runtimes = listOf(runtime("26.5"), runtime("27.0"), runtime("28.0", false))
        assertEquals("26.5", latestIosRuntime(runtimes, "com.apple.CoreSimulator.SimRuntime.iOS-26-5")["version"])
        assertTrue(runCatching { latestIosRuntime(runtimes, "com.apple.CoreSimulator.SimRuntime.iOS-28-0") }.isFailure)
        assertTrue(runCatching { latestIosRuntime(runtimes, "com.apple.CoreSimulator.SimRuntime.iOS-25-0") }.isFailure)
    }

    @Test fun usesOnlyDeviceTypesSupportedByTheSelectedRuntime() {
        val ipad = mapOf("productFamily" to "iPad", "identifier" to "ipad")
        val iphone = mapOf("productFamily" to "iPhone", "identifier" to "supported-iphone")
        assertEquals(iphone, runtimeIphone(mapOf("supportedDeviceTypes" to listOf(ipad, iphone))))
    }

    @Test fun missingCompatiblePhoneIsAnExplicitFailure() {
        assertTrue(runCatching { runtimeIphone(mapOf("supportedDeviceTypes" to emptyList<Any>())) }.isFailure)
        assertTrue(runCatching { runtimeIphone(emptyMap()) }.isFailure)
    }

    @Test fun requiresTheExactBundleAndAPositivePidFromSimulator() {
        assertEquals(1234L, iosLaunchPid("moe.tarsin.ehviewer: 1234\n", "moe.tarsin.ehviewer"))
        assertTrue(runCatching { iosLaunchPid("com.apple.Preferences: 1234\n", "moe.tarsin.ehviewer") }.isFailure)
        assertTrue(runCatching { iosLaunchPid("moe.tarsin.ehviewer: 0\n", "moe.tarsin.ehviewer") }.isFailure)
        assertTrue(runCatching { iosLaunchPid("Application launch failed", "moe.tarsin.ehviewer") }.isFailure)
    }

    @Test fun requiresMeasuredRustOwnershipEvidenceFromEachProcess() {
        assertEquals(2000, rustInteropResults("EhViewer[123] EhViewerM1:rust-ffi-ready completed=2000 live=0\n"))
        assertEquals(3000, rustInteropResults("EhViewerM1:rust-ffi-ready completed=3000 live=0"))
        for (text in listOf("", "EhViewerM1:rust-ffi-ready completed=10 live=0", "EhViewerM1:rust-ffi-ready completed=2000 live=1")) {
            assertTrue(runCatching { rustInteropResults(text) }.isFailure)
        }
        assertTrue(runCatching { rustInteropResults("EhViewerM1:rust-ffi-ready completed=2000 live=0\n".repeat(2)) }.isFailure)
    }
}
