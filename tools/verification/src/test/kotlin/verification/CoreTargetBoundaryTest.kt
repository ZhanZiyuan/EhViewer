package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertThrows
import org.junit.Test

class CoreTargetBoundaryTest {
    private val domain = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("core/domain/build.gradle.kts") }.first(Files::isRegularFile).readText()
    private val common = generateSequence(Path.of("").toAbsolutePath()) { it.parent }
        .map { it.resolve("core/common/build.gradle.kts") }.first(Files::isRegularFile).readText()
    private val configuration = mapOf("core/domain/build.gradle.kts" to domain, "core/common/build.gradle.kts" to common, "core/data/build.gradle.kts" to "android library")

    @Test fun actualReviewedDomainConfigurationPasses() = verifyCoreTargetBoundary(configuration)

    @Test fun unreviewedNativeWebAndDuplicateJvmTargetsFail() {
        for (extra in listOf("iosArm64()", "iosSimulatorArm64()", "linuxX64()", "mingwX64()", "js()", "wasmJs()", "jvm(\"desktop\")")) {
            assertThrows(IllegalArgumentException::class.java) { verifyCoreTargetBoundary(configuration + ("core/domain/build.gradle.kts" to domain + "\n$extra")) }
        }
    }

    @Test fun removedRenamedAndConventionlessDomainTargetsFail() {
        for (changed in listOf(domain.replace("jvm(\"desktop\")", ""), domain.replace("jvm(\"desktop\")", "jvm(\"other\")"), domain.replace("alias(libs.plugins.ehviewer.multiplatform.library)", ""))) {
            assertThrows(IllegalArgumentException::class.java) { verifyCoreTargetBoundary(configuration + ("core/domain/build.gradle.kts" to changed)) }
        }
    }

    @Test fun missingOrRenamedNativeTargetsCannotClaimIosCoverage() {
        for (changed in listOf(domain.replace("iosArm64()", ""), domain.replace("iosSimulatorArm64()", ""), domain.replace("iosSimulatorArm64()", "iosX64()"))) {
            assertThrows(IllegalArgumentException::class.java) { verifyCoreTargetBoundary(configuration + ("core/domain/build.gradle.kts" to changed)) }
        }
    }

    @Test fun legacyModulesAndConventionsCannotAcquireUnreviewedTargets() {
        for (path in listOf("core/common/build.gradle.kts", "core/data/build.gradle.kts", "core/i18n/build.gradle.kts", "core/ui/build.gradle.kts", "build-logic/convention/src/main/kotlin/Plugin.kt")) {
            for (extra in listOf("jvm(\"desktop\")", "iosArm64()")) {
                assertThrows(IllegalArgumentException::class.java) { verifyCoreTargetBoundary(configuration + (path to extra)) }
            }
        }
    }

    @Test fun commonJvmCannotBeRemovedRenamedDuplicatedOrPromotedToNative() {
        val variants = listOf(common.replace("jvm(\"desktop\")", ""), common.replace("jvm(\"desktop\")", "jvm(\"other\")"), common.replace("alias(libs.plugins.ehviewer.multiplatform.library)", "")) +
            listOf("jvm(\"desktop\")", "iosArm64()", "iosSimulatorArm64()", "linuxX64()", "wasmJs()").map { common + "\n" + it }
        variants.forEach { changed -> assertThrows(IllegalArgumentException::class.java) { verifyCoreTargetBoundary(configuration + ("core/common/build.gradle.kts" to changed)) } }
    }
}
