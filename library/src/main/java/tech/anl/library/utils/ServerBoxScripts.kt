package tech.anl.library.utils

import android.content.Context
import java.io.File

/**
 * Guest-side scripts that ship with the library rather than with the support assets or the
 * filesystem images. They are copied into filesDir/support, which every PRoot session binds at
 * /support/common.
 */
object ServerBoxScripts {
    const val START_SSH_SERVER = "serverbox_startSSHServer.sh"
    const val RENAME_LEGACY_USER = "serverbox_renameLegacyUser.sh"

    private val scripts = mapOf(
        "serverbox/startSSHServer.sh" to START_SSH_SERVER,
        "serverbox/renameLegacyUser.sh" to RENAME_LEGACY_USER
    )

    fun install(context: Context, supportDir: File) {
        supportDir.mkdirs()
        scripts.forEach { (asset, name) ->
            val target = File(supportDir, name)
            context.assets.open(asset).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.setExecutable(true, false)
            target.setReadable(true, false)
        }
    }
}
