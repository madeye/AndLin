package tech.anl.vnc.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.Deflater
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class RfbClientTest {
    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val white = 0xFFFFFFFF.toInt()
    private val black = 0xFF000000.toInt()
    private val grey = 0xFF808080.toInt()

    /** Writes a pixel in the client's requested format (little-endian B, G, R, X). */
    private fun DataOutputStream.pixel(argb: Int) {
        writeByte(argb and 0xFF)
        writeByte((argb ushr 8) and 0xFF)
        writeByte((argb ushr 16) and 0xFF)
        writeByte(0)
    }

    private fun DataOutputStream.rect(x: Int, y: Int, w: Int, h: Int, enc: Int) {
        writeShort(x); writeShort(y); writeShort(w); writeShort(h); writeInt(enc)
    }

    private fun compactLength(out: DataOutputStream, len: Int) {
        if (len < 0x80) {
            out.writeByte(len)
        } else if (len < 0x4000) {
            out.writeByte((len and 0x7F) or 0x80); out.writeByte(len ushr 7)
        } else {
            out.writeByte((len and 0x7F) or 0x80); out.writeByte(((len ushr 7) and 0x7F) or 0x80); out.writeByte(len ushr 14)
        }
    }

    private fun deflateSync(deflater: Deflater, data: ByteArray): ByteArray {
        deflater.setInput(data)
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (true) {
            val n = deflater.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
            out.write(buf, 0, n)
            if (n < buf.size) break
        }
        return out.toByteArray()
    }

    private class Recorder : RfbListener {
        val connected = CountDownLatch(1)
        val updated = CountDownLatch(1)
        val bell = CountDownLatch(1)
        val cutText = AtomicReference<String>()
        val cutLatch = CountDownLatch(1)
        val cursor = AtomicReference<RemoteCursor>()
        val disconnected = CountDownLatch(1)
        val error = AtomicReference<Throwable>()
        var name = ""

        override fun onConnected(desktopName: String, width: Int, height: Int) {
            name = desktopName; connected.countDown()
        }
        override fun onUpdateEnd() = updated.countDown()
        override fun onBell() = bell.countDown()
        override fun onServerCutText(text: String) {
            cutText.set(text); cutLatch.countDown()
        }
        override fun onCursor(cursor: RemoteCursor?) = this.cursor.set(cursor)
        override fun onDisconnected(error: Throwable?) {
            this.error.set(error); disconnected.countDown()
        }
    }

    private fun serverInit(out: DataOutputStream, w: Int, h: Int, name: String) {
        out.writeShort(w); out.writeShort(h)
        // server pixel format (ignored by the client)
        out.write(byteArrayOf(32, 24, 0, 1, 0, 255.toByte(), 0, 255.toByte(), 0, 255.toByte(), 16, 8, 0, 0, 0, 0))
        out.writeInt(name.length); out.write(name.toByteArray())
        out.flush()
    }

    /** Reads SetPixelFormat, SetEncodings and the first FramebufferUpdateRequest. */
    private fun readClientSetup(inp: DataInputStream): IntArray {
        assertEquals(0, inp.readUnsignedByte())
        val pf = ByteArray(19).also { inp.readFully(it) }
        assertEquals(32, pf[3].toInt()) // bpp
        assertEquals(0, pf[5].toInt()) // little endian
        assertEquals(1, pf[6].toInt()) // true colour
        assertEquals(16, pf[13].toInt()) // red shift
        assertEquals(2, inp.readUnsignedByte())
        inp.readUnsignedByte()
        val n = inp.readUnsignedShort()
        val encs = IntArray(n) { inp.readInt() }
        assertEquals(3, inp.readUnsignedByte())
        assertEquals(0, inp.readUnsignedByte()) // non-incremental
        inp.readFully(ByteArray(8))
        return encs
    }

    @Test(timeout = 20_000)
    fun handshakeVncAuthAndDecodeAllEncodings() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val password = "s3cret!"
        val serverError = AtomicReference<Throwable>()
        val clientMessages = AtomicReference<ByteArray>()
        val encodingsSeen = AtomicReference<IntArray>()
        val rec = Recorder()
        val t = Thread {
            try {
                server.accept().use { s ->
                    val inp = DataInputStream(s.getInputStream())
                    val out = DataOutputStream(s.getOutputStream())
                    out.write("RFB 003.008\n".toByteArray()); out.flush()
                    val v = ByteArray(12).also { inp.readFully(it) }
                    assertEquals("RFB 003.008\n", String(v))
                    out.writeByte(2); out.writeByte(1); out.writeByte(2); out.flush()
                    assertEquals(2, inp.readUnsignedByte())
                    val challenge = ByteArray(16) { (it * 17 + 3).toByte() }
                    out.write(challenge); out.flush()
                    val response = ByteArray(16).also { inp.readFully(it) }
                    // Independent check with the JCA DES implementation.
                    val key = ByteArray(8)
                    password.toByteArray().copyInto(key, 0, 0, minOf(8, password.length))
                    for (i in key.indices) key[i] = (Integer.reverse(key[i].toInt() and 0xFF) ushr 24).toByte()
                    val jca = Cipher.getInstance("DES/ECB/NoPadding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES")) }
                    assertArrayEquals(jca.doFinal(challenge), response)
                    out.writeInt(0); out.flush()
                    assertEquals(1, inp.readUnsignedByte()) // shared
                    serverInit(out, 64, 48, "test desktop")
                    encodingsSeen.set(readClientSetup(inp))

                    val msg = ByteArrayOutputStream()
                    val m = DataOutputStream(msg)
                    m.writeByte(0); m.writeByte(0); m.writeShort(11)
                    // 1. Raw 4x2 at (0,0): row0 red, row1 green
                    m.rect(0, 0, 4, 2, 0)
                    repeat(4) { m.pixel(red) }; repeat(4) { m.pixel(green) }
                    // 2. CopyRect: copy (0,0,4,2) to (10,10)
                    m.rect(10, 10, 4, 2, 1)
                    m.writeShort(0); m.writeShort(0)
                    // 3. RRE at (20,0) 8x8 blue bg with a white 2x2 at (1,1)
                    m.rect(20, 0, 8, 8, 2)
                    m.writeInt(1); m.pixel(blue); m.pixel(white)
                    m.writeShort(1); m.writeShort(1); m.writeShort(2); m.writeShort(2)
                    // 4. Hextile at (0,16) 20x16: tile0 (16x16) bg grey + fg-subrect red at (2,3) 4x5,
                    //    tile1 (4x16) raw blue
                    m.rect(0, 16, 20, 16, 5)
                    m.writeByte(2 or 4 or 8); m.pixel(grey); m.pixel(red)
                    m.writeByte(1); m.writeByte((2 shl 4) or 3); m.writeByte(((4 - 1) shl 4) or (5 - 1))
                    m.writeByte(1); repeat(4 * 16) { m.pixel(blue) }
                    // 5. ZRLE at (32,16) 16x8: two rects' worth of tiles in one persistent stream
                    val deflater = Deflater()
                    val z1 = ByteArrayOutputStream().apply {
                        write(1); write(0); write(0xFF); write(0) // solid tile: CPIXEL B,G,R = green
                    }.toByteArray()
                    val c1 = deflateSync(deflater, z1)
                    m.rect(32, 16, 16, 8, 16); m.writeInt(c1.size); m.write(c1)
                    // 6. ZRLE at (32,24) 4x2: packed palette (2 colours), then second rect RLE
                    val z2 = ByteArrayOutputStream().apply {
                        write(2)
                        write(byteArrayOf(0, 0, 0xFF.toByte())) // red
                        write(byteArrayOf(0xFF.toByte(), 0, 0)) // blue
                        write(0b01010000) // row0: red blue red blue
                        write(0b10100000) // row1: blue red blue red
                    }.toByteArray()
                    val c2 = deflateSync(deflater, z2)
                    m.rect(32, 24, 4, 2, 16); m.writeInt(c2.size); m.write(c2)
                    // 7. ZRLE at (40,24) 5x1: plain RLE: 3 white + 2 black
                    val z3 = ByteArrayOutputStream().apply {
                        write(128)
                        write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte())); write(2)
                        write(byteArrayOf(0, 0, 0)); write(1)
                    }.toByteArray()
                    val c3 = deflateSync(deflater, z3)
                    m.rect(40, 24, 5, 1, 16); m.writeInt(c3.size); m.write(c3)
                    // 8. Tight fill at (50,0) 4x4 red (TPIXEL is R,G,B)
                    m.rect(50, 0, 4, 4, 7)
                    m.writeByte(0x80); m.writeByte(0xFF); m.writeByte(0); m.writeByte(0)
                    // 9. Tight basic, copy filter, zlib stream 0, 4x4 = 48 bytes: blue
                    m.rect(50, 8, 4, 4, 7)
                    m.writeByte(0x00)
                    val tightRaw = ByteArray(48) { if (it % 3 == 2) 0xFF.toByte() else 0 }
                    val tightDef = Deflater()
                    val tc = deflateSync(tightDef, tightRaw)
                    compactLength(m, tc.size); m.write(tc)
                    // 10. Tight palette filter, 2 colours, 3x2 (uncompressed: 2 bytes < 12)
                    m.rect(56, 0, 3, 2, 7)
                    m.writeByte(0x40 or 0x10) // explicit filter, stream 1
                    m.writeByte(1); m.writeByte(1)
                    m.write(byteArrayOf(0, 0, 0)); m.write(byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()))
                    m.writeByte(0b10100000); m.writeByte(0b01000000)
                    // 11. Cursor 2x2 hotspot (1,1): top-left visible only
                    m.rect(1, 1, 2, 2, -239)
                    repeat(4) { m.pixel(red) }
                    m.writeByte(0b10000000); m.writeByte(0)
                    // Bell and cut text
                    m.writeByte(2)
                    m.writeByte(3); m.write(ByteArray(3)); m.writeInt(5); m.write("héllo".toByteArray(Charsets.ISO_8859_1))
                    out.write(msg.toByteArray()); out.flush()

                    // The client should now request an incremental update.
                    assertEquals(3, inp.readUnsignedByte())
                    assertEquals(1, inp.readUnsignedByte())
                    inp.readFully(ByteArray(8))
                    // Then the pointer + key events the test sends.
                    val rest = ByteArray(6 + 8 + 8 + 8 + 3)
                    inp.readFully(rest)
                    clientMessages.set(rest)
                }
            } catch (e: Throwable) {
                serverError.set(e)
            }
        }
        t.start()

        val client = RfbClient("127.0.0.1", server.localPort, password, rec)
        client.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals("test desktop", rec.name)
        assertTrue(rec.updated.await(5, TimeUnit.SECONDS))
        assertTrue(rec.bell.await(5, TimeUnit.SECONDS))
        assertTrue(rec.cutLatch.await(5, TimeUnit.SECONDS))
        assertEquals("héllo", rec.cutText.get())
        val encs = encodingsSeen.get()
        for (e in intArrayOf(7, 16, 5, 1, 2, 0, -239, -223, -308, -224)) assertTrue("encoding $e", e in encs)

        val fb = client.framebuffer
        assertEquals(64, fb.width)
        assertEquals(48, fb.height)
        // Raw
        assertEquals(red, fb.getPixel(0, 0)); assertEquals(red, fb.getPixel(3, 0))
        assertEquals(green, fb.getPixel(0, 1)); assertEquals(black, fb.getPixel(4, 0))
        // CopyRect
        assertEquals(red, fb.getPixel(10, 10)); assertEquals(green, fb.getPixel(13, 11))
        // RRE
        assertEquals(blue, fb.getPixel(20, 0)); assertEquals(white, fb.getPixel(21, 1))
        assertEquals(white, fb.getPixel(22, 2)); assertEquals(blue, fb.getPixel(23, 3))
        // Hextile
        assertEquals(grey, fb.getPixel(0, 16)); assertEquals(red, fb.getPixel(2, 19))
        assertEquals(red, fb.getPixel(5, 23)); assertEquals(grey, fb.getPixel(6, 19))
        assertEquals(grey, fb.getPixel(2, 24)); assertEquals(blue, fb.getPixel(16, 16))
        assertEquals(blue, fb.getPixel(19, 31))
        // ZRLE
        assertEquals(green, fb.getPixel(32, 16)); assertEquals(green, fb.getPixel(47, 23))
        assertEquals(red, fb.getPixel(32, 24)); assertEquals(blue, fb.getPixel(33, 24))
        assertEquals(blue, fb.getPixel(32, 25)); assertEquals(red, fb.getPixel(35, 25))
        assertEquals(white, fb.getPixel(40, 24)); assertEquals(white, fb.getPixel(42, 24))
        assertEquals(black, fb.getPixel(43, 24)); assertEquals(black, fb.getPixel(44, 24))
        // Tight
        assertEquals(red, fb.getPixel(50, 0)); assertEquals(red, fb.getPixel(53, 3))
        assertEquals(blue, fb.getPixel(50, 8)); assertEquals(blue, fb.getPixel(53, 11))
        assertEquals(white, fb.getPixel(56, 0)); assertEquals(black, fb.getPixel(57, 0))
        assertEquals(white, fb.getPixel(58, 0)); assertEquals(white, fb.getPixel(57, 1))
        assertEquals(black, fb.getPixel(56, 1))
        // Cursor
        val cursor = rec.cursor.get()
        assertNotNull(cursor)
        assertEquals(1, cursor.hotX)
        assertEquals(red, cursor.pixels[0]); assertEquals(0, cursor.pixels[1])

        client.sendPointer(12, 34, 1)
        client.sendKey(Keysyms.RETURN, true)
        client.sendKey(Keysyms.RETURN, false)
        client.sendClientCutText("abc")
        t.join(5000)
        serverError.get()?.let { throw it }
        val cm = DataInputStream(clientMessages.get().inputStream())
        assertEquals(5, cm.readUnsignedByte()); assertEquals(1, cm.readUnsignedByte())
        assertEquals(12, cm.readUnsignedShort()); assertEquals(34, cm.readUnsignedShort())
        assertEquals(4, cm.readUnsignedByte()); assertEquals(1, cm.readUnsignedByte()); cm.readShort()
        assertEquals(Keysyms.RETURN, cm.readInt())
        assertEquals(4, cm.readUnsignedByte()); assertEquals(0, cm.readUnsignedByte()); cm.readShort()
        assertEquals(Keysyms.RETURN, cm.readInt())
        assertEquals(6, cm.readUnsignedByte()); cm.readFully(ByteArray(3)); assertEquals(3, cm.readInt())
        assertEquals("abc", String(ByteArray(3).also { cm.readFully(it) }))

        // The server hung up: the client reports a disconnect.
        assertTrue(rec.disconnected.await(5, TimeUnit.SECONDS))
        client.close()
        server.close()
    }

    @Test(timeout = 20_000)
    fun authFailureReportsReason() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val t = Thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = DataOutputStream(s.getOutputStream())
                out.write("RFB 003.008\n".toByteArray()); out.flush()
                inp.readFully(ByteArray(12))
                out.writeByte(1); out.writeByte(2); out.flush()
                inp.readUnsignedByte()
                out.write(ByteArray(16)); out.flush()
                inp.readFully(ByteArray(16))
                out.writeInt(1); out.writeInt(14); out.write("Wrong password".toByteArray()); out.flush()
                try { inp.read() } catch (_: Exception) {}
            }
        }
        t.start()
        val rec = Recorder()
        RfbClient("127.0.0.1", server.localPort, "nope", rec).start()
        assertTrue(rec.disconnected.await(5, TimeUnit.SECONDS))
        val err = rec.error.get()
        assertTrue(err is RfbAuthException)
        assertEquals("Wrong password", err!!.message)
        t.join(2000)
        server.close()
    }

    @Test(timeout = 20_000)
    fun rfb33NoAuthWithDesktopSize() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val rec = Recorder()
        val resized = CountDownLatch(1)
        val listener = object : RfbListener by rec {
            override fun onResize(width: Int, height: Int) = resized.countDown()
        }
        val t = Thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = DataOutputStream(s.getOutputStream())
                out.write("RFB 003.003\n".toByteArray()); out.flush()
                val v = ByteArray(12).also { inp.readFully(it) }
                assertEquals("RFB 003.003\n", String(v))
                out.writeInt(1); out.flush() // None, no SecurityResult in 3.3
                inp.readUnsignedByte()
                serverInit(out, 8, 8, "x")
                readClientSetup(inp)
                out.writeByte(0); out.writeByte(0); out.writeShort(2)
                out.rect(0, 0, 16, 4, -223)
                out.rect(15, 3, 1, 1, 0); out.pixel(red)
                out.flush()
                inp.readUnsignedByte(); inp.readFully(ByteArray(9))
            }
        }
        t.start()
        val client = RfbClient("127.0.0.1", server.localPort, null, listener)
        client.start()
        assertTrue(resized.await(5, TimeUnit.SECONDS))
        assertTrue(rec.updated.await(5, TimeUnit.SECONDS))
        assertEquals(16, client.framebuffer.width)
        assertEquals(4, client.framebuffer.height)
        assertEquals(red, client.framebuffer.getPixel(15, 3))
        t.join(2000)
        client.close()
        server.close()
    }

    @Test(timeout = 20_000)
    fun tightSecurityExtensionWithoutAuth() {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val rec = Recorder()
        val t = Thread {
            server.accept().use { s ->
                val inp = DataInputStream(s.getInputStream())
                val out = DataOutputStream(s.getOutputStream())
                out.write("RFB 003.007\n".toByteArray()); out.flush()
                inp.readFully(ByteArray(12))
                out.writeByte(1); out.writeByte(16); out.flush()
                assertEquals(16, inp.readUnsignedByte())
                out.writeInt(1); out.write(ByteArray(16)); out.flush() // one tunnel type
                assertEquals(0, inp.readInt()) // NOTUNNEL
                out.writeInt(0); out.flush() // no auth -> no SecurityResult in 3.7
                inp.readUnsignedByte()
                serverInit(out, 4, 4, "tight")
                out.writeShort(1); out.writeShort(0); out.writeShort(1); out.writeShort(0)
                out.write(ByteArray(32)); out.flush()
                readClientSetup(inp)
                inp.read()
            }
        }
        t.start()
        val client = RfbClient("127.0.0.1", server.localPort, null, rec)
        client.start()
        assertTrue(rec.connected.await(5, TimeUnit.SECONDS))
        assertEquals("tight", rec.name)
        client.close()
        t.join(2000)
        server.close()
    }
}
