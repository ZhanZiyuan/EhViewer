package verification

import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.inputStream
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.readText

fun filesBelow(directory: Path, suffix: String): List<Path> = if (!directory.exists()) {
    emptyList()
} else {
    Files.walk(directory).use { it.filter { path -> path.isRegularFile() && path.name.endsWith(suffix) }.toList() }
}

fun verifyBytecode(): Map<String, Any?> {
    val directories = mapOf(
        "app" to listOf("javac", "built_in_kotlinc").flatMap { kind -> listOf("debug", "release").map { "app/build/intermediates/$kind/$it" } },
        "benchmark" to listOf("benchmark/build/intermediates/built_in_kotlinc/nonMinifiedRelease"),
    ) + listOf("common", "domain", "data", "i18n", "ui").associateWith { listOf("core/$it/build/classes/kotlin/android/main") }
    val counts = directories.mapValues { (module, paths) ->
        val files = paths.flatMap { filesBelow(root.resolve(it), ".class") }
        require(files.isNotEmpty()) { "No compiled Android classes for $module" }
        files.forEach { path ->
            val bytes = path.inputStream().use { it.readNBytes(8) }
            require(bytes.size == 8 && ByteBuffer.wrap(bytes).getInt(0) == 0xcafebabe.toInt() && ByteBuffer.wrap(bytes).getShort(6).toInt() == 61) { "Expected Java 17 class: $path" }
        }
        files.size
    }
    return mapOf("androidClassMajor" to 61, "classes" to counts)
}

fun verifyNativeSources(): List<Map<String, Any?>> = listOf("Debug", "RelWithDebInfo").flatMap { configuration ->
    abis.keys.map { abi ->
        val database = filesBelow(root.resolve("app/.cxx/$configuration"), "compile_commands.json")
            .filter { it.parent.name == abi }.maxByOrNull { it.getLastModifiedTime() } ?: error("Missing CMake database: $configuration/$abi")
        val sources = groovy.json.JsonSlurper().parse(database.toFile()).array().map { Path.of(it.obj().str("file")).toAbsolutePath().normalize() }
        val appSources = sources.filter {
            it.startsWith(root.resolve("app/src/main/cpp")) ||
                it.startsWith(root.resolve("native")) && it.extension in setOf("c", "cc", "cpp", "cxx")
        }
        require(appSources.isEmpty()) { "App C/C++ still compiled: $appSources" }
        require(sources.any { it.name == "rust-link-anchor.S" }) { "Rust link anchor missing" }
        mapOf(
            "configuration" to configuration,
            "abi" to abi,
            "database" to root.relativize(database).toString(),
            "compiled_sources" to sources.size,
            "app_c_sources" to appSources,
        )
    }
}

/** Only independently migrated modules may declare their exact reviewed portable targets. */
fun verifyCoreTargetBoundary(configurations: Map<String, String>) {
    val domain = configurations.getValue("core/domain/build.gradle.kts")
    val target = Regex("\\b(ios\\w*|macos\\w*|linuxX64|linuxArm64|mingwX64|js|wasmJs|jvm)\\s*\\(")
    require(target.findAll(domain).map { it.groupValues[1] }.toList() == listOf("jvm", "iosArm64", "iosSimulatorArm64") && Regex("\\bjvm\\(\\s*\"desktop\"\\s*\\)").containsMatchIn(domain)) { "Domain targets must be exactly Android, desktop JVM and the two reviewed iOS targets" }
    require("alias(libs.plugins.ehviewer.multiplatform.library)" in domain) { "Domain must retain the existing Android library convention" }
    val common = configurations.getValue("core/common/build.gradle.kts")
    require(target.findAll(common).map { it.groupValues[1] }.toList() == listOf("jvm", "iosArm64", "iosSimulatorArm64") && Regex("\\bjvm\\(\\s*\"desktop\"\\s*\\)").containsMatchIn(common)) { "Common targets must be exactly Android, desktop JVM and the two reviewed iOS targets" }
    require("alias(libs.plugins.ehviewer.multiplatform.library)" in common) { "Common must retain the existing Android library convention" }
    val portable = setOf("core/domain/build.gradle.kts", "core/common/build.gradle.kts", "core/network/build.gradle.kts", "app/shared/build.gradle.kts")
    for (path in listOf("core/network/build.gradle.kts", "app/shared/build.gradle.kts")) {
        configurations[path]?.let { text ->
            require(target.findAll(text).map { it.groupValues[1] }.toList() == listOf("jvm", "iosArm64", "iosSimulatorArm64") && Regex("\\bjvm\\(\\s*\"desktop\"\\s*\\)").containsMatchIn(text)) { "$path: expected the reviewed Android, desktop JVM and iOS targets" }
            require("alias(libs.plugins.ehviewer.multiplatform.library" in text) { "$path: missing Android library convention" }
        }
    }
    configurations.filterKeys { it !in portable }.forEach { (path, text) ->
        require(!target.containsMatchIn(text)) { "$path: non-Android target outside the reviewed domain migration" }
    }
}

fun verifyArchitecture(): Map<String, Any?> {
    val violations = mutableListOf<String>()
    var checked = 0
    val importPattern = Regex("^import\\s+([^\\s]+)", RegexOption.MULTILINE)
    listOf("common", "domain", "data", "ui", "i18n", "network").forEach { module ->
        filesBelow(root.resolve("core/$module/src"), ".kt").forEach { path ->
            checked++
            val imports = importPattern.findAll(path.readText()).map { it.groupValues[1] }.toList()
            val forbidden = listOf("com.hippo.ehviewer.") + when (module) {
                "domain" -> listOf("com.ehviewer.core.common.", "com.ehviewer.core.data.", "com.ehviewer.core.ui.", "com.ehviewer.core.i18n.")
                "common" -> listOf("com.ehviewer.core.data.", "com.ehviewer.core.ui.", "com.ehviewer.core.i18n.")
                "network" -> listOf("com.ehviewer.core.data.", "com.ehviewer.core.ui.", "com.ehviewer.core.i18n.")
                "data" -> listOf("com.ehviewer.core.ui.")
                else -> emptyList()
            }
            imports.filter { imported -> forbidden.any(imported::startsWith) }.forEach { violations += "${root.relativize(path)}: upward import $it" }
            if (module in setOf("common", "domain", "network") && path.any { it.toString() in setOf("commonMain", "nonAndroidMain") } && imports.any { imported ->
                    listOf("android.", "java.", "javax.", "androidx.compose.").any(imported::startsWith)
                }
            ) {
                violations += "${root.relativize(path)}: platform dependency in commonMain"
            }
        }
    }
    val configurations = root.resolve("core").listDirectoryEntries().map { it.resolve("build.gradle.kts") }.filter { it.exists() } +
        filesBelow(root.resolve("build-logic/convention/src/main/kotlin"), ".kt") + listOf(root.resolve("app/shared/build.gradle.kts"))
    verifyCoreTargetBoundary(configurations.associate { root.relativize(it).joinToString("/") to it.readText() })
    val loader = root.resolve("app/src/main/kotlin/com/hippo/ehviewer/gallery/ArchivePageLoader.kt").readText()
    require(!Regex("^import\\s+com\\.hippo\\.ehviewer\\.jni\\.", RegexOption.MULTILINE).containsMatchIn(loader)) { "ArchivePageLoader directly imports JNI" }
    require(root.resolve("core/domain/src/commonMain/kotlin/com/ehviewer/core/domain/reader/ArchiveCatalog.kt").isRegularFile())
    val secretPattern = Regex("(?:gh[pousr]_[A-Za-z0-9]{30,}|github_pat_[A-Za-z0-9_]{30,}|GithubTokenParts|bearerAuth\\(\\s*\"[^\"\\n]+\")")
    filesBelow(root.resolve("app/src/main"), ".kt").filter { secretPattern.containsMatchIn(it.readText()) }.forEach {
        violations += "${root.relativize(it)}: possible bundled GitHub credential" // Never print credential contents.
    }
    val files = command("git", "ls-files", "-z", "--cached", "--others", "--exclude-standard").split('\u0000')
        .filter { it.isNotEmpty() && root.resolve(it).isRegularFile() }.distinct().sorted()
    val attributes = command("git", "check-attr", "-z", "--stdin", "linguist-detectable", "linguist-language", "linguist-documentation", "linguist-generated", input = files.joinToString("\u0000", postfix = "\u0000"))
        .split('\u0000').dropLast(1).chunked(3).groupBy { it[0] }.mapValues { (_, entries) -> entries.associate { it[1] to it[2] } }
    val languages = mutableSetOf<String>()
    attributes.forEach { (path, attrs) ->
        val expected = when (Path.of(path).extension) {
            "kt", "kts" -> "Kotlin"
            "rs" -> "Rust"
            else -> null
        }
        if (attrs["linguist-detectable"] == "true") {
            if (attrs["linguist-language"] != expected) violations += "$path: falsely classified language"
            if (attrs["linguist-documentation"] != "true" && attrs["linguist-generated"] != "true") languages += attrs.getValue("linguist-language")
        } else if (expected != null && !path.startsWith("docs/")) {
            violations += "$path: Kotlin/Rust hidden from statistics"
        }
    }
    require(languages == setOf("Kotlin", "Rust")) { "Expected precisely Kotlin and Rust" }
    require(violations.isEmpty()) { violations.joinToString("\n") }
    return mapOf(
        "kotlin_files_checked" to checked,
        "language_policy" to languages.sorted(),
        "shared_core_android_only" to false,
        "legacy_shared_core_android_only" to true,
        "legacy_shared_modules" to listOf("data", "i18n", "ui"),
        "portable_common_targets" to portableCommonTargets,
        "portable_domain_targets" to portableDomainTargets,
        "portable_network_targets" to portableDomainTargets,
        "portable_gallery_ui_targets" to portableDomainTargets,
        "archive_loader_uses_port" to true,
        "bundled_github_credentials" to false,
        "result" to "PASS",
    )
}
