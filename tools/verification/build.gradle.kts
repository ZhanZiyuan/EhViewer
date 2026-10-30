plugins {
    `kotlin-dsl`
    application
    alias(libs.plugins.spotless)
}

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("stdlib"))
    implementation(localGroovy())
    implementation(libs.kotlinx.io)
    implementation(libs.okio)
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    compilerOptions.jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
}
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

application { mainClass.set("verification.MainKt") }

tasks.withType<JavaExec>().configureEach {
    workingDir(rootDir.resolve("../.."))
}

// Only a standard-library test IPC client is compiled here; no product Native library is built.
val offlineClientSource = layout.projectDirectory.file("src/test/rust/offline_process_client.rs")
val offlineClient = layout.buildDirectory.file("test-native/offline-process-client${if (System.getProperty("os.name").startsWith("Windows")) ".exe" else ""}")
val checkOfflineClientFormat = tasks.register<Exec>("checkOfflineClientFormat") {
    workingDir(rootDir.resolve("../..")) // Honor the existing pinned rust-toolchain.toml.
    inputs.file(offlineClientSource)
    inputs.file(rootDir.resolve("../../rust-toolchain.toml"))
    commandLine("rustfmt", "--edition", "2024", "--check", offlineClientSource.asFile.absolutePath)
}
val buildOfflineClient = tasks.register<Exec>("buildOfflineClient") {
    dependsOn(checkOfflineClientFormat)
    workingDir(rootDir.resolve("../.."))
    inputs.file(offlineClientSource)
    inputs.file(rootDir.resolve("../../rust-toolchain.toml"))
    outputs.file(offlineClient)
    val executableFile = offlineClient.get().asFile
    doFirst { executableFile.parentFile.mkdirs() }
    commandLine("clippy-driver", "--edition=2024", "-Dwarnings", offlineClientSource.asFile.absolutePath, "-o", offlineClient.get().asFile.absolutePath)
}

tasks.withType<Test>().configureEach {
    dependsOn(buildOfflineClient)
    systemProperty("verification.offlineClient", offlineClient.get().asFile.absolutePath)
    inputs.file(offlineClient).withPropertyName("offlineProcessClient").withPathSensitivity(PathSensitivity.NONE)
    inputs.property("offlineModelJvmArchitecture", System.getProperty("os.arch"))
    inputs.file(rootDir.resolve("../../.github/workflows/releases.yml"))
        .withPropertyName("releaseWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../.github/workflows/ci.yml"))
        .withPropertyName("ciWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../.github/ci-plan.json"))
        .withPropertyName("ciCheckPolicy")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../core/domain/build.gradle.kts"))
        .withPropertyName("reviewedDomainTargets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../core/common/build.gradle.kts"))
        .withPropertyName("reviewedCommonTargets")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../core/network/build.gradle.kts")).withPropertyName("reviewedNetworkTargets").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.file(rootDir.resolve("../../app/shared/build.gradle.kts")).withPropertyName("reviewedGalleryUiTargets").withPathSensitivity(PathSensitivity.RELATIVE)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.ktlint.get().version)
    }
    kotlinGradle { ktlint(libs.ktlint.get().version) }
}
