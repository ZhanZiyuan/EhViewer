import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
}

kotlin {
    jvm("desktop") {
        compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
    }
    sourceSets.commonTest.dependencies {
        implementation(kotlin("test"))
    }
}

tasks.named<Jar>("desktopJar") {
    archiveFileName.set("domain-jvm.jar")
}
