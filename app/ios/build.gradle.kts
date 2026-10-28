import groovy.json.JsonSlurper
import java.io.File
import java.util.concurrent.TimeUnit
import org.jetbrains.kotlin.gradle.plugin.mpp.KotlinNativeTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.spotless)
}

abstract class BuildAppleRust : DefaultTask() {
    @get:Input abstract val rustTarget: Property<String>

    @get:Internal abstract val repository: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @get:OutputFile abstract val header: RegularFileProperty

    @get:OutputFile abstract val archive: RegularFileProperty

    @get:OutputFile abstract val messages: RegularFileProperty

    @get:OutputFile abstract val diagnostics: RegularFileProperty

    @TaskAction
    fun build() {
        val repo = repository.get().asFile
        val report = messages.get().asFile.also { it.parentFile.mkdirs() }
        val errors = diagnostics.get().asFile.also { it.parentFile.mkdirs() }
        val process = ProcessBuilder(
            "cargo", "build", "--manifest-path", repo.resolve("native/rust/Cargo.toml").path,
            "--locked", "-p", "ehviewer_capi", "--release", "--target", rustTarget.get(),
            "--target-dir", repo.resolve("native/rust/target").path, "--message-format=json-render-diagnostics",
        ).directory(repo).redirectError(errors).redirectOutput(report).start()
        if (!process.waitFor(15, TimeUnit.MINUTES)) {
            process.destroyForcibly().waitFor()
            logger.error(errors.readText())
            error("Rust Apple build timed out: ${rustTarget.get()}")
        }
        if (process.exitValue() != 0) {
            logger.error(errors.readText())
            error("Rust Apple build failed: ${rustTarget.get()}; see ${report.name}")
        }
        val events = report.readLines().filter { it.startsWith('{') }.map { JsonSlurper().parseText(it) as Map<*, *> }
            .filter { (it["package_id"] as? String)?.substringAfterLast('#')?.substringBefore('@') == "ehviewer_capi" }
        val produced = events.filter { it["reason"] == "compiler-artifact" }
            .flatMap { it["filenames"] as List<*> }.map { File(it as String).canonicalFile }
        check(archive.get().asFile.canonicalFile in produced && archive.get().asFile.length() > 0) { "Cargo did not produce the expected target archive" }
        val generated = events.filter { it["reason"] == "build-script-executed" }
            .map { File(it["out_dir"] as String, "ehviewer_capi.h") }.single()
        check(generated.isFile && generated.readText().contains("ehv_hash_sha1")) { "Generated C ABI header missing" }
        header.get().asFile.also { it.parentFile.mkdirs() }.writeBytes(generated.readBytes())
    }
}

val rustRoot = rootProject.layout.projectDirectory.dir("native/rust")
val repositoryRoot = rootProject.layout.projectDirectory

kotlin {
    iosArm64()
    iosSimulatorArm64()
    targets.withType<KotlinNativeTarget>().configureEach {
        val rustTriple = when (name) {
            "iosArm64" -> "aarch64-apple-ios"
            "iosSimulatorArm64" -> "aarch64-apple-ios-sim"
            else -> error("Unsupported Rust Apple target: $name")
        }
        val rustHeader = layout.buildDirectory.file("rust/$rustTriple/include/ehviewer_capi.h")
        val rustArchive = rustRoot.file("target/$rustTriple/release/libehviewer_capi.a")
        val buildRust = tasks.register<BuildAppleRust>("buildRust${name.replaceFirstChar(Char::uppercaseChar)}") {
            rustTarget.set(rustTriple)
            repository.set(repositoryRoot)
            sources.from(
                rustRoot.file("Cargo.toml"),
                rustRoot.file("Cargo.lock"),
                repositoryRoot.file("rust-toolchain.toml"),
                fileTree(rustRoot.dir("core")) { exclude("target/**") },
                fileTree(rustRoot.dir("capi")) { exclude("target/**") },
            )
            header.set(rustHeader)
            archive.set(rustArchive)
            messages.set(layout.buildDirectory.file("rust/$rustTriple/cargo.jsonl"))
            diagnostics.set(layout.buildDirectory.file("rust/$rustTriple/cargo.log"))
        }
        compilations.getByName("main").cinterops.create("ehviewerCapi") {
            definitionFile.set(file("src/nativeInterop/cinterop/ehviewer.def"))
            includeDirs(rustHeader.get().asFile.parentFile)
            extraOpts("-libraryPath", rustArchive.asFile.parent)
            tasks.named(interopProcessingTaskName) {
                dependsOn(buildRust)
                inputs.file(buildRust.flatMap { it.archive }).withPathSensitivity(PathSensitivity.NONE)
            }
        }
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
