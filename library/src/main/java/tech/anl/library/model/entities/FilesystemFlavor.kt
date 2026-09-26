package tech.anl.library.model.entities

import java.io.File
import java.util.Locale

/**
 * One variant of a distribution's filesystem image, as listed in an app's flavors.txt:
 *
 *     Release Name, Display Name, isPaid
 *     default, Minimal, false
 *     xfce, XFCE, true
 */
data class FilesystemFlavor(val name: String, val displayName: String, val isPaid: Boolean) {
    companion object {
        const val DEFAULT = "default"
        const val SERVER = "server"

        /** The headless image every distribution gets; see FilesystemImages. */
        val serverFlavor = FilesystemFlavor(SERVER, "Server (headless)", isPaid = false)

        val defaultFlavor = FilesystemFlavor(DEFAULT, "Minimal", isPaid = false)

        fun parse(contents: String): List<FilesystemFlavor> =
            contents.lines()
                .drop(1) // header
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .mapNotNull { line ->
                    val fields = line.split(",").map { it.trim() }
                    if (fields.size < 2 || fields[0].isEmpty()) return@mapNotNull null
                    FilesystemFlavor(
                        name = fields[0].lowercase(Locale.ENGLISH),
                        displayName = fields[1],
                        isPaid = fields.getOrNull(2).toBoolean()
                    )
                }

        /**
         * The flavors to offer for an app. Distributions (those with a flavors.txt) always start
         * with the headless server image; their desktop images from flavors.txt are only offered
         * when [desktopEnabled]. Apps without a flavors.txt get the server image, or the minimal
         * desktop one when desktop sessions are on, since GUI apps need its X server.
         */
        fun readForApp(filesDir: File, appName: String, desktopEnabled: Boolean): List<FilesystemFlavor> {
            val file = File(filesDir, "apps/$appName/flavors.txt")
            if (!file.exists()) return listOf(if (desktopEnabled) defaultFlavor else serverFlavor)
            val listed = parse(file.readText())
            return listOf(serverFlavor) + if (desktopEnabled) listed.ifEmpty { listOf(defaultFlavor) } else emptyList()
        }
    }
}
