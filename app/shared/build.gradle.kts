import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library.compose)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    // Both Skia-backed targets compile the same actual decoder, while Android uses BitmapFactory.
    sourceSets.getByName("desktopMain").kotlin.srcDir("src/skiaMain/kotlin")
    sourceSets.getByName("iosMain").kotlin.srcDir("src/skiaMain/kotlin")
    sourceSets.commonMain.dependencies {
        api(projects.core.network)
        implementation(libs.compose.multiplatform.ui)
        implementation(libs.compose.multiplatform.foundation)
        implementation(libs.compose.multiplatform.runtime)
        implementation(libs.compose.material3)
        implementation(libs.compose.ui.backhandler)
        implementation(libs.serialization.json)
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
        implementation(libs.kotlinx.coroutines.test)
        implementation(libs.ktor.client.mock)
    }
}

tasks.named<Jar>("desktopJar") { archiveFileName.set("shared-ui-jvm.jar") }

val stagedReaderTest = providers.gradleProperty("ehviewer.reader.testDirectory")
val stageReaderTest = tasks.register<Copy>("stageIosReaderTest") {
    dependsOn("linkDebugTestIosSimulatorArm64")
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe"))
    into(stagedReaderTest)
    filePermissions { unix("rwxr-xr-x") }
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(stageReaderTest)
    executable(stagedReaderTest.map { File(it, "test.kexe") })
    device.set(providers.gradleProperty("ehviewer.reader.testSimulator"))
    standalone.set(false)
    timeout.set(Duration.ofMinutes(5))
}
