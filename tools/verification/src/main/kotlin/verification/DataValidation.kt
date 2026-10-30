package verification

import java.nio.file.Path

/** Require the actual database, transaction and migration suites on each applicable runtime. */
fun verifyDataTests(tests: Map<String, Any?>, native: Boolean = false) {
    require(tests["status"] == "PASS" && tests["executed"] == true)
    val suites = tests["suites"].obj().mapKeys { it.key.substringAfterLast('.').substringBefore('[') }
    val required = mapOf("RoomGalleryHistoryStoreTest" to 4, "HistoryMigrationTest" to 3, "PortablePreferencesTest" to 4) + if (native) emptyMap() else mapOf("DesktopSQLiteDriverTest" to 6)
    for ((suite, minimum) in required) require((suites[suite] as? Number)?.toInt()?.let { it >= minimum } == true) { "Required real database behavior was not tested: $suite" }
}

fun checkedDataJvmArtifact(jar: Path, evidence: Map<String, Any?>) {
    val classes = checkedJvmInventory(jar, evidence)
    require(
        listOf(
            "com/ehviewer/core/data/RoomGalleryHistoryStore.class",
            "com/ehviewer/core/database/EhDatabase.class",
            "com/ehviewer/core/database/EhDatabase_Impl.class",
            "com/ehviewer/core/database/SearchDatabase.class",
            "com/ehviewer/core/database/SearchDatabase_Impl.class",
            "com/ehviewer/core/database/DesktopSQLiteDriver.class",
        ).all(classes::containsKey),
    ) { "Actual portable database JAR omits its real generated Room implementation or native driver" }
}
