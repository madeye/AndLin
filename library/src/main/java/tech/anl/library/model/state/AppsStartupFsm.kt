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
                setAppsFilesystemCredentials(event.filesystem, event.username, event.password)
            }
            is CheckAppSessionServiceType -> checkServiceType(event.appSession)
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
     * A new apps filesystem starts without a flavor. Every new filesystem gets the headless server
     * image; where this build and device support it, the user picks whether a distribution runs
     * under PRoot or in a VM before anything is downloaded, since that decides who fetches it.
     */
    private suspend fun checkFilesystemFlavor(app: App, appsFilesystem: Filesystem) {
        if (appsFilesystem.flavor.isNotEmpty()) {
            state.postValue(FilesystemFlavorSet)
            return
        }
        val executionTypes = availableExecutionTypes(app.hasOwnImage())
        if (executionTypes.size == 1) {
            setFilesystemFlavor(appsFilesystem, FilesystemFlavor.SERVER, executionTypes.first())
            return
        }
        state.postValue(FilesystemFlavorRequired(appsFilesystem, executionTypes))
    }

    // Only filesystems with their own image (distributions, coding agents) can run in a VM; apps
    // installed into a distribution always use PRoot.
    private fun availableExecutionTypes(hasOwnImage: Boolean): List<ExecutionType> {
        val types = mutableListOf(ExecutionType.PROOT)
        if (!hasOwnImage) return types
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
                appsFilesystem.defaultPassword.isNotEmpty()
        if (credentialsAreSet) {
            state.postValue(AppsFilesystemHasCredentials)
            return
        }
        state.postValue(AppsFilesystemRequiresCredentials(appsFilesystem))
    }

    // Apps always run as SSH sessions; a new app session is switched to SSH here.
    private suspend fun checkServiceType(appSession: Session) {
        if (appSession.serviceType != ServiceType.Ssh) {
            appSession.serviceType = ServiceType.Ssh
            withContext(Dispatchers.IO) { sessionDao.updateSession(appSession) }
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

    private suspend fun setAppsFilesystemCredentials(filesystem: Filesystem, username: String, password: String) {
        filesystem.defaultUsername = username
        filesystem.defaultPassword = password
        withContext(Dispatchers.IO) { filesystemDao.updateFilesystem(filesystem) }
        state.postValue(AppsFilesystemHasCredentials)
    }

    private suspend fun updateAppSession(app: App, appSession: Session, appsFilesystem: Filesystem) {
        state.postValue(SyncingDatabaseEntries)
        appSession.filesystemId = appsFilesystem.id
        appSession.filesystemName = appsFilesystem.name
        appSession.username = appsFilesystem.defaultUsername
        appSession.password = appsFilesystem.defaultPassword
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
data class FilesystemFlavorRequired(val appsFilesystem: Filesystem, val executionTypes: List<ExecutionType>) : AppsStartupState()
object FilesystemFlavorSet : AppsStartupState()
object AppsFilesystemHasCredentials : AppsStartupState()
data class AppsFilesystemRequiresCredentials(val appsFilesystem: Filesystem) : AppsStartupState()
object AppHasServiceTypeSet : AppsStartupState()
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
data class SubmitAppsFilesystemCredentials(val filesystem: Filesystem, val username: String, val password: String) : AppsStartupEvent()
data class CheckAppSessionServiceType(val appSession: Session) : AppsStartupEvent()
data class CopyAppScriptToFilesystem(val app: App, val filesystem: Filesystem) : AppsStartupEvent()
data class SyncDatabaseEntries(val app: App, val session: Session, val filesystem: Filesystem) : AppsStartupEvent()
object ResetAppState : AppsStartupEvent()