package verification

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readBytes
import org.junit.Assert.assertEquals
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
        Files.createDirectories(repository.resolve(".github"))
        val networkSources = mapOf("android" to listOf("commonTest", "androidHostTest"), "jvm" to listOf("commonTest", "desktopTest"), "ios" to listOf("commonTest", "iosTest"))
        val readerSources = mapOf("android" to listOf("commonTest", "androidHostTest"), "jvm" to listOf("commonTest", "desktopTest", "skiaTest"), "ios" to listOf("commonTest", "iosTest", "skiaTest"))
        Files.writeString(repository.resolve(".github/ci-plan.json"), json(mapOf("product_test_sources" to mapOf("network" to networkSources, "shared-ui" to readerSources))))
        val products = linkedMapOf<String, Any?>()
        val modules = linkedMapOf<String, Any?>()
        for ((name, count) in mapOf("network" to 3, "shared-ui" to 1)) {
            val directory = repository.resolve(if (name == "network") "core/network/src/commonTest" else "app/shared/src/commonTest")
            Files.createDirectories(directory)
            repeat(count) { Files.writeString(directory.resolve("Source$it.kt"), "// Unit-test inventory fixture\n") }
            if (name == "shared-ui") {
                for ((sourceSet, file) in mapOf("androidHostTest" to "PlatformAndroid.kt", "skiaTest" to "PlatformSkia.kt")) {
                    val platform = repository.resolve("app/shared/src/$sourceSet")
                    Files.createDirectories(platform)
                    Files.writeString(platform.resolve(file), "// Platform test source inventory fixture\n")
                }
            }
            val suites = if (name == "network") mapOf("GalleryRepositoryTest" to 10, "PersistentCookieStorageTest" to 12, "GalleryContentRepositoryTest" to 10, "GalleryAuthenticationRepositoryTest" to 8, "BrowserSignInTest" to 7, "GalleryAccountRepositoryTest" to 13, "GallerySearchFiltersTest" to 6, "GalleryPreviewRepositoryTest" to 5, "GalleryDownloadQueueTest" to 10, "NetworkGalleryDownloadSourceTest" to 9, "DownloadedGalleryContentTest" to 4, "ArchiveGalleryContentTest" to 6, "GalleryOriginalImageTest" to 8, "PartialGalleryImageTest" to 10, "GalleryFavoritesRepositoryTest" to 17) else mapOf("GalleryImageLimitsTest" to 2, "ReaderStateRepositoryTest" to 9, "ReaderPageWindowTest" to 6, "ReadingNavigationTest" to 4, "GalleryAppearanceRepositoryTest" to 6, "GalleryPreviewCacheTest" to 5)
            fun receipt(target: String): Map<String, Any?> {
                val output = artifacts.resolve("tests/$target")
                Files.createDirectories(output)
                val media = if (name == "network") emptyMap() else mapOf("GalleryMediaTest" to 8) + if (target.endsWith("-jvm")) mapOf("SkiaGalleryMediaTest" to 7) else mapOf("GalleryAnimationLeaseTest" to 2)
                val files = (suites + media).map { (suite, cases) ->
                    val file = output.resolve("TEST-$suite.xml")
                    val tests = (1..cases).joinToString("") { "<testcase name=\"case$it\"/>" }
                    Files.writeString(file, "<testsuite name=\"com.ehviewer.$suite\" tests=\"$cases\" failures=\"0\" errors=\"0\" skipped=\"0\">$tests</testsuite>")
                    file.readBytes()
                }
                return sharedHostTestEvidence(files, count + if (name == "shared-ui") 1 else 0)
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
            if (name == "shared-ui") mapOf("host_tests" to (value.obj()["host_tests"].obj() + ("source_files" to 3))) else value
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

    @Test fun platformMediaSuitesAreMandatoryOnTheirActualDecoderHosts() {
        val case = fixture()
        val jvm = case.proof["product_jvm"].obj().getValue("shared-ui").obj()["host_tests"].obj()
        val android = case.proof["modules"].obj().getValue("shared-ui").obj()["host_tests"].obj()
        verifyReaderTests(jvm)
        verifyReaderTests(android, false)
        for ((receipt, missing, skia) in listOf(Triple(jvm, "SkiaGalleryMediaTest", true), Triple(android, "GalleryAnimationLeaseTest", false), Triple(jvm, "GalleryMediaTest", true))) {
            val incomplete = receipt + ("suites" to receipt["suites"].obj().filterKeys { !it.endsWith(missing) })
            assertThrows(IllegalArgumentException::class.java) { verifyReaderTests(incomplete, skia) }
        }
    }

    @Test fun declaredSharedRootsCountPlatformInputsOnOnlyTheirActualTargets() {
        val case = fixture()
        assertEquals(setOf("Source0.kt", "PlatformAndroid.kt"), productTestSources(case.repository, "shared-ui", "android").map { it.fileName.toString() }.toSet())
        for (target in listOf("jvm", "ios")) {
            assertEquals(setOf("Source0.kt", "PlatformSkia.kt"), productTestSources(case.repository, "shared-ui", target).map { it.fileName.toString() }.toSet())
        }
        Files.writeString(case.repository.resolve("app/shared/src/androidHostTest/Extra.kt"), "// New Android host regression\n")
        assertThrows(IllegalArgumentException::class.java) { verifyProductTestReceipts(case.proof, case.artifacts, case.repository) }
        val modules = case.proof["modules"].obj().mapValues { (name, value) ->
            if (name == "shared-ui") mapOf("host_tests" to (value.obj()["host_tests"].obj() + ("source_files" to 3))) else value
        }
        verifyProductTestReceipts(case.proof + ("modules" to modules), case.artifacts, case.repository)
        assertEquals(2, productTestSources(case.repository, "shared-ui", "jvm").size)
        assertEquals(2, productTestSources(case.repository, "shared-ui", "ios").size)
    }

    @Test fun unregisteredAndEscapingDirectoriesCannotMasqueradeAsExecutedTests() {
        val case = fixture()
        val outside = case.repository.resolve("app/shared/src/extraTest")
        Files.createDirectories(outside)
        Files.writeString(outside.resolve("Unregistered.kt"), "// Unregistered test root\n")
        assertThrows(IllegalArgumentException::class.java) { productTestSources(case.repository, "shared-ui", "jvm") }
        Files.delete(outside.resolve("Unregistered.kt"))
        val plan = readJson(case.repository.resolve(".github/ci-plan.json"))
        val sources = plan["product_test_sources"].obj().toMutableMap()
        sources["shared-ui"] = sources.getValue("shared-ui").obj() + ("jvm" to listOf("commonTest", "../outsideTest"))
        Files.writeString(case.repository.resolve(".github/ci-plan.json"), json(plan + ("product_test_sources" to sources)))
        assertThrows(IllegalArgumentException::class.java) { productTestSources(case.repository, "shared-ui", "jvm") }
    }
}
