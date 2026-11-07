package com.ehviewer

import java.io.File
import java.time.Duration
import java.util.concurrent.TimeUnit

/** Retain every attempt; an Apple linker crash may retry once inside the original command budget. */
class NativeBuildCommand(private val logs: File, private val timeout: Duration = Duration.ofMinutes(15)) {
    data class Result(val attempts: Int)

    private data class Attempt(val number: Int, val log: String, val exit: Int?, val timedOut: Boolean, val interrupted: Boolean, val linkerCrash: Boolean)

    fun run(name: String, directory: File, arguments: List<String>, retryAppleLinkerCrash: Boolean = false): Result {
        require(Regex("[A-Za-z0-9_-]{1,64}").matches(name) && arguments.isNotEmpty())
        require(!timeout.isNegative && !timeout.isZero)
        check(logs.isDirectory || logs.mkdirs())
        val deadline = System.nanoTime() + timeout.toNanos()
        val attempts = mutableListOf<Attempt>()
        fun record(attempt: Attempt) {
            attempts += attempt
            val values = attempts.joinToString(",\n") {
                """{"attempt":${it.number},"log":"${it.log}","exit_code":${it.exit},"timed_out":${it.timedOut},"interrupted":${it.interrupted},"apple_linker_crash":${it.linkerCrash}}"""
            }
            logs.resolve("$name.attempts.json").writeText("""{"schema":1,"timeout_millis":${timeout.toMillis()},"attempts":[$values]}""" + "\n")
        }
        for (index in 0..1) {
            val remaining = deadline - System.nanoTime()
            check(remaining > 0) { "Desktop native command timed out: $name; see retained attempts" }
            val log = logs.resolve(if (index == 0) "$name.log" else "$name.retry-1.log")
            val process = ProcessBuilder(arguments).directory(directory).redirectErrorStream(true).redirectOutput(log).start()
            val finished = try {
                process.waitFor((deadline - System.nanoTime()).coerceAtLeast(0), TimeUnit.NANOSECONDS)
            } catch (interrupted: InterruptedException) {
                terminate(process)
                record(Attempt(index + 1, log.name, null, false, true, false))
                Thread.currentThread().interrupt()
                throw interrupted
            }
            if (!finished) {
                terminate(process)
                record(Attempt(index + 1, log.name, null, true, false, false))
                error("Desktop native command timed out: $name; see retained attempts")
            }
            val exit = process.exitValue()
            val output = log.readText()
            val linkerCrash = output.contains("clang: error: unable to execute command: Segmentation fault: 11") &&
                output.contains("clang: error: linker command failed due to signal") && output.contains("Apple clang version")
            record(Attempt(index + 1, log.name, exit, false, false, linkerCrash))
            if (exit == 0) return Result(attempts.size)
            check(index == 0 && retryAppleLinkerCrash && linkerCrash) { "$name failed: $output" }
            System.err.println("Apple linker crashed during $name; retaining ${log.name} and retrying once within the original deadline.")
        }
        error("Unreachable native build state")
    }

    private fun terminate(process: Process) {
        process.descendants().use { children -> children.forEach { it.destroyForcibly() } }
        process.destroyForcibly()
        check(process.waitFor(5, TimeUnit.SECONDS)) { "Native build process did not terminate" }
    }
}
