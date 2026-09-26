package tech.anl.library.oci

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.PosixFilePermissions

class LayerExtractorTest {
    @get:Rule val temp = TemporaryFolder()

    private val rootfs by lazy { temp.newFolder("rootfs") }
    private val fakeTar = FakeTarProcessFactory()
    private val warnings = ArrayList<String>()

    private fun extract(layer: ByteArray, factory: TarProcessFactory = fakeTar): List<Int> {
        val file = temp.newFile()
        file.writeBytes(layer)
        val percents = ArrayList<Int>()
        runBlocking { LayerExtractor(factory, warn = { warnings += it }).extract(file, rootfs) { percents += it } }
        return percents
    }

    private fun path(p: String) = File(rootfs, p).toPath()
    private fun exists(p: String) = Files.exists(path(p), LinkOption.NOFOLLOW_LINKS)
    private fun mode(p: String) = PosixFilePermissions.toString(Files.getPosixFilePermissions(path(p), LinkOption.NOFOLLOW_LINKS))

    @Test
    fun `extracts files, directories and symlinks, keeping absolute link targets`() {
        val percents = extract(
            TestTar()
                .dir("./")
                .dir("usr/", mode = "555".toInt(8)) // read-only dir: its content must still land
                .file("usr/hello", "hello", mode = "755".toInt(8))
                .file("usr/su", "su", mode = "4755".toInt(8))
                .file("/abs/path", "stripped leading slash")
                .symlink("lib", "/usr/lib")
                .gzip(),
        )

        assertEquals("hello", File(rootfs, "usr/hello").readText())
        assertEquals("rwxr-xr-x", mode("usr/hello"))
        assertEquals("rwxr-xr-x", mode("usr/su"))
        // PRoot's fake root needs the setuid bit (sudo, su), so it reaches tar untouched.
        assertEquals("4755".toInt(8), fakeTar.received.single { it.path == "usr/su" }.mode and 0xfff)
        assertEquals("rwxr-xr-x", mode("usr")) // u+w added
        assertEquals("/usr/lib", Files.readSymbolicLink(path("lib")).toString())
        assertTrue(exists("abs/path"))
        assertEquals(100, percents.last())
    }

    @Test
    fun `whiteouts and opaque directories only remove lower-layer content`() {
        extract(
            TestTar()
                .dir("etc/").file("etc/a", "a").file("etc/b", "b")
                .dir("opt/").dir("opt/x/").file("opt/x/1", "1").dir("opt/x/sub/").file("opt/x/sub/old", "old")
                .dir("keep/").file("keep/k", "k")
                .dir("gone/").file("gone/deep", "deep")
                .gzip(),
        )
        fakeTar.received.clear()

        extract(
            TestTar()
                .whiteout("etc/.wh.a")
                .whiteout("gone/.wh..wh..opq")
                .whiteout(".wh.gone")
                .dir("opt/x/")
                .file("opt/x/3", "3")
                .file("opt/x/sub/new", "new") // no dir entry for sub: ancestors still count as this layer's
                .whiteout("opt/x/.wh..wh..opq") // marker after this layer's entries: they must survive
                .whiteout("nothing/.wh.here")
                .file("etc/a2", "re-added")
                .gzip(),
        )

        assertFalse(exists("etc/a"))
        assertTrue(exists("etc/b"))
        assertTrue(exists("etc/a2"))
        assertFalse(exists("gone"))
        assertTrue(exists("keep/k"))
        assertFalse("opaque dir hides lower content", exists("opt/x/1"))
        assertFalse("opaque applies recursively", exists("opt/x/sub/old"))
        assertTrue(exists("opt/x/3"))
        assertTrue(exists("opt/x/sub/new"))
        assertTrue("no whiteout markers reach tar", fakeTar.received.none { it.path.contains(".wh.") })
        assertFalse(exists("etc/.wh.a"))
    }

    @Test
    fun `a whiteout plus re-creation in the same layer keeps the new content`() {
        extract(TestTar().dir("d/").file("d/old", "old").gzip())
        extract(TestTar().whiteout(".wh.d").dir("d/").file("d/new", "new").gzip())
        assertTrue(exists("d/new"))
        assertFalse(exists("d/old"))
    }

    @Test
    fun `hard links that tar cannot create are replaced by copies`() {
        val failingTar = FakeTarProcessFactory(failHardlinks = true)
        extract(
            TestTar()
                .dir("usr/").dir("usr/bin/")
                .file("usr/bin/perl5.36.0", "#!perl", mode = "755".toInt(8))
                .hardlink("usr/bin/perl", "usr/bin/perl5.36.0")
                .hardlink("usr/bin/perl-again", "./usr/bin/perl") // a link to a link
                .symlink("usr/bin/sym", "perl")
                .hardlink("usr/bin/sym-link", "usr/bin/sym")
                .gzip(),
            failingTar,
        )

        assertEquals("#!perl", File(rootfs, "usr/bin/perl").readText())
        assertEquals("rwxr-xr-x", mode("usr/bin/perl"))
        assertEquals("#!perl", File(rootfs, "usr/bin/perl-again").readText())
        assertEquals("perl", Files.readSymbolicLink(path("usr/bin/sym-link")).toString())
        assertTrue("tar's non-zero exit is tolerated but logged", warnings.any { it.contains("tar exited with 1") })
    }

    @Test
    fun `hard links work normally when tar can create them`() {
        extract(TestTar().file("a", "x").hardlink("b", "a").gzip())
        assertEquals(
            Files.getAttribute(path("a"), "unix:ino"),
            Files.getAttribute(path("b"), "unix:ino"),
        )
    }

    @Test
    fun `device nodes, FIFOs and pseudo filesystem content are never passed to tar`() {
        extract(
            TestTar()
                .dir("dev/").charDevice("dev/null").file("dev/stray", "x")
                .dir("proc/").file("proc/cpuinfo", "x")
                .dir("sys/")
                .dir("tmp/").fifo("tmp/fifo")
                .raw(TestTar.header("tmp/blockdev", '4'))
                .file("devices-are-fine-elsewhere", "ok")
                .gzip(),
        )
        assertEquals(
            listOf("dev", "proc", "sys", "tmp", "devices-are-fine-elsewhere"),
            fakeTar.received.map { it.path.trimEnd('/') },
        )
        assertTrue(exists("dev"))
        assertFalse(exists("dev/null"))
    }

    @Test
    fun `existing hosts, hostname and resolv conf are not overwritten`() {
        File(rootfs, "etc").mkdirs()
        File(rootfs, "etc/hosts").writeText("app hosts")
        extract(
            TestTar().dir("etc/").file("etc/hosts", "image hosts").file("etc/hostname", "image hostname")
                .whiteout("etc/.wh.hosts").gzip(),
        )
        assertEquals("app hosts", File(rootfs, "etc/hosts").readText())
        assertEquals("image hostname", File(rootfs, "etc/hostname").readText())
    }

    @Test
    fun `paths escaping the rootfs are rejected`() {
        val outside = File(temp.root, "outside").apply { mkdirs() }
        File(outside, "victim").writeText("safe")
        File(temp.root, "evil").delete()

        extract(
            TestTar()
                .file("../evil", "x")
                .file("a/../../evil", "x")
                .hardlink("stolen", "../outside/victim")
                .symlink("escape", "../outside") // the symlink itself is fine...
                .file("escape/victim", "overwritten") // ...but writing through it is not
                .whiteout("escape/.wh.victim")
                .whiteout("../outside/.wh.victim")
                .whiteout("x/.wh..") // a whiteout for ".." must not delete x's parent
                .file("fine", "ok")
                .gzip(),
        )
        extract(TestTar().whiteout("escape/.wh.victim").symlink("abs", "/").file("abs/etc/passwd", "x").gzip())

        assertEquals("safe", File(outside, "victim").readText())
        assertFalse(File(temp.root, "evil").exists())
        assertFalse(exists("stolen"))
        assertTrue(exists("escape"))
        assertTrue(exists("fine"))
        assertTrue(fakeTar.received.none { it.path.startsWith("escape/") || it.path.startsWith("abs/") })
        assertTrue(warnings.any { it.contains("Skipped unsafe layer entries") })
    }

    @Test
    fun `support is made owner-writable after each layer`() {
        extract(
            TestTar()
                .dir("support/", mode = "555".toInt(8))
                .file("support/common.sh", "#!/bin/sh", mode = "555".toInt(8))
                .dir("support/sub/", mode = "555".toInt(8))
                .file("support/sub/x", "x", mode = "444".toInt(8))
                .gzip(),
        )
        assertEquals("rwxr-xr-x", mode("support"))
        assertEquals("rwxr-xr-x", mode("support/common.sh"))
        assertEquals("rw-r--r--", mode("support/sub/x"))
        assertTrue(File(rootfs, "support/sub").canWrite())
    }

    @Test
    fun `an upper layer can replace a directory with a symlink and vice versa`() {
        extract(TestTar().dir("lib/").file("lib/libc.so", "c").symlink("bin", "usr/bin").gzip())
        extract(TestTar().symlink("lib", "usr/lib").dir("bin/").file("bin/sh", "sh").gzip())
        assertEquals("usr/lib", Files.readSymbolicLink(path("lib")).toString())
        assertTrue(Files.isDirectory(path("bin"), LinkOption.NOFOLLOW_LINKS))
        assertTrue(exists("bin/sh"))
    }

    @Test
    fun `multi-member gzip and uncompressed layers are both accepted`() {
        val first = TestTar().file("one", "1").build(endMarker = false)
        val second = TestTar().file("two", "2").build()
        val out = ByteArrayOutputStream()
        out.write(TestTar.gzip(first))
        out.write(TestTar.gzip(second))
        extract(out.toByteArray())
        assertTrue(exists("one") && exists("two"))

        extract(TestTar().file("plain", "p").build())
        assertTrue(exists("plain"))
    }

    @Test
    fun `zstd layers fail clearly`() {
        try {
            extract(byteArrayOf(0x28, 0xb5.toByte(), 0x2f, 0xfd.toByte(), 0, 0, 0, 0))
            fail("expected OciException")
        } catch (e: OciException) {
            assertTrue(e.message!!.contains("zstd"))
        }
    }

    @Test
    fun `missing entries after a failed tar run are reported`() {
        val brokenTar = TarProcessFactory { dir ->
            // Swallows the stream and fails without extracting anything.
            ProcessBuilder("sh", "-c", "cat > /dev/null; echo boom >&2; exit 2").directory(dir).start()
        }
        try {
            extract(TestTar().file("f", "x").gzip(), brokenTar)
            fail("expected OciException")
        } catch (e: OciException) {
            assertTrue(e.message, e.message!!.contains("missing") && e.message!!.contains("boom"))
        }
    }

    @Test
    fun `works with the host tar`() {
        val hostTar = File("/usr/bin/tar")
        assumeTrue(hostTar.canExecute())
        val longName = "deep/" + "n".repeat(180) + "/file"
        extract(
            TestTar()
                .dir("ro/", mode = "555".toInt(8)).file("ro/inside", "in")
                .gnuLongName(longName).file("short", "long named")
                .file("orig", "o", mode = "750".toInt(8)).hardlink("linked", "orig")
                .symlink("abs", "/etc/passwd")
                .whiteout(".wh.nothing")
                .gzip(),
            CommandTarProcessFactory.hostTar(hostTar.path),
        )
        assertEquals("in", File(rootfs, "ro/inside").readText())
        assertEquals("long named", File(rootfs, longName).readText())
        assertEquals("o", File(rootfs, "linked").readText())
        assertEquals("rwxr-x---", mode("orig"))
        assertEquals("/etc/passwd", Files.readSymbolicLink(path("abs")).toString())
    }
}
