package tech.anl.library.adb

import android.os.Build
import android.util.Log
import tech.anl.library.companion.CompanionApp
import java.io.File

/** The one-off ADB jobs AndLin runs through [AdbSetupFlow]. Each returns a user-facing error or null. */
object AdbJobs {
    private const val TAG = "AdbJobs"

    /** Streams [apk] to the package manager. */
    suspend fun installCompanion(adb: AdbClient, apk: File, onProgress: (Long, Long) -> Unit): String? {
        val (ok, out) = adb.install(apk, onProgress)
        return if (ok) null else "Installing failed: ${out.lines().lastOrNull { it.isNotBlank() } ?: "unknown error"}"
    }

    /**
     * Grants what the companion never asks for itself. Failures of individual grants are collected
     * rather than aborting, since some are optional on a given device (e.g. POST_NOTIFICATIONS
     * before Android 13). Returns the list of problems (empty = all good).
     */
    suspend fun grantCompanionPermissions(adb: AdbClient, app: CompanionApp): List<String> {
        val problems = mutableListOf<String>()
        val pkg = app.packageName
        suspend fun must(label: String, cmd: String) {
            val (code, out) = adb.shell(cmd)
            if (code != 0) {
                Log.w(TAG, "$cmd -> $code: $out")
                problems += "$label: ${out.ifBlank { "exit $code" }}"
            }
        }
        suspend fun optional(cmd: String) {
            val (code, out) = adb.shell(cmd)
            if (code != 0) Log.i(TAG, "optional '$cmd' -> $code: $out")
        }
        if (app == CompanionApp.VM) {
            must("MANAGE_VIRTUAL_MACHINE", "pm grant $pkg android.permission.MANAGE_VIRTUAL_MACHINE")
            must("USE_CUSTOM_VIRTUAL_MACHINE", "pm grant $pkg android.permission.USE_CUSTOM_VIRTUAL_MACHINE")
            relaxHiddenApiPolicyIfNeeded(adb)?.let { problems += it }
        }
        // All files access, for sharing the device's storage into the VM (the companion opens the files).
        adb.shell("appops set $pkg MANAGE_EXTERNAL_STORAGE allow").let { (code, out) ->
            if (code != 0) {
                // Older builds only accept the uid form.
                val (code2, out2) = adb.shell("appops set --uid $pkg MANAGE_EXTERNAL_STORAGE allow")
                if (code2 != 0) problems += "MANAGE_EXTERNAL_STORAGE: ${out2.ifBlank { out }}"
            }
        }
        if (Build.VERSION.SDK_INT >= 33) {
            optional("pm grant $pkg android.permission.POST_NOTIFICATIONS")
        }
        return problems
    }

    /**
     * UserLAnd VM reaches @hide AVF APIs (custom-image VM config) by reflection. Devices whose
     * global hidden_api_policy is unset, or where reflective access is blocked, need it relaxed.
     * Values: 0 default, 1 disabled (all allowed), 2 warn only, 3 enforced.
     */
    suspend fun relaxHiddenApiPolicyIfNeeded(adb: AdbClient): String? {
        val (_, current) = adb.shell("settings get global hidden_api_policy")
        val value = current.trim()
        val permissive = value == "1" || value == "2"
        val missing = value.isEmpty() || value == "null"
        if (permissive) return null
        if (!missing && HiddenApiProbe.reflectiveAccessWorks()) return null
        val (code, out) = adb.shell("settings put global hidden_api_policy 1")
        return if (code == 0) null else "hidden_api_policy: ${out.ifBlank { "exit $code" }}"
    }

    /** Turns Android's phantom process killer off for good. Returns an error or null. */
    suspend fun disablePhantomProcessKiller(adb: AdbClient): String? {
        val errors = mutableListOf<String>()
        // Keep device_config from being overwritten by the next server sync (Android 12+).
        adb.shell("device_config set_sync_disabled_for_tests persistent").let { (code, out) ->
            if (code != 0) Log.i(TAG, "set_sync_disabled_for_tests unsupported: $out")
        }
        adb.shell("device_config put activity_manager max_phantom_processes 2147483647").let { (code, out) ->
            if (code != 0) errors += out.ifBlank { "device_config exit $code" }
        }
        if (Build.VERSION.SDK_INT >= 32) {
            adb.shell("settings put global settings_enable_monitor_phantom_procs false").let { (code, out) ->
                if (code != 0) errors += out.ifBlank { "settings exit $code" }
            }
        }
        return errors.takeIf { it.isNotEmpty() }?.joinToString("\n")
    }
}

/** Whether this process (same target SDK as the companions) may reflect into @hide AVF classes. */
object HiddenApiProbe {
    fun reflectiveAccessWorks(): Boolean {
        if (Build.VERSION.SDK_INT < 34) return true
        return try {
            val builder = Class.forName("android.system.virtualmachine.VirtualMachineCustomImageConfig\$Builder")
            builder.declaredMethods.isNotEmpty()
        } catch (e: Throwable) {
            false
        }
    }
}
