package tech.anl.terminal

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat

/** What to run in a terminal session. [arguments] are argv[1..]; [environment] holds "KEY=VALUE" entries. */
data class TerminalSpec(
    val executable: String,
    val arguments: List<String>,
    val environment: List<String>,
    val workingDir: String,
    val title: String,
    val sessionKey: String,
    val banner: String? = null,
)

/** Entry point used by the rest of the app to open and close terminal sessions. */
object TerminalLauncher {
    const val EXTRA_EXECUTABLE = "tech.anl.terminal.EXECUTABLE"
    const val EXTRA_ARGUMENTS = "tech.anl.terminal.ARGUMENTS"
    const val EXTRA_ENVIRONMENT = "tech.anl.terminal.ENVIRONMENT"
    const val EXTRA_WORKING_DIR = "tech.anl.terminal.WORKING_DIR"
    const val EXTRA_TITLE = "tech.anl.terminal.TITLE"
    const val EXTRA_BANNER = "tech.anl.terminal.BANNER"
    const val EXTRA_SESSION_KEY = "tech.anl.terminal.SESSION_KEY"

    private const val TAG = "TerminalLauncher"

    /**
     * Starts (or re-attaches to, if [TerminalSpec.sessionKey] is live) a session and brings
     * up [TerminalActivity]. Safe to call from a Service.
     */
    fun launch(context: Context, spec: TerminalSpec) {
        val app = context.applicationContext
        try {
            ContextCompat.startForegroundService(app, Intent(app, TerminalService::class.java).putSpec(spec))
        } catch (e: RuntimeException) {
            // Background start restrictions: the activity starts the service once it is visible.
            Log.w(TAG, "Deferring terminal service start", e)
        }
        val activity = Intent(app, TerminalActivity::class.java)
            .putSpec(spec)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(activity)
    }

    /**
     * Kills every session immediately (no exit prompt), closes [TerminalActivity] and stops
     * [TerminalService].
     */
    fun closeAll(context: Context) {
        val app = context.applicationContext
        val work = Runnable {
            TerminalSessions.closeAll()
            app.stopService(Intent(app, TerminalService::class.java))
        }
        if (Looper.myLooper() == Looper.getMainLooper()) work.run() else Handler(Looper.getMainLooper()).post(work)
    }

    internal fun Intent.putSpec(spec: TerminalSpec): Intent = apply {
        putExtra(EXTRA_EXECUTABLE, spec.executable)
        putExtra(EXTRA_ARGUMENTS, spec.arguments.toTypedArray())
        putExtra(EXTRA_ENVIRONMENT, spec.environment.toTypedArray())
        putExtra(EXTRA_WORKING_DIR, spec.workingDir)
        putExtra(EXTRA_TITLE, spec.title)
        putExtra(EXTRA_SESSION_KEY, spec.sessionKey)
        spec.banner?.let { putExtra(EXTRA_BANNER, it) }
    }

    internal fun Intent.getSpec(): TerminalSpec? {
        val executable = getStringExtra(EXTRA_EXECUTABLE) ?: return null
        return TerminalSpec(
            executable = executable,
            arguments = getStringArrayExtra(EXTRA_ARGUMENTS)?.toList().orEmpty(),
            environment = getStringArrayExtra(EXTRA_ENVIRONMENT)?.toList().orEmpty(),
            workingDir = getStringExtra(EXTRA_WORKING_DIR) ?: "/",
            title = getStringExtra(EXTRA_TITLE) ?: executable.substringAfterLast('/'),
            sessionKey = getStringExtra(EXTRA_SESSION_KEY) ?: executable,
            banner = getStringExtra(EXTRA_BANNER),
        )
    }
}
