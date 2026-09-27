package tech.anl.library.utils

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

/**
 * What this app's merged manifest declares, for features that exist in one distribution and not
 * another. The Google Play build (the host app's playRelease build type) strips permissions Play
 * does not allow; the library has no build type of its own, so it asks at run time instead.
 * Never request, or offer a feature that needs, a permission that is not declared here.
 */
object DeclaredPermissions {
    @Volatile private var cached: Set<String>? = null

    fun all(context: Context): Set<String> = cached ?: load(context).also { cached = it }

    fun isDeclared(context: Context, permission: String): Boolean = permission in all(context)

    /**
     * Whether this build may download and install the VM/QEMU companion apps. The Play build
     * does not declare REQUEST_INSTALL_PACKAGES: Play forbids installing apps from outside Play.
     */
    fun canInstallCompanionApps(context: Context): Boolean =
        isDeclared(context, Manifest.permission.REQUEST_INSTALL_PACKAGES)

    fun canRecordAudio(context: Context): Boolean = isDeclared(context, Manifest.permission.RECORD_AUDIO)

    fun canUseCamera(context: Context): Boolean = isDeclared(context, Manifest.permission.CAMERA)

    private fun load(context: Context): Set<String> = try {
        val pm = context.packageManager
        val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(PackageManager.GET_PERMISSIONS.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
        }
        info.requestedPermissions?.toSet() ?: emptySet()
    } catch (e: PackageManager.NameNotFoundException) {
        emptySet()
    }
}
