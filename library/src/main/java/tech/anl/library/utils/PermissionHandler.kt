package tech.anl.library.utils

import android.Manifest
import android.annotation.TargetApi
import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.R

class PermissionHandler {
    companion object {
        private const val permissionRequestCode = 1234
        private const val optionalPermissionRequestCode = 1235
        private const val optionalPermissionsAskedPref = "optional_permissions_asked"
        private const val storageRequestedPref = "storage_permissions_requested"

        /**
         * Storage permission only matters up to Android 9: downloads land in the app's own
         * external files dir and /sdcard goes through droid_files, and the manifest caps
         * WRITE_EXTERNAL_STORAGE at maxSdkVersion 28, so on newer releases it can never be
         * granted and must not gate anything.
         */
        fun permissionsAreGranted(context: Context): Boolean {
            if (Build.VERSION.SDK_INT > Build.VERSION_CODES.P) return true
            return (
                        ContextCompat.checkSelfPermission(context,
                                Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&

                        ContextCompat.checkSelfPermission(context,
                                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
                    )
        }

        fun isStoragePermissionRequest(requestCode: Int) = requestCode == permissionRequestCode

        /**
         * Asks once, without blocking anything, for the permissions sessions can use but don't
         * need: notifications (the session service's notification) and, where the build uses it,
         * the microphone (pulseaudio input).
         */
        fun requestOptionalPermissions(activity: Activity) {
            val prefs = activity.getSharedPreferences("permissions", Context.MODE_PRIVATE)
            if (prefs.getBoolean(optionalPermissionsAskedPref, false)) return
            val wanted = mutableListOf<String>()
            if (BuildConfig.USES_MICROPHONE) wanted += Manifest.permission.RECORD_AUDIO
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                wanted += Manifest.permission.POST_NOTIFICATIONS
            }
            val missing = wanted.filter {
                ContextCompat.checkSelfPermission(activity, it) != PackageManager.PERMISSION_GRANTED
            }
            prefs.edit().putBoolean(optionalPermissionsAskedPref, true).apply()
            if (missing.isNotEmpty()) {
                activity.requestPermissions(missing.toTypedArray(), optionalPermissionRequestCode)
            }
        }

        /**
         * Checks the actual grant state rather than indexing into grantResults, which is empty
         * when the request was interrupted (e.g. by another request already in flight).
         */
        fun permissionsWereGranted(context: Context, requestCode: Int): Boolean =
            requestCode == permissionRequestCode && permissionsAreGranted(context)

        @TargetApi(Build.VERSION_CODES.M)
        fun showPermissionsNecessaryDialog(activity: Activity) {
            val builder = AlertDialog.Builder(activity)
            builder.setMessage(R.string.alert_permissions_necessary_message)
                    .setTitle(R.string.alert_permissions_necessary_title)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        dialog.dismiss()
                        if (activity.getSharedPreferences("permissions", Context.MODE_PRIVATE)
                                .getBoolean(storageRequestedPref, false) &&
                            !activity.shouldShowRequestPermissionRationale(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                        ) {
                            // Denied with "don't ask again": requestPermissions would fail
                            // instantly without showing anything, so send the user to settings.
                            openAppSettings(activity)
                            return@setPositiveButton
                        }
                        activity.getSharedPreferences("permissions", Context.MODE_PRIVATE).edit()
                                .putBoolean(storageRequestedPref, true).apply()
                        activity.requestPermissions(arrayOf(
                                Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                                permissionRequestCode)
                    }
                    .setNegativeButton(R.string.alert_permissions_necessary_cancel_button) { dialog, _ ->
                        dialog.dismiss()
                    }
            builder.create().show()
        }

        private fun openAppSettings(activity: Activity) {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", activity.packageName, null))
            try {
                activity.startActivity(intent)
            } catch (e: ActivityNotFoundException) {
                activity.startActivity(Intent(Settings.ACTION_SETTINGS))
            }
        }
    }
}