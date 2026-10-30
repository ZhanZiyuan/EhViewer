import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

val stagedNativeTest = providers.gradleProperty("ehviewer.network.testDirectory")
val stageNativeTest = tasks.register<Copy>("stageIosNetworkTest") {
    dependsOn("linkDebugTestIosSimulatorArm64")
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe"))
    into(stagedNativeTest)
    filePermissions { unix("rwxr-xr-x") }
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(stageNativeTest)
    executable(stagedNativeTest.map { File(it, "test.kexe") })
    device.set(providers.gradleProperty("ehviewer.network.testSimulator"))
    standalone.set(false)
    timeout.set(Duration.ofMinutes(5))
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        commonMain.dependencies {
            api(projects.core.domain)
            api(libs.ktor.client.core)
            implementation(libs.serialization.json)
            implementation(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("jvmMain").dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        getByName("iosMain").dependencies {
            implementation(libs.ktor.client.darwin)
        }
    }
}

tasks.named<Jar>("desktopJar") { archiveFileName.set("network-jvm.jar") }
