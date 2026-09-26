package tech.anl.library.proot

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Encodes directory listings into the records PRoot copies verbatim into the guest's getdents
 * buffer, split into pages of at most [DroidFilesProtocol.GETDENTS_READ_LIMIT] bytes (PRoot reads
 * the page file into a 1000-byte buffer; one page is returned per guest getdents call).
 */
object Dirents {
    const val DT_UNKNOWN = 0
    const val DT_DIR = 4
    const val DT_REG = 8
    const val DT_LNK = 10

    data class Entry(val name: String, val type: Int, val ino: Long)

    /** offsetof(struct linux_dirent64, d_name) */
    private const val DIRENT64_NAME_OFFSET = 19

    private fun align(value: Int, to: Int) = (value + to - 1) / to * to

    /**
     * `struct linux_dirent64 { u64 d_ino; s64 d_off; u16 d_reclen; u8 d_type; char d_name[]; }`,
     * NUL-terminated name, record padded to a multiple of 8.
     */
    fun record64(entry: Entry, off: Long): ByteArray {
        val name = entry.name.toByteArray(Charsets.UTF_8)
        val reclen = align(DIRENT64_NAME_OFFSET + name.size + 1, 8)
        val buf = ByteBuffer.allocate(reclen).order(ByteOrder.LITTLE_ENDIAN)
        buf.putLong(entry.ino)
        buf.putLong(off)
        buf.putShort(reclen.toShort())
        buf.put(entry.type.toByte())
        buf.put(name)
        return buf.array() // remainder (NUL + padding) is already zero
    }

    /**
     * Legacy `struct linux_dirent { ulong d_ino; ulong d_off; u16 d_reclen; char d_name[]; }`
     * followed by NUL, padding and `d_type` in the record's last byte, padded to `sizeof(long)`
     * of the *guest* ([longSize]).
     */
    fun recordLegacy(entry: Entry, off: Long, longSize: Int): ByteArray {
        require(longSize == 4 || longSize == 8)
        val name = entry.name.toByteArray(Charsets.UTF_8)
        val nameOffset = 2 * longSize + 2
        val reclen = align(nameOffset + name.size + 2, longSize)
        val buf = ByteBuffer.allocate(reclen).order(ByteOrder.LITTLE_ENDIAN)
        if (longSize == 8) {
            buf.putLong(entry.ino)
            buf.putLong(off)
        } else {
            buf.putInt(entry.ino.toInt())
            buf.putInt(off.toInt())
        }
        buf.putShort(reclen.toShort())
        buf.put(name)
        buf.put(reclen - 1, entry.type.toByte())
        return buf.array()
    }

    /**
     * Packs [entries] greedily into pages no larger than [limit] bytes. `d_off` of each record is
     * the 1-based index of the entry after it, like a real filesystem cookie. Always returns at
     * least one page when [entries] is non-empty.
     */
    fun paginate(
        entries: List<Entry>,
        limit: Int = DroidFilesProtocol.GETDENTS_READ_LIMIT,
        encode: (Entry, Long) -> ByteArray,
    ): List<ByteArray> {
        val pages = mutableListOf<ByteArray>()
        var current = java.io.ByteArrayOutputStream()
        entries.forEachIndexed { index, entry ->
            val record = encode(entry, (index + 1).toLong())
            if (record.size > limit) return@forEachIndexed // cannot be delivered; skip
            if (current.size() + record.size > limit) {
                pages += current.toByteArray()
                current = java.io.ByteArrayOutputStream()
            }
            current.write(record)
        }
        if (current.size() > 0) pages += current.toByteArray()
        return pages
    }

    /** A stable, non-zero inode number for a path (some readers skip d_ino == 0). */
    fun inodeFor(path: String): Long {
        val h = path.hashCode().toLong() and 0x7fffffffL
        return if (h == 0L) 1L else h
    }

    /** "." and ".." followed by [children] (sorted by name for a deterministic order). */
    fun withDotEntries(relativeDir: String, children: List<Entry>): List<Entry> {
        val parent = relativeDir.substringBeforeLast('/', "")
        return listOf(
            Entry(".", DT_DIR, inodeFor(relativeDir)),
            Entry("..", DT_DIR, inodeFor(parent)),
        ) + children.sortedBy { it.name }
    }
}
