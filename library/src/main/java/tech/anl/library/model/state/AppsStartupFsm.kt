package tech.anl.library.model.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.*
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports

class AppsStartupFsm(
    anlDatabase: AnlDatabase,
    private val filesystemManager: FilesystemManager,
    private val anlFiles: AnlFiles,
    private val desktopEnabledProvider: () -> Boolean = { false },
    private val executionTypeSupported: (ExecutionType) -> Boolean = { it.isSupportedOnThisDevice() },
    private val logger: Logger = LogcatLogger()
) {

    private val className = "AppsFSM"

    private val sessionDao = anlDatabase.sessionDao()
    private val filesystemDao = anlDatabase.filesystemDao()

    private val state = MutableLiveData<AppsStartupState>().apply { postValue(WaitingForAppSelection) }

    fun getState(): LiveData<AppsStartupState> {
        return state
    }

    internal fun setState(newState: AppsStartupState) {
        state.postValue(newState)
    }

    fun transitionIsAcceptable(event: AppsStartupEvent): Boolean {
        val currentState = state.value!!
        return when (event) {
            is AppSelected -> currentState is WaitingForAppSelection
            is CheckFilesystemFlavor -> currentState is DatabaseEntriesFetched
            is SubmitFilesystemFlavor -> currentState is FilesystemFlavorRequired
            is CheckAppsFilesystemCredentials -> currentState is FilesystemFlavorSet
            is SubmitAppsFilesystemCredentials -> currentState is AppsFilesystemRequiresCredentials
            is CheckAppSessionServiceType -> currentState is AppsFilesystemHasCredentials
            is SubmitAppSessionServiceType -> currentState is AppRequiresServiceType
            is CopyAppScriptToFilesystem -> currentState is AppHasServiceTypeSet
            is SyncDatabaseEntries -> currentState is AppScriptCopySucceeded
            is ResetAppState -> true
        }
    }

    fun submitEvent(event: AppsStartupEvent, coroutineScope: CoroutineScope) = coroutineScope.launch {
        val eventBreadcrumb = AnlBreadcrumb(className, BreadcrumbType.ReceivedEvent, "Event: $event State: ${state.value}")
        logger.addBreadcrumb(eventBreadcrumb)
        if (!transitionIsAcceptable(event)) {
            state.postValue(IncorrectAppTransition(event, state.value!!))
            return@launch
        }
        return@launch when (event) {
            is AppSelected -> fetchDatabaseEntries(event.app)
            is CheckFilesystemFlavor -> checkFilesystemFlavor(event.app, event.appsFilesystem)
            is SubmitFilesystemFlavor -> setFilesystemFlavor(event.filesystem, event.flavor, event.executionType)
            is CheckAppsFilesystemCredentials -> checkAppsFilesystemCredentials(event.appsFilesystem)
            is SubmitAppsFilesystemCredentials -> {
                setAppsFilesystemCredentials(event.filesystem, event.username, event.password, event.vncPassword)
            }
            is CheckAppSessionServiceType -> checkServiceType(event.appSession)
            is SubmitAppSessionServiceType -> setServiceType(event.appSession, event.serviceType)
            is CopyAppScriptToFilesystem -> copyAppScriptToFilesystem(event.app, event.filesystem)
            is SyncDatabaseEntries -> updateAppSession(event.app, event.session, event.filesystem)
            is ResetAppState -> state.postValue(WaitingForAppSelection)
        }
    }

    private suspend fun fetchDatabaseEntries(app: App) {
        state.postValue(FetchingDatabaseEntries)
        try {
            val appsFilesystem = findAppsFilesystem(app)
            val appSession = findAppSession(app, appsFilesystem.id)
            state.postValue(DatabaseEntriesFetched(appsFilesystem, appSession))
        } catch (err: Exception) {
            state.postValue(DatabaseEntriesFetchFailed)
        }
    }

    /**
     * A new apps filesystem starts without a flavor. The user picks one (and, where this build and
     * device support it, whether it runs under PRoot or in a VM) before anything is downloaded,
     * since the choice decides which image gets fetched and by whom.
     */
    private val desktopEnabled: Boolean get() = desktopEnabledProvider()

    private suspend fun checkFilesystemFlavor(app: App, appsFilesystem: Filesystem) {
        if (appsFilesystem.flavor.isNotEmpty()) {
            state.postValue(FilesystemFlavorSet)
            return
        }
        val flavors = FilesystemFlavor.readForApp(anlFiles.filesDir, app.name, desktopEnabled)
        val isDistribution = java.io.File(anlFiles.filesDir, "apps/${app.name}/flavors.txt").exists()
        val executionTypes = availableExecutionTypes(isDistribution)
        if (flavors.size == 1 && executionTypes.size == 1) {
            setFilesystemFlavor(appsFilesystem, flavors.first().name, executionTypes.first())
            return
        }
        state.postValue(FilesystemFlavorRequired(appsFilesystem, flavors, executionTypes))
    }

    // Only distributions that ship a flavors.txt can run in a VM; plain apps always use PRoot.
    private fun availableExecutionTypes(isDistribution: Boolean): List<ExecutionType> {
        val types = mutableListOf(ExecutionType.PROOT)
        if (!isDistribution) return types
        if (BuildConfig.ENABLE_AVF_BACKEND && executionTypeSupported(ExecutionType.AVF)) types.add(ExecutionType.AVF)
        if (BuildConfig.ENABLE_QEMU_BACKEND && executionTypeSupported(ExecutionType.QEMU)) types.add(ExecutionType.QEMU)
        return types
    }

    private suspend fun setFilesystemFlavor(filesystem: Filesystem, flavor: String, executionType: ExecutionType) {
        filesystem.flavor = flavor
        filesystem.executionType = executionType
        withContext(Dispatchers.IO) { filesystemDao.updateFilesystem(filesystem) }
        state.postValue(FilesystemFlavorSet)
    }

    private fun checkAppsFilesystemCredentials(appsFilesystem: Filesystem) {
        val credentialsAreSet = appsFilesystem.defaultUsername.isNotEmpty() &&
                appsFilesystem.defaultPassword.isNotEmpty() &&
                appsFilesystem.defaultVncPassword.isNotEmpty()
        if (credentialsAreSet) {
            state.postValue(AppsFilesystemHasCredentials)
            return
        }
        state.postValue(AppsFilesystemRequiresCredentials(appsFilesystem))
    }

    private fun checkServiceType(appSession: Session) {
        if (appSession.serviceType == ServiceType.Unselected) {
            state.postValue(AppRequiresServiceType)
            return
        }
        state.postValue(AppHasServiceTypeSet)
    }

    private suspend fun copyAppScriptToFilesystem(app: App, filesystem: Filesystem) {
        state.postValue(CopyingAppScript)
        try {
            withContext(Dispatchers.IO) {
                filesystemManager.moveAppScriptToRequiredLocation(app.name, filesystem)
            }
            state.postValue(AppScriptCopySucceeded)
        } catch (err: Exception) {
            state.postValue(AppScriptCopyFailed)
        }
    }

    private suspend fun setServiceType(appSession: Session, serviceType: ServiceType) = withContext(Dispatchers.IO) {
        appSession.serviceType = serviceType
        sessionDao.updateSession(appSession)
        state.postValue(AppHasServiceTypeSet)
    }

    @Throws(NoSuchElementException::class) // If second database call fails
    private suspend fun findAppsFilesystem(app: App): Filesystem = withContext(Dispatchers.IO) {
        val potentialAppFilesystem = filesystemDao.findAppsFilesystemByType(app.filesystemRequired)

        if (potentialAppFilesystem.isEmpty()) {
            val deviceArchitecture = anlFiles.getArchType()
            // An empty flavor marks the filesystem as not yet configured; see checkFilesystemFlavor().
            val fsToInsert = Filesystem(0, name = "apps", archType = deviceArchitecture,
                    distributionType = app.filesystemRequired, isAppsFilesystem = true, isProtected = false,
                    flavor = "")
            filesystemDao.insertFilesystem(fsToInsert)
        }

        return@withContext filesystemDao.findAppsFilesystemByType(app.filesystemRequired).first()
    }

    @Throws(NoSuchElementException::class) // If second database call fails
    private suspend fun findAppSession(app: App, filesystemId: Long): Session = withContext(Dispatchers.IO) {
        val potentialAppSession = sessionDao.findAppsSession(app.name)

        if (potentialAppSession.isEmpty()) {
            val sessionToInsert = Session(id = 0, name = app.name, filesystemId = filesystemId, isAppsSession = true)
            sessionDao.insertSession(sessionToInsert)
        }

        return@withContext sessionDao.findAppsSession(app.name).first()
    }

    private suspend fun setAppsFilesystemCredentials(filesystem: Filesystem, username: String, password: String, vncPassword: String) {
        filesystem.defaultUsername = username
        filesystem.defaultPassword = password
        filesystem.defaultVncPassword = vncPassword
        withContext(Dispatchers.IO) { filesystemDao.updateFilesystem(filesystem) }
        state.postValue(AppsFilesystemHasCredentials)
    }

    private suspend fun updateAppSession(app: App, appSession: Session, appsFilesystem: Filesystem) {
        state.postValue(SyncingDatabaseEntries)
        appSession.filesystemId = appsFilesystem.id
        appSession.filesystemName = appsFilesystem.name
        appSession.username = appsFilesystem.defaultUsername
        appSession.password = appsFilesystem.defaultPassword
        appSession.vncPassword = appsFilesystem.defaultVncPassword
        withContext(Dispatchers.IO) { sessionDao.updateSession(appSession) }
        state.postValue(AppDatabaseEntriesSynced(app, appSession, appsFilesystem))
    }
}

sealed class AppsStartupState
data class IncorrectAppTransition(val event: AppsStartupEvent, val state: AppsStartupState) : AppsStartupState()
object WaitingForAppSelection : AppsStartupState()
object FetchingDatabaseEntries : AppsStartupState()
data class DatabaseEntriesFetched(val appsFilesystem: Filesystem, val appSession: Session) : AppsStartupState()
object DatabaseEntriesFetchFailed : AppsStartupState()
data class FilesystemFlavorRequired(val appsFilesystem: Filesystem, val flavors: List<FilesystemFlavor>, val executionTypes: List<ExecutionType>) : AppsStartupState()
object FilesystemFlavorSet : AppsStartupState()
object AppsFilesystemHasCredentials : AppsStartupState()
data class AppsFilesystemRequiresCredentials(val appsFilesystem: Filesystem) : AppsStartupState()
object AppHasServiceTypeSet : AppsStartupState()
object AppRequiresServiceType : AppsStartupState()
object CopyingAppScript : AppsStartupState()
object AppScriptCopySucceeded : AppsStartupState()
object AppScriptCopyFailed : AppsStartupState()
object SyncingDatabaseEntries : AppsStartupState()
data class AppDatabaseEntriesSynced(val app: App, val session: Session, val filesystem: Filesystem) : AppsStartupState()

sealed class AppsStartupEvent
data class AppSelected(val app: App) : AppsStartupEvent()
data class CheckFilesystemFlavor(val app: App, val appsFilesystem: Filesystem) : AppsStartupEvent()
data class SubmitFilesystemFlavor(val filesystem: Filesystem, val flavor: String, val executionType: ExecutionType) : AppsStartupEvent()
data class CheckAppsFilesystemCredentials(val appsFilesystem: Filesystem) : AppsStartupEvent()
data class SubmitAppsFilesystemCredentials(val filesystem: Filesystem, val username: String, val password: String, val vncPassword: String) : AppsStartupEvent()
data class CheckAppSessionServiceType(val appSession: Session) : AppsStartupEvent()
data class SubmitAppSessionServiceType(val appSession: Session, val serviceType: ServiceType) : AppsStartupEvent()
data class CopyAppScriptToFilesystem(val app: App, val filesystem: Filesystem) : AppsStartupEvent()
data class SyncDatabaseEntries(val app: App, val session: Session, val filesystem: Filesystem) : AppsStartupEvent()
object ResetAppState : AppsStartupEvent()