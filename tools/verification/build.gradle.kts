plugins {
    `kotlin-dsl`
    application
    alias(libs.plugins.spotless)
}

repositories { mavenCentral() }

dependencies {
    implementation(kotlin("stdlib"))
    implementation(localGroovy())
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

tasks.withType<Test>().configureEach {
    inputs.file(rootDir.resolve("../../.github/workflows/releases.yml"))
        .withPropertyName("releaseWorkflow")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

spotless {
    kotlin {
        target("src/**/*.kt")
        ktlint(libs.ktlint.get().version)
    }
    kotlinGradle { ktlint(libs.ktlint.get().version) }
}
