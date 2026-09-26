package tech.anl.library.companion

import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.Collections
import kotlin.concurrent.thread

/**
 * A stand-in for a companion's control server on a local port: length-prefixed JSON, one request
 * per connection, each connection on its own thread (like the real thing), so status polls run
 * while a blocking call is in flight.
 */
class FakeCompanion(private val handler: FakeCompanion.(Map<String, Any?>) -> Map<String, Any?>?) : AutoCloseable {
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    val port: Int get() = server.localPort

    /** False simulates a dead companion process: connections are closed without a reply. */
    @Volatile var alive = true

    val requests: MutableList<Map<String, Any?>> = Collections.synchronizedList(mutableListOf())

    fun commands(): List<String> = synchronized(requests) { requests.map { it["cmd"].toString() } }
    fun commandsExceptPolls(): List<String> =
        commands().filter { it != "getStatus" && it != "getProgressMessage" && it != "getLastError" && it != "getPort" }

    init {
        thread(isDaemon = true, name = "fake-companion-accept") {
            while (!server.isClosed) {
                val socket = try { server.accept() } catch (e: Exception) { break }
                thread(isDaemon = true, name = "fake-companion-conn") { handle(socket) }
            }
        }
    }

    private fun handle(socket: Socket) {
        socket.use { s ->
            if (!alive) return
            val input = DataInputStream(s.getInputStream())
            val len = input.readInt()
            val buf = ByteArray(len).also { input.readFully(it) }
            val req = CompanionJson.decode(String(buf, Charsets.UTF_8))
            requests.add(req)
            val resp = handler(req) ?: return // null: close without answering (process "died")
            val bytes = CompanionJson.encode(resp).toByteArray(Charsets.UTF_8)
            DataOutputStream(s.getOutputStream()).apply { writeInt(bytes.size); write(bytes); flush() }
        }
    }

    override fun close() { server.close() }

    companion object {
        fun ok(result: Any? = null): Map<String, Any?> = if (result == null) mapOf("ok" to true) else mapOf("ok" to true, "result" to result)
        fun err(msg: String): Map<String, Any?> = mapOf("ok" to false, "error" to msg)
    }
}

/** [CompanionConnection] over the real transport to a [FakeCompanion]. */
class FakeConnection(private val fake: FakeCompanion) : CompanionConnection {
    @Volatile var wakes = 0
    private val transport = CompanionTransport("127.0.0.1", fake.port)

    override suspend fun request(cmd: String, fields: Map<String, Any?>, timeoutMs: Long): CompanionResponse =
        transport.send(CompanionJson.request(cmd, fields), timeoutMs)

    override suspend fun wake() {
        wakes++
        fake.alive = true
    }

    override suspend fun isReachable(): Boolean = fake.alive
}
