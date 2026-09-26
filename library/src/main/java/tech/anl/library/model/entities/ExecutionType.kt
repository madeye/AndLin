package tech.anl.library.model.entities

import android.content.Context
import android.os.Build
import androidx.room.TypeConverter

/**
 * Where a filesystem's Linux userspace actually runs.
 *
 * PROOT runs in this app's own process tree. AVF and QEMU run inside a full VM hosted by a
 * separately installed companion app (UserLAnd VM / UserLAnd QEMU), which this app drives over a
 * local control socket; see tech.anl.library.companion.
 */
enum class ExecutionType(val minSupportedSdk: Int) {
    PROOT(Build.VERSION_CODES.LOLLIPOP),
    // The Android Virtualization Framework's app-facing API arrived in Android 14.
    AVF(Build.VERSION_CODES.UPSIDE_DOWN_CAKE),
    // The companion's embedded ADB installer and QEMU build both need API 28's bionic.
    QEMU(Build.VERSION_CODES.P);

    val isVm: Boolean get() = this != PROOT

    fun isSupportedOnThisDevice(): Boolean = Build.VERSION.SDK_INT >= minSupportedSdk

    /**
     * Like [isSupportedOnThisDevice], but also checks what the API level doesn't guarantee: many
     * Android 14+ devices (e.g. Qualcomm phones running Gunyah instead of pKVM) ship without AVF,
     * and QEMU's native code is arm64-only.
     */
    fun isSupportedOnThisDevice(context: Context): Boolean = isSupportedOnThisDevice() && when (this) {
        PROOT -> true
        AVF -> context.packageManager.hasSystemFeature(AVF_FEATURE)
        QEMU -> Build.SUPPORTED_ABIS.contains("arm64-v8a")
    }

    companion object {
        private const val AVF_FEATURE = "android.software.virtualization_framework"

        fun fromString(value: String?): ExecutionType =
            values().firstOrNull { it.name.equals(value, ignoreCase = true) } ?: PROOT
    }
}

class ExecutionTypeConverter {
    @TypeConverter
    fun fromString(value: String?): ExecutionType = ExecutionType.fromString(value)

    @TypeConverter
    fun toString(value: ExecutionType): String = value.name
}
