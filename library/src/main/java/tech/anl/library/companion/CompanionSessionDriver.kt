package tech.anl.library.companion

import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.net.InetSocketAddress
import java.net.Socket

/** Behavioural differences between the two companions that the shared session logic cares about. */
data class CompanionBackendTraits(
    val name: String,
    /** How many times to re-issue `start` when the companion reports "wedged" (VM only). */
    val wedgedRetries: Int,
    /** QEMU's stop kills the companion process: EOF instead of a reply is success. */
    val stopKillsProcess: Boolean,
    /**
     * Whether a new imageRef is honoured on a later `setup` for the same fsId. The VM companion
     * records the first imageRef it sees for an fsId and ignores later ones (even across repair);
     * QEMU uses the passed ref whenever its layers are missing, which a failed pull leaves them.
     */
    val honoursNewImageRef: Boolean,
    /**
     * Whether "idle" reliably means the image was never built. QEMU reports idle after a process
     * restart until its first successful boot even though the disk exists, so it can't be trusted.
     */
    val idleMeansNotBuilt: Boolean,
    val stopTimeoutMs: Long
) {
    companion object {
        val VM = CompanionBackendTraits("UserLAnd VM", wedgedRetries = 1, stopKillsProcess = false,
            honoursNewImageRef = false, idleMeansNotBuilt = true, stopTimeoutMs = CompanionTimeouts.VM_STOP_MS)
        val QEMU = CompanionBackendTraits("UserLAnd QEMU", wedgedRetries = 0, stopKillsProcess = true,
            honoursNewImageRef = true, idleMeansNotBuilt = false, stopTimeoutMs = CompanionTimeouts.QEMU_STOP_MS)
    }
}

/** User-facing text the driver produces itself. Managers pass localized versions. */
data class CompanionDriverStrings(
    val preparing: String = "Preparing virtual machine...",
    val booting: String = "Booting virtual machine, please wait...",
    val stoppingPrevious: String = "Stopping the previous session...",
    val retryingWedged: String = "The virtual machine stopped responding, restarting it...",
    val retryingRegistry: String = "Download failed, retrying from the main image registry...",
    val setupFailed: String = "Setting up the virtual machine failed.",
    val startFailed: String = "Starting the virtual machine failed.",
    val notSetUp: String = "The virtual machine image has not been set up yet.",
    val corrupt: String = "The virtual machine's disk is corrupted and needs to be repaired.",
    val companionDied: String = "The companion app stopped unexpectedly.",
    val companionUnreachable: String = "Could not reach the companion app. Make sure it is installed and try again.",
    val portInUse: String = "Another virtual machine session is still using the network ports. Stop it and try again.",
    val unsupportedServiceType: String = "Virtual machine sessions support only SSH and VNC."
)

/** Arguments of the companion `start` command, already validated. */
data class CompanionStartParams(
    val serviceType: String,
    val username: String,
    val password: String,
    val vncPassword: String,
    val geometry: String,
    val appScript: String,
    val sessionId: Long,
    val settingsEnabled: Boolean,
    /** Host directory to share into the guest, or null for none. */
    val sharedPath: String?,
    val soundEnabled: Boolean,
    val memoryBytes: Long,
    val useAllCores: Boolean
) {
    fun toFields(fsId: String): Map<String, Any?> = linkedMapOf(
        "fsId" to fsId,
        "serviceType" to serviceType,
        "username" to username,
        "password" to password,
        "vncPassword" to vncPassword,
        "geometry" to geometry,
        "appScript" to appScript,
        "sessionId" to sessionId,
        "settingsEnabled" to settingsEnabled,
        // Omitted (not "") when null: the VM companion shares any non-null path, even "".
        "sharedPath" to sharedPath,
        "soundEnabled" to soundEnabled,
        "memoryBytes" to memoryBytes,
        "useAllCores" to useAllCores
    )
}

/** Remembers which filesystems this app has started VMs for, across process death. */
interface StartedVmStore {
    fun add(fsId: String)
    fun remove(fsId: String)
    fun all(): Set<String>
}

class InMemoryStartedVmStore : StartedVmStore {
    private val ids = java.util.Collections.synchronizedSet(mutableSetOf<String>())
    override fun add(fsId: String) { ids.add(fsId) }
    override fun remove(fsId: String) { ids.remove(fsId) }
    override fun all(): Set<String> = synchronized(ids) { ids.toSet() }
}

class SharedPrefsStartedVmStore(private val prefs: SharedPreferences, private val key: String) : StartedVmStore {
    @Synchronized override fun add(fsId: String) {
        prefs.edit().putStringSet(key, all() + fsId).apply()
    }
    @Synchronized override fun remove(fsId: String) {
        prefs.edit().putStringSet(key, all() - fsId).apply()
    }
    @Synchronized override fun all(): Set<String> = prefs.getStringSet(key, emptySet())?.toSet() ?: emptySet()
}

/**
 * The protocol-level session logic shared by [AvfSessionManager] and [QemuSessionManager]. It has no
 * Android dependencies beyond logging so it can be exercised against a fake companion socket.
 */
class CompanionSessionDriver(
    private val conn: CompanionConnection,
    private val traits: CompanionBackendTraits,
    private val store: StartedVmStore,
    private val strings: CompanionDriverStrings = CompanionDriverStrings(),
    private val pollIntervalMs: Long = CompanionTimeouts.POLL_INTERVAL_MS,
    private val portProbe: suspend (Int) -> Boolean = ::probeLocalPort
) {
    private val tag = "CompanionDriver"

    // Guards the start/orphan-cleanup interplay: a cleanup must never stop a VM whose start began
    // after the cleanup was requested (the stale "active" set it was given can't know about it).
    private val guard = Mutex()
    private var generation = 0L
    private val starting = mutableSetOf<String>()
    private val startedAtGeneration = mutableMapOf<String, Long>()

    /** What the parallel pollers saw last; QEMU's start failures lose this state when the process dies. */
    private class PollSnapshot {
        @Volatile var progress: String = ""
        @Volatile var error: String = ""
        @Volatile var status: CompanionStatus = CompanionStatus.UNKNOWN
    }

    // ---------------------------------------------------------------- setup / repair

    /**
     * Ensures the image is built. [imageRefs] are tried in order (the first is the preferred
     * mirror, the rest fallbacks); fallbacks are only used by backends that honour a new ref.
     */
    suspend fun setup(fsId: String, imageRefs: List<String>, repair: Boolean, onProgress: (String) -> Unit): VmResult {
        require(imageRefs.isNotEmpty()) { "no image reference" }
        onProgress(strings.preparing)
        try {
            conn.wake()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return VmResult.Failure(describeWakeFailure(e))
        }

        if (!repair) {
            var status = statusOrUnknown(fsId)
            if (status == CompanionStatus.SETUP) status = awaitNotBusy(fsId, onProgress)
            when (status) {
                CompanionStatus.READY, CompanionStatus.RUNNING, CompanionStatus.STARTING, CompanionStatus.WEDGED ->
                    return VmResult.Success(VmEndpoints.DEFAULT)
                CompanionStatus.CORRUPT -> return VmResult.Failure(lastErrorOr(fsId, strings.corrupt), corrupt = true)
                else -> Unit
            }
        }

        val refs = if (traits.honoursNewImageRef) imageRefs.distinct() else listOf(imageRefs.first())
        var lastFailure: VmResult.Failure? = null
        for ((index, ref) in refs.withIndex()) {
            if (index > 0) onProgress(strings.retryingRegistry)
            // Only the first attempt of a repair wipes the disk; a fallback just re-runs setup,
            // which pulls again because the failed pull left no layers behind.
            val cmd = if (repair && index == 0) "repair" else "setup"
            val result = runBuild(fsId, cmd, ref, onProgress)
            if (result !is VmResult.Failure || result.corrupt) return result
            lastFailure = result
            Log.w(tag, "${traits.name}: $cmd($fsId, $ref) failed: ${result.message}")
        }
        return lastFailure!!
    }

    private suspend fun runBuild(fsId: String, cmd: String, imageRef: String, onProgress: (String) -> Unit): VmResult {
        val snapshot = PollSnapshot()
        val outcome = withPolling(fsId, snapshot, onProgress) {
            conn.request(cmd, mapOf("fsId" to fsId, "imageRef" to imageRef), CompanionTimeouts.BLOCKING)
        }
        when (outcome) {
            is CallOutcome.Replied -> if (!outcome.response.ok) {
                return VmResult.Failure(outcome.response.error?.ifBlank { null } ?: strings.setupFailed)
            }
            is CallOutcome.Gone -> {
                // The companion died mid-build; it may still have finished (or marked the disk corrupt).
                if (!rewakeQuietly()) return VmResult.Failure(snapshot.error.ifBlank { strings.companionDied })
            }
            is CallOutcome.Unreachable -> return VmResult.Failure(strings.companionUnreachable)
            is CallOutcome.Failed -> return VmResult.Failure(outcome.error.message ?: strings.setupFailed)
        }
        // {"ok":true} only means the build finished, not that it succeeded.
        return when (statusOrUnknown(fsId)) {
            CompanionStatus.READY -> VmResult.Success(VmEndpoints.DEFAULT)
            CompanionStatus.CORRUPT -> VmResult.Failure(lastErrorOr(fsId, strings.corrupt), corrupt = true)
            else -> VmResult.Failure(lastErrorOr(fsId, snapshot.error.ifBlank { strings.setupFailed }))
        }
    }

    private suspend fun awaitNotBusy(fsId: String, onProgress: (String) -> Unit): CompanionStatus {
        var last = ""
        while (true) {
            val status = statusOrUnknown(fsId)
            if (!status.isBusy) return status
            val progress = runCatching { conn.request("getProgressMessage", mapOf("fsId" to fsId)).orThrow().resultString }
                .getOrDefault("")
            normaliseProgress(progress)?.let { if (it != last) { last = it; onProgress(it) } }
            delay(pollIntervalMs)
        }
    }

    // ---------------------------------------------------------------- start

    suspend fun start(fsId: String, params: CompanionStartParams, onProgress: (String) -> Unit): VmResult {
        if (params.serviceType != "ssh" && params.serviceType != "vnc") {
            return VmResult.Failure(strings.unsupportedServiceType)
        }
        val validation = CompanionInputValidator.validate(params.username, params.password, params.vncPassword, params.geometry)
        if (validation is CompanionInputValidator.Result.Invalid) return VmResult.Failure(validation.message)

        guard.withLock {
            generation++
            starting += fsId
            startedAtGeneration[fsId] = generation
        }
        try {
            return startLocked(fsId, params, onProgress)
        } finally {
            withContext(kotlinx.coroutines.NonCancellable) { guard.withLock { starting -= fsId } }
        }
    }

    private suspend fun startLocked(fsId: String, params: CompanionStartParams, onProgress: (String) -> Unit): VmResult {
        onProgress(strings.booting)
        try {
            conn.wake()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            return VmResult.Failure(describeWakeFailure(e))
        }

        when (val status = statusOrUnknown(fsId)) {
            CompanionStatus.CORRUPT -> return VmResult.Failure(lastErrorOr(fsId, strings.corrupt), corrupt = true)
            CompanionStatus.RUNNING, CompanionStatus.STARTING -> {
                // A leftover session (possibly with a dead VM behind a "running" status, which the
                // companion never clears): start from a clean slate rather than reconnect to it.
                onProgress(strings.stoppingPrevious)
                Log.i(tag, "${traits.name}: $fsId is $status before start, stopping it first")
                stop(fsId)
                try { conn.wake() } catch (e: CancellationException) { throw e } catch (e: Exception) {
                    return VmResult.Failure(describeWakeFailure(e))
                }
                onProgress(strings.booting)
            }
            CompanionStatus.IDLE -> if (traits.idleMeansNotBuilt) return VmResult.Failure(strings.notSetUp)
            else -> Unit
        }

        var attempt = 0
        while (true) {
            val snapshot = PollSnapshot()
            val outcome = withPolling(fsId, snapshot, onProgress) {
                conn.request("start", params.toFields(fsId), CompanionTimeouts.BLOCKING)
            }
            when (outcome) {
                is CallOutcome.Replied -> if (!outcome.response.ok) {
                    val err = outcome.response.error.orEmpty()
                    val message = if (err.contains("in use", ignoreCase = true) || err.contains("EADDRINUSE")) {
                        strings.portInUse
                    } else err.ifBlank { strings.startFailed }
                    return VmResult.Failure(message)
                }
                is CallOutcome.Gone -> {
                    // QEMU kills its own process on start failure; the error it recorded first is
                    // only known if a poll raced the kill.
                    if (!rewakeQuietly()) return VmResult.Failure(snapshot.error.ifBlank { strings.companionDied })
                    val status = statusOrUnknown(fsId)
                    return if (status == CompanionStatus.CORRUPT) {
                        VmResult.Failure(lastErrorOr(fsId, strings.corrupt), corrupt = true)
                    } else {
                        VmResult.Failure(snapshot.error.ifBlank { strings.startFailed })
                    }
                }
                is CallOutcome.Unreachable -> return VmResult.Failure(strings.companionUnreachable)
                is CallOutcome.Failed -> return VmResult.Failure(outcome.error.message ?: strings.startFailed)
            }

            when (statusOrUnknown(fsId)) {
                CompanionStatus.RUNNING -> {
                    val ssh = port(fsId, "ssh")
                    val vnc = port(fsId, "vnc")
                    val wanted = if (params.serviceType == "ssh") ssh else vnc
                    if (wanted <= 0) return VmResult.Failure(lastErrorOr(fsId, strings.startFailed))
                    store.add(fsId)
                    return VmResult.Success(VmEndpoints(ssh, vnc))
                }
                CompanionStatus.WEDGED -> {
                    if (attempt < traits.wedgedRetries) {
                        attempt++
                        Log.w(tag, "${traits.name}: $fsId wedged (${lastErrorOr(fsId, "")}), retrying start #$attempt")
                        onProgress(strings.retryingWedged)
                        continue
                    }
                    return VmResult.Failure(lastErrorOr(fsId, strings.startFailed))
                }
                CompanionStatus.CORRUPT -> return VmResult.Failure(lastErrorOr(fsId, strings.corrupt), corrupt = true)
                else -> return VmResult.Failure(lastErrorOr(fsId, snapshot.error.ifBlank { strings.startFailed }))
            }
        }
    }

    // ---------------------------------------------------------------- stop / state

    suspend fun stop(fsId: String): Boolean {
        if (!conn.isReachable()) {
            // No companion process means no VM: the VM lives inside it.
            store.remove(fsId)
            return true
        }
        val stopped = try {
            conn.request("stop", mapOf("fsId" to fsId), traits.stopTimeoutMs).orThrow()
            true
        } catch (e: CompanionGoneException) {
            if (!traits.stopKillsProcess) Log.w(tag, "${traits.name} closed the connection during stop")
            true
        } catch (e: CompanionUnreachableException) {
            true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(tag, "${traits.name}: stop($fsId) failed", e)
            false
        }
        if (stopped) {
            store.remove(fsId)
            // Let a dying QEMU process release its port so an immediate restart wakes a fresh one.
            if (traits.stopKillsProcess) awaitUnreachable(5_000)
        }
        return stopped
    }

    /** Whether this app believes it has a VM running on this backend (started and not yet stopped). */
    fun hasTrackedSessions(): Boolean = store.all().isNotEmpty()

    suspend fun isRunning(fsId: String): Boolean {
        if (!conn.isReachable()) return false
        val status = statusOrUnknown(fsId)
        if (status != CompanionStatus.RUNNING) return false
        // The VM companion never clears "running" when the VM dies; the forwarded ports tell.
        return portProbe(VmEndpoints.DEFAULT.sshPort) || portProbe(VmEndpoints.DEFAULT.vncPort)
    }

    suspend fun stopOrphans(activeFsIds: Set<String>) {
        val cutoff = guard.withLock { generation }
        val candidates = store.all() - activeFsIds
        if (candidates.isEmpty()) return
        if (!conn.isReachable()) {
            // Nothing can be running; forget what isn't being started right now.
            guard.withLock {
                candidates.filter { it !in starting && (startedAtGeneration[it] ?: 0L) <= cutoff }.forEach(store::remove)
            }
            return
        }
        for (fsId in candidates) {
            guard.withLock {
                if (fsId in starting || (startedAtGeneration[fsId] ?: 0L) > cutoff) {
                    Log.i(tag, "${traits.name}: not treating $fsId as orphaned, a start is newer than this cleanup")
                    return@withLock
                }
                val status = statusOrUnknown(fsId)
                if (status == CompanionStatus.RUNNING || status == CompanionStatus.STARTING || status == CompanionStatus.WEDGED) {
                    Log.i(tag, "${traits.name}: stopping orphaned VM for filesystem $fsId ($status)")
                    stop(fsId)
                } else {
                    store.remove(fsId)
                }
            }
        }
    }

    // ---------------------------------------------------------------- helpers

    private sealed class CallOutcome {
        data class Replied(val response: CompanionResponse) : CallOutcome()
        object Gone : CallOutcome()
        object Unreachable : CallOutcome()
        data class Failed(val error: Exception) : CallOutcome()
    }

    /** Runs a blocking call while polling progress/error/status on separate connections. */
    private suspend fun withPolling(
        fsId: String,
        snapshot: PollSnapshot,
        onProgress: (String) -> Unit,
        block: suspend () -> CompanionResponse
    ): CallOutcome = coroutineScope {
        val poller = launch {
            var lastShown = ""
            // Give the blocking call a head start so the first poll sees its state.
            delay(pollIntervalMs / 2)
            while (isActive) {
                try {
                    val p = conn.request("getProgressMessage", mapOf("fsId" to fsId)).orThrow().resultString
                    snapshot.progress = p
                    normaliseProgress(p)?.let { if (it != lastShown) { lastShown = it; onProgress(it) } }
                    val e = conn.request("getLastError", mapOf("fsId" to fsId)).orThrow().resultString
                    if (e.isNotBlank()) snapshot.error = e
                    snapshot.status = CompanionStatus.of(conn.request("getStatus", mapOf("fsId" to fsId)).orThrow().resultString)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // The companion may be busy dying (QEMU); the blocking call will tell us.
                }
                delay(pollIntervalMs)
            }
        }
        try {
            CallOutcome.Replied(block())
        } catch (e: CompanionGoneException) {
            CallOutcome.Gone
        } catch (e: CompanionUnreachableException) {
            CallOutcome.Unreachable
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            CallOutcome.Failed(e)
        } finally {
            poller.cancel()
        }
    }

    private suspend fun statusOrUnknown(fsId: String): CompanionStatus = try {
        CompanionStatus.of(conn.request("getStatus", mapOf("fsId" to fsId)).orThrow().resultString)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        CompanionStatus.UNKNOWN
    }

    private suspend fun lastErrorOr(fsId: String, fallback: String): String = try {
        conn.request("getLastError", mapOf("fsId" to fsId)).orThrow().resultString.ifBlank { fallback }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        fallback
    }

    private suspend fun port(fsId: String, serviceType: String): Int = try {
        conn.request("getPort", mapOf("fsId" to fsId, "serviceType" to serviceType)).orThrow().resultInt(-1)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        -1
    }

    private suspend fun rewakeQuietly(): Boolean = try {
        conn.wake(); true
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(tag, "${traits.name}: could not re-wake the companion", e)
        false
    }

    private suspend fun awaitUnreachable(timeoutMs: Long) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline && conn.isReachable()) delay(100)
    }

    private fun describeWakeFailure(e: Exception): String = when (e) {
        is CompanionNotInstalledException -> strings.companionUnreachable
        else -> e.message?.takeIf { e !is CompanionUnreachableException } ?: strings.companionUnreachable
    }

    /** Companion progress text, made consistent with the rest of the app. Null = nothing to show. */
    private fun normaliseProgress(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        return when (s.replace("…", "...")) {
            "Booting virtual machine...", "Booting..." -> strings.booting
            "Preparing virtual machine..." -> strings.preparing
            else -> s
        }
    }

    companion object {
        suspend fun probeLocalPort(port: Int): Boolean = withContext(Dispatchers.IO) {
            try {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 500) }
                true
            } catch (e: Exception) {
                false
            }
        }
    }
}
