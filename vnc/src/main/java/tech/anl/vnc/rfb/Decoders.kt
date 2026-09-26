package tech.anl.vnc.rfb

import java.io.IOException

/** Decodes a JPEG image into ARGB pixels; implemented on Android with BitmapFactory. */
fun interface JpegDecoder {
    /** Returns [width]*[height] ARGB pixels, or null if the image could not be decoded. */
    fun decode(data: ByteArray, width: Int, height: Int): IntArray?
}

/**
 * Rectangle decoders for the encodings we advertise. Pixel data is assumed to
 * be in the format requested by [RfbClient]: 32bpp, depth 24, little-endian,
 * red/green/blue shifts 16/8/0 (byte order B, G, R, X), which maps directly
 * onto Android's ARGB_8888 ints once the alpha byte is forced to 0xFF.
 */
internal class Decoders(private val fb: Framebuffer, private val jpeg: JpegDecoder?) {
    private var bytes = ByteArray(0)
    private var ints = IntArray(0)
    private val tile = IntArray(64 * 64)
    private val zrle = ZlibStream()
    private val tight = Array(4) { ZlibStream() }

    private fun byteBuf(n: Int): ByteArray {
        if (bytes.size < n) bytes = ByteArray(n)
        return bytes
    }

    private fun intBuf(n: Int): IntArray {
        if (ints.size < n) ints = IntArray(n)
        return ints
    }

    private fun checkSize(w: Int, h: Int) {
        if (w < 0 || h < 0 || w.toLong() * h > MAX_RECT_PIXELS) throw IOException("rectangle too large: ${w}x$h")
    }

    fun raw(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        checkSize(w, h)
        if (w == 0 || h == 0) return
        // Row bands keep the scratch buffers bounded for huge updates.
        val bandRows = maxOf(1, minOf(h, (1 shl 20) / maxOf(1, w)))
        var row = 0
        while (row < h) {
            val rows = minOf(bandRows, h - row)
            val n = w * rows
            val b = byteBuf(n * 4)
            inp.readFully(b, 0, n * 4)
            val px = intBuf(n)
            Pixels.convert32(b, 0, px, 0, n)
            fb.putRect(x, y + row, w, rows, px)
            row += rows
        }
    }

    fun copyRect(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        val sx = inp.u16()
        val sy = inp.u16()
        fb.copyRect(sx, sy, x, y, w, h)
    }

    fun rre(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        val count = inp.u32()
        if (count > MAX_RECT_PIXELS) throw IOException("too many RRE subrectangles")
        fb.fillRect(x, y, w, h, inp.pixel())
        for (i in 0 until count) {
            val color = inp.pixel()
            val sx = inp.u16()
            val sy = inp.u16()
            val sw = inp.u16()
            val sh = inp.u16()
            fb.fillRect(x + sx, y + sy, minOf(sw, w - sx), minOf(sh, h - sy), color)
        }
    }

    fun hextile(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        checkSize(w, h)
        var bg = Framebuffer.OPAQUE_BLACK
        var fg = 0xFFFFFFFF.toInt()
        val raw = byteBuf(16 * 16 * 4)
        var ty = y
        while (ty < y + h) {
            val th = minOf(16, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(16, x + w - tx)
                val sub = inp.u8()
                if (sub and HEX_RAW != 0) {
                    inp.readFully(raw, 0, tw * th * 4)
                    Pixels.convert32(raw, 0, tile, 0, tw * th)
                } else {
                    if (sub and HEX_BG != 0) bg = inp.pixel()
                    if (sub and HEX_FG != 0) fg = inp.pixel()
                    java.util.Arrays.fill(tile, 0, tw * th, bg)
                    if (sub and HEX_ANY_SUBRECTS != 0) {
                        val n = inp.u8()
                        val coloured = sub and HEX_SUBRECTS_COLOURED != 0
                        for (i in 0 until n) {
                            val color = if (coloured) inp.pixel() else fg
                            val xy = inp.u8()
                            val wh = inp.u8()
                            val sx = xy ushr 4
                            val sy = xy and 15
                            val ex = minOf(tw, sx + (wh ushr 4) + 1)
                            val ey = minOf(th, sy + (wh and 15) + 1)
                            for (r in sy until ey) {
                                java.util.Arrays.fill(tile, r * tw + minOf(sx, ex), r * tw + ex, color)
                            }
                        }
                    }
                }
                fb.putRect(tx, ty, tw, th, tile)
                tx += 16
            }
            ty += 16
        }
    }

    private fun zCpixel(): Int {
        val b0 = zrle.u8()
        val b1 = zrle.u8()
        val b2 = zrle.u8()
        return Pixels.bgr(b0, b1, b2)
    }

    private fun zRunLength(): Int {
        var run = 1
        var b: Int
        do {
            b = zrle.u8()
            run += b
        } while (b == 255)
        return run
    }

    fun zrle(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        checkSize(w, h)
        val len = inp.u32()
        if (len > MAX_COMPRESSED) throw IOException("ZRLE data too large")
        val data = byteBuf(len.toInt())
        inp.readFully(data, 0, len.toInt())
        zrle.setInput(data, len.toInt())
        val palette = IntArray(128)
        var ty = y
        while (ty < y + h) {
            val th = minOf(64, y + h - ty)
            var tx = x
            while (tx < x + w) {
                val tw = minOf(64, x + w - tx)
                val n = tw * th
                val sub = zrle.u8()
                when {
                    sub == 0 -> for (i in 0 until n) tile[i] = zCpixel()
                    sub == 1 -> java.util.Arrays.fill(tile, 0, n, zCpixel())
                    sub in 2..16 -> {
                        for (i in 0 until sub) palette[i] = zCpixel()
                        val bits = if (sub == 2) 1 else if (sub <= 4) 2 else 4
                        val mask = (1 shl bits) - 1
                        for (r in 0 until th) {
                            var shift = 8
                            var byte = 0
                            for (c in 0 until tw) {
                                if (shift == 0 || shift == 8) {
                                    byte = zrle.u8()
                                    shift = 8
                                }
                                shift -= bits
                                val idx = (byte ushr shift) and mask
                                tile[r * tw + c] = palette[minOf(idx, sub - 1)]
                            }
                        }
                    }
                    sub == 128 -> {
                        var i = 0
                        while (i < n) {
                            val color = zCpixel()
                            val run = minOf(zRunLength(), n - i)
                            java.util.Arrays.fill(tile, i, i + run, color)
                            i += run
                        }
                    }
                    sub >= 130 -> {
                        val size = sub - 128
                        for (p in 0 until size) palette[p] = zCpixel()
                        var i = 0
                        while (i < n) {
                            val idx = zrle.u8()
                            val color = palette[minOf(idx and 127, size - 1)]
                            val run = if (idx and 128 != 0) minOf(zRunLength(), n - i) else 1
                            java.util.Arrays.fill(tile, i, i + run, color)
                            i += run
                        }
                    }
                    else -> throw IOException("bad ZRLE subencoding $sub")
                }
                fb.putRect(tx, ty, tw, th, tile)
                tx += 64
            }
            ty += 64
        }
    }

    private fun compactLength(inp: RfbInput): Int {
        var b = inp.u8()
        var len = b and 0x7F
        if (b and 0x80 != 0) {
            b = inp.u8()
            len = len or ((b and 0x7F) shl 7)
            if (b and 0x80 != 0) {
                b = inp.u8()
                len = len or (b shl 14)
            }
        }
        return len
    }

    private fun tightData(inp: RfbInput, size: Int, stream: Int): ByteArray {
        val out = ByteArray(size)
        if (size < TIGHT_MIN_TO_COMPRESS) {
            inp.readFully(out, 0, size)
        } else {
            val len = compactLength(inp)
            val compressed = inp.bytes(len)
            tight[stream].setInput(compressed, len)
            tight[stream].readFully(out, 0, size)
        }
        return out
    }

    fun tight(inp: RfbInput, x: Int, y: Int, w: Int, h: Int) {
        checkSize(w, h)
        val ctrl = inp.u8()
        for (i in 0 until 4) if (ctrl and (1 shl i) != 0) tight[i].reset()
        val comp = ctrl ushr 4
        when {
            comp == TIGHT_FILL -> {
                val r = inp.u8()
                val g = inp.u8()
                val b = inp.u8()
                fb.fillRect(x, y, w, h, Pixels.rgb(r, g, b))
            }
            comp == TIGHT_JPEG -> {
                val data = inp.bytes(compactLength(inp))
                val px = jpeg?.decode(data, w, h)
                if (px != null && px.size >= w * h) fb.putRect(x, y, w, h, px)
            }
            comp > TIGHT_JPEG -> throw IOException("unsupported Tight compression $comp")
            else -> {
                val stream = comp and 3
                val filter = if (comp and 4 != 0) inp.u8() else TIGHT_FILTER_COPY
                val n = w * h
                val px = intBuf(n)
                when (filter) {
                    TIGHT_FILTER_COPY -> {
                        val d = tightData(inp, n * 3, stream)
                        for (i in 0 until n) {
                            px[i] = Pixels.rgb(
                                d[i * 3].toInt() and 0xFF,
                                d[i * 3 + 1].toInt() and 0xFF,
                                d[i * 3 + 2].toInt() and 0xFF
                            )
                        }
                    }
                    TIGHT_FILTER_PALETTE -> {
                        val colors = inp.u8() + 1
                        val palette = IntArray(colors)
                        for (i in 0 until colors) palette[i] = Pixels.rgb(inp.u8(), inp.u8(), inp.u8())
                        if (colors == 2) {
                            val rowBytes = (w + 7) / 8
                            val d = tightData(inp, rowBytes * h, stream)
                            for (r in 0 until h) {
                                for (c in 0 until w) {
                                    val bit = (d[r * rowBytes + c / 8].toInt() ushr (7 - c % 8)) and 1
                                    px[r * w + c] = palette[bit]
                                }
                            }
                        } else {
                            val d = tightData(inp, n, stream)
                            for (i in 0 until n) px[i] = palette[minOf(d[i].toInt() and 0xFF, colors - 1)]
                        }
                    }
                    TIGHT_FILTER_GRADIENT -> {
                        val d = tightData(inp, n * 3, stream)
                        val prev = IntArray(w * 3)
                        val cur = IntArray(w * 3)
                        for (r in 0 until h) {
                            for (c in 0 until w) {
                                for (k in 0 until 3) {
                                    val above = prev[c * 3 + k]
                                    val left = if (c > 0) cur[(c - 1) * 3 + k] else 0
                                    val aboveLeft = if (c > 0) prev[(c - 1) * 3 + k] else 0
                                    val predicted = (left + above - aboveLeft).coerceIn(0, 255)
                                    cur[c * 3 + k] = (predicted + d[(r * w + c) * 3 + k]) and 0xFF
                                }
                                px[r * w + c] = Pixels.rgb(cur[c * 3], cur[c * 3 + 1], cur[c * 3 + 2])
                            }
                            System.arraycopy(cur, 0, prev, 0, cur.size)
                        }
                    }
                    else -> throw IOException("bad Tight filter $filter")
                }
                fb.putRect(x, y, w, h, px)
            }
        }
    }

    /** Rich cursor pseudo-encoding: pixels plus a 1bpp transparency mask. */
    fun cursor(inp: RfbInput, hotX: Int, hotY: Int, w: Int, h: Int): RemoteCursor? {
        checkSize(w, h)
        if (w == 0 || h == 0) return null
        val raw = inp.bytes(w * h * 4)
        val maskRow = (w + 7) / 8
        val mask = inp.bytes(maskRow * h)
        val px = IntArray(w * h)
        Pixels.convert32(raw, 0, px, 0, w * h)
        for (r in 0 until h) {
            for (c in 0 until w) {
                val visible = (mask[r * maskRow + c / 8].toInt() ushr (7 - c % 8)) and 1
                if (visible == 0) px[r * w + c] = 0
            }
        }
        return RemoteCursor(w, h, hotX.coerceIn(0, w - 1), hotY.coerceIn(0, h - 1), px)
    }

    fun release() {
        zrle.end()
        tight.forEach { it.end() }
    }

    companion object {
        const val MAX_RECT_PIXELS = 16384L * 16384L
        const val MAX_COMPRESSED = 256L * 1024 * 1024
        private const val HEX_RAW = 1
        private const val HEX_BG = 2
        private const val HEX_FG = 4
        private const val HEX_ANY_SUBRECTS = 8
        private const val HEX_SUBRECTS_COLOURED = 16
        private const val TIGHT_FILL = 8
        private const val TIGHT_JPEG = 9
        private const val TIGHT_FILTER_COPY = 0
        private const val TIGHT_FILTER_PALETTE = 1
        private const val TIGHT_FILTER_GRADIENT = 2
        private const val TIGHT_MIN_TO_COMPRESS = 12
    }
}
