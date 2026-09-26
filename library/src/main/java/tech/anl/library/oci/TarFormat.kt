package tech.anl.library.oci

import java.io.EOFException
import java.io.InputStream
import java.io.OutputStream

/** The kinds of tar entries, independent of the ustar/GNU typeflag spelling. */
enum class TarEntryType { FILE, HARDLINK, SYMLINK, CHAR_DEVICE, BLOCK_DEVICE, DIRECTORY, FIFO, OTHER }

/**
 * One logical tar entry, with GNU long-name/long-link and PAX overrides already applied.
 *
 * [size] is the number of data bytes that follow the header. Like Go's archive/tar (which writes
 * OCI layers) it is forced to 0 for header-only types (links, directories, devices, FIFOs).
 */
data class TarEntry(
    val path: String,
    val type: TarEntryType,
    val linkTarget: String,
    val size: Long,
    val mode: Int,
    val mtime: Long,
)

/** Header field parsing shared by the reader and tests. */
internal object TarHeaders {
    const val BLOCK = 512
    private const val MAX_META_SIZE = 1 shl 20

    /**
     * Parses a numeric header field: octal digits (optionally padded with spaces/NULs) or, when the
     * high bit of the first byte is set, GNU base-256 big-endian binary.
     */
    fun parseNumeric(block: ByteArray, offset: Int, length: Int): Long {
        val first = block[offset].toInt() and 0xff
        if (first and 0x80 != 0) {
            if (first == 0xff) throw OciException("Negative base-256 value in tar header")
            var value = (first and 0x7f).toLong()
            for (i in offset + 1 until offset + length) {
                if (value > (Long.MAX_VALUE shr 8)) throw OciException("Base-256 value overflows in tar header")
                value = (value shl 8) or (block[i].toLong() and 0xff)
            }
            return value
        }
        var i = offset
        val end = offset + length
        while (i < end && (block[i] == ' '.code.toByte() || block[i] == 0.toByte())) i++
        var value = 0L
        while (i < end) {
            val c = block[i].toInt()
            if (c == 0 || c == ' '.code) break
            if (c < '0'.code || c > '7'.code) throw OciException("Invalid octal digit in tar header")
            value = value * 8 + (c - '0'.code)
            i++
        }
        return value
    }

    /** A NUL-terminated string field. */
    fun parseString(block: ByteArray, offset: Int, length: Int): String {
        var end = offset
        while (end < offset + length && block[end] != 0.toByte()) end++
        return String(block, offset, end - offset, Charsets.UTF_8)
    }

    fun isZeroBlock(block: ByteArray) = block.all { it == 0.toByte() }

    /** Validates the header checksum; both unsigned and (historic) signed sums are accepted. */
    fun checksumMatches(block: ByteArray): Boolean {
        val stored = parseNumeric(block, 148, 8)
        var unsigned = 0L
        var signed = 0L
        for (i in 0 until BLOCK) {
            val b = if (i in 148 until 156) ' '.code.toByte() else block[i]
            unsigned += b.toInt() and 0xff
            signed += b.toInt()
        }
        return stored == unsigned || stored == signed
    }

    /** Parses PAX extended header records (`"<len> <key>=<value>\n"`, len counts bytes). */
    fun parsePax(data: ByteArray): Map<String, String> {
        val records = HashMap<String, String>()
        var pos = 0
        while (pos < data.size) {
            if (data[pos] == 0.toByte()) break
            var space = pos
            while (space < data.size && data[space] != ' '.code.toByte()) space++
            val length = String(data, pos, space - pos, Charsets.US_ASCII).toIntOrNull()
                ?: throw OciException("Malformed PAX header record")
            val end = pos + length
            if (length <= 0 || end > data.size || data[end - 1] != '\n'.code.toByte()) {
                throw OciException("Malformed PAX header record")
            }
            val record = String(data, space + 1, end - space - 2, Charsets.UTF_8)
            val eq = record.indexOf('=')
            if (eq > 0) records[record.substring(0, eq)] = record.substring(eq + 1)
            pos = end
        }
        return records
    }

    fun checkMetaSize(size: Long) {
        if (size > MAX_META_SIZE) throw OciException("Tar metadata header too large ($size bytes)")
    }
}

/**
 * Streaming tar reader that understands ustar, GNU ('L'/'K' long names, base-256 numbers) and
 * PAX ('x' per-entry and 'g' global headers). It never buffers entry data: call [next] for the
 * header, then [readData] (or nothing — the remainder is skipped on the following [next]).
 */
class TarStreamReader(private val input: InputStream) {
    private val block = ByteArray(TarHeaders.BLOCK)
    private val globalPax = HashMap<String, String>()
    private var remaining = 0L
    private var padding = 0
    private var ended = false

    /** Returns the next entry, or null at the end-of-archive marker (or a clean EOF). */
    fun next(): TarEntry? {
        skipData()
        if (ended) return null

        var longName: String? = null
        var longLink: String? = null
        var pax: Map<String, String> = emptyMap()
        var sawMeta = false

        while (true) {
            if (!readBlock()) {
                if (sawMeta) throw OciException("Tar stream ends inside an entry's extended headers")
                ended = true
                return null
            }
            if (TarHeaders.isZeroBlock(block)) {
                // One zero block is enough to know the archive is over; the second is optional.
                ended = true
                return null
            }
            if (!TarHeaders.checksumMatches(block)) throw OciException("Corrupt tar header (bad checksum)")

            val flag = block[156].toInt().toChar()
            val headerSize = TarHeaders.parseNumeric(block, 124, 12)
            when (flag) {
                'L', 'K', 'x', 'g' -> {
                    TarHeaders.checkMetaSize(headerSize)
                    val data = readMeta(headerSize.toInt())
                    when (flag) {
                        'L' -> longName = cString(data)
                        'K' -> longLink = cString(data)
                        'x' -> pax = TarHeaders.parsePax(data)
                        'g' -> globalPax.putAll(TarHeaders.parsePax(data))
                    }
                    sawMeta = true
                }
                else -> return buildEntry(flag, headerSize, longName, longLink, pax)
            }
        }
    }

    /** Reads entry data; returns -1 once the current entry's data is exhausted. */
    fun readData(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size): Int {
        if (remaining == 0L) return -1
        val n = input.read(buffer, offset, minOf(length.toLong(), remaining).toInt())
        if (n < 0) throw EOFException("Tar stream truncated inside entry data")
        remaining -= n
        return n
    }

    private fun buildEntry(
        flag: Char,
        headerSize: Long,
        longName: String?,
        longLink: String?,
        pax: Map<String, String>,
    ): TarEntry {
        if (flag == 'S' || pax.keys.any { it.startsWith("GNU.sparse") } || globalPax.keys.any { it.startsWith("GNU.sparse") }) {
            throw OciException("Sparse files in image layers are not supported")
        }

        // Only POSIX ustar ("ustar\0") has a prefix field; GNU ("ustar  ") reuses those bytes.
        val isPosixUstar = block[257] == 'u'.code.toByte() && block[258] == 's'.code.toByte() &&
            block[259] == 't'.code.toByte() && block[260] == 'a'.code.toByte() &&
            block[261] == 'r'.code.toByte() && block[262] == 0.toByte()
        var headerName = TarHeaders.parseString(block, 0, 100)
        if (isPosixUstar) {
            val prefix = TarHeaders.parseString(block, 345, 155)
            if (prefix.isNotEmpty()) headerName = "$prefix/$headerName"
        }

        val path = pax["path"] ?: longName ?: globalPax["path"] ?: headerName
        val linkTarget = pax["linkpath"] ?: longLink ?: globalPax["linkpath"] ?: TarHeaders.parseString(block, 157, 100)
        val size = (pax["size"] ?: globalPax["size"])?.let {
            it.toLongOrNull() ?: throw OciException("Malformed PAX size '$it'")
        } ?: headerSize
        if (size < 0) throw OciException("Negative entry size in tar header")

        var type = when (flag) {
            '0', '\u0000', '7' -> TarEntryType.FILE
            '1' -> TarEntryType.HARDLINK
            '2' -> TarEntryType.SYMLINK
            '3' -> TarEntryType.CHAR_DEVICE
            '4' -> TarEntryType.BLOCK_DEVICE
            '5' -> TarEntryType.DIRECTORY
            '6' -> TarEntryType.FIFO
            else -> TarEntryType.OTHER
        }
        // Pre-POSIX archives mark directories as regular files with a trailing slash.
        if (type == TarEntryType.FILE && path.endsWith("/")) type = TarEntryType.DIRECTORY

        val hasData = type == TarEntryType.FILE || type == TarEntryType.OTHER
        val dataSize = if (hasData) size else 0L
        remaining = dataSize
        padding = ((TarHeaders.BLOCK - dataSize % TarHeaders.BLOCK) % TarHeaders.BLOCK).toInt()

        return TarEntry(
            path = path,
            type = type,
            linkTarget = linkTarget,
            size = dataSize,
            mode = (TarHeaders.parseNumeric(block, 100, 8) and 0xfff).toInt(),
            mtime = TarHeaders.parseNumeric(block, 136, 12),
        )
    }

    private fun skipData() {
        val scratch = ByteArray(8192)
        while (remaining > 0) readData(scratch)
        skipFully(padding.toLong())
        padding = 0
    }

    private fun readMeta(size: Int): ByteArray {
        val data = ByteArray(size)
        readFully(data, size)
        skipFully(((TarHeaders.BLOCK - size % TarHeaders.BLOCK) % TarHeaders.BLOCK).toLong())
        return data
    }

    /** Reads one header block; false on EOF before any byte of it. */
    private fun readBlock(): Boolean {
        var read = 0
        while (read < TarHeaders.BLOCK) {
            val n = input.read(block, read, TarHeaders.BLOCK - read)
            if (n < 0) {
                if (read == 0) return false
                throw EOFException("Tar stream truncated inside a header")
            }
            read += n
        }
        return true
    }

    private fun readFully(buffer: ByteArray, length: Int) {
        var read = 0
        while (read < length) {
            val n = input.read(buffer, read, length - read)
            if (n < 0) throw EOFException("Tar stream truncated")
            read += n
        }
    }

    private fun skipFully(count: Long) {
        if (count <= 0) return
        val scratch = ByteArray(minOf(count, 8192L).toInt())
        var left = count
        while (left > 0) {
            val n = input.read(scratch, 0, minOf(left, scratch.size.toLong()).toInt())
            if (n < 0) throw EOFException("Tar stream truncated")
            left -= n
        }
    }

    private fun cString(data: ByteArray): String {
        val end = data.indexOf(0).let { if (it < 0) data.size else it }
        return String(data, 0, end, Charsets.UTF_8)
    }
}

/**
 * Writes a normalized tar stream: plain ustar headers, with GNU 'L'/'K' records for names or
 * link targets longer than the ustar fields and base-256 sizes past the octal limit.
 *
 * GNU extensions are used instead of PAX because Android's toybox tar only honours `path=` in PAX
 * headers (no `linkpath=`/`size=`), while every tar we care about understands GNU long names.
 */
class TarStreamWriter(private val output: OutputStream) {
    private var remaining = 0L
    private var written = 0L

    fun putEntry(path: String, type: TarEntryType, linkTarget: String = "", size: Long = 0, mode: Int, mtime: Long) {
        check(remaining == 0L) { "Previous entry is incomplete: $remaining bytes missing" }
        require(type == TarEntryType.FILE || size == 0L) { "Only regular files carry data" }

        val name = if (type == TarEntryType.DIRECTORY && !path.endsWith("/")) "$path/" else path
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val linkBytes = linkTarget.toByteArray(Charsets.UTF_8)
        if (nameBytes.size > 100) writeLongRecord('L', nameBytes)
        if (linkBytes.size > 100) writeLongRecord('K', linkBytes)

        val flag = when (type) {
            TarEntryType.FILE -> '0'
            TarEntryType.HARDLINK -> '1'
            TarEntryType.SYMLINK -> '2'
            TarEntryType.CHAR_DEVICE -> '3'
            TarEntryType.BLOCK_DEVICE -> '4'
            TarEntryType.DIRECTORY -> '5'
            TarEntryType.FIFO -> '6'
            TarEntryType.OTHER -> throw IllegalArgumentException("Cannot write entries of unknown type")
        }
        writeHeader(nameBytes, linkBytes, flag, size, mode, mtime)
        remaining = size
    }

    fun write(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size) {
        check(length <= remaining) { "Writing past the end of the entry" }
        output.write(buffer, offset, length)
        remaining -= length
        written += length
    }

    /** Pads the current entry's data to a block boundary. */
    fun closeEntry() {
        check(remaining == 0L) { "Entry is incomplete: $remaining bytes missing" }
        padTo512()
    }

    /** Writes the end-of-archive marker and flushes (does not close the stream). */
    fun finish() {
        closeEntry()
        output.write(ByteArray(TarHeaders.BLOCK * 2))
        output.flush()
    }

    private fun writeLongRecord(flag: Char, value: ByteArray) {
        val data = value + 0 // NUL-terminated, as GNU tar writes it
        writeHeader("././@LongLink".toByteArray(), ByteArray(0), flag, data.size.toLong(), 0, 0)
        output.write(data)
        written = data.size.toLong()
        padTo512()
    }

    private fun writeHeader(name: ByteArray, link: ByteArray, flag: Char, size: Long, mode: Int, mtime: Long) {
        val header = ByteArray(TarHeaders.BLOCK)
        name.copyInto(header, 0, 0, minOf(name.size, 100))
        writeOctal(header, 100, 8, (mode and 0xfff).toLong())
        writeOctal(header, 108, 8, 0) // uid: ownership is never restored by an app-level tar
        writeOctal(header, 116, 8, 0) // gid
        writeNumber(header, 124, 12, size)
        writeNumber(header, 136, 12, mtime.coerceAtLeast(0))
        header[156] = flag.code.toByte()
        link.copyInto(header, 157, 0, minOf(link.size, 100))
        "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(header, 257)
        writeOctal(header, 329, 8, 0)
        writeOctal(header, 337, 8, 0)

        for (i in 148 until 156) header[i] = ' '.code.toByte()
        val sum = header.sumOf { it.toInt() and 0xff }
        val checksum = Integer.toOctalString(sum).padStart(6, '0').toByteArray(Charsets.US_ASCII)
        checksum.copyInto(header, 148)
        header[154] = 0
        header[155] = ' '.code.toByte()
        output.write(header)
        written = 0
    }

    private fun padTo512() {
        val pad = ((TarHeaders.BLOCK - written % TarHeaders.BLOCK) % TarHeaders.BLOCK).toInt()
        if (pad > 0) output.write(ByteArray(pad))
        written = 0
    }

    /** Octal with a trailing NUL, or GNU base-256 when it doesn't fit. */
    private fun writeNumber(header: ByteArray, offset: Int, length: Int, value: Long) {
        if (value < 1L shl (3 * (length - 1))) {
            writeOctal(header, offset, length, value)
        } else {
            var v = value
            for (i in offset + length - 1 downTo offset + 1) {
                header[i] = (v and 0xff).toByte()
                v = v shr 8
            }
            header[offset] = 0x80.toByte()
        }
    }

    private fun writeOctal(header: ByteArray, offset: Int, length: Int, value: Long) {
        val digits = java.lang.Long.toOctalString(value).padStart(length - 1, '0')
        require(digits.length == length - 1) { "Value $value does not fit a $length-byte octal field" }
        digits.toByteArray(Charsets.US_ASCII).copyInto(header, offset)
        header[offset + length - 1] = 0
    }
}
