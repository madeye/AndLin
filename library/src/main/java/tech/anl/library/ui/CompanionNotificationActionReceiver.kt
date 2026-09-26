package tech.anl.library.ui

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.util.Log
import android.widget.Toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.anl.library.MainActivity
import tech.anl.library.R
import tech.anl.library.ServerService
import tech.anl.library.companion.VmSessionManagers
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.model.entities.Session
import tech.anl.library.model.entities.toServiceType
import tech.anl.library.model.repositories.AnlDatabase

/**
 * Target of the companion apps' own session-notification actions. UserLAnd VM / UserLAnd QEMU send
 * package-scoped broadcasts (setPackage("tech.anl")), so this receiver must be exported with an
 * intent filter for both actions. The companions don't stop anything themselves on "Stop Session";
 * that is this app's job.
 *
 * No permission guards it (the companions hold none we could require); the worst another app can
 * do with it is stop a session or open settings, both of which the user can undo.
 */
class CompanionNotificationActionReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP_SESSION -> {
                val sessionId = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (sessionId < 0) return
                val pending = goAsync()
                scope.launch {
                    try {
                        stopSession(context.applicationContext, sessionId)
                    } catch (e: Exception) {
                        Log.e(TAG, "Stopping session $sessionId failed", e)
                    } finally {
                        pending.finish()
                    }
                }
            }
            ACTION_OPEN_SETTINGS -> {
                val open = Intent(context, MainActivity::class.java)
                    .setType("settings")
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                context.startActivity(open)
            }
        }
    }

    private suspend fun stopSession(context: Context, sessionId: Long) {
        val session = loadSession(context, sessionId) ?: run {
            Log.w(TAG, "No session $sessionId")
            return
        }
        val killIntent = Intent(context, ServerService::class.java)
            .putExtra("type", "kill")
            .putExtra("session", session)
        val handedOff = try {
            context.startService(killIntent) != null
        } catch (e: IllegalStateException) {
            // Background start restrictions: ServerService isn't running (e.g. the app was killed
            // while the VM kept running in the companion). Stop the VM directly instead.
            false
        } catch (e: SecurityException) {
            false
        }
        if (handedOff) return

        val db = AnlDatabase.getInstance(context)
        val filesystemType = loadExecutionType(context, session.filesystemId)
        val manager = filesystemType?.let { VmSessionManagers.forExecutionType(context, it) }
        val stopped = manager?.stopSession(session) ?: false
        if (stopped) {
            session.active = false
            db.sessionDao().updateSession(session)
        } else {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, R.string.companion_stop_session_failed, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /** SessionDao has no by-id query; read the row directly. */
    private fun loadSession(context: Context, id: Long): Session? {
        val db = AnlDatabase.getInstance(context).openHelper.readableDatabase
        db.query("SELECT * FROM session WHERE id = ?", arrayOf<Any>(id)).use { c ->
            if (!c.moveToFirst()) return null
            return Session(
                id = c.long("id"),
                name = c.string("name"),
                filesystemId = c.long("filesystemId"),
                filesystemName = c.string("filesystemName"),
                active = c.long("active") != 0L,
                username = c.string("username"),
                password = c.string("password"),
                vncPassword = c.string("vncPassword"),
                serviceType = c.string("serviceType").toServiceType(),
                port = c.long("port"),
                pid = c.long("pid"),
                geometry = c.string("geometry"),
                isAppsSession = c.long("isAppsSession") != 0L,
                isProtected = c.long("isProtected") != 0L
            )
        }
    }

    private fun loadExecutionType(context: Context, filesystemId: Long): ExecutionType? {
        val db = AnlDatabase.getInstance(context).openHelper.readableDatabase
        db.query("SELECT executionType FROM filesystem WHERE id = ?", arrayOf<Any>(filesystemId)).use { c ->
            if (!c.moveToFirst()) return null
            return ExecutionType.fromString(c.getString(0))
        }
    }

    private fun Cursor.string(column: String): String {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) "" else getString(i)
    }

    private fun Cursor.long(column: String): Long {
        val i = getColumnIndex(column)
        return if (i < 0 || isNull(i)) 0L else getLong(i)
    }

    companion object {
        private const val TAG = "CompanionNotifAction"
        const val ACTION_STOP_SESSION = "tech.ula.STOP_SESSION"
        const val ACTION_OPEN_SETTINGS = "tech.ula.OPEN_SETTINGS"
        const val EXTRA_SESSION_ID = "sessionId"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
