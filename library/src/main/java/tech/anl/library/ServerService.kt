package tech.anl.library

import tech.anl.customlibrary.BuildConfig
import android.app.Service
import android.content.Context
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.media.AudioManager
import android.media.ToneGenerator
import android.net.Uri
import android.os.IBinder
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import kotlinx.coroutines.*
import tech.anl.library.model.entities.App
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.*
import tech.anl.terminal.TerminalLauncher
import tech.anl.terminal.TerminalSpec
import tech.anl.library.desktop.DesktopViewer
import tech.anl.library.companion.VmEndpoints
import tech.anl.library.companion.VmLaunchOptions
import tech.anl.library.companion.VmResult
import tech.anl.library.companion.VmSessionManager
import tech.anl.library.companion.VmSessionManagers
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.proot.DroidFilesGrants
import tech.anl.library.proot.DroidFilesServer
import tech.anl.library.proot.KillReport
import java.io.File
import java.util.*
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.coroutines.CoroutineContext

class ServerService : Service(), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default + job

    companion object {
        const val SERVER_SERVICE_RESULT: String = "tech.anl.library.ServerService.RESULT"

        // Session ids to bring back after a reboot when "Start on boot" is on.
        private const val AUTOSTART_SESSIONS_KEY = "andlin_autostart_session_ids"
        private const val SESSION_WATCH_INTERVAL_MS = 5_000L

        fun autostartSessionIds(context: Context): Set<Long> =
            context.defaultSharedPreferences.getStringSet(AUTOSTART_SESSIONS_KEY, emptySet())!!
                .mapNotNull { it.toLongOrNull() }.toSet()
    }

    private val activeSessions: MutableMap<Long, Session> = mutableMapOf()

    private lateinit var lastSession: Session

    private lateinit var broadcaster: LocalBroadcastManager

    private val notificationManager: NotificationConstructor by lazy {
        NotificationConstructor(this)
    }

    private val anlFiles by lazy { AnlFiles(this, this.applicationInfo.nativeLibraryDir) }

    private val busyboxExecutor by lazy {
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, anlFiles)
        BusyboxExecutor(anlFiles, prootDebugLogger)
    }

    private val localServerManager by lazy {
        LocalServerManager(this.filesDir.path, busyboxExecutor, this.defaultSharedPreferences)
    }

    private val droidFilesServer by lazy { DroidFilesServer(this, anlFiles.supportDir, DroidFilesGrants(this)) }

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // Sessions this service is stopping on purpose, so their deaths aren't reported as crashes.
    private val deliberatelyStopped = mutableSetOf<Long>()

    override fun onCreate() {
        broadcaster = LocalBroadcastManager.getInstance(this)
        notificationManager.createServiceNotificationChannel()
        AndlinScripts.install(this, anlFiles.supportDir)
        try {
            droidFilesServer.start()
        } catch (err: Exception) {
            LogcatLogger().addExceptionBreadcrumb(err)
        }
        launch { watchSessions() }
        launch { stopOrphanedVmSessions() }
    }

    // VMs outlive this service if the app was force-stopped; stop any this process doesn't know.
    private suspend fun stopOrphanedVmSessions() {
        VmSessionManagers.all(this).forEach { manager ->
            try {
                manager.stopOrphanedSessions(activeSessions.values.map { it.filesystemId }.toSet())
            } catch (err: Exception) {
                LogcatLogger().addExceptionBreadcrumb(err)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)

        when (intent?.getStringExtra("type")) {
            "start" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                val launchClient = intent.getBooleanExtra("launchClient", true)
                val vmOptions: VmLaunchOptions? = intent.getParcelableExtra("vmOptions")
                this.launch { startSession(session, launchClient, vmOptions) }
            }
            "startAutostartSessions" -> this.launch { startAutostartSessions() }
            "stopApp" -> {
                val app: App = intent.getParcelableExtra("app")!!
                stopApp(app)
            }
            "restartRunningSession" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                this.launch { reconnectOrRestart(session) }
            }
            "kill" -> {
                val session: Session = intent.getParcelableExtra("session")!!
                killSession(session)
            }
            "filesystemIsBeingDeleted" -> {
                val filesystemId: Long = intent.getLongExtra("filesystemId", -1)
                cleanUpFilesystem(filesystemId)
            }
            "stopAll" -> {
                activeSessions.values.toList().forEach { session ->
                    killSession(session)
                }
            }
        }

        return START_STICKY
    }

    private fun keepSessionsRunning(): Boolean =
        defaultSharedPreferences.getBoolean("pref_keep_sessions_running", true)

    // A server should outlive the UI, so swiping the app away only stops sessions when the user
    // has turned "Keep sessions running" off.
    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        if (keepSessionsRunning() && activeSessions.isNotEmpty()) return
        activeSessions.values.toList().forEach { killSession(it) }
        this.coroutineContext.cancel()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        releaseLocks()
        droidFilesServer.stop()
        // Redundancy to ensure no hanging processes, given broad device spectrum.
        this.coroutineContext.cancel()
    }

    private fun acquireLocks() {
        if (!keepSessionsRunning()) return
        if (wakeLock == null) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AndLin:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
        if (wifiLock == null) {
            val wifiManager = applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "AndLin:sessions").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseLocks() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wakeLock = null
        wifiLock?.takeIf { it.isHeld }?.release()
        wifiLock = null
    }

    private fun removeSession(session: Session) {
        activeSessions.remove(session.pid)
        if (activeSessions.isEmpty()) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun updateSession(session: Session) = CoroutineScope(Dispatchers.Default).launch {
        AnlDatabase.getInstance(this@ServerService).sessionDao().updateSession(session)
    }

    private fun rememberForAutostart(session: Session, running: Boolean) {
        val ids = autostartSessionIds(this).toMutableSet()
        if (running) ids.add(session.id) else ids.remove(session.id)
        defaultSharedPreferences.edit().putStringSet(AUTOSTART_SESSIONS_KEY, ids.map { it.toString() }.toSet()).apply()
    }

    private fun killSession(session: Session) {
        deliberatelyStopped.add(session.id)
        val vmManager = vmManagers[session.id]
        if (vmManager != null) {
            vmManagers.remove(session.id)
            vmEndpoints.remove(session.id)
            launch {
                if (!vmManager.stopSession(session)) sendVmFailure(getString(R.string.vm_stop_failed))
            }
        } else {
            KillReport.markDeliberateStop(killReportFor(session))
            localServerManager.stopService(session)
        }
        if (session.serviceType == ServiceType.Ssh) TerminalLauncher.closeAll(this)
        removeSession(session)
        rememberForAutostart(session, running = false)
        session.active = false
        updateSession(session)
    }

    private fun killReportFor(session: Session): File =
        busyboxExecutor.busyboxWrapper.killReportFile(File(filesDir, session.filesystemId.toString()))

    private suspend fun startSession(session: Session, launchClient: Boolean = true, vmOptions: VmLaunchOptions? = null) {
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        acquireLocks()
        deliberatelyStopped.remove(session.id)
        val filesystem = withContext(Dispatchers.IO) {
            AnlDatabase.getInstance(this@ServerService).filesystemDao().getFilesystemById(session.filesystemId)
        }
        if (filesystem != null && filesystem.executionType.isVm) {
            startVmSession(session, filesystem, launchClient, vmOptions ?: VmLaunchOptions.DEFAULT)
            return
        }
        KillReport.rotate(killReportFor(session))
        session.pid = localServerManager.startServer(session)

        while (!localServerManager.isServerRunning(session)) {
            delay(500)
        }

        session.active = true
        updateSession(session)
        activeSessions[session.pid] = session
        lastSession = session
        rememberForAutostart(session, running = true)
        if (launchClient) startClient(session)
    }

    // Maps session id to the companion driving it, for VM sessions.
    private val vmManagers = mutableMapOf<Long, VmSessionManager>()
    private val vmEndpoints = mutableMapOf<Long, VmEndpoints>()

    private suspend fun startVmSession(session: Session, filesystem: Filesystem, launchClient: Boolean, options: VmLaunchOptions) {
        val manager = VmSessionManagers.forExecutionType(this, filesystem.executionType) ?: run {
            sendVmFailure(getString(R.string.vm_backend_unavailable))
            removeSessionIfIdle()
            return
        }
        val imageRef = FilesystemImages.imageRef(filesystem.distributionType, filesystem.flavor)
        val progress: (String) -> Unit = { message -> sendVmProgress(message) }

        val setup = manager.setupFilesystem(filesystem, imageRef, progress)
        if (setup is VmResult.Failure) {
            sendVmFailure(setup.message)
            removeSessionIfIdle()
            return
        }
        val appScript = File(filesDir, "apps/${session.name}/${session.name}.sh").takeIf { it.exists() }?.readText()
        val started = manager.startSession(session, filesystem, options, session.geometry, appScript, progress)
        when (started) {
            is VmResult.Failure -> {
                sendVmFailure(started.message)
                removeSessionIfIdle()
            }
            is VmResult.Success -> {
                vmManagers[session.id] = manager
                vmEndpoints[session.id] = started.endpoints
                // VM sessions have no local server process; key them by a negative pseudo-pid.
                session.pid = -session.id
                session.active = true
                updateSession(session)
                activeSessions[session.pid] = session
                lastSession = session
                rememberForAutostart(session, running = true)
                if (launchClient) startClient(session)
            }
        }
    }

    private fun removeSessionIfIdle() {
        if (activeSessions.isEmpty()) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun sendVmProgress(message: String) {
        broadcaster.sendBroadcast(Intent(SERVER_SERVICE_RESULT).putExtra("type", "vmProgress").putExtra("message", message))
    }

    private fun sendVmFailure(message: String) {
        broadcaster.sendBroadcast(Intent(SERVER_SERVICE_RESULT).putExtra("type", "vmFailed").putExtra("message", message))
    }

    private suspend fun isSessionAlive(session: Session): Boolean {
        val manager = vmManagers[session.id] ?: return localServerManager.isServerRunning(session)
        val filesystem = withContext(Dispatchers.IO) {
            AnlDatabase.getInstance(this@ServerService).filesystemDao().getFilesystemById(session.filesystemId)
        } ?: return false
        return manager.isRunning(filesystem)
    }

    /** Called after a reboot: restart the sessions that were running, without opening clients. */
    private suspend fun startAutostartSessions() {
        // Started with startForegroundService(), so this must happen before any slow work.
        startForeground(NotificationConstructor.serviceNotificationId, notificationManager.buildPersistentServiceNotification())
        val sessionDao = AnlDatabase.getInstance(this).sessionDao()
        val ids = autostartSessionIds(this)
        val sessions = withContext(Dispatchers.IO) { sessionDao.getAllSessionsList() }
            .filter { it.id in ids }
        if (sessions.isEmpty()) {
            stopSelf()
            return
        }
        sessions.forEach { startSession(it, launchClient = false) }
    }

    // The server behind a session can die while the client is closed (the phantom process
    // killer, OOM, a crash). Reconnecting would only hit a closed port, so start it again.
    private suspend fun reconnectOrRestart(session: Session) {
        val running = activeSessions.values.firstOrNull { it.id == session.id }
        if (running != null && isSessionAlive(running)) {
            startClient(running)
            return
        }
        running?.let { removeSessionQuietly(it) }
        startSession(session)
    }

    private fun removeSessionQuietly(session: Session) {
        activeSessions.remove(session.pid)
    }

    private suspend fun watchSessions() {
        while (true) {
            delay(SESSION_WATCH_INTERVAL_MS)
            activeSessions.values.toList().forEach { session ->
                if (session.id in deliberatelyStopped) return@forEach
                if (isSessionAlive(session)) return@forEach
                handleUnexpectedDeath(session)
            }
        }
    }

    private fun handleUnexpectedDeath(session: Session) {
        removeSessionQuietly(session)
        session.active = false
        updateSession(session)
        if (vmManagers.remove(session.id) != null) {
            vmEndpoints.remove(session.id)
            broadcaster.sendBroadcast(Intent(SERVER_SERVICE_RESULT).putExtra("type", "sessionDied")
                .putExtra("sessionId", session.id).putExtra("killedByHost", false))
            return
        }
        val report = KillReport.parse(killReportFor(session))
        val intent = Intent(SERVER_SERVICE_RESULT)
            .putExtra("type", "sessionDied")
            .putExtra("sessionId", session.id)
            .putExtra("killedByHost", KillReport.wasKilledByHost(report))
        broadcaster.sendBroadcast(intent)
        if (activeSessions.isEmpty()) {
            releaseLocks()
            stopForeground(STOP_FOREGROUND_REMOVE)
        }
    }

    private fun stopApp(app: App) {
        val appSessions = activeSessions.filter { (_, session) ->
            session.name == app.name
        }
        appSessions.forEach { (_, session) ->
            killSession(session)
        }
    }

    private fun startClient(session: Session) {
        when (session.serviceType) {
            ServiceType.Ssh -> startSshClient(session)
            ServiceType.Vnc -> startVncClient(session)
            else -> sendDialogBroadcast("unhandledSessionServiceType")
        }
        sendSessionActivatedBroadcast()
    }

    private fun startSshClient(session: Session) {
        val home = File(filesDir, "terminal_home").apply { mkdirs() }
        // Generated inside the guest by andlin_startSSHServer.sh and authorized for the default
        // user, so the terminal still gets in when password logins are turned off.
        val clientKey = File(filesDir, "${session.filesystemId}/support/andlin_client_key")
        val sshPort = vmEndpoints[session.id]?.sshPort ?: 2022
        val arguments = mutableListOf("-y", "-y", "-p", sshPort.toString())
        if (clientKey.exists()) arguments += listOf("-i", clientKey.absolutePath)
        arguments += "${session.username}@127.0.0.1"
        val spec = TerminalSpec(
            executable = File(anlFiles.supportDir, "dbclient").absolutePath,
            // -y -y: accept the server's host key even if it changed, since reinstalling a
            // filesystem regenerates its dropbear keys.
            arguments = arguments,
            environment = listOf(
                "HOME=${home.absolutePath}",
                "TERM=xterm-256color",
                "LANG=en_US.UTF-8",
                "LD_LIBRARY_PATH=${anlFiles.supportDir.absolutePath}",
                "DROPBEAR_PASSWORD=${session.password}"
            ),
            workingDir = home.absolutePath,
            title = session.name,
            sessionKey = "session-${session.id}",
            banner = if (session.id in vmEndpoints) getString(R.string.vm_terminal_banner) else null
        )
        TerminalLauncher.launch(this, spec)
    }

    private fun startVncClient(session: Session) {
        if (!DesktopSupport.isEnabled(this)) {
            sendDialogBroadcast("desktopUnavailable")
            return
        }
        val prefs = this.defaultSharedPreferences
        DesktopViewer.launch(
            context = this,
            host = "127.0.0.1",
            port = vmEndpoints[session.id]?.vncPort ?: (5900 + BuildConfig.VNC_DISPLAY.toInt()),
            password = session.vncPassword,
            inputMode = prefs.getString("pref_default_vnc_input_mode", BuildConfig.DEFAULT_VNC_INPUT_MODE)
                ?: BuildConfig.DEFAULT_VNC_INPUT_MODE,
            hideToolbar = prefs.getBoolean("pref_hide_vnc_toolbar", BuildConfig.DEFAULT_HIDE_VNC_TOOLBAR),
            hideExtraKeys = prefs.getBoolean("pref_hide_vnc_extra_keys", BuildConfig.DEFAULT_HIDE_VNC_EXTRA_KEYS),
            title = session.name
        )
    }

    private fun cleanUpFilesystem(filesystemId: Long) {
        activeSessions.values.filter { it.filesystemId == filesystemId }
                .forEach { killSession(it) }
    }

    private fun sendSessionActivatedBroadcast() {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "sessionActivated")
        broadcaster.sendBroadcast(intent)
    }

    private fun sendDialogBroadcast(type: String) {
        val intent = Intent(SERVER_SERVICE_RESULT)
                .putExtra("type", "dialog")
                .putExtra("dialogType", type)
        broadcaster.sendBroadcast(intent)
    }
}
