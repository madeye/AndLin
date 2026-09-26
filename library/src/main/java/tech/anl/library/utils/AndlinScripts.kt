package tech.anl.library.utils

import android.content.Context
import java.io.File

/**
 * Guest-side scripts that ship with the library rather than with the support assets or the
 * filesystem images. They are copied into filesDir/support, which every PRoot session binds at
 * /support/common.
 */
object AndlinScripts {
    const val START_SSH_SERVER = "andlin_startSSHServer.sh"

    private val scripts = mapOf("andlin/startSSHServer.sh" to START_SSH_SERVER)

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
