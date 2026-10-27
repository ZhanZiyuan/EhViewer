import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.spotless)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        binaries.executable {
            baseName = "EhViewer"
            entryPoint = "com.hippo.ehviewer.ios.main"
        }
    }
    sourceSets.commonMain.dependencies {
        implementation(libs.compose.multiplatform.ui)
        implementation(libs.compose.multiplatform.foundation)
        implementation(libs.compose.multiplatform.runtime)
        implementation(libs.compose.material3)
    }
}

val androidVersionSource = providers.fileContents(rootProject.layout.projectDirectory.file("app/build.gradle.kts")).asText
val simulatorBundle = layout.buildDirectory.dir("simulator/EhViewer.app")
val stageSimulatorApp = tasks.register<Sync>("stageSimulatorApp") {
    dependsOn("linkDebugExecutableIosSimulatorArm64")
    into(simulatorBundle)
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugExecutable/EhViewer.kexe")) {
        rename { "EhViewer" }
        filePermissions { unix("rwxr-xr-x") }
    }
    from("Info.plist") {
        expand(
            "appVersion" to Regex("val releaseVersion = \"([^\"]+)\"").find(androidVersionSource.get())!!.groupValues[1],
            "buildVersion" to Regex("versionCode = (\\d+)").find(androidVersionSource.get())!!.groupValues[1],
        )
    }
}

tasks.register<Exec>("packageSimulatorApp") {
    dependsOn(stageSimulatorApp)
    commandLine("codesign", "--force", "--sign", "-", simulatorBundle.get().asFile.absolutePath)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.ktlint.get().version)
    }
    kotlinGradle { ktlint(libs.ktlint.get().version) }
}
