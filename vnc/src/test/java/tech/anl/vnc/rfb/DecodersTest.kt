package tech.anl.vnc.rfb

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.util.zip.Deflater

class DecodersTest {
    private fun DataOutputStream.pixel(argb: Int) {
        writeByte(argb and 0xFF); writeByte((argb ushr 8) and 0xFF); writeByte((argb ushr 16) and 0xFF); writeByte(0)
    }

    private fun input(build: DataOutputStream.() -> Unit): RfbInput {
        val bytes = ByteArrayOutputStream()
        DataOutputStream(bytes).apply(build).flush()
        return RfbInput(ByteArrayInputStream(bytes.toByteArray()))
    }

    @Test
    fun hextileCarriesBackgroundAndForegroundAcrossTiles() {
        val fb = Framebuffer(32, 16)
        val d = Decoders(fb, null)
        val bg = 0xFF112233.toInt()
        val fg = 0xFF445566.toInt()
        val inp = input {
            // tile 0: bg + fg specified, one subrect at (0,0) 1x1
            writeByte(2 or 4 or 8); pixel(bg); pixel(fg); writeByte(1); writeByte(0); writeByte(0)
            // tile 1: nothing specified, one subrect at (15,15) 1x1 -> reuses bg/fg
            writeByte(8); writeByte(1); writeByte(0xFF); writeByte(0)
        }
        d.hextile(inp, 0, 0, 32, 16)
        assertEquals(fg, fb.getPixel(0, 0))
        assertEquals(bg, fb.getPixel(1, 0))
        assertEquals(bg, fb.getPixel(16, 0))
        assertEquals(fg, fb.getPixel(31, 15))
    }

    @Test
    fun tightGradientFilter() {
        val w = 3
        val h = 2
        val rgb = arrayOf(
            intArrayOf(10, 20, 30), intArrayOf(40, 50, 60), intArrayOf(250, 5, 128),
            intArrayOf(70, 80, 90), intArrayOf(200, 210, 220), intArrayOf(0, 255, 7)
        )
        // Encoder side of the gradient filter.
        val residual = ByteArray(w * h * 3)
        for (y in 0 until h) for (x in 0 until w) for (c in 0 until 3) {
            val above = if (y > 0) rgb[(y - 1) * w + x][c] else 0
            val left = if (x > 0) rgb[y * w + x - 1][c] else 0
            val aboveLeft = if (x > 0 && y > 0) rgb[(y - 1) * w + x - 1][c] else 0
            val pred = (left + above - aboveLeft).coerceIn(0, 255)
            residual[(y * w + x) * 3 + c] = ((rgb[y * w + x][c] - pred) and 0xFF).toByte()
        }
        val def = Deflater()
        def.setInput(residual)
        val buf = ByteArray(256)
        val n = def.deflate(buf, 0, buf.size, Deflater.SYNC_FLUSH)
        val fb = Framebuffer(w, h)
        val inp = input {
            writeByte(0x40 or 0x20) // explicit filter, stream 2
            writeByte(2) // gradient
            writeByte(n) // compact length (< 128)
            write(buf, 0, n)
        }
        Decoders(fb, null).tight(inp, 0, 0, w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val p = rgb[y * w + x]
            assertEquals(Pixels.rgb(p[0], p[1], p[2]), fb.getPixel(x, y))
        }
    }

    @Test
    fun tightJpegUsesDecoder() {
        val fb = Framebuffer(2, 1)
        val jpeg = JpegDecoder { data, w, h ->
            assertEquals(3, data.size)
            IntArray(w * h) { 0xFFABCDEF.toInt() }
        }
        val inp = input { writeByte(0x90); writeByte(3); write(byteArrayOf(1, 2, 3)) }
        Decoders(fb, jpeg).tight(inp, 0, 0, 2, 1)
        assertEquals(0xFFABCDEF.toInt(), fb.getPixel(1, 0))
    }

    @Test
    fun copyRectHandlesOverlap() {
        val fb = Framebuffer(4, 4)
        for (y in 0 until 4) for (x in 0 until 4) fb.fillRect(x, y, 1, 1, y * 4 + x)
        fb.copyRect(0, 0, 1, 1, 3, 3)
        assertEquals(0, fb.getPixel(1, 1))
        assertEquals(10, fb.getPixel(3, 3))
        assertEquals(5, fb.getPixel(2, 2))
    }

    @Test
    fun outOfBoundsRectsAreClipped() {
        val fb = Framebuffer(4, 4)
        fb.fillRect(2, 2, 10, 10, 7)
        fb.putRect(-1, -1, 2, 2, intArrayOf(1, 2, 3, 4))
        assertEquals(7, fb.getPixel(3, 3))
        assertEquals(4, fb.getPixel(0, 0))
    }
}
