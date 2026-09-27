package tech.anl.library.companion

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class CompanionSessionDriverTest {

    private val params = CompanionStartParams(
        serviceType = "ssh", username = "user", password = "secret", vncPassword = "vncpass",
        geometry = "1280x720", appScript = "", sessionId = 9L, settingsEnabled = true, sharedPath = null,
        soundEnabled = false, memoryBytes = 2L shl 30, useAllCores = true
    )

    private fun driver(fake: FakeCompanion, traits: CompanionBackendTraits, store: StartedVmStore = InMemoryStartedVmStore()) =
        FakeConnection(fake).let { conn ->
            conn to CompanionSessionDriver(conn, traits, store, pollIntervalMs = 40, portProbe = { true })
        }

    // ------------------------------------------------------------------ setup

    @Test
    fun `setup polls progress on separate connections while the blocking call runs`() = runBlocking {
        val status = Box("idle")
        val progress = Box("")
        val fake = FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getProgressMessage" -> FakeCompanion.ok(progress.v)
                "getLastError" -> FakeCompanion.ok("")
                "setup" -> {
                    status.v = "setup"
                    progress.v = "Downloading layer 1/2 (50%)"; Thread.sleep(300)
                    progress.v = "Downloading layer 2/2 (100%)"; Thread.sleep(300)
                    status.v = "ready"
                    FakeCompanion.ok()
                }
                else -> FakeCompanion.err("unknown cmd")
            }
        }
        fake.use {
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val result = d.setup("3", listOf("ghcr.io/x/y:latest"), repair = false) { seen += it }
            assertTrue(result is VmResult.Success)
            assertTrue(seen.toString(), seen.contains("Downloading layer 1/2 (50%)"))
            assertTrue(seen.toString(), seen.contains("Downloading layer 2/2 (100%)"))
            assertEquals("Preparing virtual machine...", seen.first())
            assertEquals(listOf("setup"), fake.commandsExceptPolls())
            assertEquals("3", fake.requests.first { it["cmd"] == "setup" }["fsId"])
        }
    }

    @Test
    fun `setup ok true with error status is a failure carrying the last error`() = runBlocking {
        val status = Box("idle")
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getLastError" -> FakeCompanion.ok(if (status.v == "error") "Setup timed out" else "")
                "getProgressMessage" -> FakeCompanion.ok("")
                "setup" -> { status.v = "error"; FakeCompanion.ok() }
                else -> FakeCompanion.err("unknown cmd")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            val r = d.setup("1", listOf("mirror/x", "ghcr.io/x"), repair = false) {}
            assertEquals(VmResult.Failure("Setup timed out"), r)
            // The VM companion keeps the first imageRef it saw, so no fallback is attempted.
            assertEquals(listOf("setup"), fake.commandsExceptPolls())
        }
    }

    @Test
    fun `qemu setup falls back to the original registry after a failed pull`() = runBlocking {
        val status = Box("idle")
        val refs = Collections.synchronizedList(mutableListOf<String>())
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getLastError" -> FakeCompanion.ok(if (status.v == "error") "OCI pull failed: HTTP 403" else "")
                "getProgressMessage" -> FakeCompanion.ok("")
                "setup", "repair" -> {
                    val ref = req["imageRef"].toString(); refs += ref
                    status.v = if (ref.startsWith("ghcr.nju.edu.cn")) "error" else "ready"
                    FakeCompanion.ok()
                }
                else -> FakeCompanion.err("unknown cmd")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU)
            val seen = mutableListOf<String>()
            val r = d.setup("5", listOf("ghcr.nju.edu.cn/org/img:1", "ghcr.io/org/img:1"), repair = true) { seen += it }
            assertTrue(r is VmResult.Success)
            assertEquals(listOf("ghcr.nju.edu.cn/org/img:1", "ghcr.io/org/img:1"), refs)
            // Repair wipes once; the fallback just re-runs setup.
            assertEquals(listOf("repair", "setup"), fake.commandsExceptPolls())
            assertTrue(seen.any { it.contains("retrying", ignoreCase = true) })
        }
    }

    @Test
    fun `setup reports corrupt disks without building`() = runBlocking {
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok("corrupt")
                "getLastError" -> FakeCompanion.ok("Disk image is corrupted and needs repair before it can be used.")
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            val r = d.setup("1", listOf("ghcr.io/x"), repair = false) {}
            assertTrue(r is VmResult.Failure && r.corrupt)
            assertEquals(emptyList<String>(), fake.commandsExceptPolls())
        }
    }

    @Test
    fun `setup skips building when already ready`() = runBlocking {
        FakeCompanion { req -> if (req["cmd"] == "getStatus") FakeCompanion.ok("ready") else FakeCompanion.ok("") }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU)
            assertTrue(d.setup("1", listOf("ghcr.io/x"), repair = false) {} is VmResult.Success)
            assertEquals(emptyList<String>(), fake.commandsExceptPolls())
        }
    }

    // ------------------------------------------------------------------ start

    @Test
    fun `wedged start is retried once and then succeeds`() = runBlocking {
        val status = Box("ready")
        val starts = Box(0)
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getLastError" -> FakeCompanion.ok(if (status.v == "wedged") "User provisioning timed out" else "")
                "getProgressMessage" -> FakeCompanion.ok("Booting virtual machine…")
                "getPort" -> FakeCompanion.ok(if (status.v != "running") -1 else if (req["serviceType"] == "ssh") 2022 else 5901)
                "start" -> {
                    starts.v++
                    Thread.sleep(100)
                    status.v = if (starts.v == 1) "wedged" else "running"
                    FakeCompanion.ok()
                }
                else -> FakeCompanion.err("unknown cmd")
            }
        }.use { fake ->
            val store = InMemoryStartedVmStore()
            val (_, d) = driver(fake, CompanionBackendTraits.VM, store)
            val seen = Collections.synchronizedList(mutableListOf<String>())
            val r = d.start("4", params) { seen += it }
            assertEquals(VmResult.Success(VmEndpoints(2022)), r)
            assertEquals(2, starts.v)
            assertEquals(setOf("4"), store.all())
            assertTrue(seen.contains("Booting virtual machine, please wait..."))
            assertTrue(seen.any { it.contains("stopped responding") })
            val start = fake.requests.first { it["cmd"] == "start" }
            assertEquals("ssh", start["serviceType"])
            // Still part of the companion protocol even though SSH sessions don't use them.
            assertEquals("vncpass", start["vncPassword"])
            assertEquals("1280x720", start["geometry"])
            assertEquals(9.0, start["sessionId"])
            assertEquals((2L shl 30).toDouble(), start["memoryBytes"])
            assertEquals(true, start["useAllCores"])
            assertFalse(start.containsKey("sharedPath"))
        }
    }

    @Test
    fun `second wedge is reported as a failure`() = runBlocking {
        val status = Box("ready")
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getLastError" -> FakeCompanion.ok(if (status.v == "wedged") "ssh server start timed out" else "")
                "start" -> { status.v = "wedged"; FakeCompanion.ok() }
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            assertEquals(VmResult.Failure("ssh server start timed out"), d.start("4", params) {})
            assertEquals(2, fake.commandsExceptPolls().count { it == "start" })
        }
    }

    @Test
    fun `qemu does not retry wedged`() = runBlocking {
        val status = Box("ready")
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "start" -> { status.v = "wedged"; FakeCompanion.ok() }
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU)
            assertTrue(d.start("4", params) {} is VmResult.Failure)
            assertEquals(1, fake.commandsExceptPolls().count { it == "start" })
        }
    }

    @Test
    fun `qemu start EOF re-wakes and reports corruption from disk state`() = runBlocking {
        val died = Box(false)
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(if (died.v) "corrupt" else "ready")
                "getLastError" -> FakeCompanion.ok("")
                "start" -> { died.v = true; alive = false; null }
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (conn, d) = driver(fake, CompanionBackendTraits.QEMU)
            val r = d.start("2", params) {}
            assertTrue(r.toString(), r is VmResult.Failure && r.corrupt)
            assertTrue(conn.wakes >= 2)
        }
    }

    @Test
    fun `qemu start EOF keeps the error a poll captured before the process died`() = runBlocking {
        val phase = Box(0)
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(if (phase.v == 2) "idle" else if (phase.v == 1) "error" else "ready")
                "getLastError" -> FakeCompanion.ok(if (phase.v == 1) "Timed out waiting for shell" else "")
                "start" -> { phase.v = 1; Thread.sleep(300); phase.v = 2; alive = false; null }
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU)
            assertEquals(VmResult.Failure("Timed out waiting for shell"), d.start("2", params) {})
        }
    }

    @Test
    fun `address in use is explained`() = runBlocking {
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok("ready")
                "start" -> FakeCompanion.err("java.net.BindException: Address already in use")
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            val r = d.start("2", params) {} as VmResult.Failure
            assertTrue(r.message.contains("still using the network ports"))
        }
    }

    @Test
    fun `unsafe credentials are rejected before anything is sent`() = runBlocking {
        FakeCompanion { FakeCompanion.ok("ready") }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            assertTrue(d.start("1", params.copy(password = "a'b")) {} is VmResult.Failure)
            assertTrue(d.start("1", params.copy(vncPassword = "a\nb")) {} is VmResult.Failure)
            assertTrue(d.start("1", params.copy(geometry = "1280x720; rm -rf /")) {} is VmResult.Failure)
            assertTrue(d.start("1", params.copy(serviceType = "xsdl")) {} is VmResult.Failure)
            assertTrue(d.start("1", params.copy(serviceType = "vnc")) {} is VmResult.Failure)
            assertTrue(fake.requests.isEmpty())
        }
    }

    @Test
    fun `vm idle means never built`() = runBlocking {
        FakeCompanion { req -> if (req["cmd"] == "getStatus") FakeCompanion.ok("idle") else FakeCompanion.ok("") }.use { fake ->
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            assertTrue(d.start("1", params) {} is VmResult.Failure)
            assertFalse(fake.commands().contains("start"))
        }
    }

    // ------------------------------------------------------------------ stop

    @Test
    fun `qemu stop treats EOF as success and waits for the process to go`() = runBlocking {
        FakeCompanion { req -> if (req["cmd"] == "stop") { alive = false; null } else FakeCompanion.ok("running") }.use { fake ->
            val store = InMemoryStartedVmStore().apply { add("8") }
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU, store)
            assertTrue(d.stop("8"))
            assertTrue(store.all().isEmpty())
        }
    }

    @Test
    fun `vm stop failure is reported`() = runBlocking {
        FakeCompanion { req -> if (req["cmd"] == "stop") FakeCompanion.err("boom") else FakeCompanion.ok("") }.use { fake ->
            val store = InMemoryStartedVmStore().apply { add("8") }
            val (_, d) = driver(fake, CompanionBackendTraits.VM, store)
            assertFalse(d.stop("8"))
            assertEquals(setOf("8"), store.all())
        }
    }

    @Test
    fun `stop with no companion running is success`() = runBlocking {
        FakeCompanion { FakeCompanion.ok() }.use { fake ->
            fake.alive = false
            val (_, d) = driver(fake, CompanionBackendTraits.VM)
            assertTrue(d.stop("1"))
            assertTrue(fake.requests.isEmpty())
        }
    }

    // ------------------------------------------------------------------ orphans

    @Test
    fun `orphaned sessions are stopped, active ones are not`() = runBlocking {
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok("running")
                else -> FakeCompanion.ok()
            }
        }.use { fake ->
            val store = InMemoryStartedVmStore().apply { add("1"); add("2") }
            val (_, d) = driver(fake, CompanionBackendTraits.VM, store)
            d.stopOrphans(setOf("1"))
            val stops = fake.requests.filter { it["cmd"] == "stop" }.map { it["fsId"] }
            assertEquals(listOf("2"), stops)
            assertEquals(setOf("1"), store.all())
        }
    }

    @Test
    fun `orphan cleanup never stops a session whose start is in progress`() = runBlocking {
        val startEntered = CountDownLatch(1)
        val releaseStart = CountDownLatch(1)
        val status = Box("ready")
        FakeCompanion { req ->
            when (req["cmd"]) {
                "getStatus" -> FakeCompanion.ok(status.v)
                "getPort" -> FakeCompanion.ok(2022)
                "start" -> {
                    status.v = "starting"
                    startEntered.countDown()
                    releaseStart.await(5, TimeUnit.SECONDS)
                    status.v = "running"
                    FakeCompanion.ok()
                }
                else -> FakeCompanion.ok("")
            }
        }.use { fake ->
            // "3" was running before the app was force-stopped and is being started again now.
            val store = InMemoryStartedVmStore().apply { add("3") }
            val (_, d) = driver(fake, CompanionBackendTraits.VM, store)
            withTimeout(10_000) {
                val start = async { d.start("3", params) {} }
                while (startEntered.count > 0) delay(10)
                d.stopOrphans(emptySet()) // stale "active" set from service start
                releaseStart.countDown()
                assertTrue(start.await() is VmResult.Success)
            }
            assertFalse(fake.commands().contains("stop"))
            assertEquals(setOf("3"), store.all())
        }
    }

    @Test
    fun `cleanup with the companion not running forgets stale entries`() = runBlocking {
        FakeCompanion { FakeCompanion.ok("running") }.use { fake ->
            fake.alive = false
            val store = InMemoryStartedVmStore().apply { add("1"); add("2") }
            val (_, d) = driver(fake, CompanionBackendTraits.QEMU, store)
            d.stopOrphans(setOf("2"))
            assertEquals(setOf("2"), store.all())
        }
    }
}

/** Thread-visible mutable cell for fake-companion state shared with its connection threads. */
class Box<T>(@Volatile var v: T)
