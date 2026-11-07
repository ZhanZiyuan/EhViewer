package verification

import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.io.path.writeBytes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SharedCoreTest {
    private val className = "verification/SharedCoreTest.class"
    private val compiledBytes get() = javaClass.getResourceAsStream("SharedCoreTest.class")!!.use { it.readAllBytes() }

    private fun archive(entries: Map<String, ByteArray>): ByteArray = ByteArrayOutputStream().also { output ->
        ZipOutputStream(output).use { zip ->
            entries.forEach { (name, bytes) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(bytes)
                zip.closeEntry()
            }
        }
    }.toByteArray()

    private fun fixture(directory: Path, packaged: ByteArray = compiledBytes, extra: Map<String, ByteArray> = emptyMap(), resources: String? = "<resources><string name=\"title\">Fixture</string></resources>"): Pair<Path, Path> {
        val classes = directory.resolve("classes")
        Files.createDirectories(classes.resolve(className).parent)
        classes.resolve(className).writeBytes(compiledBytes)
        val entries = mapOf(
            "AndroidManifest.xml" to "<manifest package=\"fixture.library\"/>".toByteArray(),
            "classes.jar" to archive(mapOf(className to packaged)),
        ) + extra + if (resources == null) emptyMap() else mapOf("res/values/values.xml" to resources.toByteArray())
        val aar = directory.resolve("fixture.aar")
        aar.writeBytes(archive(entries))
        return aar to classes
    }

    private fun rejects(block: () -> Unit) = assertNotNull(runCatching(block).exceptionOrNull())

    @Test fun actualCompilerClassMustBePresentAndByteIdenticalInAar() = temporary { directory ->
        val (aar, classes) = fixture(directory)
        val evidence = sharedAarEvidence(aar, classes, "fixture.library", true)
        assertEquals(61, evidence["android_class_major"])
        assertEquals(1, evidence["compiled_class_count"])
        assertEquals(1, evidence["resource_xml_count"])
        assertEquals("PASS", evidence["result"])
        val modified = compiledBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        fixture(directory, packaged = modified)
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
    }

    @Test fun wrongBytecodeAndMissingCompilerOutputCannotPass() = temporary { directory ->
        val invalid = compiledBytes.copyOf().also { it[7] = 69 }
        val (aar, classes) = fixture(directory, packaged = invalid)
        classes.resolve(className).writeBytes(invalid)
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
        Files.delete(classes.resolve(className))
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
    }

    @Test fun wrongLibraryMissingResourcesAndNoncanonicalEntriesFail() = temporary { directory ->
        val (aar, classes) = fixture(directory)
        rejects { sharedAarEvidence(aar, classes, "other.library", true) }
        fixture(directory, resources = null)
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
        fixture(directory, extra = mapOf("../outside" to byteArrayOf(1)))
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
    }

    @Test fun localizationXmlCannotResolveExternalEntities() = temporary { directory ->
        val resources = "<!DOCTYPE resources [<!ENTITY outside SYSTEM \"file:///unavailable-phase-fixture\">]><resources><string name=\"title\">&outside;</string></resources>"
        val (aar, classes) = fixture(directory, resources = resources)
        rejects { sharedAarEvidence(aar, classes, "fixture.library", true) }
    }

    @Test fun actualJvmJarMustMatchEveryRealCompilerClass() = temporary { directory ->
        val (_, classes) = fixture(directory)
        val jar = directory.resolve("library.jar").also { it.writeBytes(archive(mapOf(className to compiledBytes))) }
        val evidence = sharedJvmEvidence(jar, classes)
        assertEquals("PASS", evidence["result"])
        assertEquals(61, evidence["class_major"])
        assertEquals(1, evidence["compiled_class_count"])
        val altered = compiledBytes.copyOf().also { it[it.lastIndex] = (it.last().toInt() xor 1).toByte() }
        jar.writeBytes(archive(mapOf(className to altered)))
        rejects { sharedJvmEvidence(jar, classes) }
    }

    @Test fun missingExtraAndNonJava17JvmClassesCannotPass() = temporary { directory ->
        val (_, classes) = fixture(directory)
        val jar = directory.resolve("library.jar")
        for (entries in listOf(emptyMap(), mapOf("extra.class" to compiledBytes), mapOf(className to compiledBytes, "extra.class" to compiledBytes))) {
            jar.writeBytes(archive(entries))
            rejects { sharedJvmEvidence(jar, classes) }
        }
        val wrongVersion = compiledBytes.copyOf().also { it[7] = 69 }
        classes.resolve(className).writeBytes(wrongVersion)
        jar.writeBytes(archive(mapOf(className to wrongVersion)))
        rejects { sharedJvmEvidence(jar, classes) }
    }

    @Test fun unrelatedActualJvmJarCannotImpersonateTheProductionDomainApi() = temporary { directory ->
        val jar = directory.resolve("unrelated.jar").also { it.writeBytes(archive(mapOf(className to compiledBytes))) }
        rejects { domainJvmLinkProbe(jar) }
    }

    private fun receipt(tests: Int = 1, skipped: Int = 0, body: String = "<testcase name=\"actual\"/>") = "<testsuite name=\"fixture.Tests\" tests=\"$tests\" failures=\"0\" errors=\"0\" skipped=\"$skipped\">$body</testsuite>".toByteArray()

    @Test fun executedHostTestsRemainDistinctFromNoTestSources() {
        val evidence = sharedHostTestEvidence(listOf(receipt()), 1)
        assertEquals("PASS", evidence["status"])
        assertEquals(1, evidence["tests"])
        assertEquals(true, evidence["executed"])
        val absent = sharedHostTestEvidence(emptyList(), 0)
        assertEquals("NO_TEST_SOURCES", absent["status"])
        assertEquals(false, absent["executed"])
        rejects { sharedHostTestEvidence(emptyList(), 1) }
        rejects { sharedHostTestEvidence(listOf(receipt()), 0) }
    }

    @Test fun skippedInconsistentDuplicateAndHiddenFailuresAreRejected() {
        for (xml in listOf(receipt(0, body = ""), receipt(2), receipt(skipped = 1), receipt(body = "<testcase name=\"actual\"><failure/></testcase>"))) {
            rejects { sharedHostTestEvidence(listOf(xml), 1) }
        }
        rejects { sharedHostTestEvidence(listOf(receipt(), receipt()), 1) }
    }
}
