package com.ehviewer

import java.io.File
import kotlin.system.exitProcess

/** Child-process failure injection only; it never produces a library or substitutes a compiler. */
object NativeBuildCommandProbe {
    @JvmStatic
    fun main(arguments: Array<String>) {
        val mode = arguments[0]
        val counter = File(arguments[1])
        val attempt = if (counter.exists()) counter.readText().toInt() + 1 else 1
        counter.writeText(attempt.toString())
        println("owned_pid=${ProcessHandle.current().pid()}")
        System.out.flush()
        if (mode == "sleep") Thread.sleep(30_000)
        if (mode == "budget") Thread.sleep(if (attempt == 1) 1_100 else 2_100)
        if (mode == "ordinary") {
            System.err.println("error: owned compilation failure")
            exitProcess(1)
        }
        if (mode == "always" || mode == "foreign" || mode in setOf("once", "budget") && attempt == 1) {
            System.err.println("clang: error: unable to execute command: Segmentation fault: 11")
            System.err.println("clang: error: linker command failed due to signal (use -v to see invocation)")
            System.err.println(if (mode == "foreign") "Ubuntu clang version 21" else "Apple clang version 21.0.0")
            exitProcess(1)
        }
        println("owned command completed")
    }
}
