package tech.anl.library.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.library.R
import tech.anl.library.model.daos.SessionDao
import tech.anl.library.model.entities.App
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.utils.AppDetails

@RunWith(MockitoJUnitRunner::class)
class AppDetailsViewModelTest {

    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    // Constructor parameters
    @Mock lateinit var mockSessionDao: SessionDao
    @Mock lateinit var mockAppDetails: AppDetails
    @Mock lateinit var mockSharedPreferences: SharedPreferences

    // Mocks returned from stubs
    @Mock lateinit var mockUri: Uri
    @Mock lateinit var mockViewStateObserver: Observer<AppDetailsViewState>

    private lateinit var viewModel: AppDetailsViewModel

    private val inactiveName = "inactive"
    private val inactiveDescription = "super fun text game"
    private val inactiveApp = App(name = inactiveName, supportsGui = true, supportsCli = true)

    private val activeName = "active"
    private val activeDescription = "super fun non-functioning browser"
    private val activeApp = App(name = activeName, supportsGui = true, supportsCli = true)

    private fun stubAppDetails(app: App) {
        whenever(mockAppDetails.findIconUri(app.name)).thenReturn(mockUri)
        val description = if (app.name == activeName) activeDescription else inactiveDescription
        whenever(mockAppDetails.findAppDescription(app.name)).thenReturn(description)
    }

    private fun buildSession(app: App, serviceType: ServiceType): Session {
        val active = app.name == activeName
        return Session(name = app.name, serviceType = serviceType, active = active, id = 0, filesystemId = 0)
    }

    @Test
    fun `ViewState is accurate for a setup, inactive app`() {
        stubAppDetails(inactiveApp)

        val session = buildSession(inactiveApp, ServiceType.Ssh)
        whenever(mockSessionDao.findAppsSession(inactiveName)).thenReturn(listOf(session))

        viewModel = AppDetailsViewModel(mockSessionDao, mockAppDetails, mockSharedPreferences)
        viewModel.viewState.observeForever(mockViewStateObserver)

        runBlocking {
            viewModel.submitEvent(AppDetailsEvent.SubmitApp(inactiveApp), this)
        }

        val expectedResult = AppDetailsViewState(
                mockUri,
                inactiveName,
                inactiveDescription,
                describeStateHintEnabled = false,
                describeStateText = null,
                autoStartEnabled = false
        )
        verify(mockViewStateObserver).onChanged(expectedResult)
    }

    @Test
    fun `No hint is shown for an active app`() {
        stubAppDetails(activeApp)

        val session = buildSession(activeApp, ServiceType.Ssh)
        whenever(mockSessionDao.findAppsSession(activeName)).thenReturn(listOf(session))

        viewModel = AppDetailsViewModel(mockSessionDao, mockAppDetails, mockSharedPreferences)
        viewModel.viewState.observeForever(mockViewStateObserver)

        runBlocking {
            viewModel.submitEvent(AppDetailsEvent.SubmitApp(activeApp), this)
        }

        val expectedResult = AppDetailsViewState(
                mockUri,
                activeName,
                activeDescription,
                describeStateHintEnabled = false,
                describeStateText = null,
                autoStartEnabled = false
        )
        verify(mockViewStateObserver).onChanged(expectedResult)
    }

    @Test
    fun `Hint says to finish setup if the service type is unset`() {
        stubAppDetails(inactiveApp)

        val session = buildSession(inactiveApp, ServiceType.Unselected)
        whenever(mockSessionDao.findAppsSession(inactiveName)).thenReturn(listOf(session))

        viewModel = AppDetailsViewModel(mockSessionDao, mockAppDetails, mockSharedPreferences)
        viewModel.viewState.observeForever(mockViewStateObserver)

        runBlocking {
            viewModel.submitEvent(AppDetailsEvent.SubmitApp(inactiveApp), this)
        }

        val expectedResult = AppDetailsViewState(
                mockUri,
                inactiveName,
                inactiveDescription,
                describeStateHintEnabled = true,
                describeStateText = R.string.info_finish_app_setup,
                autoStartEnabled = false
        )
        verify(mockViewStateObserver).onChanged(expectedResult)
    }

    @Test
    fun `Hint is for finishing setup if app session cannot be found`() {
        stubAppDetails(inactiveApp)

        whenever(mockSessionDao.findAppsSession(inactiveName)).thenReturn(listOf())

        viewModel = AppDetailsViewModel(mockSessionDao, mockAppDetails, mockSharedPreferences)
        viewModel.viewState.observeForever(mockViewStateObserver)

        runBlocking {
            viewModel.submitEvent(AppDetailsEvent.SubmitApp(inactiveApp), this)
        }

        val expectedResult = AppDetailsViewState(
                mockUri,
                inactiveName,
                inactiveDescription,
                describeStateHintEnabled = true,
                describeStateText = R.string.info_finish_app_setup,
                autoStartEnabled = false
        )
        verify(mockViewStateObserver).onChanged(expectedResult)
    }
}