package tech.anl.library.companion

import com.squareup.moshi.JsonAdapter
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/*
 * Wire protocol shared by both companion apps (UserLAnd VM, UserLAnd QEMU):
 *
 *   request : int32 big-endian length N, then N bytes of a UTF-8 JSON object {"cmd": ..., "fsId": ..., ...}
 *   response: int32 big-endian length M, then M bytes of a UTF-8 JSON object {"ok": bool, "result"?: ..., "error"?: ...}
 *
 * One request per connection; requests on separate connections run concurrently in the companion,
 * which is how status is polled while a blocking setup/start is in flight. The companion crashes on
 * malformed JSON or a bad length, so requests are only ever produced by [CompanionJson].
 */

/** Base class for everything that can go wrong talking to a companion. */
open class CompanionException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Nothing is listening on the control port (companion not running, or not installed). */
class CompanionUnreachableException(message: String, cause: Throwable? = null) : CompanionException(message, cause)

/**
 * The companion closed the connection without answering. UserLAnd QEMU's stop (and its start
 * failure paths) kill the companion process before the reply is written, so this is expected there.
 */
class CompanionGoneException(message: String = "The companion app closed the connection", cause: Throwable? = null) :
    CompanionException(message, cause)

/** The companion answered {"ok": false, "error": ...}. */
class CompanionErrorException(val error: String) : CompanionException(error)

/** The companion app isn't installed at all. */
class CompanionNotInstalledException(val app: CompanionApp) :
    CompanionException("${app.packageName} is not installed")

/** Status strings returned by getStatus. */
enum class CompanionStatus(val wire: String) {
    IDLE("idle"),
    READY("ready"),
    CORRUPT("corrupt"),
    SETUP("setup"),
    STARTING("starting"),
    RUNNING("running"),
    ERROR("error"),
    /** VM only: a guest shell command timed out and the VM was force-stopped; retry start. */
    WEDGED("wedged"),
    UNKNOWN("");

    val isBusy: Boolean get() = this == SETUP || this == STARTING

    companion object {
        fun of(s: String?): CompanionStatus = values().firstOrNull { it.wire == s?.trim()?.lowercase() } ?: UNKNOWN
    }
}

/** A decoded response envelope. */
data class CompanionResponse(val ok: Boolean, val result: Any?, val error: String?) {
    fun orThrow(): CompanionResponse {
        if (!ok) throw CompanionErrorException(error?.ifBlank { null } ?: "unknown error")
        return this
    }

    val resultString: String get() = when (val r = result) {
        null -> ""
        is String -> r
        else -> r.toString()
    }

    /** JSON numbers decode as Double; normalise to Int (or [default] when absent/not numeric). */
    fun resultInt(default: Int = -1): Int = when (val r = result) {
        is Number -> r.toInt()
        is String -> r.trim().toIntOrNull() ?: default
        else -> default
    }

    companion object {
        fun fromMap(map: Map<String, Any?>): CompanionResponse {
            val ok = when (val v = map["ok"]) {
                is Boolean -> v
                is String -> v.equals("true", ignoreCase = true)
                else -> false
            }
            return CompanionResponse(ok, map["result"], map["error"]?.toString())
        }
    }
}

/** JSON encoding/decoding that works in plain JVM unit tests (org.json is stubbed there). */
object CompanionJson {
    private val moshi: Moshi = Moshi.Builder().build()
    private val mapAdapter: JsonAdapter<Map<String, Any?>> = moshi.adapter(
        Types.newParameterizedType(Map::class.java, String::class.java, Any::class.java)
    )

    fun encode(map: Map<String, Any?>): String = mapAdapter.toJson(map)

    fun decode(json: String): Map<String, Any?> =
        mapAdapter.fromJson(json) ?: throw CompanionException("empty JSON response")

    fun request(cmd: String, fields: Map<String, Any?>): Map<String, Any?> {
        val out = LinkedHashMap<String, Any?>()
        out["cmd"] = cmd
        fields.forEach { (k, v) -> if (k != "cmd") out[k] = normalise(v) }
        return out
    }

    // Moshi's reflective object adapter only knows the JSON primitives; flatten the rest.
    private fun normalise(v: Any?): Any? = when (v) {
        null, is String, is Boolean, is Long, is Double -> v
        is Int -> v.toLong()
        is Short -> v.toLong()
        is Byte -> v.toLong()
        is Float -> v.toDouble()
        is Number -> v.toLong()
        is Enum<*> -> v.name
        else -> v.toString()
    }
}

/**
 * The framing layer: one blocking request/response per TCP connection. Context-free so it can be
 * unit tested against a local ServerSocket.
 */
class CompanionTransport(
    private val host: String,
    private val port: Int,
    private val connectTimeoutMs: Int = 3_000
) {
    companion object {
        const val MAX_RESPONSE_BYTES = 16 shl 20
    }

    /**
     * Sends one request. [readTimeoutMs] <= 0 waits forever (for the blocking setup/start/repair
     * calls). Cancelling the calling coroutine closes the socket, which unblocks the read.
     */
    suspend fun send(request: Map<String, Any?>, readTimeoutMs: Long): CompanionResponse {
        val payload = CompanionJson.encode(request).toByteArray(Charsets.UTF_8)
        val socket = Socket()
        return coroutineScope {
            val closer = launch {
                try { awaitCancellation() } finally { runCatching { socket.close() } }
            }
            try {
                withContext(Dispatchers.IO) { exchange(socket, payload, readTimeoutMs) }
            } finally {
                closer.cancel()
                runCatching { socket.close() }
            }
        }
    }

    private fun exchange(socket: Socket, payload: ByteArray, readTimeoutMs: Long): CompanionResponse {
        try {
            socket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        } catch (e: ConnectException) {
            throw CompanionUnreachableException("Nothing is listening on $host:$port", e)
        } catch (e: SocketTimeoutException) {
            throw CompanionUnreachableException("Timed out connecting to $host:$port", e)
        }
        socket.tcpNoDelay = true
        socket.soTimeout = if (readTimeoutMs <= 0) 0 else readTimeoutMs.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val out = DataOutputStream(socket.getOutputStream())
        out.writeInt(payload.size)
        out.write(payload)
        out.flush()
        val input = DataInputStream(socket.getInputStream())
        val length = try {
            input.readInt()
        } catch (e: EOFException) {
            throw CompanionGoneException(cause = e)
        } catch (e: SocketTimeoutException) {
            throw CompanionException("The companion app did not answer in time", e)
        } catch (e: IOException) {
            // A reset while the companion process dies is the same thing as EOF for our purposes.
            if (e.message?.contains("reset", ignoreCase = true) == true) throw CompanionGoneException(cause = e)
            throw e
        }
        if (length < 0 || length > MAX_RESPONSE_BYTES) throw CompanionException("Bad response length $length")
        val buf = ByteArray(length)
        try {
            input.readFully(buf)
        } catch (e: EOFException) {
            throw CompanionGoneException(cause = e)
        }
        val map = try {
            CompanionJson.decode(String(buf, Charsets.UTF_8))
        } catch (e: CompanionException) {
            throw e
        } catch (e: Exception) {
            throw CompanionException("Malformed response from the companion app", e)
        }
        return CompanionResponse.fromMap(map)
    }
}

/**
 * What the session logic needs from a companion. [CompanionControlClient] is the real
 * implementation; tests substitute a fake companion on a local socket.
 */
interface CompanionConnection {
    /** Sends [cmd] with [fields]; [timeoutMs] <= 0 waits forever. */
    suspend fun request(cmd: String, fields: Map<String, Any?> = emptyMap(), timeoutMs: Long = CompanionTimeouts.GETTER_MS): CompanionResponse

    /** Starts the companion's control service (if needed) and waits for the port to accept. */
    suspend fun wake()

    /** Whether the control port currently accepts connections, without waking anything. */
    suspend fun isReachable(): Boolean
}

object CompanionTimeouts {
    const val GETTER_MS = 10_000L
    const val VM_STOP_MS = 20_000L
    const val QEMU_STOP_MS = 20_000L
    const val WAKE_MS = 15_000L
    const val POLL_INTERVAL_MS = 1_000L
    /** Blocking calls (setup/start/repair) have no read timeout. */
    const val BLOCKING = 0L
}
