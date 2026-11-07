package verification

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

internal data class OfflineProcessResult(val exitCode: Int, val stdout: String = "", val stderr: String = "")

/** A per-test loopback model; the real Bash script still sees separate native processes and exits. */
internal class OfflineProcessServer(private val handler: (String, Array<String>, String) -> OfflineProcessResult) : AutoCloseable {
    private val server = ServerSocket(0, 0, InetAddress.getByName("127.0.0.1"))
    private val failure = AtomicReference<Throwable?>()

    @Volatile private var active: Socket? = null
    val endpoint = "127.0.0.1:${server.localPort}"
    val token = UUID.randomUUID().toString()

    private fun DataInputStream.field(): String {
        val length = readInt()
        require(length in 0..1024 * 1024) { "Offline field exceeds the limit" }
        val bytes = readNBytes(length)
        check(bytes.size == length) { "Truncated offline field" }
        return bytes.decodeToString(throwOnInvalidSequence = true)
    }

    private fun DataOutputStream.field(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        require(bytes.size <= 1024 * 1024) { "Offline response exceeds the limit" }
        writeInt(bytes.size)
        write(bytes)
    }

    private val worker = Thread({
        try {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (closed: SocketException) {
                    if (server.isClosed) break else throw closed
                }
                active = client
                client.use {
                    it.soTimeout = 5000
                    it.tcpNoDelay = true
                    val result = try {
                        val input = DataInputStream(it.getInputStream())
                        require(input.field() == token) { "Invalid offline test token" }
                        val mode = input.field()
                        val count = input.readInt()
                        require(count in 0..128) { "Invalid offline argument count" }
                        val arguments = Array(count) { input.field() }
                        handler(mode, arguments, input.field())
                    } catch (problem: Exception) {
                        OfflineProcessResult(70, stderr = "Offline model failed: $problem\n")
                    }
                    if (!server.isClosed) {
                        DataOutputStream(it.getOutputStream()).use { response ->
                            response.writeInt(result.exitCode)
                            response.field(result.stdout)
                            response.field(result.stderr)
                        }
                    }
                }
                active = null
            }
        } catch (problem: Throwable) {
            if (!server.isClosed) failure.set(problem)
        }
    }, "ehviewer-offline-release-model").apply {
        isDaemon = true
        start()
    }

    override fun close() {
        server.close()
        active?.close()
        worker.join(5000)
        check(!worker.isAlive) { "Offline model worker did not stop" }
        failure.get()?.let { throw AssertionError("Offline model worker failed", it) }
    }
}
