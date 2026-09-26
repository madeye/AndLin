package tech.anl.library.companion

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * Talks to one companion app's control socket on 127.0.0.1. Every call opens its own connection,
 * so calls may run concurrently (that is how progress is polled during a blocking setup/start).
 */
class CompanionControlClient(context: Context, val app: CompanionApp) : CompanionConnection {
    private val context = context.applicationContext
    private val transport = CompanionTransport("127.0.0.1", app.controlPort)

    /** Raw call returning the response envelope as an org.json object ({"ok":..., "result":..., "error":...}). */
    suspend fun call(cmd: String, fields: Map<String, Any?> = emptyMap(), timeoutMs: Long): JSONObject {
        val response = request(cmd, fields, timeoutMs)
        return JSONObject().apply {
            put("ok", response.ok)
            response.result?.let { put("result", it) }
            response.error?.let { put("error", it) }
        }
    }

    override suspend fun request(cmd: String, fields: Map<String, Any?>, timeoutMs: Long): CompanionResponse =
        transport.send(CompanionJson.request(cmd, fields), timeoutMs)

    fun isInstalled(): Boolean = isInstalled(context, app)

    override suspend fun isReachable(): Boolean = try {
        // Unknown commands are answered with ok:false, which is still proof of life; getStatus
        // with an unused fsId is cheap and side-effect free.
        request("getStatus", mapOf("fsId" to PROBE_FS_ID), 3_000)
        true
    } catch (e: CompanionException) {
        false
    }

    /**
     * Starts the companion's control service (explicit component; it reads no action or extras)
     * and waits until its port accepts connections.
     */
    override suspend fun wake() {
        if (isReachable()) return
        if (!isInstalled()) throw CompanionNotInstalledException(app)
        val intent = Intent().setComponent(app.controlComponent)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        } catch (e: IllegalStateException) {
            // ForegroundServiceStartNotAllowedException (API 31+) is an IllegalStateException:
            // we're in the background without an exemption. A plain start works if the companion
            // happens to be allowed; otherwise report it.
            Log.w(TAG, "startForegroundService(${app.packageName}) refused", e)
            try {
                context.startService(intent)
            } catch (e2: Exception) {
                throw CompanionUnreachableException("${app.packageName} can't be started while UserLAnd is in the background", e2)
            }
        } catch (e: SecurityException) {
            throw CompanionUnreachableException("Not allowed to start ${app.packageName}", e)
        }
        val deadline = System.currentTimeMillis() + CompanionTimeouts.WAKE_MS
        while (System.currentTimeMillis() < deadline) {
            if (isReachable()) return
            delay(300)
        }
        throw CompanionUnreachableException("${app.packageName} did not open its control port in time")
    }

    companion object {
        private const val TAG = "CompanionControlClient"
        private const val PROBE_FS_ID = "__anl_probe__"

        fun isInstalled(context: Context, app: CompanionApp): Boolean = installedVersion(context, app) != null

        /** (versionName, longVersionCode) of the installed companion, or null. */
        fun installedVersion(context: Context, app: CompanionApp): Pair<String, Long>? = try {
            val info = context.packageManager.getPackageInfo(app.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode else {
                @Suppress("DEPRECATION") info.versionCode.toLong()
            }
            (info.versionName ?: "") to code
        } catch (e: PackageManager.NameNotFoundException) {
            null
        }
    }
}
