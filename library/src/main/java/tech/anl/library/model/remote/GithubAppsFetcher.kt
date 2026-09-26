package tech.anl.library.model.remote

import android.content.res.AssetManager
import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.App
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports
import java.io.File
import java.io.IOException
import java.util.Locale

class GithubAppsFetcher(
    private val filesDirPath: String,
    private val assets: AssetManager,
    private val sharedPreferences: SharedPreferences,
    private val httpStream: HttpStream = HttpStream(),
    private val logger: Logger = LogcatLogger(),
    private val appsInAssets: Boolean = BuildConfig.APPS_IN_ASSETS
) {

    // Allows destructing of the list of application elements
    private operator fun <T> List<T>.component6() = get(5)
    private operator fun <T> List<T>.component7() = get(6)
    private operator fun <T> List<T>.component8() = get(7)

    private fun baseUrl(): String {
        var appsUrl = BuildConfig.DEFAULT_APPS_URL
        if (sharedPreferences.getBoolean("pref_custom_apps_enabled", BuildConfig.DEFAULT_CUSTOM_APPS_ENABLED))
            appsUrl = sharedPreferences.getString("pref_apps", BuildConfig.DEFAULT_APPS_URL)!!
        return appsUrl
    }

    @Throws(IOException::class)
    suspend fun fetchAppsList(): List<App> = withContext(Dispatchers.IO) {
        return@withContext try {
            val url = "${baseUrl()}/apps.txt"
            val numLinesToSkip = 1 // Skip first line which defines schema
            var contents: List<String>
            if (appsInAssets) {
                val tempContents = assets.open("apps/apps.txt").bufferedReader().use { it.readText() }
                contents = tempContents.trim().lines()
            } else {
                contents = httpStream.toLines(url)
            }
            contents.drop(numLinesToSkip).map { line ->
                // Destructure app fields
                val (
                        name,
                        category,
                        filesystemRequired,
                        supportsCli,
                        supportsGui,
                        supportsStandalone,
                        isPaidApp,
                        version
                ) = line.toLowerCase(Locale.ENGLISH).split(", ")
                // Construct app
                App(
                        name,
                        category,
                        filesystemRequired,
                        supportsCli.toBoolean(),
                        supportsGui.toBoolean(),
                        supportsStandalone,
                        isPaidApp.toBoolean(),
                        version.toLong()
                )
            }
        } catch (err: Exception) {
            val exception = IOException("Error getting apps list")
            logger.addExceptionBreadcrumb(exception)
            throw exception
        }
    }

    suspend fun fetchAppIcon(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.png"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        if (appsInAssets) {
            file.parentFile!!.mkdirs()
            assets.open("apps/$directoryAndFilename").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, 1024)
                }
            }
        } else {
            val url = "${baseUrl()}/$directoryAndFilename"
            httpStream.toFile(url, file)
        }
    }

    suspend fun fetchAppDescription(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.txt"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        if (appsInAssets) {
            file.parentFile!!.mkdirs()
            assets.open("apps/$directoryAndFilename").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, 1024)
                }
            }
        } else {
            val url = "${baseUrl()}/$directoryAndFilename"
            httpStream.toTextFile(url, file)
        }
    }

    suspend fun fetchAppScript(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/${app.name}.sh"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        if (appsInAssets) {
            file.parentFile!!.mkdirs()
            assets.open("apps/$directoryAndFilename").use { input ->
                file.outputStream().use { output ->
                    input.copyTo(output, 1024)
                }
            }
        } else {
            val url = "${baseUrl()}/$directoryAndFilename"
            httpStream.toTextFile(url, file)
        }
    }

    /**
     * Fetches the optional flavors.txt listing an app's filesystem variants (minimal, XFCE, ...).
     * Most apps don't have one, so a missing file is not an error; any stale copy is removed so
     * the variant picker never offers flavors the source no longer lists.
     */
    suspend fun fetchAppFlavors(app: App) = withContext(Dispatchers.IO) {
        val directoryAndFilename = "${app.name}/flavors.txt"
        val file = File("$filesDirPath/apps/$directoryAndFilename")

        try {
            if (appsInAssets) {
                file.parentFile!!.mkdirs()
                assets.open("apps/$directoryAndFilename").use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output, 1024)
                    }
                }
            } else {
                val url = "${baseUrl()}/$directoryAndFilename"
                httpStream.toTextFile(url, file)
            }
        } catch (err: Exception) {
            file.delete()
        }
    }
}
