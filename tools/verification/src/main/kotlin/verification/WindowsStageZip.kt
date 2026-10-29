package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlin.io.path.exists
import kotlin.io.path.extension
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

private const val STAGE_MANIFEST = "EhViewer/ehviewer-stage-source.json"
private const val STAGE_FILE_LIMIT = 128L * 1024 * 1024
private const val STAGE_TOTAL_LIMIT = 1024L * 1024 * 1024

fun stageZipName(name: String): String {
    require(name.isNotEmpty() && !name.startsWith('/') && name.none { it == '\\' || it == ':' || it.code < 32 || it.code == 127 }) { "Noncanonical staged ZIP path" }
    require(name.split('/').all { it.isNotEmpty() && it !in setOf(".", "..") }) { "Noncanonical staged ZIP component" }
    return name
}

/** Verify all file bytes, not just a library subset; never extract untrusted ZIP entries. */
fun verifyStageZip(archive: Path, manifest: Map<String, Any?>): Map<String, Any?> {
    require(Files.size(archive) in 1..STAGE_TOTAL_LIMIT) { "Invalid staged ZIP size" }
    val expected = manifest["files"].array().map(Any?::obj).associate { entry ->
        val name = stageZipName(entry.str("path"))
        require(name.startsWith("EhViewer/") && !name.equals(STAGE_MANIFEST, ignoreCase = true) && !name.lowercase(Locale.ROOT).startsWith(STAGE_MANIFEST.lowercase(Locale.ROOT) + "/")) { "Staged file escapes its root or collides with the source receipt" }
        name to entry
    }
    require(expected.isNotEmpty() && expected.size == manifest["files"].array().size && expected.size <= 20_000) { "Empty, duplicated or oversized staged inventory" }
    require(expected.keys.map { it.lowercase(Locale.ROOT) }.toSet().size == expected.size) { "Case-colliding Windows files" }
    val normalized = JsonSlurper().parseText(json(manifest)).obj()
    var expanded = 0L
    ZipFile(archive.toFile()).use { zip ->
        val entries = zip.entries().asSequence().toList()
        require(entries.size == expected.size + 1 && entries.map { stageZipName(it.name) }.toSet().size == entries.size) { "Missing, extra, duplicate or noncanonical ZIP entries" }
        require(entries.map { it.name }.toSet() == expected.keys + STAGE_MANIFEST) { "Staged ZIP differs from its complete inventory" }
        for (entry in entries) {
            require(!entry.isDirectory && entry.size in 0..STAGE_FILE_LIMIT) { "Invalid staged ZIP entry size/type" }
            expanded += entry.size
            require(expanded <= STAGE_TOTAL_LIMIT) { "Staged ZIP expands beyond the inspection limit" }
            val digest = MessageDigest.getInstance("SHA-256")
            val crc = CRC32()
            var count = 0L
            val receipt = if (entry.name == STAGE_MANIFEST) java.io.ByteArrayOutputStream() else null
            zip.getInputStream(entry).use { input ->
                val buffer = ByteArray(1024 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    count += read
                    require(count <= entry.size && count <= STAGE_FILE_LIMIT) { "Staged ZIP entry exceeds its declared bounds" }
                    digest.update(buffer, 0, read)
                    crc.update(buffer, 0, read)
                    receipt?.write(buffer, 0, read)
                }
            }
            require(count == entry.size && crc.value == entry.crc) { "Staged ZIP size/CRC mismatch" }
            if (receipt != null) {
                require(JsonSlurper().parseText(receipt.toString(Charsets.UTF_8)).obj() == normalized) { "Embedded source manifest differs from its verified input" }
            } else {
                val file = expected.getValue(entry.name)
                val actual = digest.digest().joinToString("") { "%02x".format(it) }
                require(count == (file["bytes"] as Number).toLong() && actual == file.str("sha256")) { "Staged file bytes differ from the measured image" }
            }
        }
    }
    return mapOf("result" to "PASS", "file_count" to expected.size, "expanded_bytes" to expanded, "sha256" to sha256(archive))
}

/** Portable byte packaging only. A native runtime proof is required by the Windows entry point. */
fun writeStageZip(image: Path, archive: Path, metadata: Map<String, Any?>): Map<String, Any?> {
    require(Files.isDirectory(image, NOFOLLOW_LINKS) && !Files.isSymbolicLink(image) && !archive.exists(NOFOLLOW_LINKS)) { "Expected an image and a fresh ZIP" }
    val source = image.toRealPath()
    val requested = archive.toAbsolutePath().normalize()
    val destination = requested.parent.toRealPath().resolve(requested.fileName)
    require(!destination.startsWith(source) && !source.startsWith(destination)) { "Staged output overlaps its input image" }
    require(!Files.exists(source.resolve("ehviewer-stage-source.json"), NOFOLLOW_LINKS)) { "Image already contains a stage manifest" }
    val paths = Files.walk(source).use { walk ->
        walk.filter {
            require(!Files.isSymbolicLink(it) && (Files.isRegularFile(it, NOFOLLOW_LINKS) || Files.isDirectory(it, NOFOLLOW_LINKS))) { "Staged image contains links or special files" }
            Files.isRegularFile(it, NOFOLLOW_LINKS)
        }.sorted().toList()
    }
    val inventory = paths.map { file ->
        val name = stageZipName("EhViewer/" + source.relativize(file).joinToString("/"))
        val size = Files.size(file)
        require(size <= STAGE_FILE_LIMIT) { "Staged image file exceeds its limit" }
        mapOf("path" to name, "bytes" to size, "sha256" to sha256(file))
    }
    require(inventory.isNotEmpty() && inventory.size <= 20_000 && inventory.sumOf { (it["bytes"] as Number).toLong() } <= STAGE_TOTAL_LIMIT) { "Invalid staged image inventory" }
    require(inventory.map { it.getValue("path").toString().lowercase(Locale.ROOT) }.toSet().size == inventory.size) { "Case-colliding staged image files" }
    require(inventory.none { it.getValue("path").toString().equals(STAGE_MANIFEST, ignoreCase = true) }) { "Image file collides with the embedded source receipt" }
    val manifest = metadata + ("files" to inventory)
    Files.newOutputStream(destination, java.nio.file.StandardOpenOption.CREATE_NEW).use { output ->
        ZipOutputStream(output).use { zip ->
            paths.zip(inventory).forEach { (file, record) ->
                zip.putNextEntry(ZipEntry(record.getValue("path").toString()).apply { time = 0 })
                file.inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
            zip.putNextEntry(ZipEntry(STAGE_MANIFEST).apply { time = 0 })
            zip.write(json(manifest).toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
    }
    verifyStageZip(destination, manifest)
    return manifest
}

fun windowsStageZip(runtimeReport: Path, image: Path, output: Path): Map<String, Any?> {
    val source = command("git", "rev-parse", "HEAD").trim()
    val dirty = command("git", "status", "--porcelain", "--untracked-files=all").isNotBlank()
    require(System.getenv("GITHUB_ACTIONS") != "true" || !dirty) { "CI requires a clean source tree" }
    val delivery = releaseContract(root.resolve("app/build.gradle.kts").readText(), source)
    val proof = readJson(runtimeReport)
    require(proof["source_commit"] == source && proof["source_dirty"] == false && proof["target"] == "aarch64-pc-windows-msvc" && proof["result"] == "PASS" && proof["version"] == delivery["version"]) { "A clean same-source Windows runtime proof is required" }
    val launches = proof["launches"].array().map(Any?::obj)
    require(
        launches.size == 2 && launches.all {
            it["application_version"] == delivery["version"] && it["java_version"] == "25.0.5.0.2" && it["windows_code_page"] == "65001" && it["native_encoding"] == "UTF-8" &&
                it["render_api"] == "SOFTWARE_COMPAT" && it["window_showing"] == "true" && it["compose_frames"] == "1" && it["rust_hash_results"] == "2000" && it["rust_active_results"] == "0" &&
                it["rust_ownership_errors"] == "PASS" && it["rust_library_closed"] == "PASS"
        },
    ) { "Both measured Windows runtime launches must pass" }
    require(sha256(runtimeReport.parent.resolve("jvm-ffi-proof.json")) == proof["jvm_ffi_report_sha256"]) { "Runtime FFI proof changed" }
    val native = proof["native_files"].array().map(Any?::obj).associate { entry -> stageZipName(entry.str("path").replace('\\', '/')) to entry.str("sha256") }
    require(native.size == proof["native_files"].array().size && setOf("EhViewer.exe", "runtime/bin/server/jvm.dll", "app/skiko-windows-arm64.dll", "app/resources/ehviewer_capi.dll").all { it in native }) { "Required bundled entry, JVM, Skiko or Rust is missing" }
    val actual = Files.walk(image).use { walk -> walk.filter { Files.isRegularFile(it) && it.extension.lowercase(Locale.ROOT) in setOf("exe", "dll") }.map { image.relativize(it).joinToString("/") }.toList().toSet() }
    require(actual == native.keys) { "Measured native inventory differs from the entire image" }
    native.forEach { (name, expected) ->
        val file = image.resolve(name)
        require(sha256(file) == expected) { "Measured Windows native bytes changed" }
        file.inputStream().use { verifyWindowsArm64Pe(it.readNBytes(1 shl 20)) }
    }
    proof["installed_notices"].obj().forEach { (name, expected) ->
        require(sha256(image.resolve("app/resources/licenses/" + stageZipName(name.replace('\\', '/')))) == expected) { "Measured Windows notice changed" }
    }
    val requested = output.toAbsolutePath().normalize()
    val stageOutput = requireNotNull(requested.parent) { "Stage output cannot be a filesystem root" }.toRealPath().resolve(requested.fileName)
    val actualImage = image.toRealPath()
    require(!stageOutput.exists(NOFOLLOW_LINKS) && !stageOutput.startsWith(actualImage) && !actualImage.startsWith(stageOutput)) { "Use a fresh separate stage directory" }
    Files.createDirectory(stageOutput)
    val name = "EhViewer-${delivery["version"]}-windows-arm64.zip"
    val manifest = writeStageZip(
        image,
        stageOutput.resolve(name),
        mapOf(
            "schema_version" to 1, "verification_scope" to "M2_WINDOWS_ARM64_RUNTIME_STAGE", "source_commit" to source, "source_dirty" to false, "packager_source_dirty" to dirty,
            "version" to delivery["version"], "android_version_code" to delivery["android_version_code"], "target" to "aarch64-pc-windows-msvc", "runtime_proof_sha256" to sha256(runtimeReport),
            "zip_extracted_launch" to "NOT_VERIFIED", "product_p0_verified" to false, "all_eleven_assets_verified" to false, "publish_enabled" to false,
        ),
    )
    val result = manifest + mapOf("archive" to name, "archive_evidence" to verifyStageZip(stageOutput.resolve(name), manifest), "native_files_verified" to native.size)
    stageOutput.resolve("windows-arm64-package.json").writeText(json(result))
    return result
}
