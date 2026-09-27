package tech.anl.library.viewmodel

import android.content.SharedPreferences
import android.net.Uri
import androidx.annotation.StringRes
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.google.gson.Gson
import kotlinx.coroutines.*
import tech.anl.library.R
import tech.anl.library.model.daos.SessionDao
import tech.anl.library.model.entities.App
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.utils.AppDetails
import kotlin.coroutines.CoroutineContext

data class AppDetailsViewState(
        val appIconUri: Uri,
        val appTitle: String,
        val appDescription: String,
        val describeStateHintEnabled: Boolean,
        @StringRes val describeStateText: Int?,
        val autoStartEnabled: Boolean
)

sealed class AppDetailsEvent {
    data class SubmitApp(val app: App) : AppDetailsEvent()
    data class AutoStartChanged(val autoStartEnabled: Boolean, val app: App) : AppDetailsEvent()
}

class AppDetailsViewModel(private val sessionDao: SessionDao, private val appDetails: AppDetails, private val prefs: SharedPreferences) : ViewModel(), CoroutineScope {
    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    val viewState = MutableLiveData<AppDetailsViewState>()

    fun submitEvent(event: AppDetailsEvent, coroutineScope: CoroutineScope = this) = coroutineScope.launch {
        return@launch when (event) {
            is AppDetailsEvent.SubmitApp -> constructView(event.app)
            is AppDetailsEvent.AutoStartChanged -> handleAutoStartChanged(event)
        }
    }

    private suspend fun constructView(app: App) {
        val appSession = getAppSession(app)
        viewState.postValue(buildViewState(app, appSession))
    }

    private suspend fun getAppSession(app: App): Session? = withContext(Dispatchers.IO) {
        val listOfPotentialSessions = sessionDao.findAppsSession(app.name)
        return@withContext if (listOfPotentialSessions.isEmpty()) null
        else listOfPotentialSessions.first()
    }

    private fun buildViewState(app: App, appSession: Session?): AppDetailsViewState {
        val appIconUri = appDetails.findIconUri(app.name)
        val appTitle = app.name
        val appDescription = appDetails.findAppDescription(app.name)

        val describeStateHintEnabled = getStateHintEnabled(appSession)
        val describeStateText = getStateDescription(appSession)

        var autoAppEnabled = false
        val gson = Gson()
        val json = prefs.getString("AutoApp", " ")
        if (json != null)
            if (json.compareTo(" ") != 0) {
                val autoApp = gson.fromJson(json, App::class.java)
                if (autoApp.name.compareTo(appTitle) == 0)
                    autoAppEnabled = true
            }

        return AppDetailsViewState(
                appIconUri,
                appTitle,
                appDescription,
                describeStateHintEnabled,
                describeStateText,
                autoAppEnabled
        )
    }

    private fun handleAutoStartChanged(event: AppDetailsEvent.AutoStartChanged) {
        this.launch {
            if (event.autoStartEnabled)
                with(prefs.edit()) {
                    val gson = Gson()
                    val json= gson.toJson(event.app)
                    putString("AutoApp", json)
                    apply()
                }
            else
                with(prefs.edit()) {
                    remove("AutoApp")
                    apply()
                }
        }
    }

    private fun getStateHintEnabled(appSession: Session?): Boolean {
        return getStateDescription(appSession) != null
    }

    @StringRes
    private fun getStateDescription(appSession: Session?): Int? {
        return when {
            appSession == null || appSession.serviceType == ServiceType.Unselected -> {
                R.string.info_finish_app_setup
            }
            else -> {
                null
            }
        }
    }
}

class AppDetailsViewmodelFactory(private val sessionDao: SessionDao, private val appDetails: AppDetails, private val prefs: SharedPreferences) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return AppDetailsViewModel(sessionDao, appDetails, prefs) as T
    }
}