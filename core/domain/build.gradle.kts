import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
}

kotlin {
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}

tasks.named<Jar>("desktopJar") {
    archiveFileName.set("domain-jvm.jar")
}

val stagedNativeTest = providers.gradleProperty("ehviewer.domain.testDirectory")
val stageNativeTest = tasks.register<Copy>("stageIosDomainTest") {
    dependsOn("linkDebugTestIosSimulatorArm64")
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe"))
    into(stagedNativeTest)
    filePermissions { unix("rwxr-xr-x") }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(stageNativeTest)
    executable(stagedNativeTest.map { File(it, "test.kexe") })
    device.set(providers.gradleProperty("ehviewer.domain.testSimulator"))
    standalone.set(false)
    timeout.set(Duration.ofMinutes(5))
}
