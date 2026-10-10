package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.createDirectories
import kotlin.io.path.createDirectory
import kotlin.io.path.deleteExisting
import kotlin.io.path.exists
import kotlin.io.path.fileSize
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.inputStream
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name
import kotlin.io.path.outputStream
import kotlin.io.path.writeText

val permissions700 = PosixFilePermissions.fromString("rwx------")
val permissions600 = PosixFilePermissions.fromString("rw-------")

fun versionFromTag(tag: String): String {
    require(
        Regex("v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(-[0-9A-Za-z]+([.-][0-9A-Za-z]+)*)?").matches(tag) &&
            listOf("snapshot", "default", "marshmallow").none { it in tag.lowercase() },
    ) { "Expected a version tag; snapshot/flavor names are forbidden" }
    return tag.removePrefix("v")
}

fun attachmentPlan(metadata: Map<String, Any?>, tag: String): List<Pair<ApkInput, String>> {
    val version = versionFromTag(tag)
    val inputs = apkInputs(metadata, "moe.tarsin.ehviewer")
    require(inputs.all { it.version == version }) { "Release tag and packaged versionName differ" }
    return inputs.map { it to "EhViewer-$version-${it.abi}.apk" }.sortedBy { it.second }
}

fun privateDirectory(path: Path) {
    // Reject symlink ancestors too, so a nominally private path cannot redirect writes.
    var ancestor: Path? = path.toAbsolutePath()
    while (ancestor != null) {
        require(!ancestor.isSymbolicLink()) { "Private diagnostics path must not contain a symlink" }
        ancestor = ancestor.parent
    }
    Files.createDirectories(path, PosixFilePermissions.asFileAttribute(permissions700))
    val attributes = Files.readAttributes(path, "posix:permissions,owner", NOFOLLOW_LINKS)
    val user = Files.getOwner(root)
    require(attributes["permissions"] == permissions700 && attributes["owner"] == user) { "Private directory must be owner-only (0700) and owned by this user" }
}

fun retainFile(source: Path, destination: Path) {
    require(source.isRegularFile(NOFOLLOW_LINKS) && source.fileSize() > 0) { "Missing/empty/non-regular release input" }
    if (destination.exists(NOFOLLOW_LINKS)) {
        require(destination.isRegularFile(NOFOLLOW_LINKS) && sha256(destination) == sha256(source)) { "Refusing to overwrite different retained diagnostics" }
    } else {
        Files.createFile(destination, PosixFilePermissions.asFileAttribute(permissions600))
        source.inputStream().use { input -> destination.outputStream().use(input::copyTo) }
    }
    require(destination.getPosixFilePermissions() == permissions600 && Files.getOwner(destination) == Files.getOwner(root)) { "Retained file must be owner-only (0600)" }
}

fun prepareRelease(apkDirectory: Path, tools: AndroidTools, output: Path, privateRoot: Path, tag: String, retention: Int = 180): Map<String, Any?> {
    require(retention in 1..3650) { "Retention must be between 1 and 3650 days" }
    val version = versionFromTag(tag)
    val metadata = readJson(apkDirectory.resolve("output-metadata.json"))
    val plan = attachmentPlan(metadata, tag)
    verifyApks(apkDirectory, tools)
    val source = command("git", "rev-parse", "HEAD").trim()
    val diagnostics = mapOf(
        "mapping.txt" to root.resolve("app/build/outputs/mapping/release/mapping.txt"),
        "native-debug-symbols.zip" to root.resolve("app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"),
    )
    diagnostics.values.forEach { require(it.isRegularFile(NOFOLLOW_LINKS) && it.fileSize() > 0) { "Missing diagnostics" } }
    verifyDiagnostics(diagnostics.getValue("native-debug-symbols.zip"), apkDirectory, tools)
    require(!output.isSymbolicLink() && (!output.exists() || output.isDirectory() && output.listDirectoryEntries().isEmpty())) { "Refusing to overwrite populated public payload" }
    privateDirectory(privateRoot)
    val private = privateRoot.resolve("$version-$source")
    privateDirectory(private)
    val now = Instant.now()
    val retained = diagnostics.mapValues { (name, original) ->
        val destination = private.resolve(name)
        retainFile(original, destination)
        mapOf("sha256" to sha256(destination), "bytes" to destination.fileSize())
    }
    output.parent.createDirectories()
    val staging = Files.createTempDirectory(output.parent, ".release-")
    val attachments: List<Map<String, Any?>>
    try {
        attachments = plan.map { (input, name) ->
            val file = staging.resolve(name)
            Files.copy(apkDirectory.resolve(input.file), file)
            mapOf("name" to name, "abi" to input.abi, "sha256" to sha256(file))
        }
        require(staging.listDirectoryEntries().map { it.name }.toSet() == plan.map { it.second }.toSet())
        if (output.exists()) output.deleteExisting() // Checked empty above.
        Files.move(staging, output)
    } finally {
        if (staging.exists()) Files.walk(staging).use { it.sorted(Comparator.reverseOrder()).forEach(Files::delete) }
    }
    val manifest = mapOf(
        "mode" to "verified-payload", "tag" to tag, "version" to version,
        "versionCode" to plan.first().first.code, "source_commit" to source,
        "source_dirty" to command("git", "status", "--porcelain").isNotBlank(),
        "created_at" to now.toString(), "publication" to "separate gated Release job", "attachments" to attachments,
        "github_automatic_sources" to listOf("Source code (zip)", "Source code (tar.gz)"),
        "diagnostics" to mapOf(
            "access" to "local owner only; no public upload",
            "retention_days" to retention,
            "retain_until" to now.plus(retention.toLong(), ChronoUnit.DAYS).toString(),
            "files" to retained,
        ),
    )
    val manifestPath = private.resolve("manifest.json")
    require(!manifestPath.isSymbolicLink()) { "Retained manifest cannot be a symlink" }
    if (!manifestPath.exists()) Files.createFile(manifestPath, PosixFilePermissions.asFileAttribute(permissions600))
    require(manifestPath.getPosixFilePermissions() == permissions600 && Files.getOwner(manifestPath) == Files.getOwner(root))
    manifestPath.writeText(json(manifest))
    output.parent.resolve("release-manifest.json").writeText(json(manifest))
    return manifest
}

fun releaseInputTests(tools: AndroidTools): List<Map<String, Any?>> {
    val original = root.resolve("app/build/outputs/apk/release")
    val metadata = readJson(original.resolve("output-metadata.json"))
    val inputs = apkInputs(metadata, "moe.tarsin.ehviewer")
    return listOf("version-mismatch", "missing-apk", "extra-apk", "tampered-signature").map { scenario ->
        temporary { temp ->
            val directory = temp.resolve("inputs").createDirectory()
            original.listDirectoryEntries().filter { it.isRegularFile() }.forEach { Files.copy(it, directory.resolve(it.name)) }
            val first = directory.resolve(inputs.first().file)
            when (scenario) {
                "missing-apk" -> first.deleteExisting()
                "extra-apk" -> Files.copy(first, directory.resolve("unexpected.apk"))
                "tampered-signature" -> {
                    val changed = temp.resolve("changed.apk")
                    java.util.zip.ZipFile(first.toFile()).use { source ->
                        ZipOutputStream(changed.outputStream()).use { zip ->
                            source.entries().asSequence().forEach { entry ->
                                zip.putNextEntry(ZipEntry(entry.name))
                                if (!entry.isDirectory) source.getInputStream(entry).use { it.copyTo(zip) }
                                zip.closeEntry()
                            }
                            zip.putNextEntry(ZipEntry("tampered.txt"))
                            zip.write("signature regression probe".toByteArray())
                            zip.closeEntry()
                        }
                    }
                    Files.move(changed, first, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                }
            }
            val failure = runCatching {
                prepareRelease(directory, tools, temp.resolve("payload"), temp.resolve("private"), if (scenario == "version-mismatch") "v999.999.999" else "v${inputs.first().version}")
            }.exceptionOrNull() ?: error("Unsafe release input accepted: $scenario")
            if (scenario == "tampered-signature") require(failure is CommandFailure && failure.command.first().endsWith("apksigner")) { "Tampering must fail signature verification" }
            require(!temp.resolve("payload").exists()) { "Failed input produced public payload" }
            mapOf("scenario" to scenario, "rejected" to true, "error_type" to failure.javaClass.simpleName, "public_payload_created" to false)
        }
    }
}
