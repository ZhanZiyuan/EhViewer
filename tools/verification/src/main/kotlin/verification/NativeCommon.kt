package verification

val commonNativeDependencies = setOf(
    "EhViewer.core:domain", "com.squareup.okio:okio",
    "io.arrow-kt:arrow-annotations", "io.arrow-kt:arrow-atomic", "io.arrow-kt:arrow-autoclose",
    "io.arrow-kt:arrow-core", "io.arrow-kt:arrow-exception-utils", "io.arrow-kt:arrow-functions",
    "io.arrow-kt:arrow-fx-coroutines", "io.arrow-kt:arrow-resilience",
    "org.jetbrains.kotlinx:atomicfu", "org.jetbrains.kotlinx:atomicfu-cinterop-interop",
    "org.jetbrains.kotlinx:kotlinx-coroutines-core", "org.jetbrains.kotlinx:kotlinx-datetime",
    "org.jetbrains.kotlinx:kotlinx-io-bytestring", "org.jetbrains.kotlinx:kotlinx-io-core",
    "org.jetbrains.kotlinx:kotlinx-serialization-cbor", "org.jetbrains.kotlinx:kotlinx-serialization-core", "stdlib",
)

fun verifyCommonNativeTests(tests: Map<String, Any?>) {
    require(
        tests["status"] == "PASS" && tests["executed"] == true && tests["source_files"] == 2 && tests["tests"] == 8 &&
            tests["suites"].obj() == mapOf(
                "iosSimulatorArm64Test.com.ehviewer.core.domain.GalleryTitlesTest" to 3,
                "iosSimulatorArm64Test.com.ehviewer.core.files.FilesystemTest" to 5,
            ),
    ) { "Native common must execute all original title and actual filesystem/cancellation tests" }
}

fun verifyCommonNativeApiDump(text: String) {
    require(
        listOf(
            "public final val SystemFileSystem: okio/FileSystem",
            "public final inline fun <T#0 /* T */> okio/Path.read(f: @kotlin/ExtensionFunctionType kotlin/Function1<kotlinx/io/Source, T#0>): T#0",
            "public final inline fun <T#0 /* T */> okio/Path.write(f: @kotlin/ExtensionFunctionType kotlin/Function1<kotlinx/io/Sink, T#0>): T#0",
            "public final infix fun okio/Path.moveTo(target: okio/Path): kotlin/Unit",
            "public final infix fun okio/Path.sendTo(target: okio/Path): kotlin/Unit",
            "public final val okio/Path.isDirectory: kotlin/Boolean",
            "public final val okio/Path.isFile: kotlin/Boolean",
            "public final fun launchIO(block:",
            "public final suspend fun <T#0 /* T */> withIOContext(block:",
        ).all(text::contains),
    ) { "Actual common KLIB lost its filesystem or I/O dispatcher public API" }
}

val commonNativeSpec = NativeLibrarySpec(
    "common",
    1,
    portableCommonTargets,
    commonNativeDependencies,
    (0..3).map { "default/linkdata/package_com.ehviewer.core.files/${it}_files.knm" } +
        (0..6).map { "default/linkdata/package_com.ehviewer.core.util/${it}_util.knm" },
    ::verifyCommonNativeApiDump,
    ::verifyCommonNativeTests,
    listOf("commonTest", "nonAndroidTest"),
)
