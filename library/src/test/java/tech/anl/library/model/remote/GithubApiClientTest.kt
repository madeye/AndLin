package tech.anl.library.model.remote

import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import com.squareup.moshi.Moshi
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.utils.Logger
import tech.anl.library.utils.AnlFiles
import java.io.IOException
import java.util.TimeZone

@RunWith(MockitoJUnitRunner::class)
class GithubApiClientTest {

    @get:Rule val server = MockWebServer()

    @Mock lateinit var mockAnlFiles: AnlFiles

    @Mock lateinit var mockUrlProvider: UrlProvider

    @Mock lateinit var mockLogger: Logger

    private val moshi = Moshi.Builder().build()

    private lateinit var githubApiClient: GithubApiClient

    private val testRepo = "repo"

    // "repo" doesn't appear in the per-distro DEFAULT_RELEASE string, so releaseForRepo()
    // falls back to "latest" for it: this exercises the API-hitting path.
    private val testReleaseToUse = releaseForRepo(BuildConfig.DEFAULT_RELEASE, testRepo)
    private val testEndpoint = "/repos/CypherpunkArmory/UserLAnd-Assets-$testRepo/releases/$testReleaseToUse"

    private val testArch = "arch"
    private val testAssetsTxtUrl = "assetsTxtUrl"
    private val testAssetsTxtName = "$testArch-assets.txt"
    private val testAssetsTxtDownloadUrl = "assetsTxtDownloadUrl"

    private val testUrl = "testUrl"
    private val testAssetType = "testType"
    private val testName = "testName"
    private val testTag = "v1.0.0"
    private val testAssetUrl = "assetUrl"
    private val testAssetName = "$testArch-$testAssetType"
    private val testAssetDownloadUrl = "assetDownloadUrl"

    private val testAssetsJson = """
    [
        {
            "url": "$testAssetUrl",
            "name": "$testAssetName",
            "browser_download_url": "$testAssetDownloadUrl"
        },
        {
            "url": "$testAssetsTxtUrl",
            "name": "$testAssetsTxtName",
            "browser_download_url": "$testAssetsTxtDownloadUrl"
        }
    ]
    """
    private val json: String = """
    {
        "url": "$testUrl",
        "name": "$testName",
        "tag_name": "$testTag",
        "assets": $testAssetsJson
    }
    """

    private lateinit var originalTimeZone: TimeZone

    @Before
    fun setup() {
        // Make GithubMirror.preferred() a no-op regardless of the machine running the tests.
        originalTimeZone = TimeZone.getDefault()
        TimeZone.setDefault(TimeZone.getTimeZone("America/Los_Angeles"))

        whenever(mockAnlFiles.getArchType()).thenReturn(testArch)

        githubApiClient = GithubApiClient(mockAnlFiles, mockUrlProvider, mockLogger)
    }

    @After
    fun teardown() {
        server.shutdown()
        TimeZone.setDefault(originalTimeZone)
    }

    private fun stubBaseUrl() {
        val url = server.url("/")
        whenever(mockUrlProvider.getBaseUrl()).thenReturn("${url.toUrl()}")
    }

    @Test
    fun `JsonClass correctly generate ReleasesResponse`() {
        val adapter = moshi.adapter(GithubApiClient.ReleasesResponse::class.java)
        val releasesResponse: GithubApiClient.ReleasesResponse = adapter.fromJson(json)!!

        assertEquals(testUrl, releasesResponse.url)
        assertEquals(testName, releasesResponse.name)
        assertEquals(testTag, releasesResponse.tag)
        assertEquals(testAssetUrl, releasesResponse.assets[0].url)
        assertEquals(testAssetName, releasesResponse.assets[0].name)
        assertEquals(testAssetDownloadUrl, releasesResponse.assets[0].downloadUrl)
    }

    @Test
    fun `getAssetsListDownloadUrl can parse json results`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result = runBlocking { githubApiClient.getAssetsListDownloadUrl(testRepo) }

        val request = server.takeRequest()
        assertEquals(testEndpoint, request.path)
        assertEquals(testAssetsTxtDownloadUrl, result)
    }

    @Test
    fun `getAssetsListDownloadUrl memoizes results`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result1 = runBlocking { githubApiClient.getAssetsListDownloadUrl(testRepo) }
        val result2 = runBlocking { githubApiClient.getAssetsListDownloadUrl(testRepo) }

        assertEquals(testAssetsTxtDownloadUrl, result1)
        assertEquals(testAssetsTxtDownloadUrl, result2)
        verify(mockUrlProvider, times(1)).getBaseUrl()
    }

    @Test(expected = IOException::class)
    fun `getAssetsListDownloadUrl throws IOException if server response is not successful`() {
        val response = MockResponse()
        response.setResponseCode(500)
        server.enqueue(response)
        stubBaseUrl()

        runBlocking { githubApiClient.getAssetsListDownloadUrl(testRepo) }

        verify(mockLogger.addExceptionBreadcrumb(IOException()))
    }

    @Test
    fun `getLatestReleaseVersion can parse json results`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result = runBlocking { githubApiClient.getLatestReleaseVersion(testRepo) }

        val request = server.takeRequest()
        assertEquals(testEndpoint, request.path)
        assertEquals(testTag, result)
    }

    @Test
    fun `getLatestReleaseVersion memoizes result`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result1 = runBlocking { githubApiClient.getLatestReleaseVersion(testRepo) }
        val result2 = runBlocking { githubApiClient.getLatestReleaseVersion(testRepo) }

        val request = server.takeRequest()
        assertEquals(testEndpoint, request.path)
        assertEquals(testTag, result1)
        assertEquals(testTag, result2)
        verify(mockUrlProvider, times(1)).getBaseUrl()
    }

    @Test(expected = IOException::class)
    fun `getLatestReleaseVersion throws IOException if server response is not successful`() {
        val response = MockResponse()
        response.setResponseCode(500)
        server.enqueue(response)
        stubBaseUrl()

        runBlocking { githubApiClient.getLatestReleaseVersion(testRepo) }

        verify(mockLogger.addExceptionBreadcrumb(IOException()))
    }

    @Test
    fun `getAssetEndpoint can parse json results`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }

        val request = server.takeRequest()
        assertEquals(testEndpoint, request.path)
        assertEquals(testAssetDownloadUrl, result)
    }

    @Test
    fun `getAssetEndpoint memoizes result`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val result1 = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }
        val result2 = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }

        val request = server.takeRequest()
        assertEquals(testEndpoint, request.path)
        assertEquals(testAssetDownloadUrl, result1)
        assertEquals(testAssetDownloadUrl, result2)
        verify(mockUrlProvider, times(1)).getBaseUrl()
    }

    @Test(expected = IOException::class)
    fun `getAssetEndpoint throws IOException if server response is not successful`() {
        val response = MockResponse()
        response.setResponseCode(500)
        server.enqueue(response)
        stubBaseUrl()

        runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }

        verify(mockLogger).addExceptionBreadcrumb(IOException())
    }

    @Test
    fun `Results are memoized across API queries`() {
        val response = MockResponse()
        response.setBody(json)
        server.enqueue(response)
        stubBaseUrl()

        val assetsListResult = runBlocking { githubApiClient.getAssetsListDownloadUrl(testRepo) }
        val releaseVersionResult = runBlocking { githubApiClient.getLatestReleaseVersion(testRepo) }
        val assetEndpointResult = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }

        assertEquals(testAssetsTxtDownloadUrl, assetsListResult)
        assertEquals(testTag, releaseVersionResult)
        assertEquals(testAssetDownloadUrl, assetEndpointResult)
        verify(mockUrlProvider, times(1)).getBaseUrl()
    }

    @Test
    fun `pinned per-distro release builds the download URL directly without calling the API`() {
        val pinnedRepo = "Ubuntu"
        githubApiClient = GithubApiClient(
            mockAnlFiles,
            mockUrlProvider,
            mockLogger,
            defaultRelease = "ubuntu:tags/v0.0.21,debian:tags/v0.0.15"
        )

        val assetsUrl = runBlocking { githubApiClient.getAssetsListDownloadUrl(pinnedRepo) }
        val version = runBlocking { githubApiClient.getLatestReleaseVersion(pinnedRepo) }
        val endpoint = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, pinnedRepo) }

        assertEquals(
            "https://github.com/CypherpunkArmory/UserLAnd-Assets-$pinnedRepo/releases/download/v0.0.21/$testArch-assets.txt",
            assetsUrl
        )
        assertEquals("v0.0.21", version)
        assertEquals(
            "https://github.com/CypherpunkArmory/UserLAnd-Assets-$pinnedRepo/releases/download/v0.0.21/$testArch-$testAssetType",
            endpoint
        )
        verify(mockUrlProvider, never()).getBaseUrl()
    }

    @Test
    fun `single pinned release for every repo builds the download URL directly`() {
        githubApiClient = GithubApiClient(
            mockAnlFiles,
            mockUrlProvider,
            mockLogger,
            defaultRelease = "tags/v7.7.9"
        )

        val endpoint = runBlocking { githubApiClient.getAssetEndpoint(testAssetType, testRepo) }

        assertEquals(
            "https://github.com/CypherpunkArmory/UserLAnd-Assets-$testRepo/releases/download/v7.7.9/$testArch-$testAssetType",
            endpoint
        )
        verify(mockUrlProvider, never()).getBaseUrl()
    }
}
