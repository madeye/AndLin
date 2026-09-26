package tech.anl.library.companion

import android.content.ComponentName
import android.content.Context
import android.content.SharedPreferences
import tech.anl.customlibrary.BuildConfig as CustomBuildConfig
import tech.anl.library.BuildConfig
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.utils.defaultSharedPreferences

/**
 * The separately installed apps that host UserLAnd's VM backends. Each exposes an exported control
 * service with a TCP control socket on localhost; see [CompanionControlClient].
 */
enum class CompanionApp(
    val packageName: String,
    val controlServiceClass: String,
    val controlPort: Int,
    val releaseAssetName: String,
    val executionType: ExecutionType
) {
    VM("tech.ula.vm", "tech.ula.vm.AvfControlService", 17601, "userland-vm.apk", ExecutionType.AVF),
    QEMU("tech.ula.qemu", "tech.ula.qemu.QemuControlService", 17602, "userland-qemu.apk", ExecutionType.QEMU);

    /** "vm" or "qemu": the prefix of this companion's release tags. */
    val releaseTagPrefix: String get() = if (this == VM) "vm" else "qemu"

    val controlComponent: ComponentName get() = ComponentName(packageName, controlServiceClass)

    fun releaseUrl(channel: String): String = "$RELEASES_BASE/$releaseTagPrefix-$channel/$releaseAssetName"

    fun versionUrl(channel: String): String = "$RELEASES_BASE/$releaseTagPrefix-$channel/version.txt"

    companion object {
        const val RELEASES_BASE = "https://github.com/CypherpunkArmory/UserLAnd-Releases/releases/download"

        fun forExecutionType(t: ExecutionType): CompanionApp? = values().firstOrNull { it.executionType == t }

        fun forPackage(packageName: String): CompanionApp? = values().firstOrNull { it.packageName == packageName }
    }
}

/**
 * Which release channel companion apps are installed and update-checked from: "latest" (promoted)
 * or "next" (every push, untested). Comes from the app's BuildConfig; debug builds may override it
 * at runtime so an upgrade can be tested from latest to next.
 */
object CompanionChannel {
    const val PREF_OVERRIDE = "pref_companion_channel_override"
    val KNOWN_CHANNELS = listOf("latest", "next")

    fun get(context: Context): String = resolve(
        CustomBuildConfig.COMPANION_CHANNEL,
        BuildConfig.DEBUG,
        prefs(context).getString(PREF_OVERRIDE, null)
    )

    /** Debug builds only; passing null clears the override. */
    fun setOverride(context: Context, channel: String?) {
        prefs(context).edit().apply {
            if (channel.isNullOrBlank()) remove(PREF_OVERRIDE) else putString(PREF_OVERRIDE, channel)
        }.apply()
    }

    /** Pure resolution rule, separated for unit tests. */
    fun resolve(buildChannel: String, isDebugBuild: Boolean, override: String?): String {
        val base = buildChannel.ifBlank { "latest" }
        if (!isDebugBuild) return base
        val o = override?.trim()
        return if (o != null && o in KNOWN_CHANNELS) o else base
    }

    private fun prefs(context: Context): SharedPreferences = context.defaultSharedPreferences
}
