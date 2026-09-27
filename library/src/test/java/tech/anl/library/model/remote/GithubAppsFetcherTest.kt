package tech.anl.library.model.remote

import android.content.SharedPreferences
import android.content.res.AssetManager
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.App
import tech.anl.library.utils.HttpStream
import tech.anl.library.utils.Logger
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException

@RunWith(MockitoJUnitRunner::class)
class GithubAppsFetcherTest {

    @get:Rule val tempFolder = TemporaryFolder()

    private lateinit var testFilesDir: File

    @Mock lateinit var mockHttpStream: HttpStream

    @Mock lateinit var logger: Logger

    @Mock lateinit var mockSharedPreferences: SharedPreferences

    @Mock lateinit var mockAssetManager: AssetManager

    private lateinit var githubAppsFetcher: GithubAppsFetcher

    private val baseUrl = BuildConfig.DEFAULT_APPS_URL
    private val appsListUrl = "$baseUrl/apps.txt"

    private val appName = "name"
    private val app = App(name = appName)

    @Before
    fun setup() {
        testFilesDir = tempFolder.root

        // These tests exercise the remote (HttpStream-backed) path, regardless of how
        // APPS_IN_ASSETS is set for this build flavor.
        githubAppsFetcher = GithubAppsFetcher(
            testFilesDir.path,
            mockAssetManager,
            mockSharedPreferences,
            mockHttpStream,
            logger,
            appsInAssets = false
        )
    }

    @Test
    fun `Correctly parses remote apps list`() {
        val appCategory = "category"
        val appFilesystem = "filesystem"
        val supportsCli = "true"
        val supportsGui = "false"
        val supportsStandalone = "false"
        val isPaidApp = "false"
        val appVersion = "1"
        val appString = "$appName, $appCategory, $appFilesystem, $supportsCli, $supportsGui, " +
                "$supportsStandalone, $isPaidApp, $appVersion"
        val appsList = listOf("", appString)

        runBlocking {
            whenever(mockHttpStream.toLines(appsListUrl)).thenReturn(appsList)
        }

        val result = runBlocking {
            githubAppsFetcher.fetchAppsList()
        }

        val expectedApp = App(
                name = appName,
                category = appCategory,
                filesystemRequired = appFilesystem,
                supportsCli = supportsCli.toBoolean(),
                supportsGui = supportsGui.toBoolean(),
                supportsStandalone = supportsStandalone,
                isPaidApp = isPaidApp.toBoolean(),
                version = appVersion.toLong()
        )
        val expectedResult = listOf(expectedApp)
        assertEquals(expectedResult, result)
    }

    @Test(expected = IOException::class)
    fun `Throws exception if fetching apps list fails`() {
        runBlocking {
            whenever(mockHttpStream.toLines(appsListUrl)).thenThrow(IOException())
        }

        runBlocking {
            githubAppsFetcher.fetchAppsList()
        }
    }

    @Test
    fun `fetchAppIcon maps a remote source to a PNG file`() {
        val directoryAndFilename = "${app.name}/${app.name}.png"

        runBlocking {
            githubAppsFetcher.fetchAppIcon(app)
        }

        val expectedUrl = "$baseUrl/$directoryAndFilename"
        val expectedFile = File(testFilesDir.path, "apps/$directoryAndFilename")
        verifyBlocking(mockHttpStream) { toFile(expectedUrl, expectedFile) }
    }

    @Test(expected = IOException::class)
    fun `fetchAppIcon throws IOException on failure`() {
        val directoryAndFilename = "${app.name}/${app.name}.png"
        runBlocking {
            whenever(mockHttpStream.toFile(
                    "$baseUrl/$directoryAndFilename",
                    File(testFilesDir.path, "apps/$directoryAndFilename")
            )).thenThrow(IOException())
        }

        runBlocking {
            githubAppsFetcher.fetchAppIcon(app)
        }
    }

    @Test
    fun `fetchAppDescription maps a remote source to a text file`() {
        val directoryAndFilename = "${app.name}/${app.name}.txt"

        runBlocking {
            githubAppsFetcher.fetchAppDescription(app)
        }

        val expectedUrl = "$baseUrl/$directoryAndFilename"
        val expectedFile = File(testFilesDir.path, "apps/$directoryAndFilename")
        verifyBlocking(mockHttpStream) { toTextFile(expectedUrl, expectedFile) }
    }

    @Test(expected = IOException::class)
    fun `fetchAppDescription throws IOException on failure`() {
        val directoryAndFilename = "${app.name}/${app.name}.txt"
        runBlocking {
            whenever(mockHttpStream.toTextFile(
                    "$baseUrl/$directoryAndFilename",
                    File(testFilesDir.path, "apps/$directoryAndFilename")
            )).thenThrow(IOException())
        }

        runBlocking {
            githubAppsFetcher.fetchAppDescription(app)
        }
    }

    @Test
    fun `fetchAppScript maps a remote source to a text file`() {
        val directoryAndFilename = "${app.name}/${app.name}.sh"

        runBlocking {
            githubAppsFetcher.fetchAppScript(app)
        }

        val expectedUrl = "$baseUrl/$directoryAndFilename"
        val expectedFile = File(testFilesDir.path, "apps/$directoryAndFilename")
        verifyBlocking(mockHttpStream) { toTextFile(expectedUrl, expectedFile) }
    }

    @Test(expected = IOException::class)
    fun `fetchAppScript throws IOException on failure`() {
        val directoryAndFilename = "${app.name}/${app.name}.sh"
        runBlocking {
            whenever(mockHttpStream.toTextFile(
                    "$baseUrl/$directoryAndFilename",
                    File(testFilesDir.path, "apps/$directoryAndFilename")
            )).thenThrow(IOException())
        }

        runBlocking {
            githubAppsFetcher.fetchAppScript(app)
        }
    }

    // --- APPS_IN_ASSETS = true path (this build flavor's default) ---

    private fun fetcherWithAssets(): GithubAppsFetcher = GithubAppsFetcher(
        testFilesDir.path,
        mockAssetManager,
        mockSharedPreferences,
        mockHttpStream,
        logger,
        appsInAssets = true
    )

    @Test
    fun `fetchAppIcon copies the bundled asset when apps are served from assets`() {
        val directoryAndFilename = "${app.name}/${app.name}.png"
        val bytes = byteArrayOf(1, 2, 3, 4)
        whenever(mockAssetManager.open("apps/$directoryAndFilename")).thenReturn(ByteArrayInputStream(bytes))

        runBlocking {
            fetcherWithAssets().fetchAppIcon(app)
        }

        val expectedFile = File(testFilesDir.path, "apps/$directoryAndFilename")
        assertEquals(true, expectedFile.exists())
        assertEquals(bytes.toList(), expectedFile.readBytes().toList())
    }
}
