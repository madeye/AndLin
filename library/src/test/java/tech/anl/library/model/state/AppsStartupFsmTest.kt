package tech.anl.library.model.state

import androidx.arch.core.executor.testing.InstantTaskExecutorRule
import androidx.lifecycle.Observer
import org.mockito.kotlin.any
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import kotlinx.coroutines.runBlocking
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.library.model.daos.FilesystemDao
import tech.anl.library.model.daos.SessionDao
import tech.anl.library.model.entities.App
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.FilesystemFlavor
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports
import tech.anl.library.utils.preferences.AppsPreferences
import java.io.File
import java.io.IOException

@RunWith(MockitoJUnitRunner::class)
class AppsStartupFsmTest {

    @get:Rule val instantExecutorRule = InstantTaskExecutorRule()

    @get:Rule val tempFolder = TemporaryFolder()

    // Mocks

    @Mock lateinit var mockFilesystemDao: FilesystemDao

    @Mock lateinit var mockSessionDao: SessionDao

    @Mock lateinit var mockAnlDatabase: AnlDatabase

    @Mock lateinit var mockAppsPreferences: AppsPreferences

    @Mock lateinit var mockFilesystemManager: FilesystemManager

    @Mock lateinit var mockAnlFiles: AnlFiles

    @Mock lateinit var mockLogger: Logger

    @Mock lateinit var mockStateObserver: Observer<AppsStartupState>

    private lateinit var appsFsm: AppsStartupFsm

    // Test setup variables
    private val appsFilesystemName = "apps"
    private val appsFilesystemType = "type"
    private val appName = "app"

    val defaultUsername = "user"
    val defaultPassword = "password"
    private val appsFilesystem = Filesystem(id = 0, name = appsFilesystemName, distributionType = appsFilesystemType, isAppsFilesystem = true)
    private val appsFilesystemWithCredentials = Filesystem(id = 0, name = appsFilesystemName, distributionType = appsFilesystemType, isAppsFilesystem = true, defaultUsername = defaultUsername, defaultPassword = defaultPassword, defaultVncPassword = defaultPassword)

    private val appSession = Session(id = 0, name = appName, filesystemId = 0, isAppsSession = true)

    private val app = App(name = appName, filesystemRequired = appsFilesystemType)

    private val incorrectTransitionEvent = AppSelected(app)
    private val incorrectTransitionState = FetchingDatabaseEntries
    private val possibleEvents = listOf(
            AppSelected(app),
            CheckFilesystemFlavor(app, appsFilesystem),
            SubmitFilesystemFlavor(appsFilesystem, "flavor", ExecutionType.PROOT),
            CheckAppsFilesystemCredentials(appsFilesystem),
            SubmitAppsFilesystemCredentials(appsFilesystem, "", "", ""),
            CheckAppSessionServiceType(appSession),
            SubmitAppSessionServiceType(appSession, ServiceType.Unselected),
            CopyAppScriptToFilesystem(app, appsFilesystem),
            SyncDatabaseEntries(app, appSession, appsFilesystem),
            ResetAppState
    )
    private val possibleStates = listOf(
            IncorrectAppTransition(incorrectTransitionEvent, incorrectTransitionState),
            WaitingForAppSelection,
            FetchingDatabaseEntries,
            DatabaseEntriesFetched(appsFilesystem, appSession),
            FilesystemFlavorRequired(appsFilesystem, listOf(), listOf()),
            FilesystemFlavorSet,
            AppsFilesystemHasCredentials,
            AppsFilesystemRequiresCredentials(appsFilesystem),
            AppHasServiceTypeSet,
            AppRequiresServiceType,
            CopyingAppScript,
            AppScriptCopySucceeded,
            AppScriptCopyFailed,
            SyncingDatabaseEntries,
            AppDatabaseEntriesSynced(app, appSession, appsFilesystem)
    )

    @Before
    fun setup() {
        whenever(mockAnlDatabase.filesystemDao()).thenReturn(mockFilesystemDao)
        whenever(mockAnlDatabase.sessionDao()).thenReturn(mockSessionDao)

        appsFsm = AppsStartupFsm(mockAnlDatabase, mockFilesystemManager, mockAnlFiles, logger = mockLogger)
    }

    @Test
    fun `Only allows correct state transitions`() = runBlocking {
        appsFsm.getState().observeForever(mockStateObserver)

        for (event in possibleEvents) {
            for (state in possibleStates) {
                appsFsm.setState(state)
                val result = appsFsm.transitionIsAcceptable(event)
                when {
                    event is AppSelected && state is WaitingForAppSelection -> assertTrue(result)
                    event is CheckFilesystemFlavor && state is DatabaseEntriesFetched -> assertTrue(result)
                    event is SubmitFilesystemFlavor && state is FilesystemFlavorRequired -> assertTrue(result)
                    event is CheckAppsFilesystemCredentials && state is FilesystemFlavorSet -> assertTrue(result)
                    event is SubmitAppsFilesystemCredentials && state is AppsFilesystemRequiresCredentials -> assertTrue(result)
                    event is CheckAppSessionServiceType && state is AppsFilesystemHasCredentials -> assertTrue(result)
                    event is SubmitAppSessionServiceType && state is AppRequiresServiceType -> assertTrue(result)
                    event is CopyAppScriptToFilesystem && state is AppHasServiceTypeSet -> assertTrue(result)
                    event is SyncDatabaseEntries && state is AppScriptCopySucceeded -> assertTrue(result)
                    event is ResetAppState -> assertTrue(result)
                    else -> assertFalse(result)
                }
            }
        }
    }

    @Test
    fun `Exits early when an incorrect transition is submitted`() {
        val state = WaitingForAppSelection
        appsFsm.setState(state)
        appsFsm.getState().observeForever(mockStateObserver)

        val event = CheckAppsFilesystemCredentials(appsFilesystem)
        runBlocking { appsFsm.submitEvent(event, this) }

        verify(mockStateObserver).onChanged(IncorrectAppTransition(event, state))
        verify(mockStateObserver, times(2)).onChanged(any())
    }

    @Test
    fun `Initial state is WaitingForApps`() {
        appsFsm.getState().observeForever(mockStateObserver)

        val expectedState = WaitingForAppSelection
        verify(mockStateObserver).onChanged(expectedState)
    }

    @Test
    fun `State can be reset`() {
        appsFsm.getState().observeForever(mockStateObserver)

        for (state in possibleStates) {
            appsFsm.setState(state)
            runBlocking { appsFsm.submitEvent(ResetAppState, this) }
        }

        val numberOfStates = possibleStates.size
        // Will initially be WaitingForAppSelection (+1), the test for that state (+1), and then reset for each
        verify(mockStateObserver, times(numberOfStates + 2)).onChanged(WaitingForAppSelection)
    }

    @Test
    fun `Inserts apps filesystem DB entry if not yet present in DB`() {
        appsFsm.setState(WaitingForAppSelection)
        appsFsm.getState().observeForever(mockStateObserver)

        whenever(mockSessionDao.findAppsSession(app.name))
                .thenReturn(listOf(appSession))
        whenever(mockAnlFiles.getArchType())
                .thenReturn("")
        whenever(mockFilesystemDao.findAppsFilesystemByType(app.filesystemRequired))
                .thenReturn(listOf())
                .thenReturn(listOf(appsFilesystem))

        runBlocking { appsFsm.submitEvent(AppSelected(app), this) }

        verify(mockStateObserver).onChanged(FetchingDatabaseEntries)
        verify(mockStateObserver).onChanged(DatabaseEntriesFetched(appsFilesystem, appSession))
        verify(mockFilesystemDao, times(2)).findAppsFilesystemByType(app.filesystemRequired)
        // New apps filesystems are inserted with an empty flavor; the user picks one later via
        // the CheckFilesystemFlavor/SubmitFilesystemFlavor steps.
        verify(mockFilesystemDao).insertFilesystem(appsFilesystem.copy(flavor = ""))
    }

    @Test
    fun `Inserts app session DB entry if not yet present in DB`() {
        appsFsm.setState(WaitingForAppSelection)
        appsFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemDao.findAppsFilesystemByType(app.filesystemRequired))
                .thenReturn(listOf(appsFilesystem))
        whenever(mockSessionDao.findAppsSession(app.name))
                .thenReturn(listOf())
                .thenReturn(listOf(appSession))

        runBlocking { appsFsm.submitEvent(AppSelected(app), this) }

        verify(mockStateObserver).onChanged(FetchingDatabaseEntries)
        verify(mockStateObserver).onChanged(DatabaseEntriesFetched(appsFilesystem, appSession))
        verify(mockSessionDao, times(2)).findAppsSession(app.name)
        verify(mockSessionDao).insertSession(appSession)
    }

    @Test
    fun `Fetches database entries when app selected`() {
        appsFsm.setState(WaitingForAppSelection)
        appsFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemDao.findAppsFilesystemByType(app.filesystemRequired))
                .thenReturn(listOf(appsFilesystem))
        whenever(mockSessionDao.findAppsSession(app.name))
                .thenReturn(listOf(appSession))

        runBlocking { appsFsm.submitEvent(AppSelected(app), this) }

        verify(mockStateObserver).onChanged(FetchingDatabaseEntries)
        verify(mockStateObserver).onChanged(DatabaseEntriesFetched(appsFilesystem, appSession))
    }

    @Test
    fun `Posts failure state if database fetching fails`() {
        appsFsm.setState(WaitingForAppSelection)
        appsFsm.getState().observeForever(mockStateObserver)

        whenever(mockAnlFiles.getArchType())
                .thenReturn("")
        whenever(mockFilesystemDao.findAppsFilesystemByType(app.filesystemRequired))
                .thenReturn(listOf())
                .thenReturn(listOf()) // Simulate failure to retrieve previous insertion

        runBlocking { appsFsm.submitEvent(AppSelected(app), this) }

        verify(mockFilesystemDao, times(2)).findAppsFilesystemByType(app.filesystemRequired)
        verify(mockFilesystemDao).insertFilesystem(appsFilesystem.copy(flavor = ""))
        verify(mockStateObserver).onChanged(FetchingDatabaseEntries)
        verify(mockStateObserver).onChanged(DatabaseEntriesFetchFailed)
    }

    @Test
    fun `Requires credentials to be set if username is missing`() {
        appsFsm.setState(FilesystemFlavorSet)
        appsFsm.getState().observeForever(mockStateObserver)

        val filesystemWithoutUsername = appsFilesystemWithCredentials
        filesystemWithoutUsername.defaultUsername = ""
        runBlocking { appsFsm.submitEvent(CheckAppsFilesystemCredentials(filesystemWithoutUsername), this) }

        verify(mockStateObserver).onChanged(AppsFilesystemRequiresCredentials(filesystemWithoutUsername))
    }

    @Test
    fun `Requires credentials to be set if password is missing`() {
        appsFsm.setState(FilesystemFlavorSet)
        appsFsm.getState().observeForever(mockStateObserver)

        val filesystemWithoutPassword = appsFilesystemWithCredentials
        filesystemWithoutPassword.defaultPassword = ""
        runBlocking { appsFsm.submitEvent(CheckAppsFilesystemCredentials(filesystemWithoutPassword), this) }

        verify(mockStateObserver).onChanged(AppsFilesystemRequiresCredentials(filesystemWithoutPassword))
    }

    @Test
    fun `Requires credentials to be set if vnc password is missing`() {
        appsFsm.setState(FilesystemFlavorSet)
        appsFsm.getState().observeForever(mockStateObserver)

        val filesystemWithoutVncPassword = appsFilesystemWithCredentials
        filesystemWithoutVncPassword.defaultVncPassword = ""
        runBlocking { appsFsm.submitEvent(CheckAppsFilesystemCredentials(filesystemWithoutVncPassword), this) }

        verify(mockStateObserver).onChanged(AppsFilesystemRequiresCredentials(filesystemWithoutVncPassword))
    }

    @Test
    fun `State is AppsFilesystemHasCredentials if they are set`() {
        appsFsm.setState(FilesystemFlavorSet)
        appsFsm.getState().observeForever(mockStateObserver)

        runBlocking { appsFsm.submitEvent(CheckAppsFilesystemCredentials(appsFilesystemWithCredentials), this) }

        verify(mockStateObserver).onChanged(AppsFilesystemHasCredentials)
    }

    // --- CheckFilesystemFlavor / SubmitFilesystemFlavor step ---

    @Test
    fun `CheckFilesystemFlavor posts FilesystemFlavorSet directly if a flavor is already set`() {
        appsFsm.setState(DatabaseEntriesFetched(appsFilesystem, appSession))
        appsFsm.getState().observeForever(mockStateObserver)

        val filesystemWithFlavor = appsFilesystem.copy(flavor = "xfce")
        runBlocking { appsFsm.submitEvent(CheckFilesystemFlavor(app, filesystemWithFlavor), this) }

        verify(mockStateObserver).onChanged(FilesystemFlavorSet)
        verify(mockFilesystemDao, never()).updateFilesystem(any())
    }

    @Test
    fun `CheckFilesystemFlavor auto-selects the only flavor and execution type when there is no choice to make`() {
        whenever(mockAnlFiles.filesDir).thenReturn(tempFolder.root)
        appsFsm.setState(DatabaseEntriesFetched(appsFilesystem, appSession))
        appsFsm.getState().observeForever(mockStateObserver)

        // No apps/<name>/flavors.txt on disk => not a distribution => PROOT is the only
        // execution type, and (with desktop sessions disabled by default) the headless server
        // image is the only flavor. checkFilesystemFlavor() should auto-select both.
        val filesystemWithoutFlavor = appsFilesystem.copy(flavor = "")
        runBlocking { appsFsm.submitEvent(CheckFilesystemFlavor(app, filesystemWithoutFlavor), this) }

        val expectedFilesystem = filesystemWithoutFlavor.copy(
            flavor = FilesystemFlavor.serverFlavor.name,
            executionType = ExecutionType.PROOT
        )
        verify(mockFilesystemDao).updateFilesystem(expectedFilesystem)
        verify(mockStateObserver).onChanged(FilesystemFlavorSet)
    }

    @Test
    fun `CheckFilesystemFlavor posts FilesystemFlavorRequired when the app is a distribution with desktop sessions enabled`() {
        whenever(mockAnlFiles.filesDir).thenReturn(tempFolder.root)
        val flavorsFile = File(tempFolder.root, "apps/${app.name}/flavors.txt")
        flavorsFile.parentFile!!.mkdirs()
        flavorsFile.writeText("Release Name, Display Name, isPaid\nxfce, XFCE, false\n")

        val desktopEnabledFsm = AppsStartupFsm(
            mockAnlDatabase,
            mockFilesystemManager,
            mockAnlFiles,
            desktopEnabledProvider = { true },
            logger = mockLogger
        )
        desktopEnabledFsm.setState(DatabaseEntriesFetched(appsFilesystem, appSession))
        desktopEnabledFsm.getState().observeForever(mockStateObserver)

        val filesystemWithoutFlavor = appsFilesystem.copy(flavor = "")
        runBlocking { desktopEnabledFsm.submitEvent(CheckFilesystemFlavor(app, filesystemWithoutFlavor), this) }

        verify(mockStateObserver).onChanged(
            FilesystemFlavorRequired(
                filesystemWithoutFlavor,
                listOf(FilesystemFlavor.serverFlavor, FilesystemFlavor("xfce", "XFCE", isPaid = false)),
                listOf(ExecutionType.PROOT)
            )
        )
        verify(mockFilesystemDao, never()).updateFilesystem(any())
    }

    @Test
    fun `SubmitFilesystemFlavor updates the filesystem and posts FilesystemFlavorSet`() {
        appsFsm.setState(FilesystemFlavorRequired(appsFilesystem, listOf(), listOf()))
        appsFsm.getState().observeForever(mockStateObserver)

        val filesystemToUpdate = appsFilesystem.copy(flavor = "")
        runBlocking {
            appsFsm.submitEvent(SubmitFilesystemFlavor(filesystemToUpdate, "xfce", ExecutionType.PROOT), this)
        }

        val expectedFilesystem = filesystemToUpdate.copy(flavor = "xfce", executionType = ExecutionType.PROOT)
        verify(mockFilesystemDao).updateFilesystem(expectedFilesystem)
        verify(mockStateObserver).onChanged(FilesystemFlavorSet)
    }

    @Test
    fun `Sets credentials and updates state to AppsFilesystemHasCredentials on event submission`() {
        appsFsm.setState(AppsFilesystemRequiresCredentials(appsFilesystem))
        appsFsm.getState().observeForever(mockStateObserver)

        runBlocking { appsFsm.submitEvent(SubmitAppsFilesystemCredentials(appsFilesystem, defaultUsername, defaultPassword, defaultPassword), this) }

        verify(mockFilesystemDao).updateFilesystem(appsFilesystemWithCredentials)
        verify(mockStateObserver).onChanged(AppsFilesystemHasCredentials)
    }

    @Test
    fun `State is AppServiceTypeSet if already set`() {
        appsFsm.setState(AppsFilesystemHasCredentials)
        appsFsm.getState().observeForever(mockStateObserver)
        appSession.serviceType = ServiceType.Ssh

        runBlocking { appsFsm.submitEvent(CheckAppSessionServiceType(appSession), this) }

        verify(mockStateObserver).onChanged(AppHasServiceTypeSet)
    }

    @Test
    fun `State is AppRequiresServiceType is it is not set`() {
        appsFsm.setState(AppsFilesystemHasCredentials)
        appsFsm.getState().observeForever(mockStateObserver)
        appSession.serviceType = ServiceType.Unselected

        runBlocking { appsFsm.submitEvent(CheckAppSessionServiceType(appSession), this) }

        verify(mockStateObserver).onChanged(AppRequiresServiceType)
    }

    @Test
    fun `Sets service preference on event submission`() {
        appsFsm.setState(AppRequiresServiceType)
        appsFsm.getState().observeForever(mockStateObserver)

        runBlocking { appsFsm.submitEvent(SubmitAppSessionServiceType(appSession, ServiceType.Ssh), this) }

        val expectedSession = appSession
        expectedSession.serviceType = ServiceType.Ssh
        verify(mockSessionDao).updateSession(expectedSession)
        verify(mockStateObserver).onChanged(AppHasServiceTypeSet)
    }

    @Test
    fun `State is CopySucceeded`() {
        appsFsm.setState(AppHasServiceTypeSet)
        appsFsm.getState().observeForever(mockStateObserver)

        runBlocking { appsFsm.submitEvent(CopyAppScriptToFilesystem(app, appsFilesystem), this) }

        verify(mockStateObserver).onChanged(CopyingAppScript)
        verify(mockStateObserver).onChanged(AppScriptCopySucceeded)
    }

    @Test
    fun `State is CopyFailed`() {
        appsFsm.setState(AppHasServiceTypeSet)
        appsFsm.getState().observeForever(mockStateObserver)

        whenever(mockFilesystemManager.moveAppScriptToRequiredLocation(app.name, appsFilesystem))
                .thenThrow(IOException())

        runBlocking { appsFsm.submitEvent(CopyAppScriptToFilesystem(app, appsFilesystem), this) }

        verify(mockStateObserver).onChanged(CopyingAppScript)
        verify(mockStateObserver).onChanged(AppScriptCopyFailed)
    }

    @Test
    fun `Syncs session database entry correctly`() {
        appsFsm.setState(AppScriptCopySucceeded)
        appsFsm.getState().observeForever(mockStateObserver)

        runBlocking { appsFsm.submitEvent(SyncDatabaseEntries(app, appSession, appsFilesystemWithCredentials), this) }

        val updatedAppSession = appSession
        updatedAppSession.filesystemId = appsFilesystemWithCredentials.id
        updatedAppSession.filesystemName = appsFilesystemWithCredentials.name
        updatedAppSession.username = appsFilesystemWithCredentials.defaultUsername
        updatedAppSession.password = appsFilesystemWithCredentials.defaultPassword
        updatedAppSession.vncPassword = appsFilesystemWithCredentials.defaultVncPassword

        verify(mockSessionDao).updateSession(updatedAppSession)
        verify(mockStateObserver).onChanged(SyncingDatabaseEntries)
        verify(mockStateObserver).onChanged(AppDatabaseEntriesSynced(app, updatedAppSession, appsFilesystemWithCredentials))
    }
}