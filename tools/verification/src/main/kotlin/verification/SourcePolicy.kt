package verification

import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.util.Locale
import kotlin.io.path.readText

private const val MIGRATION_BASE = "423b8af13632896b55780783b54b5f720ec4c9dd"

private val forbiddenSourceExtensions = setOf(
    "swift", "m", "mm", "java", "c", "cc", "cpp", "cxx", "h", "hh", "hpp", "hxx",
    "py", "pyw", "js", "jsx", "mjs", "cjs", "ts", "tsx", "dart", "go", "sh", "bash",
    "zsh", "fish", "ps1", "bat", "cmd", "rb", "pl", "php", "lua", "cs", "fs", "vb",
)

// Directly retained third-party material, independently audited before M2. Not a dependency SBOM.
private val retainedOrigins = mapOf(
    "app/desktop/licenses/skiko-0.144.6/LICENSE.txt" to "c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4",
    "app/desktop/licenses/skiko-0.144.6/NOTICE.txt" to "35dd077c24768b53625101cfcc0ad853ae7e2abc08bd2a111a0267760d1406d8",
    "app/desktop/licenses/skiko-0.144.6/origin.toml" to "fd9442a5e2b5e4ea7cbac659841258fe2f370c973dfe0b43e4dac378440e3bd5",
)

fun sourcePolicy(repository: Path, baseline: String): Map<String, Any?> {
    require(Regex("[0-9a-f]{40}").matches(baseline)) { "Expected full audited baseline SHA" }
    val directory = repository.toRealPath()
    fun git(vararg args: String) = command("git", "-C", directory, *args)
    require(Path.of(git("rev-parse", "--show-toplevel").trim()).toRealPath() == directory) { "Expected repository root" }
    git("merge-base", "--is-ancestor", baseline, "HEAD") // Missing/shallow or unrelated history must fail.
    fun paths(output: String) = output.split('\u0000').filter(String::isNotEmpty)
    val index = paths(git("ls-files", "--stage", "-z"))
    require(index.none { it.startsWith("120000 ") || it.startsWith("160000 ") }) { "Symlink or submodule in source index" }
    require(index.all { it.substringBefore('\t').endsWith(" 0") }) { "Unmerged source index" }
    val indexObjects = index.associate { it.substringAfter('\t') to it.substringBefore('\t').split(' ')[1] }
    val untracked = paths(git("ls-files", "--others", "--exclude-standard", "-z"))
    val files = (paths(git("ls-files", "--cached", "-z")) + untracked).distinct().sorted()
    val indexChanged = paths(git("diff", "--cached", "--name-only", "--no-renames", "--diff-filter=ACMRT", "-z", baseline, "--"))
    val changed = (paths(git("diff", "--name-only", "--no-renames", "--diff-filter=ACMRT", "-z", baseline, "--")) + indexChanged + untracked).distinct().sorted()
    val violations = mutableListOf<String>()
    files.forEach { name ->
        val relative = Path.of(name)
        require(!relative.isAbsolute && relative.normalize() == relative && !relative.startsWith("..")) { "Unsafe repository path" }
        var current = directory
        for (part in relative) {
            current = current.resolve(part)
            if (Files.isSymbolicLink(current)) violations += "$name: symbolic link crosses source boundary"
        }
        if (Files.isDirectory(current, NOFOLLOW_LINKS)) violations += "$name: nested repository/directory in file inventory"
    }
    require(violations.isEmpty()) { violations.joinToString("\n") } // Never read through a discovered link.
    val referencePackage = Regex("(?m)^\\s*(?:package|import)\\s+(?:me\\.him188\\.ani|org\\.openani)(?:[.;\\s]|$)")
    val referenceLicense = Regex("(?im)^\\s*(?://|/\\*|\\*|#).*SPDX-License-Identifier:\\s*AGPL[- ]")
    var sources = 0
    changed.forEach { name ->
        val path = directory.resolve(name)
        val extension = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val kotlinOrRust = extension in setOf("kt", "rs") || name.endsWith(".gradle.kts")
        if (extension in forbiddenSourceExtensions || extension == "kts" && !name.endsWith(".gradle.kts")) {
            violations += "$name: changed implementation must use Kotlin or Rust"
        }
        if (kotlinOrRust) sources++
        fun content(text: String, location: String) {
            val prefix = text.take(256).removePrefix("\uFEFF")
            if (prefix.startsWith("#!") && !(extension == "rs" && prefix.startsWith("#!["))) {
                violations += "$name ($location): new or changed interpreter script"
            }
            if (kotlinOrRust && (referencePackage.containsMatchIn(text) || referenceLicense.containsMatchIn(text))) {
                violations += "$name ($location): reference implementation origin requires separate license review and permission"
            }
        }
        if (Files.isRegularFile(path, NOFOLLOW_LINKS)) content(path.readText(), "worktree")
        if (name in indexChanged) content(git("cat-file", "blob", indexObjects.getValue(name)), "index")
    }
    require(violations.isEmpty()) { violations.joinToString("\n") }
    return mapOf(
        "schema" to 1, "baseline_commit" to baseline,
        "source_commit" to git("rev-parse", "HEAD").trim(),
        "source_dirty" to git("status", "--porcelain").isNotBlank(),
        "inventory_files" to files.size, "changed_files" to changed,
        "changed_kotlin_rust_files" to sources, "untracked_files" to untracked,
        "reference_checkout_required" to false,
        "complete_copy_or_license_audit" to "NOT_VERIFIED", "result" to "PASS",
    )
}

fun verifySourcePolicy(): Map<String, Any?> {
    val origin = command("git", "remote", "get-url", "origin").trim().removeSuffix(".git")
    require(origin in setOf("https://github.com/ZhanZiyuan/EhViewer", "git@github.com:ZhanZiyuan/EhViewer")) { "Unexpected target repository" }
    val result = sourcePolicy(root, MIGRATION_BASE)
    retainedOrigins.forEach { (name, expected) ->
        require(sha256(root.resolve(name)) == expected) { "$name: audited third-party bytes changed" }
    }
    listOf("animeko-reference-audit.md", "assessment.md", "source-policy.md").forEach {
        require(Files.isRegularFile(root.resolve("docs/kmp/$it"), NOFOLLOW_LINKS)) { "Missing source/license audit: $it" }
    }
    return result + mapOf("retained_third_party_files" to retainedOrigins, "reference_audit" to "docs/kmp/animeko-reference-audit.md")
}
