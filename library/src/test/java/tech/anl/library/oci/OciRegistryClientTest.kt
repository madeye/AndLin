package tech.anl.library.oci

import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.Collections
import kotlin.random.Random

class OciRegistryClientTest {
    @get:Rule val temp = TemporaryFolder()

    private val registries = ArrayList<FakeRegistry>()
    private val servers = ArrayList<MockWebServer>()

    @After
    fun tearDown() {
        registries.forEach { it.shutdown() }
        servers.forEach { it.shutdown() }
    }

    private fun registry(requireToken: Boolean = true) = FakeRegistry(requireToken = requireToken).also { registries += it }

    private fun client(candidates: (String) -> List<String> = { listOf(it) }) = OciRegistryClient(
        OkHttpClient(),
        registryCandidates = candidates,
        baseUrlFor = { "http://$it" },
        retryDelayMillis = 1,
    )

    /** An index with arm64, arm/v7 and amd64 images (and an attestation), each with one layer. */
    private fun FakeRegistry.publishMultiArch(): Map<String, OciDescriptor> {
        val layers = mapOf(
            "linux/arm64" to addBlob("arm64 layer".toByteArray()),
            "linux/arm/v7" to addBlob("armv7 layer".toByteArray()),
            "linux/amd64" to addBlob("amd64 layer".toByteArray()),
        )
        tagIndex("latest", layers.mapValues { (platform, layer) -> addImage(listOf(layer), platform.split('/')[1]) })
        return layers
    }

    @Test
    fun `resolves the platform manifest through the anonymous token flow, skipping attestations`() = runBlocking {
        val registry = registry()
        val layers = registry.publishMultiArch()

        val image = client().resolveManifest(registry.reference, "arm64-v8a")

        assertEquals(listOf(layers.getValue("linux/arm64")), image.layers)
        assertEquals(OciPlatform("linux", "arm64", "v8"), image.platform)
        assertEquals(listOf(registry.registry), image.registries)
        val first = registry.requests.first()
        assertEquals("/v2/${registry.repository}/manifests/latest", first.path)
        assertNull(first.getHeader("Authorization"))
        assertTrue(first.getHeader("Accept")!!.contains(OciRegistryClient.OCI_INDEX))
        val tokenRequest = registry.requests.first { it.requestUrl!!.encodedPath == "/token" }
        assertEquals("repository:${registry.repository}:pull", tokenRequest.requestUrl!!.queryParameter("scope"))
        assertTrue(registry.requests.last().getHeader("Authorization") == "Bearer ${registry.token}")
        assertTrue(image.manifestDigest.startsWith("sha256:"))
    }

    @Test
    fun `maps every supported ABI and rejects x86`() = runBlocking {
        val registry = registry()
        val layers = registry.publishMultiArch()
        val client = client()

        assertEquals(layers.getValue("linux/arm/v7"), client.resolveManifest(registry.reference, "armeabi-v7a").layers.single())
        assertEquals(layers.getValue("linux/amd64"), client.resolveManifest(registry.reference, "x86_64").layers.single())
        try {
            client.resolveManifest(registry.reference, "x86")
            fail("x86 must be rejected")
        } catch (e: OciException) {
            assertTrue(e.message!!.contains("x86"))
        }
    }

    @Test
    fun `accepts a single-platform manifest and resolves by digest`() = runBlocking {
        val registry = registry(requireToken = false)
        val layer = registry.addBlob("layer".toByteArray())
        val digest = registry.addImage(listOf(layer))

        val image = client().resolveManifest(registry.reference.withDigest(digest), "arm64-v8a")
        assertEquals(digest, image.manifestDigest)
        assertEquals(listOf(layer), image.layers)
    }

    @Test
    fun `zstd layers and missing platforms fail clearly without trying mirrors`() = runBlocking {
        val registry = registry()
        val mirror = registry()
        val zstd = registry.addBlob("z".toByteArray()).copy(mediaType = OciRegistryClient.OCI_LAYER_ZSTD)
        registry.tagIndex("latest", mapOf("linux/arm64" to registry.addImage(listOf(zstd))))
        val client = client { listOf(registry.registry, mirror.registry) }

        try {
            client.resolveManifest(registry.reference, "arm64-v8a")
            fail("zstd must be rejected")
        } catch (e: OciException) {
            assertTrue(e.message!!.contains("zstd"))
        }
        try {
            client.resolveManifest(registry.reference, "x86_64")
            fail("amd64 is not in the index")
        } catch (e: OciException) {
            assertTrue(e.message!!.contains("linux/amd64"))
        }
        assertTrue(mirror.requests.isEmpty())
    }

    @Test
    fun `downloads a blob, resuming with Range after a mid-stream disconnect`() = runBlocking {
        val registry = registry()
        val content = Random(1).nextBytes(512 * 1024)
        val blob = registry.addBlob(content)
        registry.disconnectFirst[blob.digest] = 1
        val dest = File(temp.root, "cache/blob")
        val progress = Collections.synchronizedList(ArrayList<Long>())

        client().downloadBlob(registry.reference, blob, dest, { progress += it })

        assertArrayEquals(content, dest.readBytes())
        assertFalse(File(dest.path + ".partial").exists())
        val ranged = registry.requests.mapNotNull { it.getHeader("Range") }
        assertEquals("exactly one resumed request", 1, ranged.size)
        val resumedAt = Regex("""bytes=(\d+)-""").find(ranged.single())!!.groupValues[1].toLong()
        assertTrue("resume offset $resumedAt", resumedAt > 0 && resumedAt < content.size)
        assertEquals(content.size.toLong(), progress.last())

        // A second call finds the verified file and doesn't download again.
        val before = registry.blobRequests(blob.digest)
        client().downloadBlob(registry.reference, blob, dest, {})
        assertEquals(before, registry.blobRequests(blob.digest))
    }

    @Test
    fun `digest mismatch is detected`() = runBlocking {
        val registry = registry()
        val blob = registry.addBlob("abcdef".toByteArray())
        val wrong = blob.copy(digest = FakeRegistry.sha256("abcdeg".toByteArray()))
        // Serve blob content under the wrong digest via a CDN that ignores the requested digest.
        val cdn = MockWebServer().also { servers += it }
        cdn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest) = MockResponse().setBody("abcdef")
        }
        registry.redirectBlobsTo = cdn.url("").toString().trimEnd('/')
        try {
            client().downloadBlob(registry.reference, wrong, File(temp.root, "x"), {})
            fail("expected digest mismatch")
        } catch (e: OciException) {
            assertTrue(e.suppressed.any { it.message!!.contains("Digest mismatch") } || e.message!!.contains("Digest mismatch"))
        }
    }

    @Test
    fun `follows blob redirects to another host without leaking the token`() = runBlocking {
        val registry = registry()
        val content = "cdn content".toByteArray()
        val blob = registry.addBlob(content)
        val cdnRequests = Collections.synchronizedList(ArrayList<RecordedRequest>())
        val cdn = MockWebServer().also { servers += it }
        cdn.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                cdnRequests += request
                return FakeRegistry.blobResponse(content, request.getHeader("Range"))
            }
        }
        cdn.start()
        // A different host name for the same loopback interface makes this a cross-host redirect.
        val otherHost = if (registry.server.hostName == "127.0.0.1") "localhost" else "127.0.0.1"
        registry.redirectBlobsTo = "http://$otherHost:${cdn.port}"

        val dest = File(temp.root, "redirected")
        client().downloadBlob(registry.reference, blob, dest, {})

        assertArrayEquals(content, dest.readBytes())
        assertEquals(1, cdnRequests.size)
        assertNull(cdnRequests.single().getHeader("Authorization"))
        assertTrue(registry.requests.any { it.getHeader("Authorization") == "Bearer ${registry.token}" })
    }

    @Test
    fun `falls back to a mirror when the preferred registry is down`() = runBlocking {
        val primary = registry()
        val mirror = registry(requireToken = false) // like ghcr.nju.edu.cn: no auth challenge
        val layers = mirror.publishMultiArch()
        primary.failEverythingWith = 503
        val client = client { listOf(primary.registry, mirror.registry) }

        val image = client.resolveManifest(primary.reference, "arm64-v8a")
        assertEquals(listOf(mirror.registry, primary.registry), image.registries)
        assertEquals(primary.reference, image.reference)
        assertTrue("primary was retried before giving up", primary.requests.size >= 2)

        val layer = layers.getValue("linux/arm64")
        val dest = File(temp.root, "from-mirror")
        client.downloadBlob(image.reference, layer, dest, {}, image.registries)
        assertEquals("arm64 layer", dest.readText())
    }

    @Test
    fun `falls back to the next registry for a blob that keeps failing`() = runBlocking {
        val primary = registry()
        val mirror = registry(requireToken = false)
        val primaryLayers = primary.publishMultiArch()
        mirror.publishMultiArch()
        primary.failBlobsWith = 500
        val client = client { listOf(primary.registry, mirror.registry) }

        val image = client.resolveManifest(primary.reference, "arm64-v8a")
        assertEquals(primary.registry, image.registries.first())
        val layer = primaryLayers.getValue("linux/arm64")
        val dest = File(temp.root, "fallback")
        client.downloadBlob(image.reference, layer, dest, {}, image.registries)

        assertEquals("arm64 layer", dest.readText())
        assertEquals("primary gets all its retries", 4, primary.blobRequests(layer.digest))
        assertEquals(1, mirror.blobRequests(layer.digest))
    }

    // --- slow registries ------------------------------------------------------------------------

    /** Treats anything under 1 MB/s over 200 ms as slow, so a throttled FakeRegistry trips it quickly. */
    private fun speedCheckingClient(candidates: (String) -> List<String>) = OciRegistryClient(
        OkHttpClient(),
        registryCandidates = candidates,
        baseUrlFor = { "http://$it" },
        retryDelayMillis = 1,
        minBytesPerSecond = 1024 * 1024,
        slowWindowMillis = 200,
    )

    /** 1 KB every 50 ms: about 20 KB/s. */
    private fun FakeRegistry.crawl() { throttleBlobs = 1024L to 50L }

    @Test
    fun `switches away from a slow registry and resumes from the partial download`() = runBlocking {
        val slow = registry().apply { crawl() }
        val fast = registry(requireToken = false)
        val content = Random(2).nextBytes(256 * 1024)
        val blob = slow.addBlob(content)
        fast.addBlob(content)
        val client = speedCheckingClient { listOf(slow.registry, fast.registry) }
        val notices = Collections.synchronizedList(ArrayList<String>())
        val dest = File(temp.root, "switched")

        client.downloadBlob(slow.reference, blob, dest, {}, onNotice = { notices += it })

        assertArrayEquals(content, dest.readBytes())
        assertEquals("the slow registry is not retried", 1, slow.blobRequests(blob.digest))
        val resumedAt = Regex("""bytes=(\d+)-""").find(fast.requests.last().getHeader("Range")!!)!!.groupValues[1].toLong()
        assertTrue("resumed at $resumedAt", resumedAt > 0)
        assertTrue(notices.single(), notices.single().startsWith("${slow.registry} is slow") && notices.single().endsWith("switching to ${fast.registry}"))

        // Later blobs go to the fast registry first.
        val next = slow.addBlob("second".toByteArray())
        fast.addBlob("second".toByteArray())
        client.downloadBlob(slow.reference, next, File(temp.root, "second"), {})
        assertEquals(0, slow.blobRequests(next.digest))
    }

    @Test
    fun `never switches to a registry already measured as slower`() = runBlocking {
        val slow = registry().apply { crawl() } // ~20 KB/s
        val slower = registry(requireToken = false).apply { throttleBlobs = 256L to 50L } // ~5 KB/s
        val content = Random(5).nextBytes(16 * 1024)
        val blob = slow.addBlob(content)
        slower.addBlob(content)
        val client = speedCheckingClient { listOf(slow.registry, slower.registry) }
        val notices = Collections.synchronizedList(ArrayList<String>())

        client.downloadBlob(slow.reference, blob, File(temp.root, "first"), {}, onNotice = { notices += it })

        // Probes the untried registry once, finds it slower and comes back to stay.
        assertEquals(listOf("switching to ${slower.registry}", "switching to ${slow.registry}"), notices.map { it.substringAfter("; ") })
        assertEquals(1, slower.blobRequests(blob.digest))

        // The next blob starts on the faster of the two and doesn't probe the other again.
        val next = slow.addBlob(content.reversedArray())
        slower.addBlob(content.reversedArray())
        notices.clear()
        client.downloadBlob(slow.reference, next, File(temp.root, "next"), {}, onNotice = { notices += it })
        assertTrue(notices.toString(), notices.isEmpty())
        assertEquals(0, slower.blobRequests(next.digest))
    }

    @Test
    fun `falls back to a slow registry when the fast one fails`() = runBlocking {
        val slow = registry().apply { crawl() }
        val broken = registry(requireToken = false).apply { failBlobsWith = 500 }
        val content = Random(3).nextBytes(16 * 1024)
        val blob = slow.addBlob(content)
        val dest = File(temp.root, "slow-but-works")

        speedCheckingClient { listOf(slow.registry, broken.registry) }
            .downloadBlob(slow.reference, blob, dest, {})

        assertArrayEquals(content, dest.readBytes())
        assertEquals("slow, then retried last without the speed check", 2, slow.blobRequests(blob.digest))
    }

    @Test
    fun `the only registry is never dropped for being slow`() = runBlocking {
        val slow = registry().apply { crawl() }
        val content = Random(4).nextBytes(16 * 1024)
        val blob = slow.addBlob(content)
        val dest = File(temp.root, "only")

        speedCheckingClient { listOf(it) }.downloadBlob(slow.reference, blob, dest, {})

        assertArrayEquals(content, dest.readBytes())
        assertEquals(1, slow.blobRequests(blob.digest))
    }

    @Test
    fun `a 404 on every registry is reported as such`() = runBlocking {
        val registry = registry()
        try {
            client().resolveManifest(registry.reference.withDigest("sha256:" + "0".repeat(64)), "arm64-v8a")
            fail("expected 404")
        } catch (e: OciException) {
            assertEquals(404, e.httpStatus)
        }
    }
}
