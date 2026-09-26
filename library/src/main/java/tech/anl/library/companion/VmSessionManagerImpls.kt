package tech.anl.library.companion

import android.content.Context
import android.os.Environment
import tech.anl.customlibrary.BuildConfig as CustomBuildConfig
import tech.anl.library.R
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.Session
import tech.anl.library.utils.RegistryMirror
import tech.anl.library.utils.defaultSharedPreferences

/** Shared plumbing of the two companion-backed [VmSessionManager]s. */
abstract class CompanionVmSessionManager internal constructor(
    context: Context,
    val app: CompanionApp,
    traits: CompanionBackendTraits,
    protected val pulse: PulseAudioServer?
) : VmSessionManager {
    protected val context: Context = context.applicationContext
    val client = CompanionControlClient(this.context, app)
    protected val driver = CompanionSessionDriver(
        client,
        traits,
        SharedPrefsStartedVmStore(this.context.defaultSharedPreferences, "companion_started_vms_${app.name.lowercase()}"),
        driverStrings(this.context)
    )

    private fun fsId(filesystemId: Long) = filesystemId.toString()

    /**
     * The preferred registry first (the China mirror in Chinese time zones), then the original ref
     * as a fallback. Only backends that honour a new ref for an existing fsId use the fallback.
     */
    protected fun imageRefCandidates(imageRef: String): List<String> =
        listOf(RegistryMirror.preferredImageRef(imageRef), imageRef).distinct()

    override suspend fun setupFilesystem(filesystem: Filesystem, imageRef: String, onProgress: (String) -> Unit): VmResult =
        driver.setup(fsId(filesystem.id), imageRefCandidates(imageRef), repair = false, onProgress = onProgress)

    override suspend fun repairFilesystem(filesystem: Filesystem, imageRef: String, onProgress: (String) -> Unit): VmResult =
        driver.setup(fsId(filesystem.id), imageRefCandidates(imageRef), repair = true, onProgress = onProgress)

    override suspend fun startSession(
        session: Session,
        filesystem: Filesystem,
        options: VmLaunchOptions,
        geometry: String,
        appScript: String?,
        onProgress: (String) -> Unit
    ): VmResult {
        val sound = pulse != null && PulseAudioServer.isSoundEnabled(context)
        if (sound && startSoundBeforeBoot) pulse?.start()
        val params = CompanionStartParams(
            serviceType = session.serviceType.toString(),
            username = session.username,
            password = session.password,
            vncPassword = session.vncPassword,
            geometry = geometry,
            appScript = decorateAppScript(appScript.orEmpty(), sound),
            sessionId = session.id,
            settingsEnabled = !CustomBuildConfig.DEFAULT_HIDE_SETTINGS,
            // The companion opens these files itself, so it needs All files access (granted by the
            // install wizard) rather than this app.
            sharedPath = if (options.shareStorage) Environment.getExternalStorageDirectory().absolutePath else null,
            soundEnabled = sound,
            memoryBytes = options.memoryBytes,
            useAllCores = options.useAllCores
        )
        val result = driver.start(fsId(filesystem.id), params, onProgress)
        // The AVF tap interface may only exist once a VM runs; start() re-reads the subnets and
        // restarts the server if the ACL changed.
        if (result is VmResult.Success && sound) pulse?.start()
        return result
    }

    override suspend fun stopSession(session: Session): Boolean {
        val stopped = driver.stop(fsId(session.filesystemId))
        if (stopped) maybeStopPulse()
        return stopped
    }

    override suspend fun isRunning(filesystem: Filesystem): Boolean = driver.isRunning(fsId(filesystem.id))

    override suspend fun stopOrphanedSessions(activeFilesystemIds: Set<Long>) {
        driver.stopOrphans(activeFilesystemIds.map { fsId(it) }.toSet())
    }

    /** Whether the sound server must be up before the guest boots (QEMU) or can follow it (AVF). */
    protected open val startSoundBeforeBoot: Boolean = false

    protected open fun decorateAppScript(appScript: String, soundEnabled: Boolean): String = appScript

    private suspend fun maybeStopPulse() {
        val p = pulse ?: return
        // Only one VM backend session can run at a time (both forward the same host ports), but
        // check both backends before silencing a shared server.
        val anyRunning = VmSessionManagers.all(context).any { mgr ->
            mgr is CompanionVmSessionManager && mgr.client.isReachable() && mgr.hasTrackedSessions()
        }
        if (!anyRunning) p.stop()
    }

    internal fun hasTrackedSessions(): Boolean = driver.hasTrackedSessions()

    companion object {
        fun driverStrings(context: Context) = CompanionDriverStrings(
            preparing = context.getString(R.string.companion_progress_preparing),
            booting = context.getString(R.string.companion_progress_booting),
            stoppingPrevious = context.getString(R.string.companion_progress_stopping_previous),
            retryingWedged = context.getString(R.string.companion_progress_retrying_wedged),
            retryingRegistry = context.getString(R.string.companion_progress_retrying_registry),
            setupFailed = context.getString(R.string.companion_error_setup_failed),
            startFailed = context.getString(R.string.companion_error_start_failed),
            notSetUp = context.getString(R.string.companion_error_not_set_up),
            corrupt = context.getString(R.string.companion_error_corrupt),
            companionDied = context.getString(R.string.companion_error_died),
            companionUnreachable = context.getString(R.string.companion_error_unreachable),
            portInUse = context.getString(R.string.companion_error_port_in_use),
            unsupportedServiceType = context.getString(R.string.companion_error_service_type)
        )
    }
}

/**
 * Sessions in a VM run by UserLAnd VM (tech.ula.vm) on the Android Virtualization Framework.
 * [pulse] provides sound when enabled; the guest finds it at its default gateway on port 4713.
 */
class AvfSessionManager(context: Context, pulse: PulseAudioServer?) :
    CompanionVmSessionManager(context, CompanionApp.VM, CompanionBackendTraits.VM, pulse)

/**
 * Sessions in a QEMU (TCG) VM run by UserLAnd QEMU (tech.ula.qemu). The companion ignores the
 * memory/CPU options and doesn't configure sound itself, so the guest is pointed at the host
 * PulseAudio through SLIRP's 10.0.2.2 by the app script.
 */
class QemuSessionManager @JvmOverloads constructor(context: Context, pulse: PulseAudioServer? = null) :
    CompanionVmSessionManager(context, CompanionApp.QEMU, CompanionBackendTraits.QEMU, pulse) {

    override val startSoundBeforeBoot: Boolean = true

    override fun decorateAppScript(appScript: String, soundEnabled: Boolean): String =
        if (soundEnabled) "export PULSE_SERVER=10.0.2.2\n$appScript" else appScript
}
