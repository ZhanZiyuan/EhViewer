package verification

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import kotlin.io.path.getPosixFilePermissions
import kotlin.io.path.setPosixFilePermissions
import kotlin.io.path.writeText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTest {
    private fun sample(abi: String = "arm64-v8a", align: Long = 16384, offset: Long = 0, count: Int = 1): ByteArray {
        val buffer = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(byteArrayOf(0x7f, 69, 76, 70, if (abi == "armeabi-v7a") 1 else 2, 1))
        buffer.putShort(18, abis.getValue(abi).toShort())
        if (abi != "armeabi-v7a") {
            buffer.putLong(32, 64)
            buffer.putShort(54, 56)
            buffer.putShort(56, count.toShort())
            buffer.putInt(64, 1)
            buffer.putLong(72, offset)
            buffer.putLong(112, align)
        } else {
            buffer.putInt(28, 52)
            buffer.putShort(42, 32)
            buffer.putShort(44, count.toShort())
            buffer.putInt(52, 1)
            buffer.putInt(56, offset.toInt())
            buffer.putInt(80, align.toInt())
        }
        return buffer.array()
    }
    private fun rejects(block: () -> Unit) {
        assertNotNull(runCatching(block).exceptionOrNull())
    }
    private fun metadata(): MutableMap<String, Any?> = mutableMapOf(
        "artifactType" to mapOf("type" to "APK"),
        "applicationId" to "moe.tarsin.ehviewer",
        "elements" to (abis.keys + "universal").map { abi ->
            mutableMapOf<String, Any?>(
                "filters" to if (abi == "universal") emptyList<Any>() else listOf(mapOf("filterType" to "ABI", "value" to abi)),
                "versionCode" to 180066,
                "versionName" to "1.15.2",
                "outputFile" to "app-$abi-release.apk",
            )
        }.toMutableList(),
    )

    @Suppress("UNCHECKED_CAST")
    private fun elements(data: Map<String, Any?>): MutableList<MutableMap<String, Any?>> = data["elements"] as MutableList<MutableMap<String, Any?>>

    @Test fun allAbis() {
        abis.keys.forEach { assertEquals(listOf(16384L), elfAlignment(sample(it), it)) }
    }

    @Test fun rejectsFourKiB64() {
        listOf("arm64-v8a", "x86_64").forEach { rejects { elfAlignment(sample(it, 4096), it) } }
    }

    @Test fun acceptsFourKiB32() {
        assertEquals(listOf(4096L), elfAlignment(sample("armeabi-v7a", 4096), "armeabi-v7a"))
    }

    @Test fun wrongMachine() {
        rejects { elfAlignment(sample("x86_64"), "arm64-v8a") }
    }

    @Test fun misalignedLoad() {
        rejects { elfAlignment(sample(offset = 1), "arm64-v8a") }
    }

    @Test fun nonPowerOfTwo() {
        rejects { elfAlignment(sample(align = 20000), "arm64-v8a") }
    }

    @Test fun emptyLoads() {
        rejects { elfAlignment(sample(count = 0), "arm64-v8a") }
    }

    @Test fun magicAndClass() {
        rejects { elfAlignment("bad".toByteArray(), "arm64-v8a") }
        rejects { elfAlignment(sample("armeabi-v7a"), "arm64-v8a") }
    }

    @Test fun truncatedHeaders() {
        rejects { elfAlignment(sample().copyOf(80), "arm64-v8a") }
        rejects { elfAlignment(sample(count = 65535), "arm64-v8a") }
    }

    @Test fun normalizesTags() {
        listOf("1.15.2", "v1.15.2").forEach { assertEquals("1.15.2", versionFromTag(it)) }
        assertEquals("1.15.2-RC1", versionFromTag("v1.15.2-RC1"))
    }

    @Test fun rejectsUnsafeTags() {
        listOf("", "v", "../1.15.2", "v1.15", "01.15.2", "1.15.2-SNAPSHOT", "1.15.2-default", "1.15.2-marshmallow", "v1.15.2\n", "1.15.2;echo", "1.15.2+meta").forEach { rejects { versionFromTag(it) } }
    }

    @Test fun fourNames() {
        assertEquals((abis.keys + "universal").map { "EhViewer-1.15.2-$it.apk" }.toSet(), attachmentPlan(metadata(), "v1.15.2").map { it.second }.toSet())
    }

    @Test fun matrixMetadataRequiresItsExactArchitecture() {
        (abis.keys + "universal").forEach { abi ->
            val data = metadata()
            data["elements"] = elements(data).filter { it["outputFile"] == "app-$abi-release.apk" }
            assertEquals(abi, apkInputs(data, "moe.tarsin.ehviewer", setOf(abi)).single().abi)
            rejects { apkInputs(data, "moe.tarsin.ehviewer") }
            rejects { apkInputs(data, "moe.tarsin.ehviewer", setOf("x86")) }
        }
    }

    @Test fun matrixPlanRejectsMixedSourcesVersionsAndDirtyArtifacts() {
        val source = "1".repeat(40)
        fun parts(): Map<String, Map<String, Any?>> = (abis.keys + "universal").associateWith { abi ->
            mapOf(
                "mode" to "verified-payload", "tag" to "v1.15.5", "version" to "1.15.5", "versionCode" to 180069,
                "source_commit" to source, "source_dirty" to false, "source_status" to emptyList<String>(),
                "diagnostics" to mapOf("retention_days" to 90),
                "attachments" to listOf(mapOf("abi" to abi, "name" to "EhViewer-1.15.5-$abi.apk", "sha256" to "a".repeat(64))),
            )
        }
        assertEquals(4, releasePartsPlan(parts(), "v1.15.5", source).size)
        rejects { releasePartsPlan(parts() - "universal", "v1.15.5", source) }
        listOf(
            "source_commit" to "2".repeat(40),
            "source_dirty" to true,
            "source_status" to listOf("?? unexpected"),
            "versionCode" to 180068,
            "version" to "1.15.4",
            "diagnostics" to mapOf("retention_days" to 14),
            "attachments" to listOf(mapOf("abi" to "universal", "name" to "../bad.apk", "sha256" to "a".repeat(64))),
        ).forEach { changed ->
            val data = parts().toMutableMap()
            data["arm64-v8a"] = data.getValue("arm64-v8a") + changed
            rejects { releasePartsPlan(data, "v1.15.5", source) }
        }
    }

    @Test fun missingOrDuplicateAbi() {
        val missing = metadata()
        elements(missing).removeLast()
        rejects { attachmentPlan(missing, "v1.15.2") }
        val duplicate = metadata()
        elements(duplicate).add(elements(duplicate).first())
        rejects { attachmentPlan(duplicate, "v1.15.2") }
        val unknown = metadata()
        elements(unknown)[0]["filters"] = listOf(mapOf("filterType" to "ABI", "value" to "x86"))
        rejects { attachmentPlan(unknown, "v1.15.2") }
    }

    @Test fun versionMismatch() {
        val data = metadata()
        elements(data)[2]["versionName"] = "1.15.2-SNAPSHOT"
        rejects { attachmentPlan(data, "v1.15.2") }
        rejects { attachmentPlan(metadata(), "v1.15.3") }
    }

    @Test fun consistentCodes() {
        listOf(-1, 0, true, "180066", 180067).forEach { code ->
            val data = metadata()
            elements(data)[1]["versionCode"] = code
            rejects { attachmentPlan(data, "v1.15.2") }
        }
    }

    @Test fun identityAndArtifact() {
        listOf("applicationId" to "moe.tarsin.ehviewer.m", "artifactType" to mapOf("type" to "BUNDLE")).forEach { (key, value) ->
            val data = metadata()
            data[key] = value
            rejects { attachmentPlan(data, "v1.15.2") }
        }
    }

    @Test fun filtersAndPaths() {
        listOf("../outside.apk", "/tmp/out.apk", "sub/out.apk", "sub\\out.apk", "mapping.txt").forEach { name ->
            val data = metadata()
            elements(data)[0]["outputFile"] = name
            rejects { attachmentPlan(data, "v1.15.2") }
        }
        val data = metadata()
        elements(data)[0]["filters"] = listOf(mapOf("filterType" to "DENSITY", "value" to "hdpi"))
        rejects { attachmentPlan(data, "v1.15.2") }
    }

    @Test fun duplicateInput() {
        val data = metadata()
        elements(data)[1]["outputFile"] = elements(data)[0]["outputFile"]
        rejects { attachmentPlan(data, "v1.15.2") }
    }

    @Test fun ownerOnlyDirectory() = temporary { temp ->
        val path = temp.resolve("private")
        privateDirectory(path)
        assertEquals(permissions700, path.getPosixFilePermissions())
        path.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rwxr-xr-x"))
        rejects { privateDirectory(path) }
        val link = temp.resolve("link")
        Files.createSymbolicLink(link, path)
        rejects { privateDirectory(link) }
        rejects { privateDirectory(link.resolve("child")) }
    }

    @Test fun retainSameFileAndRejectOverwrite() = temporary { temp ->
        val source = temp.resolve("source").apply { writeText("diagnostic") }
        val destination = temp.resolve("retained")
        retainFile(source, destination)
        assertEquals(permissions600, destination.getPosixFilePermissions())
        retainFile(source, destination)
        source.writeText("different")
        rejects { retainFile(source, destination) }
    }

    @Test fun retainedFileRejectsBroadPermissions() = temporary { temp ->
        val source = temp.resolve("source").apply { writeText("diagnostic") }
        val destination = temp.resolve("retained")
        retainFile(source, destination)
        destination.setPosixFilePermissions(java.nio.file.attribute.PosixFilePermissions.fromString("rw-r--r--"))
        rejects { retainFile(source, destination) }
    }

    @Test fun retainedFileRejectsSymlinkAndEmpty() = temporary { temp ->
        val source = temp.resolve("source").apply { writeText("diagnostic") }
        val destination = temp.resolve("retained")
        Files.createSymbolicLink(destination, source)
        rejects { retainFile(source, destination) }
        source.writeText("")
        rejects { retainFile(source, temp.resolve("empty")) }
    }

    @Test fun failedCommandsAndTimeoutsPropagate() {
        assertTrue(runCatching { command("git", "definitely-not-a-command") }.exceptionOrNull() is CommandFailure)
        rejects { command("sleep", "2", timeout = java.time.Duration.ofMillis(50)) }
    }

    @Test fun machineReadableOutputExcludesDiagnostics() {
        assertEquals("clean\n", command("git", "-c", "alias.output-probe=!printf diagnostic >&2; printf 'clean\\n'", "output-probe", mergeError = false))
    }

    @Test fun jsonRoundTrip() = temporary { temp ->
        val path = temp.resolve("metadata.json").apply { writeText(json(metadata())) }
        assertEquals(4, attachmentPlan(readJson(path), "v1.15.2").size)
    }
}
