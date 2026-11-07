package verification

import groovy.json.JsonSlurper
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.inputStream
import kotlin.io.path.readText
import kotlin.io.path.writeText

/** Run real Rust crypto, filesystem and iOS Keychain tests in the same owned Simulator. */
fun iosSessionBackendTests(device: String, output: Path): Map<String, Any?> {
    val messages = try {
        command(
            "cargo", "test", "--manifest-path", root.resolve("native/rust/Cargo.toml"), "--locked",
            "-p", "ehviewer_session", "--lib", "--target", "aarch64-apple-ios-sim", "--target-dir", root.resolve("native/rust/target"), "--no-run", "--message-format=json",
            environment = mapOf("EHVIEWER_IOS_SESSION_TESTS" to "1"), timeout = Duration.ofMinutes(10),
        ).also { output.resolve("session-build.jsonl").writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve("session-build.jsonl").writeText(failure.output)
        throw failure
    }
    val binary = messages.lineSequence().filter { it.startsWith('{') }.map { JsonSlurper().parseText(it).obj() }
        .filter { it["reason"] == "compiler-artifact" && it["executable"] is String && it["target"].obj()["name"] == "ehviewer_session" }
        .map { Path.of(it.str("executable")).toRealPath() }.single()
    require(binary.startsWith(root.resolve("native/rust/target").toRealPath()))
    binary.inputStream().use { verifyDomainSimulatorMachO(it.readNBytes(1024 * 1024)) }
    val entitlements = output.resolve("session-entitlements.plist")
    command("xcrun", "segedit", binary, "-extract", "__TEXT", "__entitlements", entitlements)
    require(entitlements.readText().trimEnd('\u0000').trim() == root.resolve("app/ios/Simulator.entitlements").readText().trim())
    val tests = try {
        command("xcrun", "simctl", "spawn", device, binary, "--test-threads=1", "--color", "never", timeout = Duration.ofMinutes(5))
            .also { output.resolve("session-tests.log").writeText(it) }
    } catch (failure: CommandFailure) {
        output.resolve("session-tests.log").writeText(failure.output)
        throw failure
    }
    require(tests.contains("test result: ok. 3 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out;") && tests.contains("real_platform_credential_store_restores_an_owned_encrypted_session ... ok"))
    Files.copy(binary, output.resolve("session-tests.kexe"))
    return mapOf("result" to "PASS", "passed" to 3, "ignored" to 0, "execution" to "REAL_IOS_KEYCHAIN_ON_OWNED_SIMULATOR", "test_executable_sha256" to sha256(binary), "simulator_identity_sha256" to sha256(entitlements))
}
