package tech.anl.library.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DirentsTest {

    @Test
    fun `dirent64 layout and reclen alignment`() {
        // 19 + 1 + 1 = 21 -> 24
        val rec = Dirents.record64(Dirents.Entry("a", Dirents.DT_REG, 42), 7)
        assertEquals(24, rec.size)
        val b = ByteBuffer.wrap(rec).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(42L, b.getLong(0))
        assertEquals(7L, b.getLong(8))
        assertEquals(24, b.getShort(16).toInt())
        assertEquals(Dirents.DT_REG, rec[18].toInt())
        assertEquals('a'.code, rec[19].toInt())
        assertEquals(0, rec[20].toInt())

        // 19 + 5 + 1 = 25 -> 32 ; 19 + 4 + 1 = 24 stays 24
        assertEquals(32, Dirents.record64(Dirents.Entry("abcde", Dirents.DT_DIR, 1), 1).size)
        assertEquals(24, Dirents.record64(Dirents.Entry("abcd", Dirents.DT_DIR, 1), 1).size)
        for (len in 1..300) {
            val size = Dirents.record64(Dirents.Entry("x".repeat(len), Dirents.DT_REG, 1), 1).size
            assertEquals(0, size % 8)
            assertTrue(size >= 19 + len + 1)
        }
    }

    @Test
    fun `d_type values`() {
        assertEquals(0, Dirents.DT_UNKNOWN)
        assertEquals(4, Dirents.DT_DIR)
        assertEquals(8, Dirents.DT_REG)
        assertEquals(10, Dirents.DT_LNK)
        assertEquals(Dirents.DT_DIR, Dirents.record64(Dirents.Entry("d", Dirents.DT_DIR, 1), 1)[18].toInt())
    }

    @Test
    fun `legacy dirent puts d_type in the last byte`() {
        // 64-bit long: 8+8+2 = 18, + "ab" + NUL + type = 22 -> 24
        val rec64 = Dirents.recordLegacy(Dirents.Entry("ab", Dirents.DT_DIR, 5), 3, 8)
        assertEquals(24, rec64.size)
        val b = ByteBuffer.wrap(rec64).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(5L, b.getLong(0))
        assertEquals(3L, b.getLong(8))
        assertEquals(24, b.getShort(16).toInt())
        assertEquals('a'.code, rec64[18].toInt())
        assertEquals(0, rec64[20].toInt())
        assertEquals(Dirents.DT_DIR, rec64[23].toInt())

        // 32-bit long: 4+4+2 = 10, + "abc" + NUL + type = 15 -> 16
        val rec32 = Dirents.recordLegacy(Dirents.Entry("abc", Dirents.DT_REG, 5), 3, 4)
        assertEquals(16, rec32.size)
        val c = ByteBuffer.wrap(rec32).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(5, c.getInt(0))
        assertEquals(3, c.getInt(4))
        assertEquals(16, c.getShort(8).toInt())
        assertEquals(Dirents.DT_REG, rec32[15].toInt())
    }

    @Test
    fun `pages never exceed what PRoot reads and keep every entry`() {
        val entries = Dirents.withDotEntries(
            "Documents",
            (1..200).map { Dirents.Entry("file-number-$it.txt", Dirents.DT_REG, it.toLong()) },
        )
        val pages = Dirents.paginate(entries) { e, off -> Dirents.record64(e, off) }
        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.size <= DroidFilesProtocol.GETDENTS_READ_LIMIT })

        // walk every page and collect names + d_off
        val names = mutableListOf<String>()
        val offs = mutableListOf<Long>()
        for (page in pages) {
            val b = ByteBuffer.wrap(page).order(ByteOrder.LITTLE_ENDIAN)
            var pos = 0
            while (pos < page.size) {
                val reclen = b.getShort(pos + 16).toInt()
                assertTrue(reclen > 0)
                offs += b.getLong(pos + 8)
                var end = pos + 19
                while (page[end] != 0.toByte()) end++
                names += String(page, pos + 19, end - pos - 19)
                pos += reclen
            }
            assertEquals(page.size, pos)
        }
        assertEquals(entries.map { it.name }, names)
        assertEquals((1..entries.size).map { it.toLong() }, offs)
        assertEquals(listOf(".", ".."), names.take(2))
    }

    @Test
    fun `empty directory still yields dot entries`() {
        val pages = Dirents.paginate(Dirents.withDotEntries("Music", emptyList())) { e, o -> Dirents.record64(e, o) }
        assertEquals(1, pages.size)
        assertEquals(48, pages[0].size)
    }

    @Test
    fun `inodes are non-zero`() {
        assertTrue(Dirents.inodeFor("") != 0L)
        assertTrue(Dirents.inodeFor("Documents/a") > 0)
    }
}
