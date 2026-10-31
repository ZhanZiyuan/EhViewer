import java.time.Duration
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest

plugins {
    alias(libs.plugins.ehviewer.multiplatform.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

kotlin {
    jvm("desktop") { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
    iosArm64()
    iosSimulatorArm64()
    sourceSets {
        val nonAndroidTest = create("nonAndroidTest") { dependsOn(commonTest.get()) }
        getByName("desktopTest").dependsOn(nonAndroidTest)
        getByName("iosTest").dependsOn(nonAndroidTest)
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        getByName("desktopMain").dependencies { implementation(libs.androidx.sqlite.bundled) }
        getByName("iosMain").dependencies { implementation(libs.androidx.sqlite.bundled) }
        commonMain {
            dependencies {
                api(projects.core.common)
                api(libs.androidx.datastore)
                api(libs.androidx.room.paging)
                implementation(libs.ktor.client.core)
            }
        }
        findByName("androidMain")?.dependencies {
            implementation(project.dependencies.platform(libs.okhttp.bom))
            implementation(libs.ktor.client.okhttp)
        }
    }
}

dependencies {
    if (!providers.gradleProperty("ehviewer.desktopOnly").map(String::toBoolean).getOrElse(false)) add("kspAndroid", libs.androidx.room.compiler)
    add("kspDesktop", libs.androidx.room.compiler)
    add("kspIosArm64", libs.androidx.room.compiler)
    add("kspIosSimulatorArm64", libs.androidx.room.compiler)
}

room {
    schemaDirectory("$projectDir/schemas")
}

tasks.named<Jar>("desktopJar") { archiveFileName.set("data-jvm.jar") }

tasks.withType<Test>().configureEach {
    inputs.property("sqliteOperatingSystem", System.getProperty("os.name"))
    inputs.property("sqliteArchitecture", System.getProperty("os.arch"))
}

val migrationFixtures = tasks.register<GenerateDatabaseMigrationFixtures>("generateDatabaseMigrationFixtures") {
    historicalSchema.set(layout.projectDirectory.file("schemas/com.ehviewer.core.database.EhDatabase/17.json"))
    outputDirectory.set(layout.buildDirectory.dir("generated/migration-test-fixtures"))
}
kotlin.sourceSets.getByName("nonAndroidTest").kotlin.srcDir(migrationFixtures.map { it.outputDirectory })

if (System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in setOf("aarch64", "arm64")) {
    val windowsSqlite = tasks.register<BuildWindowsSqliteJni>("buildWindowsArm64SqliteJni") {
        outputDirectory.set(layout.buildDirectory.dir("generated/windows-sqlite-resources"))
    }
    kotlin.sourceSets.getByName("desktopMain").resources.srcDir(windowsSqlite.map { it.outputDirectory })
}

val stagedDataTest = providers.gradleProperty("ehviewer.data.testDirectory")
val stageDataTest = tasks.register<Copy>("stageIosDataTest") {
    dependsOn("linkDebugTestIosSimulatorArm64")
    from(layout.buildDirectory.file("bin/iosSimulatorArm64/debugTest/test.kexe"))
    into(stagedDataTest)
    filePermissions { unix("rwxr-xr-x") }
}
tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    dependsOn(stageDataTest)
    executable(stagedDataTest.map { File(it, "test.kexe") })
    device.set(providers.gradleProperty("ehviewer.data.testSimulator"))
    standalone.set(false)
    timeout.set(Duration.ofMinutes(5))
}
