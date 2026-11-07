package verification

import java.io.PrintStream
import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.io.path.writeText

/** Quote CreateProcess arguments using the Windows CRT contract, without a shell. */
internal fun windowsProcessCommandLine(arguments: List<String>): String {
    require(arguments.isNotEmpty())
    return arguments.joinToString(" ") { argument ->
        require(argument.none { it == '\u0000' || it == '\r' || it == '\n' })
        buildString {
            append('"')
            var slashes = 0
            for (character in argument) {
                if (character == '\\') {
                    slashes++
                } else {
                    repeat(if (character == '"') slashes * 2 + 1 else slashes) { append('\\') }
                    slashes = 0
                    append(character)
                }
            }
            repeat(slashes * 2) { append('\\') }
            append('"')
        }
    }.also { require(it.length < 32767) }
}

private class WindowsDesktopApi(private val arena: Arena) {
    private val linker = Linker.nativeLinker()
    private val user = SymbolLookup.libraryLookup("user32.dll", arena)
    private val kernel = SymbolLookup.libraryLookup("kernel32.dll", arena)
    fun function(library: String, name: String, result: MemoryLayout, vararg parameters: MemoryLayout) = linker.downcallHandle(
        (if (library == "user") user else kernel).find(name).orElseThrow(),
        FunctionDescriptor.of(result, *parameters),
    )
    fun wide(value: String): MemorySegment {
        val bytes = (value + '\u0000').toByteArray(Charsets.UTF_16LE)
        return arena.allocate(bytes.size.toLong(), 2).also { it.copyFrom(MemorySegment.ofArray(bytes)) }
    }
    fun objectName(handle: MemorySegment): String {
        val buffer = arena.allocate(1024, 2)
        val size = arena.allocate(JAVA_INT)
        check(function("user", "GetUserObjectInformationW", JAVA_INT, ADDRESS, JAVA_INT, ADDRESS, JAVA_INT, ADDRESS).invokeWithArguments(handle, 2, buffer, 1024, size) as Int != 0)
        val bytes = size.get(JAVA_INT, 0)
        check(bytes in 2..1024 && bytes % 2 == 0)
        return String(buffer.asSlice(0, (bytes - 2).toLong()).toArray(java.lang.foreign.ValueLayout.JAVA_BYTE), Charsets.UTF_16LE)
    }
    fun error(): Int = function("kernel", "GetLastError", JAVA_INT).invokeWithArguments() as Int
}

/** Ephemeral hosted-CI desktop. No privacy choices, policies, credentials or user desktop changes. */
object OwnedWindowsDesktop {
    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getProperty("os.name").startsWith("Windows"))
        require(args.size == 3)
        val output = Path.of(args[2]).toAbsolutePath()
        val name = "EhViewer-CI-${ProcessHandle.current().pid()}-${UUID.randomUUID()}"
        val result = linkedMapOf<String, Any?>("desktop" to name, "result" to "FAIL", "original_desktop_restored" to false)
        Arena.ofConfined().use { arena ->
            val api = WindowsDesktopApi(arena)
            val station = api.function("user", "GetProcessWindowStation", ADDRESS).invokeWithArguments() as MemorySegment
            check(api.objectName(station).equals("WinSta0", ignoreCase = true)) { "An interactive hosted window station is required" }
            val original = api.function("user", "OpenInputDesktop", ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT).invokeWithArguments(0, 0, 0x0101) as MemorySegment
            check(original.address() != 0L) { "Cannot retain original input desktop: ${api.error()}" }
            val closeDesktop = api.function("user", "CloseDesktop", JAVA_INT, ADDRESS)
            val switch = api.function("user", "SwitchDesktop", JAVA_INT, ADDRESS)
            var desktop = MemorySegment.NULL
            var child: ProcessHandle? = null
            try {
                result["original_desktop"] = api.objectName(original)
                // Same window-station security descriptor; no hook, journal or cross-account flags.
                desktop = api.function("user", "CreateDesktopW", ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS)
                    .invokeWithArguments(api.wide(name), MemorySegment.NULL, MemorySegment.NULL, 0, 0x01c7, MemorySegment.NULL) as MemorySegment
                check(desktop.address() != 0L) { "Cannot create owned desktop: ${api.error()}" }
                check(api.objectName(desktop) == name)
                check(switch.invokeWithArguments(desktop) as Int != 0) { "Cannot activate owned input desktop: ${api.error()}" }
                val java = Path.of(System.getProperty("java.home"), "bin/java.exe").toRealPath()
                val arguments = listOf(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", System.getProperty("java.class.path"), OwnedWindowsDesktopChild::class.java.name) + args + name
                // Windows x64 and ARM64 share this 64-bit STARTUPINFOW / PROCESS_INFORMATION ABI.
                check(ADDRESS.byteSize() == 8L)
                val startup = arena.allocate(104, 8)
                startup.set(JAVA_INT, 0, 104)
                startup.set(ADDRESS, 16, api.wide("WinSta0\\$name"))
                val process = arena.allocate(24, 8)
                val created = api.function("kernel", "CreateProcessW", JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
                    .invokeWithArguments(api.wide(java.toString()), api.wide(windowsProcessCommandLine(arguments)), MemorySegment.NULL, MemorySegment.NULL, 0, 0x08000000, MemorySegment.NULL, api.wide(root.toRealPath().toString()), startup, process) as Int
                check(created != 0) { "Cannot start owned desktop verifier: ${api.error()}" }
                val processHandle = process.get(ADDRESS, 0)
                val threadHandle = process.get(ADDRESS, 8)
                val closeHandle = api.function("kernel", "CloseHandle", JAVA_INT, ADDRESS)
                try {
                    val pid = process.get(JAVA_INT, 16).toLong() and 0xffffffffL
                    child = ProcessHandle.of(pid).orElseThrow()
                    result["verifier_pid"] = pid
                    val waited = api.function("kernel", "WaitForSingleObject", JAVA_INT, ADDRESS, JAVA_INT)
                        .invokeWithArguments(processHandle, Duration.ofMinutes(9).toMillis().toInt()) as Int
                    check(waited == 0) { "Owned desktop verifier did not finish: wait=$waited error=${api.error()}" }
                    val exit = arena.allocate(JAVA_INT)
                    check(api.function("kernel", "GetExitCodeProcess", JAVA_INT, ADDRESS, ADDRESS).invokeWithArguments(processHandle, exit) as Int != 0)
                    result["verifier_exit_code"] = exit.get(JAVA_INT, 0)
                    check(exit.get(JAVA_INT, 0) == 0) { "Actual owned-desktop product checks failed; raw screenshots and child log retained" }
                    result["result"] = "PASS"
                } finally {
                    closeHandle.invokeWithArguments(threadHandle)
                    closeHandle.invokeWithArguments(processHandle)
                }
            } finally {
                try {
                    child?.let { owner ->
                        val owned = owner.descendants().use { it.toList() }.asReversed() + owner
                        owned.filter { it.isAlive }.forEach { it.destroy() }
                        owned.filter { it.isAlive }.forEach { process ->
                            runCatching { process.onExit().get(5, TimeUnit.SECONDS) }.onFailure { if (process.isAlive) process.destroyForcibly() }
                        }
                    }
                } finally {
                    val restored = switch.invokeWithArguments(original) as Int != 0
                    result["original_desktop_restored"] = restored
                    if (!restored) result["result"] = "FAIL"
                    output.resolveSibling("${output.fileName}-desktop.json").writeText(json(result))
                    if (desktop.address() != 0L) closeDesktop.invokeWithArguments(desktop)
                    closeDesktop.invokeWithArguments(original)
                    check(restored) { "Original input desktop could not be restored" }
                }
            }
        }
    }
}

/** Starts in the named desktop before AWT/Robot initializes; executes every original UI assertion. */
object OwnedWindowsDesktopChild {
    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getProperty("os.name").startsWith("Windows") && args.size == 4)
        val output = Path.of(args[2]).toAbsolutePath()
        PrintStream(Files.newOutputStream(output.resolveSibling("${output.fileName}-desktop-child.log")), true, Charsets.UTF_8).use { log ->
            System.setOut(log)
            System.setErr(log)
            try {
                Arena.ofConfined().use { arena ->
                    val api = WindowsDesktopApi(arena)
                    val thread = api.function("kernel", "GetCurrentThreadId", JAVA_INT).invokeWithArguments() as Int
                    val desktop = api.function("user", "GetThreadDesktop", ADDRESS, JAVA_INT).invokeWithArguments(thread) as MemorySegment
                    check(args[3].startsWith("EhViewer-CI-") && api.objectName(desktop) == args[3]) { "Child is not attached to the owned desktop" }
                    val input = api.function("user", "OpenInputDesktop", ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT).invokeWithArguments(0, 0, 1) as MemorySegment
                    check(input.address() != 0L)
                    try {
                        check(api.objectName(input) == args[3]) { "Owned desktop does not receive actual OS input" }
                    } finally {
                        api.function("user", "CloseDesktop", JAVA_INT, ADDRESS).invokeWithArguments(input)
                    }
                }
                System.setProperty("verification.ownedDesktop", args[3])
                println(json(desktopProductUi(Path.of(args[0]), Path.of(args[1]), output)))
            } catch (failure: Throwable) {
                failure.printStackTrace(log)
                throw failure
            }
        }
    }
}
