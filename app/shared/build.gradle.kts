import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library.compose)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    sourceSets.commonMain.dependencies {
        api(projects.core.network)
        implementation(libs.compose.multiplatform.ui)
        implementation(libs.compose.multiplatform.foundation)
        implementation(libs.compose.multiplatform.runtime)
        implementation(libs.compose.material3)
    }
}

tasks.named<Jar>("desktopJar") { archiveFileName.set("shared-ui-jvm.jar") }
