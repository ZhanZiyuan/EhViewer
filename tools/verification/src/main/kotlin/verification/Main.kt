package verification

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected architecture, bytecode, native-sources, apks, diagnostics, prepare-snapshot, prepare-release, release-input-tests, encrypt-diagnostics, decrypt-diagnostics, diagnostics-key or device" }
    val options = Options(args.drop(1))
    fun tools(): AndroidTools = AndroidTools(options.path("--sdk", System.getenv("ANDROID_HOME")))
    val result: Any = when (args[0]) {
        "architecture" -> verifyArchitecture()
        "source-policy" -> verifySourcePolicy()
        "release-contract" -> verifyReleaseContract(if (options.has("--tag")) options.get("--tag") else null)
        "ios-startup" -> iosStartup(options.path("--app"), options.path("--output"), if (options.has("--runtime")) options.get("--runtime") else null)
        "desktop-native-smoke" -> desktopNativeSmoke(options.path("--output"))
        "desktop-intel-dmg" -> desktopIntelDmg(options.path("--dmg"), options.path("--output"))
        "desktop-intel-native" -> desktopIntelDmg(options.path("--dmg"), options.path("--output"), options.path("--producer-report"))
        "desktop-linux-arm64" -> desktopLinuxArm64(options.path("--image"), options.path("--output"))
        "windows-native-core" -> windowsNativeCore(options.path("--output"))
        "windows-jvm-ffi" -> windowsJvmFfi(options.path("--core-report"), options.path("--output"))
        "desktop-windows-arm64" -> desktopWindowsArm64(options.path("--jvm-report"), options.path("--core-report"), options.path("--output"))
        "jmod-entry" -> mapOf("archive_sha256" to sha256(options.path("--jmod")), "entry" to options.get("--entry"), "entry_sha256" to jmodEntrySha256(options.path("--jmod"), options.get("--entry")))
        "windows-manifest" -> {
            val input = java.nio.file.Files.readAllBytes(options.path("--input"))
            val updated = windowsUtf8Manifest(input)
            verifyWindowsUtf8Manifest(input, updated)
            java.nio.file.Files.write(options.path("--output"), updated, java.nio.file.StandardOpenOption.CREATE_NEW)
            mapOf("manifest_sha256" to sha256(options.path("--output")), "settings_preserved" to "PASS")
        }
        "bytecode" -> verifyBytecode()
        "native-sources" -> verifyNativeSources()
        "apks" -> {
            if (options.has("--test-signing")) require(apkInputs(readJson(root.resolve(options.positional.single()).resolve("output-metadata.json")), "moe.tarsin.ehviewer").all { it.version.endsWith("-SNAPSHOT") }) { "Test signing is allowed only for CI snapshots" }
            verifyApks(root.resolve(options.positional.single()), tools(), options.get("--application-id", "moe.tarsin.ehviewer"), !options.has("--debug") && !options.has("--test-signing"), if (options.has("--version-code")) options.get("--version-code").toInt() else null, if (options.has("--abi")) setOf(options.get("--abi")) else abis.keys + "universal")
        }
        "diagnostics" -> verifyDiagnostics(options.path("--symbols", "app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"), options.path("--apk-dir", "app/build/outputs/apk/release"), tools())
        "prepare-release" -> prepareRelease(options.path("--apk-dir", "app/build/outputs/apk/release"), tools(), options.path("--output"), options.path("--private-dir"), options.get("--tag"), options.get("--retention-days", "180").toInt(), if (options.has("--abi")) setOf(options.get("--abi")) else abis.keys + "universal")
        "prepare-snapshot" -> prepareSnapshot(options.path("--apk-dir", "app/build/outputs/apk/release"), tools(), options.path("--output"), options.has("--test-signing"), if (options.has("--private-dir")) options.path("--private-dir") else null)
        "combine-release" -> combineRelease(options.path("--parts"), tools(), options.path("--output"), options.get("--tag"))
        "release-input-tests" -> releaseInputTests(tools())
        "diagnostics-key" -> diagnosticsKey(options.path("--output"))
        "encrypt-diagnostics" -> encryptDiagnostics(options.path("--private-dir"), options.path("--public-key"), options.path("--output"))
        "decrypt-diagnostics" -> decryptDiagnostics(options.path("--input"), options.path("--private-key"), options.path("--output"))
        "device" -> deviceTests(tools().sdk, options.get("--serial"), options.path("--output"), options.path("--app", "app/build/outputs/apk/debug/app-universal-debug.apk"), options.path("--test-apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        "native-benchmark-inputs" -> com.hippo.ehviewer.jni.benchmarkArchives(options)
        "native-benchmark" -> com.hippo.ehviewer.jni.nativeArchiveBenchmark(options)
        "native-differential" -> com.hippo.ehviewer.jni.nativeArchiveDifferential(options)
        "native-regression" -> com.hippo.ehviewer.jni.nativeArchiveRegression(options)
        else -> error("Unknown command: ${args[0]}")
    }
    println(json(result))
}
