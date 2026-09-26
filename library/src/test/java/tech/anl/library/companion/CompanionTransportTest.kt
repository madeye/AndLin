package tech.anl.library.companion

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread

class CompanionTransportTest {

    @Test
    fun `request is length-prefixed JSON with cmd and typed fields`() = runBlocking {
        FakeCompanion { FakeCompanion.ok("ready") }.use { fake ->
            val t = CompanionTransport("127.0.0.1", fake.port)
            val resp = t.send(CompanionJson.request("start", mapOf(
                "fsId" to "7", "sessionId" to 42L, "settingsEnabled" to true, "sharedPath" to null, "memoryBytes" to 2147483648L
            )), 5_000)
            assertTrue(resp.ok)
            assertEquals("ready", resp.resultString)
            val req = fake.requests.single()
            assertEquals("start", req["cmd"])
            assertEquals("7", req["fsId"])
            assertEquals(42.0, req["sessionId"])
            assertEquals(true, req["settingsEnabled"])
            assertEquals(2147483648.0, req["memoryBytes"])
            assertFalse("null sharedPath must be omitted", req.containsKey("sharedPath"))
        }
    }

    @Test
    fun `numbers are encoded as JSON integers`() {
        val json = CompanionJson.encode(CompanionJson.request("start", mapOf("sessionId" to 42L, "n" to 3)))
        assertTrue(json, json.contains("\"sessionId\":42") && json.contains("\"n\":3"))
        assertTrue(json.startsWith("{\"cmd\":\"start\""))
    }

    @Test
    fun `error envelope is decoded`() = runBlocking {
        FakeCompanion { FakeCompanion.err("unknown cmd") }.use { fake ->
            val resp = CompanionTransport("127.0.0.1", fake.port).send(CompanionJson.request("nope", emptyMap()), 5_000)
            assertFalse(resp.ok)
            assertEquals("unknown cmd", resp.error)
            try {
                resp.orThrow(); fail()
            } catch (e: CompanionErrorException) {
                assertEquals("unknown cmd", e.error)
            }
        }
    }

    @Test
    fun `integer results survive decoding as doubles`() = runBlocking {
        FakeCompanion { FakeCompanion.ok(2022) }.use { fake ->
            val resp = CompanionTransport("127.0.0.1", fake.port).send(CompanionJson.request("getPort", mapOf("serviceType" to "ssh")), 5_000)
            assertEquals(2022, resp.resultInt())
        }
    }

    @Test(expected = CompanionGoneException::class)
    fun `closing without a reply is CompanionGoneException`() = runBlocking {
        FakeCompanion { null }.use { fake ->
            CompanionTransport("127.0.0.1", fake.port).send(CompanionJson.request("stop", mapOf("fsId" to "1")), 5_000)
        }
        Unit
    }

    @Test(expected = CompanionUnreachableException::class)
    fun `nothing listening is CompanionUnreachableException`() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        CompanionTransport("127.0.0.1", port).send(CompanionJson.request("getStatus", emptyMap()), 1_000)
        Unit
    }

    @Test
    fun `absurd response length is rejected`() = runBlocking {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            server.accept().use { s ->
                val input = DataInputStream(s.getInputStream())
                input.readFully(ByteArray(input.readInt()))
                DataOutputStream(s.getOutputStream()).apply { writeInt(-5); flush() }
            }
        }
        try {
            CompanionTransport("127.0.0.1", server.localPort).send(CompanionJson.request("getStatus", emptyMap()), 2_000)
            fail("expected an exception")
        } catch (e: CompanionException) {
            assertTrue(e.message!!.contains("length"))
        } finally {
            server.close()
        }
    }

    @Test
    fun `status strings map to enum`() {
        assertEquals(CompanionStatus.WEDGED, CompanionStatus.of("wedged"))
        assertEquals(CompanionStatus.RUNNING, CompanionStatus.of("Running "))
        assertEquals(CompanionStatus.UNKNOWN, CompanionStatus.of("bogus"))
        assertEquals(CompanionStatus.UNKNOWN, CompanionStatus.of(null))
    }
}
