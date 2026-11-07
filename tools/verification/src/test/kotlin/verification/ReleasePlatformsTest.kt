package verification

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ReleasePlatformsTest {
    private val source = "1".repeat(40)
    private fun parts() = nativeReleaseRoles.mapValues { (id, roles) ->
        mapOf(
            "result" to "PASS",
            "source_commit" to source,
            "source_dirty" to false,
            "target" to id,
            "version" to "1.15.10",
            "android_version_code" to 180074,
            "roles" to roles,
            "runner_architecture" to releaseTargets.single { it.id == id }.runnerArchitecture,
        )
    }

    @Test fun sevenNativePartsBindEveryEvidenceRoleToItsTarget() {
        val plan = nativeReleasePartPlan(parts(), source, "1.15.10", 180074)
        assertEquals(15, plan.size)
        assertEquals(nativeReleaseRoles.keys, plan.values.toSet())
        assertEquals(releaseTargets.filter { it.platform != "android" }.map { it.id }.toSet(), nativeReleaseRoles.keys)
        assertThrows(IllegalArgumentException::class.java) { nativeReleasePartPlan(parts() - "ios-arm64", source, "1.15.10", 180074) }
        assertThrows(IllegalArgumentException::class.java) { nativeReleasePartPlan(parts() + ("extra" to parts().values.first()), source, "1.15.10", 180074) }
    }

    @Test fun nativePartsRejectMixedVersionsSourcesCpusAndIncompleteEvidence() {
        for (target in nativeReleaseRoles.keys) {
            val changes = listOf(
                "result" to "failure", "source_commit" to "2".repeat(40), "source_dirty" to true,
                "target" to "different-cpu", "version" to "1.16.0", "android_version_code" to 180075,
                "roles" to emptyList<String>(), "roles" to (nativeReleaseRoles.getValue(target) + "extra"), "runner_architecture" to "wrong-cpu",
            )
            for (changed in changes) {
                val parts = parts().toMutableMap()
                parts[target] = parts.getValue(target) + changed
                assertThrows("$target: $changed", IllegalArgumentException::class.java) { nativeReleasePartPlan(parts, source, "1.15.10", 180074) }
            }
        }
    }
}
