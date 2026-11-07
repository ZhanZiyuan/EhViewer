package verification

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class StagedDomainTestsTest {
    @get:Rule val temporary = TemporaryFolder()

    private fun sources(): Path = temporary.newFolder().toPath().also { repository ->
        val directory = Files.createDirectories(repository.resolve("core/domain/src/commonTest"))
        repeat(3) { Files.writeString(directory.resolve("Test$it.kt"), "// Test source inventory fixture\n") }
    }

    private fun xml(jvm: Boolean): List<ByteArray> {
        val names = if (jvm) {
            listOf("GalleryDomainTitlesTest[desktop]", "GalleryModelWireTest[desktop]", "ReaderModeTest[desktop]")
        } else {
            listOf("com.ehviewer.core.domain.GalleryDomainTitlesTest", "com.ehviewer.core.model.GalleryModelWireTest", "com.ehviewer.core.domain.reader.ReaderModeTest")
        }
        return names.zip(listOf(3, 5, 3)).map { (name, count) ->
            val cases = (1..count).joinToString("") { "<testcase name=\"case$it\"/>" }
            "<testsuite name=\"$name\" tests=\"$count\" failures=\"0\" errors=\"0\" skipped=\"0\">$cases</testsuite>".toByteArray()
        }
    }

    @Test fun bothStagedHostsAcceptExpandedTestsOnlyWithCurrentSourceInventory() {
        for (jvm in listOf(false, true)) {
            val repository = sources()
            val xml = xml(jvm)
            val expected = sharedHostTestEvidence(xml, 3)
            verifyStagedDomainTests(xml, expected, jvm, repository)
            Files.writeString(repository.resolve("core/domain/src/commonTest/Helper.kt"), "// Additional inventory fixture\n")
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(xml, expected, jvm, repository) }
            verifyStagedDomainTests(xml, sharedHostTestEvidence(xml, 4), jvm, repository)
        }
    }

    @Test fun staleReceiptsMissingRegressionsAndSkippedXmlStillFail() {
        for (jvm in listOf(false, true)) {
            val repository = sources()
            val xml = xml(jvm)
            val expected = sharedHostTestEvidence(xml, 3)
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(xml, expected + ("source_files" to 2), jvm, repository) }
            val missing = xml.dropLast(1)
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(missing, sharedHostTestEvidence(missing, 3), jvm, repository) }
            val skipped = xml.map { it.toString(Charsets.UTF_8).replace("skipped=\"0\"", "skipped=\"1\"").toByteArray() }
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(skipped, expected, jvm, repository) }
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(xml.dropLast(1), expected, jvm, repository) }
            assertThrows(IllegalArgumentException::class.java) { verifyStagedDomainTests(xml(!jvm), expected, jvm, repository) }
        }
    }
}
