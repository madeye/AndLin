package tech.anl.library.companion

import android.content.Context
import android.os.Parcelable
import kotlinx.parcelize.Parcelize
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.Session

/** Per-launch choices made in [tech.anl.library.ui.VmLaunchOptionsDialog]; Parcelable so it can ride in an Intent. */
@Parcelize
data class VmLaunchOptions(val shareStorage: Boolean, val memoryBytes: Long, val useAllCores: Boolean) : Parcelable {
    companion object {
        /** Companion defaults: no sharing, 512 MiB (VM) / 1 GiB (QEMU), one CPU. */
        val DEFAULT = VmLaunchOptions(shareStorage = false, memoryBytes = 0L, useAllCores = false)
    }
}

/** Host-side (127.0.0.1) ports a running VM session is reachable on. */
data class VmEndpoints(val sshPort: Int) {
    companion object {
        /** The SSH port both companions forward; used when no session has been asked for yet. */
        val DEFAULT = VmEndpoints(2022)
    }
}

sealed class VmResult {
    data class Success(val endpoints: VmEndpoints) : VmResult()
    /** [corrupt] means the disk image must be repaired ([VmSessionManager.repairFilesystem]) before use. */
    data class Failure(val message: String, val corrupt: Boolean = false) : VmResult()
}

interface VmSessionManager {
    /**
     * Makes sure [filesystem]'s VM image is built (companion `setup`), reporting progress. Returns
     * [VmResult.Success] with the default endpoints once the image is ready, or a corrupt Failure
     * when it needs [repairFilesystem] first.
     */
    suspend fun setupFilesystem(filesystem: Filesystem, imageRef: String, onProgress: (String) -> Unit): VmResult

    /**
     * Boots [filesystem]'s VM and starts [session]'s SSH server in it. Assumes
     * [setupFilesystem] has succeeded. The VM backend is retried once when the guest shell wedges.
     */
    suspend fun startSession(
        session: Session,
        filesystem: Filesystem,
        options: VmLaunchOptions,
        geometry: String,
        appScript: String?,
        onProgress: (String) -> Unit
    ): VmResult

    /** Stops the VM behind [session]. Returns once the companion has finished stopping it. */
    suspend fun stopSession(session: Session): Boolean

    /** Deletes and rebuilds [filesystem]'s VM disk. Everything inside the VM is lost. */
    suspend fun repairFilesystem(filesystem: Filesystem, imageRef: String, onProgress: (String) -> Unit): VmResult

    /** Whether [filesystem]'s VM is currently running. Never starts the companion. */
    suspend fun isRunning(filesystem: Filesystem): Boolean

    /**
     * Stops VMs this app started earlier (e.g. before being force-stopped) whose filesystem isn't in
     * [activeFilesystemIds]. Safe to call concurrently with [startSession]: a VM whose start began
     * after this call, or is in progress, is never touched.
     */
    suspend fun stopOrphanedSessions(activeFilesystemIds: Set<Long>)
}

object VmSessionManagers {
    @Volatile private var avf: AvfSessionManager? = null
    @Volatile private var qemu: QemuSessionManager? = null

    /**
     * Process-wide managers (they carry the start/orphan-cleanup guard, so there must be only one
     * per backend). Returns null for PROOT.
     */
    fun forExecutionType(context: Context, t: ExecutionType): VmSessionManager? {
        val app = context.applicationContext
        return when (t) {
            ExecutionType.AVF -> avf ?: synchronized(this) {
                avf ?: AvfSessionManager(app, PulseAudioServer.shared(app)).also { avf = it }
            }
            ExecutionType.QEMU -> qemu ?: synchronized(this) {
                qemu ?: QemuSessionManager(app, PulseAudioServer.shared(app)).also { qemu = it }
            }
            ExecutionType.PROOT -> null
        }
    }

    /** Every VM manager, for callers that need to sweep all backends (e.g. orphan cleanup). */
    fun all(context: Context): List<VmSessionManager> =
        listOfNotNull(forExecutionType(context, ExecutionType.AVF), forExecutionType(context, ExecutionType.QEMU))
}
