package tech.anl.library.oci

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections

class OciFilesystemInstallerTest {
    @get:Rule val temp = TemporaryFolder()

    private val registry = FakeRegistry()
    private val fakeTar = FakeTarProcessFactory()

    @After
    fun tearDown() = registry.shutdown()

    private fun installer(cache: File) = OciFilesystemInstaller(
        OciRegistryClient(OkHttpClient(), baseUrlFor = { "http://$it" }, maxAttempts = 2, retryDelayMillis = 1),
        LayerExtractor(fakeTar),
        cache,
    )

    private fun publish(): List<OciDescriptor> {
        val base = registry.addBlob(
            TestTar().dir("etc/").file("etc/os-release", "ID=test").file("etc/obsolete", "x")
                .dir("support/", mode = "555".toInt(8)).file("support/extractFilesystem.sh", "#!/bin/sh", mode = "555".toInt(8))
                .gzip(),
        )
        val top = registry.addBlob(TestTar().whiteout("etc/.wh.obsolete").file("etc/motd", "hi").gzip())
        registry.tagIndex("latest", mapOf("linux/arm64" to registry.addImage(listOf(base, top))))
        return listOf(base, top)
    }

    @Test
    fun `installs all layers in order and records progress`() = runBlocking {
        publish()
        val rootfs = File(temp.root, "rootfs")
        val cache = temp.newFolder("cache")
        val events = Collections.synchronizedList(ArrayList<OciInstallProgress>())

        installer(cache).install(registry.reference.toString(), "arm64-v8a", rootfs) { events += it }

        assertEquals("ID=test", File(rootfs, "etc/os-release").readText())
        assertEquals("hi", File(rootfs, "etc/motd").readText())
        assertFalse(File(rootfs, "etc/obsolete").exists())
        assertTrue(File(rootfs, "support").canWrite())
        assertTrue(File(rootfs, "support/extractFilesystem.sh").canWrite())

        val state = File(rootfs, OciFilesystemInstaller.STATE_FILE).readLines()
        assertEquals("image ${registry.reference}", state[0])
        assertEquals(2, state.count { it.startsWith("layer ") })

        assertTrue(events.first() is OciInstallProgress.Resolving)
        assertEquals(OciInstallProgress.Finalizing, events.last())
        assertTrue(events.contains(OciInstallProgress.Downloading(2, 2, 100)))
        assertTrue(events.contains(OciInstallProgress.Extracting(1, 2, 100)))
        assertTrue(events.contains(OciInstallProgress.Extracting(2, 2, 100)))
        val extractionOrder = events.filterIsInstance<OciInstallProgress.Extracting>().map { it.layer }
        assertEquals(extractionOrder.sorted(), extractionOrder)
        assertTrue("layer files are removed", cache.listFiles()!!.isEmpty())
    }

    @Test
    fun `an interrupted install resumes after the last extracted layer`() = runBlocking {
        val (base, top) = publish()
        val rootfs = File(temp.root, "rootfs")
        val cache = temp.newFolder("cache")

        registry.failBlobsWith = null
        registry.disconnectFirst[top.digest] = 100 // the second layer can't be downloaded yet
        try {
            installer(cache).install(registry.reference.toString(), "arm64-v8a", rootfs) {}
            fail("expected the download of layer 2 to fail")
        } catch (e: OciException) {
            // expected
        }
        assertTrue(File(rootfs, "etc/os-release").exists())
        assertFalse(File(rootfs, "etc/motd").exists())

        registry.disconnectFirst.clear()
        val events = Collections.synchronizedList(ArrayList<OciInstallProgress>())
        installer(cache).install(registry.reference.toString(), "arm64-v8a", rootfs) { events += it }

        assertEquals("hi", File(rootfs, "etc/motd").readText())
        assertEquals("layer 1 was downloaded only once", 1, registry.blobRequests(base.digest))
        assertTrue(events.filterIsInstance<OciInstallProgress.Extracting>().all { it.layer == 2 })
    }

    @Test
    fun `refuses to resume a different image`() = runBlocking {
        publish()
        val rootfs = File(temp.root, "rootfs")
        File(rootfs, "support").mkdirs()
        File(rootfs, OciFilesystemInstaller.STATE_FILE).writeText("image ghcr.io/other/image:latest\nmanifest sha256:00\n")
        try {
            installer(temp.newFolder("cache")).install(registry.reference.toString(), "arm64-v8a", rootfs) {}
            fail("expected OciException")
        } catch (e: OciException) {
            assertTrue(e.message!!.contains("ghcr.io/other/image"))
        }
    }
}
