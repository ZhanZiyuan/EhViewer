package verification

import java.nio.file.Files
import java.nio.file.Path

/** A real child process for the command timeout/diagnostic regression, never product code. */
object CommandTimeoutProbe {
    @JvmStatic fun main(arguments: Array<String>) {
        if (arguments.single() == "environment") {
            println(System.getenv("EHVIEWER_COMMAND_TEST") ?: "absent")
            return
        }
        if (arguments.single() == "leaf") {
            println("timeout-leaf-ready")
        } else {
            val java = Path.of(System.getProperty("java.home"), "bin", "java")
            val child = ProcessBuilder(java.toString(), "-cp", System.getProperty("java.class.path"), CommandTimeoutProbe::class.java.name, "leaf")
                .inheritIO().start()
            Files.writeString(Path.of(arguments.single()), child.pid().toString())
            println("timeout-parent-ready")
        }
        Thread.sleep(60_000)
    }
}
