package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ProductTestReceiptsTest {
    @get:Rule val temporary = TemporaryFolder()

    private data class Fixture(val repository: Path, val artifacts: Path, val proof: Map<String, Any?>)

    private fun fixture(): Fixture {
        val repository = temporary.newFolder().toPath()
        val artifacts = repository.resolve("artifacts")
        val products = linkedMapOf<String, Any?>()
        val modules = linkedMapOf<String, Any?>()
        for ((name, count) in mapOf("network" to 3, "shared-ui" to 1)) {
            val directory = repository.resolve(if (name == "network") "core/network/src/commonTest" else "app/shared/src/commonTest")
            Files.createDirectories(directory)
            repeat(count) { Files.writeString(directory.resolve("Source$it.kt"), "// Unit-test inventory fixture\n") }
            val suites = if (name == "network") mapOf("GalleryRepositoryTest" to 10, "PersistentCookieStorageTest" to 11, "GalleryContentRepositoryTest" to 10, "GalleryAuthenticationRepositoryTest" to 8, "BrowserSignInTest" to 7) else mapOf("GalleryImageLimitsTest" to 2, "ReaderStateRepositoryTest" to 9, "ReaderPageWindowTest" to 6, "ReadingNavigationTest" to 3)
            fun receipt(target: String): Map<String, Any?> {
                val output = artifacts.resolve("tests/$target")
                Files.createDirectories(output)
                val files = suites.map { (suite, cases) ->
                    val file = output.resolve("TEST-$suite.xml")
                    val tests = (1..cases).joinToString("") { "<testcase name=\"case$it\"/>" }
                    Files.writeString(file, "<testsuite name=\"com.ehviewer.$suite\" tests=\"$cases\" failures=\"0\" errors=\"0\" skipped=\"0\">$tests</testsuite>")
                    file.readBytes()
                }
                return sharedHostTestEvidence(files, count)
            }
            products[name] = mapOf("host_tests" to receipt("$name-jvm"))
            modules[name] = mapOf("host_tests" to receipt(name))
        }
        return Fixture(repository, artifacts, mapOf("product_jvm" to products, "modules" to modules))
    }

    @Test fun newlyAddedNetworkAndUiTestsUseTheActualSourceInventoryOnBothHosts() {
        val case = fixture()
        verifyProductTestReceipts(case.proof, case.artifacts, case.repository)
        // Another source file needs a matching producer receipt, not another hard-coded stage edit.
        Files.writeString(case.repository.resolve("app/shared/src/commonTest/Helper.kt"), "// Additional source fixture\n")
        assertThrows(IllegalArgumentException::class.java) { verifyProductTestReceipts(case.proof, case.artifacts, case.repository) }
        fun revised(section: String) = case.proof.getValue(section).obj().mapValues { (name, value) ->
            if (name == "shared-ui") mapOf("host_tests" to (value.obj()["host_tests"].obj() + ("source_files" to 2))) else value
        }
        verifyProductTestReceipts(case.proof + mapOf("product_jvm" to revised("product_jvm"), "modules" to revised("modules")), case.artifacts, case.repository)
    }

    @Test fun staleProducerCountsAndMissingOrSkippedNewTestsCannotPass() {
        val case = fixture()
        val stale = case.proof["product_jvm"].obj().mapValues { (name, value) -> mapOf("host_tests" to (value.obj()["host_tests"].obj() + ("source_files" to if (name == "network") 2 else 0))) }
        assertThrows(IllegalArgumentException::class.java) { verifyProductTestReceipts(case.proof + ("product_jvm" to stale), case.artifacts, case.repository) }
        val image = case.artifacts.resolve("tests/shared-ui/TEST-GalleryImageLimitsTest.xml")
        Files.writeString(image, Files.readString(image).replace("skipped=\"0\"", "skipped=\"1\""))
        assertThrows(IllegalArgumentException::class.java) { verifyProductTestReceipts(case.proof, case.artifacts, case.repository) }
    }
}
