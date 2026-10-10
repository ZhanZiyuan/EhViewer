import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    compileOptions {
        sourceCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get())
        targetCompatibility = JavaVersion.toVersion(libs.versions.android.jvm.get())
    }
    namespace = "com.ehviewer.baselineprofile"

    defaultConfig {
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    testOptions.managedDevices.localDevices {
        create("pixel6Api35") {
            device = "Pixel 6"
            apiLevel = 35
            testedAbi = "x86_64"
            systemImageSource = "aosp-atd"
        }
    }
}

kotlin {
    val javaVersion = libs.versions.java.get().toInt()
    jvmToolchain(javaVersion)
    compilerOptions {
        jvmTarget = JvmTarget.fromTarget(libs.versions.android.jvm.get())
    }
}

baselineProfile {
    // Keep Linux CI managed devices; allow local Android devices on ARM64 hosts.
    val connected = providers.gradleProperty("baselineProfile.useConnectedDevices").map(String::toBoolean).getOrElse(false)
    useConnectedDevices = connected
    if (!connected) managedDevices += "pixel6Api35"
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}

androidComponents {
    onVariants { v ->
        val artifactsLoader = v.artifacts.getBuiltArtifactsLoader()
        v.instrumentationRunnerArguments.put(
            "targetAppId",
            v.testedApks.map { artifactsLoader.load(it)!!.applicationId },
        )
    }
}
