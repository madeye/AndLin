package tech.anl.vnc.rfb

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.util.zip.DataFormatException
import java.util.zip.Inflater

/** Big-endian reader for the RFB stream. */
class RfbInput(stream: InputStream) {
    private val input = java.io.DataInputStream(java.io.BufferedInputStream(stream, 65536))

    fun u8(): Int = input.readUnsignedByte()
    fun u16(): Int = input.readUnsignedShort()
    fun s32(): Int = input.readInt()
    fun u32(): Long = input.readInt().toLong() and 0xFFFFFFFFL
    fun readFully(b: ByteArray, off: Int = 0, len: Int = b.size) = input.readFully(b, off, len)
    fun bytes(n: Int): ByteArray = ByteArray(n).also { input.readFully(it) }
    fun skip(n: Long) {
        var left = n
        while (left > 0) {
            val s = input.skip(left)
            if (s <= 0) {
                input.readUnsignedByte()
                left--
            } else {
                left -= s
            }
        }
    }

    /** Reads a 32-bit little-endian pixel in our BGRX format as opaque ARGB. */
    fun pixel(): Int {
        val b0 = input.readUnsignedByte()
        val b1 = input.readUnsignedByte()
        val b2 = input.readUnsignedByte()
        input.readUnsignedByte()
        return Pixels.bgr(b0, b1, b2)
    }
}

object Pixels {
    fun bgr(b: Int, g: Int, r: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    fun rgb(r: Int, g: Int, b: Int): Int = (0xFF shl 24) or (r shl 16) or (g shl 8) or b

    /** Converts [count] 4-byte BGRX pixels from [src] into [dst]. */
    fun convert32(src: ByteArray, srcOff: Int, dst: IntArray, dstOff: Int, count: Int) {
        var s = srcOff
        for (i in 0 until count) {
            dst[dstOff + i] = (0xFF shl 24) or
                ((src[s + 2].toInt() and 0xFF) shl 16) or
                ((src[s + 1].toInt() and 0xFF) shl 8) or
                (src[s].toInt() and 0xFF)
            s += 4
        }
    }
}

/**
 * A persistent zlib stream fed rectangle by rectangle, as used by ZRLE and
 * Tight. Decompressed output is buffered so small reads are cheap.
 */
class ZlibStream {
    private val inflater = Inflater()
    private val buf = ByteArray(65536)
    private var pos = 0
    private var limit = 0

    fun reset() {
        inflater.reset()
        pos = 0
        limit = 0
    }

    fun setInput(data: ByteArray, len: Int = data.size) {
        inflater.setInput(data, 0, len)
    }

    private fun fill() {
        pos = 0
        limit = 0
        try {
            while (limit == 0) {
                val n = inflater.inflate(buf, 0, buf.size)
                if (n == 0) {
                    if (inflater.needsInput() || inflater.finished() || inflater.needsDictionary()) {
                        throw EOFException("zlib data exhausted")
                    }
                }
                limit = n
            }
        } catch (e: DataFormatException) {
            throw IOException("bad zlib data", e)
        }
    }

    fun u8(): Int {
        if (pos >= limit) fill()
        return buf[pos++].toInt() and 0xFF
    }

    fun readFully(dst: ByteArray, off: Int, len: Int) {
        var done = 0
        while (done < len) {
            if (pos >= limit) fill()
            val n = minOf(len - done, limit - pos)
            System.arraycopy(buf, pos, dst, off + done, n)
            pos += n
            done += n
        }
    }

    fun end() = inflater.end()
}
