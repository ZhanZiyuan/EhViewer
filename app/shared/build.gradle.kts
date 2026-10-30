import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library.compose)
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
    }
    sourceSets.commonTest.dependencies { implementation(kotlin("test")) }
}

tasks.named<Jar>("desktopJar") { archiveFileName.set("shared-ui-jvm.jar") }
