package verification

fun main(args: Array<String>) {
    require(args.isNotEmpty()) { "Expected architecture, bytecode, native-sources, apks, diagnostics, prepare-release, release-input-tests, device or emulator" }
    val options = Options(args.drop(1))
    fun tools(): AndroidTools = AndroidTools(options.path("--sdk", System.getenv("ANDROID_HOME")))
    val result: Any = when (args[0]) {
        "architecture" -> verifyArchitecture()
        "bytecode" -> verifyBytecode()
        "native-sources" -> verifyNativeSources()
        "apks" -> verifyApks(root.resolve(options.positional.single()), tools(), options.get("--application-id", "moe.tarsin.ehviewer"), !options.has("--debug"), if (options.has("--version-code")) options.get("--version-code").toInt() else null)
        "diagnostics" -> verifyDiagnostics(options.path("--symbols", "app/build/outputs/native-debug-symbols/release/native-debug-symbols.zip"), options.path("--apk-dir", "app/build/outputs/apk/release"), tools())
        "prepare-release" -> prepareRelease(options.path("--apk-dir", "app/build/outputs/apk/release"), tools(), options.path("--output"), options.path("--private-dir"), options.get("--tag"), options.get("--retention-days", "180").toInt())
        "release-input-tests" -> releaseInputTests(tools())
        "device" -> deviceTests(tools().sdk, options.get("--serial"), options.path("--output"), options.path("--app", "app/build/outputs/apk/debug/app-universal-debug.apk"), options.path("--test-apk", "app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk"))
        "emulator" -> emulatorTests(options)
        else -> error("Unknown command: ${args[0]}")
    }
    println(json(result))
}
