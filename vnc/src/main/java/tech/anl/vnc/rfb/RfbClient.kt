package tech.anl.vnc.rfb

import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.IOException
import java.io.OutputStream
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/** Authentication failed or the server refused the connection; retrying won't help. */
class RfbAuthException(message: String) : IOException(message)

/** Callbacks from the reader thread. None of them run on the Android main thread. */
interface RfbListener {
    fun onConnected(desktopName: String, width: Int, height: Int) {}
    fun onResize(width: Int, height: Int) {}
    fun onFramebufferUpdate(x: Int, y: Int, width: Int, height: Int) {}
    fun onUpdateEnd() {}
    fun onCursor(cursor: RemoteCursor?) {}
    fun onBell() {}
    fun onServerCutText(text: String) {}

    /** The connection ended. [error] is null only if the server closed cleanly. Not called after [RfbClient.close]. */
    fun onDisconnected(error: Throwable?) {}
}

/**
 * A lightweight RFB (VNC) client: protocol 3.3/3.7/3.8, None / VNC / Tight
 * security, Tight, ZRLE, Hextile, CopyRect, RRE and Raw encodings, and the
 * cursor, desktop-size and last-rect pseudo-encodings.
 *
 * Network IO happens on a reader thread (connect, handshake, server messages)
 * and a writer thread (client messages, queued), so every public method is
 * safe to call from the UI thread.
 */
class RfbClient(
    private val host: String,
    private val port: Int,
    private val password: String?,
    private val listener: RfbListener,
    private val jpegDecoder: JpegDecoder? = null,
    /** Keep retrying refused connections for this long; the server may still be starting. */
    private val connectRetryMs: Long = 10_000,
    private val encodings: IntArray = DEFAULT_ENCODINGS
) {
    val framebuffer = Framebuffer()

    @Volatile
    var desktopName: String = ""
        private set

    @Volatile
    var protocolMinor: Int = 0
        private set

    /** True once the server has sent an ExtendedDesktopSize rectangle. */
    @Volatile
    var supportsDesktopResize: Boolean = false
        private set

    private val closed = AtomicBoolean(false)
    private val started = AtomicBoolean(false)
    private val queue = LinkedBlockingQueue<ByteArray>()

    @Volatile
    private var socket: Socket? = null

    @Volatile
    private var connected = false
    private var readerThread: Thread? = null
    private var writerThread: Thread? = null
    private var screens: List<Screen> = emptyList()
    private var usesTightProtocol = false

    private data class Screen(val id: Long, val x: Int, val y: Int, val w: Int, val h: Int, val flags: Long)

    fun start() {
        if (!started.compareAndSet(false, true)) return
        readerThread = Thread({ runReader() }, "rfb-reader").apply {
            isDaemon = true
            start()
        }
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        connected = false
        queue.offer(POISON)
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        writerThread?.interrupt()
    }

    val isClosed: Boolean get() = closed.get()

    private fun runReader() {
        var decoders: Decoders? = null
        try {
            val s = connectWithRetry()
            socket = s
            if (closed.get()) {
                s.close()
                return
            }
            val inp = RfbInput(s.getInputStream())
            val out = DataOutputStream(BufferedOutputStream(s.getOutputStream(), 16384))
            handshake(inp, out)
            decoders = Decoders(framebuffer, jpegDecoder)
            connected = true
            writerThread = Thread({ runWriter(out) }, "rfb-writer").apply {
                isDaemon = true
                start()
            }
            listener.onConnected(desktopName, framebuffer.width, framebuffer.height)
            messageLoop(inp, decoders)
        } catch (t: Throwable) {
            if (!closed.get()) {
                connected = false
                closed.set(true)
                queue.offer(POISON)
                try {
                    socket?.close()
                } catch (_: IOException) {
                }
                listener.onDisconnected(if (t is java.io.EOFException) null else t)
            }
        } finally {
            decoders?.release()
        }
    }

    private fun connectWithRetry(): Socket {
        val deadline = System.currentTimeMillis() + connectRetryMs
        while (true) {
            val s = Socket()
            try {
                s.tcpNoDelay = true
                s.connect(InetSocketAddress(host, port), 5000)
                return s
            } catch (e: ConnectException) {
                s.close()
                if (closed.get() || System.currentTimeMillis() >= deadline) throw e
                Thread.sleep(500)
            }
        }
    }

    private fun runWriter(out: OutputStream) {
        try {
            while (true) {
                val msg = queue.take()
                if (msg === POISON) break
                out.write(msg)
                var next = queue.poll()
                while (next != null && next !== POISON) {
                    out.write(next)
                    next = queue.poll()
                }
                out.flush()
                if (next === POISON) break
            }
        } catch (_: InterruptedException) {
        } catch (e: IOException) {
            // The reader notices the broken socket and reports it.
            try {
                socket?.close()
            } catch (_: IOException) {
            }
        }
    }

    private fun send(msg: ByteArray) {
        if (connected && !closed.get()) queue.offer(msg)
    }

    private inline fun message(size: Int, build: DataOutputStream.() -> Unit): ByteArray {
        val bytes = ByteArrayOutputStream(size)
        DataOutputStream(bytes).apply(build).flush()
        return bytes.toByteArray()
    }

    // ---- Handshake ------------------------------------------------------

    private fun handshake(inp: RfbInput, out: DataOutputStream) {
        val version = String(inp.bytes(12), Charsets.US_ASCII)
        val match = Regex("RFB (\\d{3})\\.(\\d{3})\n").matchEntire(version)
            ?: throw IOException("not a VNC server")
        val major = match.groupValues[1].toInt()
        val minor = match.groupValues[2].toInt()
        protocolMinor = when {
            major > 3 || minor >= 8 -> 8
            minor == 7 -> 7
            else -> 3
        }
        out.write("RFB 003.00$protocolMinor\n".toByteArray(Charsets.US_ASCII))
        out.flush()

        val secType: Int
        if (protocolMinor == 3) {
            secType = inp.s32()
            if (secType == 0) throw RfbAuthException(readReason(inp))
        } else {
            val count = inp.u8()
            if (count == 0) throw RfbAuthException(readReason(inp))
            val offered = inp.bytes(count).map { it.toInt() and 0xFF }
            secType = chooseSecurity(offered)
            out.writeByte(secType)
            out.flush()
        }

        val auth = when (secType) {
            SEC_NONE -> SEC_NONE
            SEC_VNC -> {
                vncAuth(inp, out)
                SEC_VNC
            }
            SEC_TIGHT -> tightSecurity(inp, out)
            else -> throw RfbAuthException("Unsupported security type $secType")
        }

        if (auth == SEC_VNC || protocolMinor >= 8) {
            val result = inp.s32()
            if (result != 0) {
                val reason = if (protocolMinor >= 8) readReason(inp) else ""
                throw RfbAuthException(reason.ifEmpty { if (auth == SEC_VNC) "Authentication failed" else "Connection refused" })
            }
        }

        out.writeByte(1) // ClientInit: shared
        out.flush()

        val width = inp.u16()
        val height = inp.u16()
        inp.bytes(16) // server pixel format; we set our own below
        val nameLen = inp.u32()
        desktopName = if (nameLen in 0..65536) String(inp.bytes(nameLen.toInt()), Charsets.UTF_8) else {
            inp.skip(nameLen)
            ""
        }
        if (usesTightProtocol) {
            val serverMsgs = inp.u16()
            val clientMsgs = inp.u16()
            val encs = inp.u16()
            inp.u16()
            inp.skip(16L * (serverMsgs + clientMsgs + encs))
        }
        framebuffer.resize(width, height)

        out.write(setPixelFormatMessage())
        out.write(setEncodingsMessage(encodings))
        out.write(updateRequest(false, 0, 0, width, height))
        out.flush()
    }

    private fun chooseSecurity(offered: List<Int>): Int = when {
        !password.isNullOrEmpty() && SEC_VNC in offered -> SEC_VNC
        SEC_NONE in offered -> SEC_NONE
        SEC_VNC in offered -> SEC_VNC
        SEC_TIGHT in offered -> SEC_TIGHT
        else -> throw RfbAuthException("No supported security type (server offers $offered)")
    }

    private fun readReason(inp: RfbInput): String {
        val len = inp.u32()
        if (len > 65536) return "Connection refused"
        return String(inp.bytes(len.toInt()), Charsets.ISO_8859_1)
    }

    private fun vncAuth(inp: RfbInput, out: DataOutputStream) {
        val challenge = inp.bytes(16)
        out.write(VncAuth.response(password ?: "", challenge))
        out.flush()
    }

    /** TightVNC's security extension: tunnel and auth capability lists. Returns the auth used. */
    private fun tightSecurity(inp: RfbInput, out: DataOutputStream): Int {
        usesTightProtocol = true
        val tunnels = inp.u32()
        if (tunnels > 0) {
            if (tunnels > 1024) throw IOException("bad Tight tunnel count")
            inp.skip(16L * tunnels)
            out.writeInt(0) // NOTUNNEL
            out.flush()
        }
        val auths = inp.u32()
        if (auths == 0L) return SEC_NONE
        if (auths > 1024) throw IOException("bad Tight auth count")
        val codes = (0 until auths.toInt()).map {
            val code = inp.s32()
            inp.skip(12)
            code
        }
        val chosen = when {
            !password.isNullOrEmpty() && SEC_VNC in codes -> SEC_VNC
            SEC_NONE in codes -> SEC_NONE
            SEC_VNC in codes -> SEC_VNC
            else -> throw RfbAuthException("No supported Tight authentication (server offers $codes)")
        }
        out.writeInt(chosen)
        out.flush()
        if (chosen == SEC_VNC) vncAuth(inp, out)
        return chosen
    }

    // ---- Server messages ------------------------------------------------

    private fun messageLoop(inp: RfbInput, decoders: Decoders) {
        while (!closed.get()) {
            when (val type = inp.u8()) {
                0 -> framebufferUpdate(inp, decoders)
                1 -> {
                    inp.u8()
                    inp.u16()
                    val n = inp.u16()
                    inp.skip(6L * n)
                }
                2 -> listener.onBell()
                3 -> {
                    inp.bytes(3)
                    val len = inp.u32()
                    if (len > MAX_CUT_TEXT) {
                        inp.skip(len)
                    } else {
                        val text = String(inp.bytes(len.toInt()), Charsets.ISO_8859_1)
                        listener.onServerCutText(text.replace("\r\n", "\n"))
                    }
                }
                else -> throw IOException("Unknown server message $type")
            }
        }
    }

    private fun framebufferUpdate(inp: RfbInput, decoders: Decoders) {
        inp.u8()
        val count = inp.u16()
        var resized = false
        for (i in 0 until count) {
            val x = inp.u16()
            val y = inp.u16()
            val w = inp.u16()
            val h = inp.u16()
            val enc = inp.s32()
            when (enc) {
                ENC_RAW -> decoders.raw(inp, x, y, w, h)
                ENC_COPYRECT -> decoders.copyRect(inp, x, y, w, h)
                ENC_RRE -> decoders.rre(inp, x, y, w, h)
                ENC_HEXTILE -> decoders.hextile(inp, x, y, w, h)
                ENC_ZRLE -> decoders.zrle(inp, x, y, w, h)
                ENC_TIGHT -> decoders.tight(inp, x, y, w, h)
                ENC_LAST_RECT -> break
                ENC_CURSOR -> {
                    listener.onCursor(decoders.cursor(inp, x, y, w, h))
                    continue
                }
                ENC_DESKTOP_SIZE -> {
                    resize(w, h)
                    resized = true
                    continue
                }
                ENC_EXTENDED_DESKTOP_SIZE -> {
                    val n = inp.u8()
                    inp.bytes(3)
                    val list = ArrayList<Screen>(n)
                    for (s in 0 until n) {
                        list.add(Screen(inp.u32(), inp.u16(), inp.u16(), inp.u16(), inp.u16(), inp.u32()))
                    }
                    supportsDesktopResize = true
                    // x = reason (1: our request), y = status; a failed request carries the current size.
                    if (!(x == 1 && y != 0)) screens = list
                    if (w != framebuffer.width || h != framebuffer.height) {
                        resize(w, h)
                        resized = true
                    }
                    continue
                }
                else -> throw IOException("Unsupported encoding $enc")
            }
            listener.onFramebufferUpdate(x, y, w, h)
        }
        listener.onUpdateEnd()
        send(updateRequest(!resized, 0, 0, framebuffer.width, framebuffer.height))
    }

    private fun resize(w: Int, h: Int) {
        framebuffer.resize(w, h)
        listener.onResize(w, h)
    }

    // ---- Client messages ------------------------------------------------

    /** Sends a pointer position (framebuffer pixels) and button mask (bit 0 = left). */
    fun sendPointer(x: Int, y: Int, buttons: Int) {
        val cx = x.coerceIn(0, maxOf(0, framebuffer.width - 1))
        val cy = y.coerceIn(0, maxOf(0, framebuffer.height - 1))
        send(message(6) {
            writeByte(5)
            writeByte(buttons)
            writeShort(cx)
            writeShort(cy)
        })
    }

    fun sendKey(keysym: Int, down: Boolean) {
        if (keysym == 0) return
        send(message(8) {
            writeByte(4)
            writeByte(if (down) 1 else 0)
            writeShort(0)
            writeInt(keysym)
        })
    }

    /** Sends [text] as the client cut buffer (Latin-1; other characters become '?'). */
    fun sendClientCutText(text: String) {
        val bytes = text.replace("\r\n", "\n").map { if (it.code <= 0xff) it else '?' }
            .joinToString("").toByteArray(Charsets.ISO_8859_1)
        send(message(8 + bytes.size) {
            writeByte(6)
            write(ByteArray(3))
            writeInt(bytes.size)
            write(bytes)
        })
    }

    fun requestFullUpdate() {
        send(updateRequest(false, 0, 0, framebuffer.width, framebuffer.height))
    }

    /** Asks the server to change the desktop size; returns false if unsupported. */
    fun requestDesktopSize(width: Int, height: Int): Boolean {
        if (!supportsDesktopResize || width <= 0 || height <= 0) return false
        val id = screens.firstOrNull()?.id ?: 0L
        val flags = screens.firstOrNull()?.flags ?: 0L
        send(message(24) {
            writeByte(251)
            writeByte(0)
            writeShort(width)
            writeShort(height)
            writeByte(1)
            writeByte(0)
            writeInt(id.toInt())
            writeShort(0)
            writeShort(0)
            writeShort(width)
            writeShort(height)
            writeInt(flags.toInt())
        })
        return true
    }

    private fun updateRequest(incremental: Boolean, x: Int, y: Int, w: Int, h: Int) = message(10) {
        writeByte(3)
        writeByte(if (incremental) 1 else 0)
        writeShort(x)
        writeShort(y)
        writeShort(w)
        writeShort(h)
    }

    private fun setPixelFormatMessage() = message(20) {
        writeByte(0)
        write(ByteArray(3))
        writeByte(32) // bits per pixel
        writeByte(24) // depth
        writeByte(0) // little endian
        writeByte(1) // true colour
        writeShort(255)
        writeShort(255)
        writeShort(255)
        writeByte(16) // red shift
        writeByte(8) // green shift
        writeByte(0) // blue shift
        write(ByteArray(3))
    }

    private fun setEncodingsMessage(list: IntArray) = message(4 + 4 * list.size) {
        writeByte(2)
        writeByte(0)
        writeShort(list.size)
        for (e in list) writeInt(e)
    }

    companion object {
        const val SEC_NONE = 1
        const val SEC_VNC = 2
        const val SEC_TIGHT = 16

        const val ENC_RAW = 0
        const val ENC_COPYRECT = 1
        const val ENC_RRE = 2
        const val ENC_HEXTILE = 5
        const val ENC_TIGHT = 7
        const val ENC_ZRLE = 16
        const val ENC_COMPRESS_LEVEL_1 = -255
        const val ENC_CURSOR = -239
        const val ENC_DESKTOP_SIZE = -223
        const val ENC_LAST_RECT = -224
        const val ENC_EXTENDED_DESKTOP_SIZE = -308

        private const val MAX_CUT_TEXT = 4L * 1024 * 1024
        private val POISON = ByteArray(0)

        val DEFAULT_ENCODINGS = intArrayOf(
            ENC_TIGHT, ENC_ZRLE, ENC_HEXTILE, ENC_COPYRECT, ENC_RRE, ENC_RAW,
            ENC_CURSOR, ENC_DESKTOP_SIZE, ENC_EXTENDED_DESKTOP_SIZE, ENC_LAST_RECT,
            ENC_COMPRESS_LEVEL_1
        )
    }
}
