package tech.anl.library.model.repositories

import android.content.Context
import android.content.SharedPreferences
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Observer
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.verify
import org.mockito.kotlin.verifyBlocking
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyInt
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.library.model.daos.AppsDao
import tech.anl.library.model.entities.App
import tech.anl.library.model.remote.GithubAppsFetcher
import tech.anl.library.utils.Logger
import tech.anl.library.utils.preferences.AppsPreferences
import java.io.IOException


@RunWith(MockitoJUnitRunner::class)
class AppsRepositoryTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    @Mock lateinit var mockGithubAppsFetcher: GithubAppsFetcher

    @Mock lateinit var mockAppsDao: AppsDao

    @Mock lateinit var mockAppsPreferences: AppsPreferences

    @Mock lateinit var mockLogger: Logger

    @Mock lateinit var mockAppsObserver: Observer<List<App>>

    @Mock lateinit var mockRefreshStatusObserver: Observer<RefreshStatus>

    @Mock lateinit var mockContext: Context

    @Mock lateinit var mockSharedPreferences: SharedPreferences

    @Mock lateinit var mockEditor: SharedPreferences.Editor

    private val inactiveAppName = "inactive"
    private val inactiveApp = App(name = inactiveAppName, category = "distribution", filesystemRequired = inactiveAppName)
    private val activeAppName = "active"
    private val activeApp = App(name = activeAppName)
    private val appsList = listOf(inactiveApp)
    private val activeAppsList = listOf(activeApp)
    private val appsListLiveData = MutableLiveData<List<App>>()
    private val activeAppsListLiveData = MutableLiveData<List<App>>()

    private lateinit var appsRepository: AppsRepository

    @Before
    fun setup() {
        whenever(mockSharedPreferences.edit()).thenReturn(mockEditor)

        appsListLiveData.postValue(appsList)
        activeAppsListLiveData.postValue(activeAppsList)
        appsRepository = AppsRepository(
                mockAppsDao,
                mockGithubAppsFetcher,
                mockAppsPreferences,
                mockSharedPreferences,
                mockLogger
        )
    }

    @Test
    fun `Fetches apps from database`() {
        whenever(mockAppsDao.getAllApps()).thenReturn(appsListLiveData)

        appsRepository.getAllApps().observeForever(mockAppsObserver)

        verify(mockAppsDao).getAllApps()
        verify(mockAppsObserver).onChanged(appsList)
    }

    @Test
    fun `Fetches active apps from database`() {
        whenever(mockAppsDao.getActiveApps()).thenReturn(activeAppsListLiveData)

        appsRepository.getActiveApps().observeForever(mockAppsObserver)

        verify(mockAppsDao).getActiveApps()
        verify(mockAppsObserver).onChanged(activeAppsList)
    }

    @Test
    fun `Apps are inserted into the database and distributions are saved in the cache`() {
        runBlocking {
            whenever(mockGithubAppsFetcher.fetchAppsList()).thenReturn(appsList)
        }

        appsRepository.getRefreshStatus().observeForever(mockRefreshStatusObserver)

        runBlocking {
            appsRepository.refreshData(this)
        }

        verifyBlocking(mockGithubAppsFetcher) { fetchAppIcon(inactiveApp) }
        verifyBlocking(mockGithubAppsFetcher) { fetchAppDescription(inactiveApp) }
        verifyBlocking(mockGithubAppsFetcher) { fetchAppScript(inactiveApp) }
        verify(mockAppsDao).deleteAppsNotIn(listOf(inactiveAppName))
        verify(mockAppsDao).insertApp(inactiveApp)
        verify(mockAppsPreferences).setDistributionsList(setOf(inactiveAppName))
        verify(mockRefreshStatusObserver).onChanged(RefreshStatus.ACTIVE)
        verify(mockRefreshStatusObserver).onChanged(RefreshStatus.FINISHED)
    }

    @Test
    fun `Coding agents are filesystem types, other apps are not, and unavailable apps are dropped`() {
        val agent = App(name = "claude", category = "coding agent", filesystemRequired = "claude")
        val plainApp = App(name = "zork", category = "game", filesystemRequired = "debian")
        val unavailable = App(name = "codex", category = "coding agent", filesystemRequired = "codex")
        runBlocking {
            whenever(mockGithubAppsFetcher.fetchAppsList()).thenReturn(listOf(inactiveApp, agent, plainApp, unavailable))
        }
        appsRepository = AppsRepository(
                mockAppsDao,
                mockGithubAppsFetcher,
                mockAppsPreferences,
                mockSharedPreferences,
                mockLogger,
                isAvailable = { it.name != "codex" }
        )

        runBlocking {
            appsRepository.refreshData(this)
        }

        verify(mockAppsDao).deleteAppsNotIn(listOf(inactiveAppName, "claude", "zork"))
        verify(mockAppsDao, never()).insertApp(unavailable)
        verify(mockAppsPreferences).setDistributionsList(setOf(inactiveAppName, "claude"))
    }

    @Test
    fun `Failure during refresh posts RefreshStatus FAILED`() {
        runBlocking {
            whenever(mockGithubAppsFetcher.fetchAppsList()).thenThrow(IOException())
        }
        appsRepository.getRefreshStatus().observeForever(mockRefreshStatusObserver)

        runBlocking {
            appsRepository.refreshData(this)
        }

        verify(mockRefreshStatusObserver).onChanged(RefreshStatus.ACTIVE)
        verify(mockRefreshStatusObserver).onChanged(RefreshStatus.FAILED)
        verify(mockAppsDao, never()).deleteAppsNotIn(any())
    }
}