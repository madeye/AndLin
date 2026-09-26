package tech.anl.library.proot

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class DroidFilesProtocolTest {

    @Test
    fun `request sizes match sock_req_t on both word sizes`() {
        assertEquals(8 + 4096 + 4096 + 5 * 8, DroidFilesProtocol.requestSize(8))
        assertEquals(8240, DroidFilesProtocol.requestSize(8))
        assertEquals(8216, DroidFilesProtocol.requestSize(4))
    }

    private fun rawRequest(wordSize: Int, sysCall: Long, path: String, args: LongArray): ByteArray {
        val buf = ByteBuffer.allocate(DroidFilesProtocol.requestSize(wordSize)).order(ByteOrder.LITTLE_ENDIAN)
        fun word(v: Long) = if (wordSize == 8) buf.putLong(v) else buf.putInt(v.toInt())
        word(sysCall)
        val p = path.toByteArray()
        buf.put(p)
        // garbage after the terminator and in new_path, like PRoot's uninitialised stack
        buf.put(0)
        buf.put("junk".toByteArray())
        buf.position(wordSize + 4096)
        buf.put("garbage/new_path".toByteArray())
        buf.position(wordSize + 8192)
        args.forEach { word(it) }
        return buf.array()
    }

    @Test
    fun `parses a 64-bit request`() {
        val bytes = rawRequest(8, 1, "/sdcard/Documents/a.txt", longArrayOf(0x241, 0x1a4, -1, 7, 9))
        val req = DroidFilesProtocol.parseRequest(bytes, 8)
        assertEquals(DroidFilesProtocol.Op.OPENAT, req.sysCall)
        assertEquals("/sdcard/Documents/a.txt", req.path)
        assertEquals(0x241, req.flags)
        assertEquals(0x1a4, req.mode)
        assertEquals(-1L, req.sysargs[2])
    }

    @Test
    fun `parses a 32-bit request with unsigned words`() {
        val bytes = rawRequest(4, 8, "/sdcard/Music", longArrayOf(3, 0, 0xffffffffL, 0, 0))
        val req = DroidFilesProtocol.parseRequest(bytes, 4)
        assertEquals(DroidFilesProtocol.Op.GETDENTS64, req.sysCall)
        assertEquals("/sdcard/Music", req.path)
        assertEquals(3L, req.sysargs[0])
        assertEquals(0xffffffffL, req.sysargs[2])
    }

    @Test
    fun `encode and parse round trip`() {
        for (w in listOf(4, 8)) {
            val req = DroidFilesRequest(9, "/sdcard/Pictures/x/ü.png", "", longArrayOf(1, 2, 3, 4, 5))
            val back = DroidFilesProtocol.parseRequest(DroidFilesProtocol.encodeRequest(req, w), w)
            assertEquals(req.path, back.path)
            assertArrayEquals(req.sysargs, back.sysargs)
        }
    }

    @Test
    fun `status words are little endian native words`() {
        assertArrayEquals(byteArrayOf(13, 0, 0, 0, 0, 0, 0, 0), DroidFilesProtocol.encodeStatus(13, 8))
        assertArrayEquals(byteArrayOf(1, 0, 0, 0), DroidFilesProtocol.encodeStatus(1, 4))
    }

    @Test
    fun `open flags map to SAF modes`() {
        fun mode(flags: Int, wSafe: Boolean = true) = OpenFlags.plan(flags, isX86 = false, wSafe = wSafe).mode
        val o = OpenFlags
        assertEquals("r", mode(o.O_RDONLY))
        assertEquals("r", mode(o.O_RDONLY or o.O_TRUNC))
        assertEquals("w", mode(o.O_WRONLY))
        assertEquals("rw", mode(o.O_WRONLY, wSafe = false))
        assertEquals("wt", mode(o.O_WRONLY or o.O_CREAT or o.O_TRUNC))
        assertEquals("wa", mode(o.O_WRONLY or o.O_APPEND))
        assertEquals("rw", mode(o.O_RDWR))
        assertEquals("rwt", mode(o.O_RDWR or o.O_TRUNC))

        val rwAppend = OpenFlags.plan(o.O_RDWR or o.O_APPEND, isX86 = false)
        assertEquals("rw", rwAppend.mode)
        assertTrue(rwAppend.appendViaFcntl)
        assertFalse(OpenFlags.plan(o.O_WRONLY or o.O_APPEND, false).appendViaFcntl)

        val creat = OpenFlags.plan(o.O_CREAT or o.O_WRONLY or o.O_TRUNC, false)
        assertTrue(creat.create && creat.write && !creat.exclusive)
        assertTrue(OpenFlags.plan(o.O_CREAT or o.O_EXCL or o.O_WRONLY, false).exclusive)
        assertFalse(OpenFlags.plan(o.O_EXCL, false).exclusive)
    }

    @Test
    fun `O_DIRECTORY differs between arm and x86`() {
        assertTrue(OpenFlags.plan(0x4000, isX86 = false).directory)
        assertFalse(OpenFlags.plan(0x4000, isX86 = true).directory)
        assertTrue(OpenFlags.plan(0x10000, isX86 = true).directory)
        assertFalse(OpenFlags.plan(0x10000, isX86 = false).directory)
    }

    @Test
    fun `guest paths map to volume relative paths`() {
        val m = GuestPathMapper()
        assertEquals("Documents/a/b.txt", m.toRelative("/sdcard/Documents/a/b.txt"))
        assertEquals("Documents", m.toRelative("/sdcard/Documents/"))
        assertEquals("Documents/b", m.toRelative("/sdcard//Documents/./a/../b"))
        assertEquals("Music/x", m.toRelative("/storage/emulated/0/Music/x"))
        assertEquals("Music/x", m.toRelative("/storage/internal/Music/x"))
        assertNull(m.toRelative("/sdcard"))
        assertNull(m.toRelative("/sdcard/"))
        assertNull(m.toRelative("/sdcardX/Documents"))
        assertNull(m.toRelative("/sdcard/../etc/passwd"))
        assertNull(m.toRelative("/root/file"))
        assertNull(m.toRelative("relative/path"))
        assertEquals("Documents", GuestPathMapper.topOf("Documents/a/b"))
        assertEquals("Documents/a", GuestPathMapper.parentOf("Documents/a/b"))
        assertNull(GuestPathMapper.parentOf("Documents"))
        assertEquals("b", GuestPathMapper.nameOf("Documents/a/b"))
    }

    @Test
    fun `grant roots and document ids`() {
        assertEquals("Documents", DroidFilesGrants.relativeFromDocumentId("primary:Documents"))
        assertEquals("Documents/sub", DroidFilesGrants.relativeFromDocumentId("primary:Documents/sub/"))
        assertEquals("", DroidFilesGrants.relativeFromDocumentId("primary:"))
        assertNull(DroidFilesGrants.relativeFromDocumentId("1234-ABCD:Documents"))
        assertEquals("primary:Documents/x", DroidFilesGrants.documentIdFor("Documents/x"))

        val roots = listOf("Documents", "Documents/deep", "Music")
        assertEquals("Documents/deep", DroidFilesGrants.coveringRoot(roots, "Documents/deep/x"))
        assertEquals("Documents", DroidFilesGrants.coveringRoot(roots, "Documents/deeper"))
        assertEquals("Documents", DroidFilesGrants.coveringRoot(roots, "Documents"))
        assertNull(DroidFilesGrants.coveringRoot(roots, "DocumentsX/a"))
        assertNull(DroidFilesGrants.coveringRoot(roots, "Pictures"))
        assertEquals("", DroidFilesGrants.coveringRoot(listOf(""), "Pictures/a"))
    }
}
