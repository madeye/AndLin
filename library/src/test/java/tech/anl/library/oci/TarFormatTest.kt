package tech.anl.library.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class TarFormatTest {

    private fun readAll(bytes: ByteArray): List<Pair<TarEntry, String>> {
        val reader = TarStreamReader(ByteArrayInputStream(bytes))
        val result = ArrayList<Pair<TarEntry, String>>()
        while (true) {
            val entry = reader.next() ?: break
            val data = ByteArrayOutputStream()
            val buffer = ByteArray(7) // deliberately tiny, to exercise partial reads
            while (true) {
                val n = reader.readData(buffer)
                if (n < 0) break
                data.write(buffer, 0, n)
            }
            result += entry to data.toString()
        }
        return result
    }

    @Test
    fun `parses plain ustar entries including the prefix field`() {
        val tar = TestTar()
            .dir("usr/")
            .file("usr/bin/hello", "hi there", mode = "4755".toInt(8))
            .symlink("usr/lib64", "/usr/lib")
            .raw(TestTar.header("name.txt", '0', prefix = "some/deep/prefix"))
            .build()

        val entries = readAll(tar)
        assertEquals(listOf("usr/", "usr/bin/hello", "usr/lib64", "some/deep/prefix/name.txt"), entries.map { it.first.path })
        assertEquals(TarEntryType.DIRECTORY, entries[0].first.type)
        val file = entries[1]
        assertEquals(TarEntryType.FILE, file.first.type)
        assertEquals("hi there", file.second)
        assertEquals("4755".toInt(8), file.first.mode)
        assertEquals(1_700_000_000L, file.first.mtime)
        assertEquals(TarEntryType.SYMLINK, entries[2].first.type)
        assertEquals("/usr/lib", entries[2].first.linkTarget)
    }

    @Test
    fun `GNU prefix bytes are not treated as a ustar prefix`() {
        // GNU format stores atime/ctime where ustar has the prefix.
        val tar = TestTar().raw(TestTar.header("plain", '0', prefix = "00000000000", magic = "ustar  \u0000")).build()
        assertEquals("plain", readAll(tar).single().first.path)
    }

    @Test
    fun `applies GNU long names and long link targets`() {
        val longName = "a/" + "x".repeat(150) + "/file"
        val longLink = "/" + "y".repeat(120)
        val tar = TestTar()
            .gnuLongName(longName).file(longName.take(99), "content")
            .gnuLongName("$longName-link").gnuLongLink(longLink).symlink("short", "short-target")
            .build()

        val entries = readAll(tar)
        assertEquals(longName, entries[0].first.path)
        assertEquals("content", entries[0].second)
        assertEquals("$longName-link", entries[1].first.path)
        assertEquals(longLink, entries[1].first.linkTarget)
    }

    @Test
    fun `PAX records override path, linkpath and size, and global headers apply to later entries`() {
        val tar = TestTar()
            .pax("path" to "really/long/ünïcode/path", "mtime" to "1.5")
            .file("truncated", "12345")
            .pax("linkpath" to "../target")
            .hardlink("link", "ignored")
            .pax("path" to "sized", "size" to "3")
            .raw(TestTar.header("sized", '0', size = 999))
            .raw(TestTar.padded("abc".toByteArray()))
            .pax("comment" to "ignored", global = true)
            .file("after-global", "z")
            .build()

        val entries = readAll(tar)
        assertEquals("really/long/ünïcode/path", entries[0].first.path)
        assertEquals("12345", entries[0].second)
        assertEquals("../target", entries[1].first.linkTarget)
        assertEquals(TarEntryType.HARDLINK, entries[1].first.type)
        assertEquals(3L, entries[2].first.size)
        assertEquals("abc", entries[2].second)
        assertEquals("after-global", entries[3].first.path)
    }

    @Test
    fun `parses base-256 numbers`() {
        val header = TestTar.header("big", '0', size = 10L * 1024 * 1024 * 1024, base256Size = true)
        assertEquals(10L * 1024 * 1024 * 1024, TarHeaders.parseNumeric(header, 124, 12))

        // A small base-256 size is legal too, and the entry reads normally.
        val tar = TestTar().raw(TestTar.header("small", '0', size = 4, base256Size = true))
            .raw(TestTar.padded("data".toByteArray())).build()
        assertEquals("data", readAll(tar).single().second)
    }

    @Test
    fun `parses octal fields padded with spaces or NULs`() {
        val field = "  0644 \u0000".toByteArray()
        assertEquals("644".toInt(8).toLong(), TarHeaders.parseNumeric(field, 0, field.size))
    }

    @Test
    fun `header-only types carry no data even if a size is set`() {
        val tar = TestTar().raw(TestTar.header("dir", '5', size = 100)).file("next", "ok").build()
        val entries = readAll(tar)
        assertEquals(0L, entries[0].first.size)
        assertEquals("next", entries[1].first.path)
    }

    @Test
    fun `rejects corrupt headers and truncated data`() {
        val corrupt = TestTar().file("f", "data").build().also { it[10] = 'Z'.code.toByte() }
        expectOci { readAll(corrupt) }

        val truncated = TestTar().file("f", "x".repeat(2000)).build(endMarker = false).copyOf(1200)
        try {
            readAll(truncated)
            fail("expected truncation to be detected")
        } catch (e: java.io.EOFException) {
            // expected
        }
    }

    @Test
    fun `rejects sparse files`() {
        expectOci { readAll(TestTar().raw(TestTar.header("sparse", 'S')).build()) }
    }

    @Test
    fun `a stream without end marker ends cleanly at EOF`() {
        val tar = TestTar().file("only", "x").build(endMarker = false)
        assertEquals(1, readAll(tar).size)
    }

    @Test
    fun `writer output round-trips through the reader, using GNU records for long names`() {
        val out = ByteArrayOutputStream()
        val writer = TarStreamWriter(out)
        val longPath = "deep/" + "d".repeat(200) + "/file"
        val longTarget = "/" + "t".repeat(150)
        writer.putEntry("dir", TarEntryType.DIRECTORY, mode = "755".toInt(8), mtime = 5)
        writer.putEntry(longPath, TarEntryType.FILE, size = 3, mode = "644".toInt(8), mtime = 6)
        writer.write("abc".toByteArray())
        writer.closeEntry()
        writer.putEntry("sym", TarEntryType.SYMLINK, linkTarget = longTarget, mode = "777".toInt(8), mtime = 7)
        writer.finish()

        val bytes = out.toByteArray()
        assertEquals(0, bytes.size % 512)
        // The long name record follows the "dir/" header.
        assertTrue("GNU long name record expected", String(bytes, 512 + 156, 1) == "L")
        val entries = readAll(bytes)
        assertEquals(listOf("dir/", longPath, "sym"), entries.map { it.first.path })
        assertEquals("abc", entries[1].second)
        assertEquals(longTarget, entries[2].first.linkTarget)
        assertEquals(TarEntryType.DIRECTORY, entries[0].first.type)
    }

    @Test
    fun `writer encodes huge sizes in base-256`() {
        val out = ByteArrayOutputStream()
        TarStreamWriter(out).putEntry("huge", TarEntryType.FILE, size = 9L shl 30, mode = 0, mtime = 0)
        val header = out.toByteArray().copyOf(512)
        assertEquals(0x80, header[124].toInt() and 0xff)
        assertEquals(9L shl 30, TarHeaders.parseNumeric(header, 124, 12))
        assertTrue(TarHeaders.checksumMatches(header))
    }

    @Test
    fun `empty stream yields no entries`() {
        assertNull(TarStreamReader(ByteArrayInputStream(ByteArray(0))).next())
    }

    private fun expectOci(block: () -> Unit) {
        try {
            block()
            fail("expected OciException")
        } catch (e: OciException) {
            // expected
        }
    }
}
