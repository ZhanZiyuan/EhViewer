package verification

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_INT
import java.nio.file.Path

/** CI-only window lease. Runs in the inspected packaged JDK, not the runner's default JVM. */
object OwnedWindowsWindow {
    @JvmStatic
    fun main(args: Array<String>) {
        require(System.getenv("GITHUB_ACTIONS") == "true" && System.getProperty("os.name").startsWith("Windows"))
        require(args.size == 3 && args[2] in setOf("promote", "restore"))
        val pid = args[0].toLong().also { require(it in 1..Int.MAX_VALUE.toLong()) }
        val launcher = Path.of(args[1]).toRealPath()
        fun owned() = ProcessHandle.of(pid).orElse(null)?.let { process ->
            process.isAlive && process.info().command().orElse(null)?.let { Path.of(it).toRealPath() == launcher } == true
        } == true
        require(owned()) { "Window owner no longer matches the inspected launcher" }
        Arena.ofConfined().use { arena ->
            val library = SymbolLookup.libraryLookup("user32.dll", arena)
            val linker = Linker.nativeLinker()
            fun function(name: String, result: MemoryLayout, vararg parameters: MemoryLayout) = linker.downcallHandle(library.find(name).orElseThrow(), FunctionDescriptor.of(result, *parameters))
            val find = function("FindWindowExW", ADDRESS, ADDRESS, ADDRESS, ADDRESS, ADDRESS)
            val windowPid = function("GetWindowThreadProcessId", JAVA_INT, ADDRESS, ADDRESS)
            val visible = function("IsWindowVisible", JAVA_INT, ADDRESS)
            val position = function("SetWindowPos", JAVA_INT, ADDRESS, ADDRESS, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT, JAVA_INT)
            val foreground = function("SetForegroundWindow", JAVA_INT, ADDRESS)
            val show = function("ShowWindow", JAVA_INT, ADDRESS, JAVA_INT)
            val iconic = function("IsIconic", JAVA_INT, ADDRESS)
            val pidBuffer = arena.allocate(JAVA_INT)
            // Snapshot handles before changing Z order, otherwise FindWindowEx can revisit a window.
            val windows = mutableListOf<MemorySegment>()
            var previous = MemorySegment.NULL
            val visited = mutableSetOf<Long>()
            while (true) {
                val window = find.invokeWithArguments(MemorySegment.NULL, previous, MemorySegment.NULL, MemorySegment.NULL) as MemorySegment
                if (window.address() == 0L) break
                check(visited.add(window.address()) && visited.size <= 4096) { "Window enumeration did not terminate" }
                windowPid.invokeWithArguments(window, pidBuffer)
                if (pidBuffer.get(JAVA_INT, 0).toLong() == pid && visible.invokeWithArguments(window) as Int != 0) windows.add(window)
                previous = window
            }
            for (window in windows) {
                check(owned()) { "Window owner changed during activation" }
                windowPid.invokeWithArguments(window, pidBuffer)
                check(pidBuffer.get(JAVA_INT, 0).toLong() == pid) { "Window handle changed owners" }
                val promote = args[2] == "promote"
                val minimized = iconic.invokeWithArguments(window) as Int != 0
                if (promote && minimized) show.invokeWithArguments(window, 9) // SW_RESTORE, owned window only
                // Keep geometry intact. Never dismiss or change the runner's privacy/security UI.
                val flags = if (promote) 0x0043 else 0x0013 // NOMOVE | NOSIZE | (SHOWWINDOW or NOACTIVATE)
                val changed = position.invokeWithArguments(window, MemorySegment.ofAddress(if (promote) -1L else -2L), 0, 0, 0, 0, flags) as Int
                check(changed != 0) { "SetWindowPos rejected the owned window" }
                val activated = if (promote) foreground.invokeWithArguments(window) as Int != 0 else false
                println("pid=$pid hwnd=${window.address()} mode=${args[2]} minimized_before=$minimized foreground=$activated")
            }
            println("owned_windows=${windows.size}")
        }
    }
}

internal fun ownedWindowsWindowCommand(java: Path, classpath: String, processId: Long, launcher: Path, mode: String): List<String> {
    require(processId in 1..Int.MAX_VALUE.toLong() && mode in setOf("promote", "restore"))
    return listOf(java.toString(), "--enable-native-access=ALL-UNNAMED", "-cp", classpath, OwnedWindowsWindow::class.java.name, processId.toString(), launcher.toString(), mode)
}
