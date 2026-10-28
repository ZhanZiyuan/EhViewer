package com.hippo.ehviewer.desktop

import java.lang.foreign.Arena
import java.lang.foreign.FunctionDescriptor
import java.lang.foreign.Linker
import java.lang.foreign.MemoryLayout.PathElement.groupElement
import java.lang.foreign.MemorySegment
import java.lang.foreign.SymbolLookup
import java.lang.foreign.ValueLayout.ADDRESS
import java.lang.foreign.ValueLayout.JAVA_BYTE
import java.lang.foreign.ValueLayout.JAVA_CHAR
import java.lang.foreign.ValueLayout.JAVA_INT
import java.lang.foreign.ValueLayout.JAVA_LONG
import java.lang.invoke.MethodHandle
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Base64
import java.util.Properties

private interface CapiSymbols : AutoCloseable {
    val loadedPath: Path
    fun find(name: String): MemorySegment
}

/** Own one LoadLibraryExW reference. Dependency resolution stays in the bundle/system directories. */
private class WindowsCapiLibrary(path: Path, private val arena: Arena) : CapiSymbols {
    private val linker = Linker.nativeLinker()
    private val kernel = SymbolLookup.libraryLookup("kernel32.dll", arena)
    private val stateLayout = Linker.Option.captureStateLayout()
    private val state = arena.allocate(stateLayout)
    private val errorOffset = stateLayout.byteOffset(groupElement("GetLastError"))
    private val capture = Linker.Option.captureCallState("GetLastError")
    private var module = MemorySegment.NULL
    override val loadedPath: Path

    private fun function(name: String, descriptor: FunctionDescriptor): MethodHandle = linker.downcallHandle(
        kernel.find(name).orElseThrow { UnsatisfiedLinkError("Missing Windows loader function: $name") },
        descriptor,
        capture,
    )

    private fun call(function: MethodHandle, vararg arguments: Any): Any? = function.invokeWithArguments(listOf(state) + arguments)
    private fun error(): Int = state.get(JAVA_INT, errorOffset)
    private val free = function("FreeLibrary", FunctionDescriptor.of(JAVA_INT, ADDRESS))

    init {
        try {
            val text = path.toString()
            val wide = arena.allocate((text.length + 1L) * 2, 2)
            text.forEachIndexed { index, character -> wide.setAtIndex(JAVA_CHAR, index.toLong(), character) }
            wide.setAtIndex(JAVA_CHAR, text.length.toLong(), '\u0000')
            val load = function("LoadLibraryExW", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS, JAVA_INT))
            // DLL_LOAD_DIR | DEFAULT_DIRS: use the DLL's siblings, application/system/user dirs, not PATH.
            module = call(load, wide, MemorySegment.NULL, 0x1100) as MemorySegment
            check(module.address() != 0L) { "Cannot load $path (Windows error ${error()})" }
            val buffer = arena.allocate(32768L * 2, 2)
            val moduleName = function("GetModuleFileNameW", FunctionDescriptor.of(JAVA_INT, ADDRESS, ADDRESS, JAVA_INT))
            val length = call(moduleName, module, buffer, 32768) as Int
            check(length in 1..32767) { "Cannot identify the loaded DLL (Windows error ${error()})" }
            loadedPath = Path.of(CharArray(length) { buffer.getAtIndex(JAVA_CHAR, it.toLong()) }.concatToString()).toRealPath()
            check(loadedPath == path) { "Loaded a different Rust C ABI library: $loadedPath" }
        } catch (failure: Throwable) {
            try {
                if (module.address() != 0L) call(free, module)
            } catch (cleanup: Throwable) {
                failure.addSuppressed(cleanup)
            }
            throw failure
        }
    }

    override fun find(name: String): MemorySegment {
        val address = call(
            function("GetProcAddress", FunctionDescriptor.of(ADDRESS, ADDRESS, ADDRESS)),
            module,
            arena.allocateFrom(name),
        ) as MemorySegment
        check(address.address() != 0L) { "Missing Rust export $name (Windows error ${error()})" }
        return address
    }

    override fun close() {
        try {
            check(call(free, module) as Int != 0) { "Cannot unload Rust C ABI (Windows error ${error()})" }
        } finally {
            module = MemorySegment.NULL
            arena.close()
        }
    }
}

private fun openCapi(path: Path): CapiSymbols {
    val arena = Arena.ofConfined()
    try {
        if (System.getProperty("os.name").startsWith("Windows")) return WindowsCapiLibrary(path, arena)
        val lookup = SymbolLookup.libraryLookup(path, arena)
        return object : CapiSymbols {
            override val loadedPath = path
            override fun find(name: String): MemorySegment = lookup.find(name).orElseThrow { UnsatisfiedLinkError("Missing Rust export: $name") }
            override fun close() = arena.close()
        }
    } catch (failure: Throwable) {
        arena.close()
        throw failure
    }
}

/** Exercise the generated ABI contract with real Rust-owned IDs and independently computed hashes. */
private fun probeCapi(symbols: CapiSymbols): Int {
    val linker = Linker.nativeLinker()
    fun function(name: String, descriptor: FunctionDescriptor) = linker.downcallHandle(symbols.find(name), descriptor)
    val abi = function("ehv_abi_version", FunctionDescriptor.of(JAVA_INT))
    val hash = function("ehv_hash_sha1", FunctionDescriptor.of(JAVA_INT, ADDRESS, JAVA_LONG, ADDRESS))
    val copy = function("ehv_buffer_copy", FunctionDescriptor.of(JAVA_INT, JAVA_LONG, ADDRESS, JAVA_LONG, ADDRESS))
    val free = function("ehv_buffer_free", FunctionDescriptor.of(JAVA_INT, JAVA_LONG))
    val active = function("ehv_active_buffers", FunctionDescriptor.of(JAVA_INT, ADDRESS))
    check(abi.invokeWithArguments() == 1) { "Unsupported Rust C ABI version" }
    val inputs = listOf("abc".toByteArray(), byteArrayOf(), byteArrayOf(0, -1, 1, -128), "Kotlin + Rust 跨平台 🔒".toByteArray(Charsets.UTF_8))
    Arena.ofConfined().use { arena ->
        val count = arena.allocate(JAVA_LONG)
        fun empty() {
            check(active.invokeWithArguments(count) == 0 && count.get(JAVA_LONG, 0) == 0L) { "Rust-owned results leaked" }
        }
        empty()
        repeat(2000) { index ->
            Arena.ofConfined().use { call ->
                val input = inputs[index % inputs.size]
                val borrowed = if (input.isEmpty()) MemorySegment.NULL else call.allocateFrom(JAVA_BYTE, *input)
                val handle = call.allocate(JAVA_LONG)
                check(hash.invokeWithArguments(borrowed, input.size.toLong(), handle) == 0)
                val id = handle.get(JAVA_LONG, 0)
                check(id != 0L)
                try {
                    val written = call.allocate(JAVA_LONG)
                    check(copy.invokeWithArguments(id, MemorySegment.NULL, 0L, written) == 3)
                    check(written.get(JAVA_LONG, 0) == 40L)
                    val output = call.allocate(40).fill(0x5a)
                    check(copy.invokeWithArguments(id, output, 39L, written) == 3)
                    check(output.toArray(JAVA_BYTE).all { it == 0x5a.toByte() })
                    check(copy.invokeWithArguments(id, output, 40L, written) == 0 && written.get(JAVA_LONG, 0) == 40L)
                    val expected = MessageDigest.getInstance("SHA-1").digest(input).joinToString("") { "%02x".format(it) }
                    check(output.toArray(JAVA_BYTE).toString(Charsets.US_ASCII) == expected) { "Rust/JVM hash mismatch" }
                } finally {
                    check(free.invokeWithArguments(id) == 0) { "Cannot release Rust-owned result" }
                }
                check(free.invokeWithArguments(id) == 2) { "Double-free was accepted" }
                val written = call.allocate(JAVA_LONG)
                check(copy.invokeWithArguments(id, MemorySegment.NULL, 0L, written) == 2) { "Stale result remained readable" }
            }
            empty()
        }
    }
    return 2000
}

internal data class OwnedCapiResult(val completed: Int, val loadedPath: Path)

/** Return only after the real ownership checks and library close have both succeeded. */
internal fun verifyOwnedCapi(library: Path): OwnedCapiResult = openCapi(library.toRealPath()).use { symbols ->
    OwnedCapiResult(probeCapi(symbols), symbols.loadedPath)
}

/** M1 standalone JVM caller, also used by the packaged Windows Compose probe. */
fun main(arguments: Array<String>) {
    require(arguments.size == 4 && arguments[0] == "--library-base64" && arguments[2] == "--output-base64") { "Expected --library-base64 <UTF-8 path> --output-base64 <UTF-8 path>" }
    require(ADDRESS.byteSize() == 8L) { "This C ABI probe requires a 64-bit JVM" }
    fun path(argument: String) = Path.of(Base64.getDecoder().decode(argument).decodeToString(throwOnInvalidSequence = true))
    val library = path(arguments[1]).toRealPath()
    val output = path(arguments[3]).toAbsolutePath().normalize()
    require(!Files.exists(output)) { "Use a fresh FFI report directory" }
    val (completed, loaded) = verifyOwnedCapi(library)
    val proof = Properties().apply {
        setProperty("result", "PASS")
        setProperty("architecture", System.getProperty("os.arch"))
        setProperty("os", System.getProperty("os.name"))
        setProperty("java_version", System.getProperty("java.version"))
        setProperty("java_home", System.getProperty("java.home"))
        setProperty("path_transport", "utf8-base64")
        setProperty("native_encoding", System.getProperty("native.encoding", "unknown"))
        setProperty("library", loaded.toString())
        setProperty("abi_version", "1")
        setProperty("rust_hash_results", completed.toString())
        setProperty("rust_active_results", "0")
        setProperty("undersized_copy", "PASS")
        setProperty("stale_handle", "PASS")
        setProperty("double_free", "PASS")
        setProperty("library_closed", "PASS")
        setProperty("linker", Linker.nativeLinker().javaClass.name)
    }
    Files.createDirectories(output)
    Files.newOutputStream(output.resolve("ffi.properties")).use { proof.store(it, "Measured JVM/Rust owned-result evidence") }
}
