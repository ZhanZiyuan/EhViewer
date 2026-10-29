import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        val nonAndroidMain = create("nonAndroidMain") { dependsOn(commonMain.get()) }
        val nonAndroidTest = create("nonAndroidTest") { dependsOn(commonTest.get()) }
        getByName("desktopMain").dependsOn(nonAndroidMain)
        getByName("desktopTest").dependsOn(nonAndroidTest)
        getByName("iosMain").dependsOn(nonAndroidMain)
        getByName("iosTest").dependsOn(nonAndroidTest)
        getByName("jvmMain").dependencies {
            api(libs.logcat)
        }
        commonTest {
            dependencies {
                implementation(kotlin("test"))
            }
        }
        commonMain {
            dependencies {
                api(projects.core.domain)
                api(libs.kotlinx.coroutines.core)
                api(libs.kotlinx.datetime)
                api(libs.kotlinx.io)
                api(libs.okio)
                api(libs.serialization.cbor)
                api(project.dependencies.platform(libs.arrow.stack))
                api(libs.bundles.arrow)
            }
        }
        androidMain {
            dependencies {
                api(libs.kotlinx.coroutines.android)
                api(libs.splitties.appctx)
                implementation(libs.androidx.core)
            }
        }
    }
}

tasks.named<Jar>("desktopJar") {
    archiveFileName.set("common-jvm.jar")
}

val stagedNativeTest = providers.gradleProperty("ehviewer.common.testDirectory")
val stageNativeTest = tasks.register<Copy>("stageIosCommonTest") {
    dependsOn("linkDebugTestIosSimulatorArm64")
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe"))
    into(stagedNativeTest)
    filePermissions { unix("rwxr-xr-x") }
}

tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(stageNativeTest)
    executable(stagedNativeTest.map { File(it, "test.kexe") })
    device.set(providers.gradleProperty("ehviewer.common.testSimulator"))
    standalone.set(false)
    timeout.set(Duration.ofMinutes(5))
}
