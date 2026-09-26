package tech.anl.library.oci

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/**
 * Builds raw tar streams for tests, block by block, so tests control exactly which header
 * variants (ustar prefix, GNU long names, PAX, base-256) appear — independent of the production
 * [TarStreamWriter].
 */
class TestTar {
    private val out = ByteArrayOutputStream()

    fun file(path: String, content: String, mode: Int = "644".toInt(8)) = apply {
        val data = content.toByteArray()
        out.write(header(path, '0', size = data.size.toLong(), mode = mode))
        out.write(padded(data))
    }

    fun dir(path: String, mode: Int = "755".toInt(8)) = apply { out.write(header(path, '5', mode = mode)) }

    fun symlink(path: String, target: String) = apply { out.write(header(path, '2', link = target, mode = "777".toInt(8))) }

    fun hardlink(path: String, target: String) = apply { out.write(header(path, '1', link = target)) }

    fun charDevice(path: String) = apply { out.write(header(path, '3', mode = "666".toInt(8))) }

    fun fifo(path: String) = apply { out.write(header(path, '6', mode = "644".toInt(8))) }

    /** An empty OCI whiteout marker file. */
    fun whiteout(path: String) = apply { out.write(header(path, '0')) }

    fun gnuLongName(name: String) = apply { meta('L', name.toByteArray() + 0) }

    fun gnuLongLink(link: String) = apply { meta('K', link.toByteArray() + 0) }

    fun pax(vararg records: Pair<String, String>, global: Boolean = false) =
        apply { meta(if (global) 'g' else 'x', paxRecords(*records)) }

    fun raw(bytes: ByteArray) = apply { out.write(bytes) }

    fun build(endMarker: Boolean = true): ByteArray {
        if (endMarker) out.write(ByteArray(1024))
        return out.toByteArray()
    }

    fun gzip(): ByteArray = gzip(build())

    private fun meta(flag: Char, data: ByteArray) {
        out.write(header("././@LongLink", flag, size = data.size.toLong()))
        out.write(padded(data))
    }

    companion object {
        fun header(
            name: String,
            type: Char,
            size: Long = 0,
            mode: Int = "644".toInt(8),
            link: String = "",
            prefix: String = "",
            magic: String = "ustar\u000000",
            base256Size: Boolean = false,
        ): ByteArray {
            val h = ByteArray(512)
            name.toByteArray().copyInto(h, 0, 0, minOf(100, name.toByteArray().size))
            octal(h, 100, 8, mode.toLong())
            octal(h, 108, 8, 1000)
            octal(h, 116, 8, 1000)
            if (base256Size) {
                var v = size
                for (i in 135 downTo 125) {
                    h[i] = (v and 0xff).toByte()
                    v = v shr 8
                }
                h[124] = 0x80.toByte()
            } else {
                octal(h, 124, 12, size)
            }
            octal(h, 136, 12, 1_700_000_000)
            h[156] = type.code.toByte()
            link.toByteArray().copyInto(h, 157)
            magic.toByteArray().copyInto(h, 257)
            prefix.toByteArray().copyInto(h, 345)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            Integer.toOctalString(sum).padStart(6, '0').toByteArray().copyInto(h, 148)
            h[154] = 0
            return h
        }

        fun padded(data: ByteArray): ByteArray = data.copyOf((data.size + 511) / 512 * 512)

        fun paxRecords(vararg records: Pair<String, String>): ByteArray {
            val out = ByteArrayOutputStream()
            for ((key, value) in records) {
                val body = " $key=$value\n".toByteArray()
                // The length prefix counts itself, so find the fixed point.
                var length = body.size + 1
                while (length.toString().length + body.size != length) length = length.toString().length + body.size
                out.write(length.toString().toByteArray())
                out.write(body)
            }
            return out.toByteArray()
        }

        fun gzip(bytes: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(bytes) }
            return out.toByteArray()
        }

        private fun octal(h: ByteArray, offset: Int, length: Int, value: Long) {
            java.lang.Long.toOctalString(value).padStart(length - 1, '0').toByteArray().copyInto(h, offset)
        }
    }
}
