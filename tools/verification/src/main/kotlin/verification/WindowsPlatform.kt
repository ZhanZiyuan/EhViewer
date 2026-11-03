package verification

/** One reviewed descriptor keeps native CPU, ABI, vendor runtime and package names consistent. */
enum class WindowsPlatform(
    val target: String,
    val machine: Int,
    val nativeArchitecture: String,
    val assetArchitecture: String,
    val jvmArchitectures: Set<String>,
    val runnerArchitecture: String,
    val processorArchitecture: String,
    val jdkSha256: String,
) {
    ARM64("aarch64-pc-windows-msvc", 0xaa64, "arm64", "arm64", setOf("aarch64"), "ARM64", "ARM64", "f227cc459fbd65a42c1b28123d68e207eb500899c1c78238f21a038cd33688a3"),
    X64("x86_64-pc-windows-msvc", 0x8664, "x64", "x86_64", setOf("amd64", "x86_64"), "X64", "AMD64", "5da7525c8268a10a66bd76a39f4e9af24d4ebc974d42800a609852c498a68460"),
    ;

    val jdkUrl: String get() = "https://aka.ms/download-jdk/microsoft-jdk-25.0.5.0.2-windows-${if (this == ARM64) "aarch64" else "x64"}.zip"
    val vcpkgTriplet: String get() = "$nativeArchitecture-windows"
    val skikoSha256: String get() = if (this == ARM64) "8bddb4a42facbf3117447f9acda90b2e7f02aa38f259c1c92e91c2f685650549" else "5061ffbbfbad9fc8c6c8e0c1741b674c863293d3d071b061db9e6e0fc3ef1d38"
    val stageScope: String get() = if (this == ARM64) "M2_WINDOWS_ARM64_RUNTIME_STAGE" else "M6_WINDOWS_X64_RUNTIME_STAGE"
    val launchScope: String get() = if (this == ARM64) "M2_WINDOWS_ARM64_ZIP_LAUNCH" else "M6_WINDOWS_X64_ZIP_LAUNCH"
    val releaseTarget: String get() = if (this == ARM64) "windows-arm64" else "windows-x64"

    fun requireNativeHost() {
        require(System.getProperty("os.name").startsWith("Windows") && System.getProperty("os.arch") in jvmArchitectures) { "Use a native matching-CPU Windows JDK" }
        require(System.getenv("GITHUB_ACTIONS") != "true" || System.getenv("RUNNER_ARCH") == runnerArchitecture) { "Windows runner CPU differs from this target" }
        val processor = System.getenv("PROCESSOR_ARCHITEW6432") ?: System.getenv("PROCESSOR_ARCHITECTURE")
        require(processor.equals(processorArchitecture, ignoreCase = true)) { "Windows execution must use the native system CPU" }
    }

    companion object {
        fun fromTarget(target: String): WindowsPlatform = entries.singleOrNull { it.target == target } ?: error("Unsupported Windows target: $target")
    }
}
