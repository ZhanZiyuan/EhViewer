package verification

import java.time.Instant
import java.time.OffsetDateTime
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
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

    @Test fun generalAcceptanceDoesNotImplicitlySelectADeviceSpecificRuntime() {
        val specialized = runtime("27.2") + ("identifier" to "com.apple.CoreSimulator.SimRuntime.iOS-27-2-iPhone19-4")
        assertEquals("27.0", latestIosRuntime(listOf(runtime("27.0"), specialized))["version"])
        assertEquals(specialized, latestIosRuntime(listOf(specialized), specialized["identifier"] as String))
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

    @Test fun logWindowRetainsBothFractionalBoundaryEventsAndAllLifecycleTransitions() {
        val events = listOf(
            "2026-10-28T23:59:59.999999Z",
            "2026-10-29T00:00:01.125Z",
            "2026-10-29T00:00:03.500Z",
            "2026-10-29T00:00:05.001Z",
        ).map(Instant::parse)
        val query = iosLogQuery(events.first(), events.last())
        assertTrue(events.all { !it.isBefore(query.start) && it.isBefore(query.end) })
        assertTrue(Instant.parse("2026-10-28T23:55:00Z").isBefore(query.start))
        assertEquals(Instant.parse("2026-10-28T23:59:58Z"), query.start)
        assertEquals(Instant.parse("2026-10-29T00:00:06Z"), query.end)
    }

    @Test fun logCommandUsesExplicitUtcBoundsAndPreservesTheFullOriginalPredicate() {
        val query = iosLogQuery(
            OffsetDateTime.parse("2026-10-29T07:00:00.500+08:00").toInstant(),
            OffsetDateTime.parse("2026-10-29T07:01:02.900+08:00").toInstant(),
        )
        val arguments = query.arguments("owned-simulator", "moe.tarsin.ehviewer")
        assertEquals(listOf("xcrun", "simctl", "spawn", "owned-simulator", "log", "show", "--style", "compact"), arguments.take(8))
        assertEquals("2026-10-28 22:59:59+0000", arguments[arguments.indexOf("--start") + 1])
        assertEquals("2026-10-28 23:01:03+0000", arguments[arguments.indexOf("--end") + 1])
        assertEquals("process == \"EhViewer\" OR eventMessage CONTAINS \"moe.tarsin.ehviewer\"", arguments.last())
        assertFalse(arguments.contains("--last"))
    }

    @Test fun reversedLogWindowsFailInsteadOfCollectingAnUnrelatedRange() {
        val now = Instant.parse("2026-10-29T00:00:00Z")
        assertThrows(IllegalArgumentException::class.java) { iosLogQuery(now, now.minusNanos(1)) }
        val instant = iosLogQuery(now, now)
        assertTrue(instant.start.isBefore(now) && instant.end.isAfter(now))
    }

    private fun event(pid: Int, marker: String) = "2026-10-29 00:00:00.001 Df EhViewer[$pid:abc] EhViewerM1:$marker\n"

    private fun lifecycle() = listOf(
        101 to "cold-start", 101 to "rust-ffi-ready completed=2000 live=0", 101 to "compose-ready",
        101 to "active", 101 to "background", 101 to "active", 202 to "cold-start",
        202 to "rust-ffi-ready completed=2000 live=0", 202 to "compose-ready", 202 to "active",
    )

    @Test fun unifiedLogRequiresBothRealProcessLifecyclesAndOwnershipEvidence() {
        val log = lifecycle().joinToString("") { (pid, marker) -> event(pid, marker) }
        val result = iosUnifiedLifecycle(log + event(999, "rust-ffi-ready completed=1 live=9"), 101, 202)
        assertEquals(2, result.getValue("first")["active"])
        assertEquals(1, result.getValue("first")["background"])
        assertEquals(1, result.getValue("second")["compose-ready"])
        assertEquals(2000, result.getValue("first")["rust_completed_results"])
        assertEquals(2000, result.getValue("second")["rust_completed_results"])
    }

    @Test fun truncatedUnifiedLogCannotProduceASuccessfulReceipt() {
        lifecycle().indices.forEach { missing ->
            val truncated = lifecycle().filterIndexed { index, _ -> index != missing }.joinToString("") { (pid, marker) -> event(pid, marker) }
            assertThrows(RuntimeException::class.java) { iosUnifiedLifecycle(truncated, 101, 202) }
        }
        val log = lifecycle().joinToString("") { (pid, marker) -> event(pid, marker) }
        assertThrows(IllegalArgumentException::class.java) { iosUnifiedLifecycle(log.replace("live=0", "live=1"), 101, 202) }
    }

    @Test fun unrelatedOrReusedProcessesCannotSubstituteForEitherColdStart() {
        val log = lifecycle().joinToString("") { (pid, marker) -> event(pid, marker) }
        assertThrows(IllegalArgumentException::class.java) { iosUnifiedLifecycle(log, 101, 101) }
        assertThrows(IllegalArgumentException::class.java) { iosUnifiedLifecycle(log, 0, 202) }
        assertThrows(IllegalArgumentException::class.java) { iosUnifiedLifecycle(log.replace("EhViewer[202:", "EhViewer[999:"), 101, 202) }
    }
}
