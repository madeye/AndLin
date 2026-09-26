package tech.anl.library.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.Session
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports

class SessionListViewModel(
    private val anlDatabase: AnlDatabase
) : ViewModel() {

    private val sessions: LiveData<List<Session>> by lazy {
        anlDatabase.sessionDao().getAllSessions()
    }

    private val filesystems: LiveData<List<Filesystem>> by lazy {
        anlDatabase.filesystemDao().getAllFilesystems()
    }

    fun getSessionsAndFilesystems(): LiveData<Pair<List<Session>, List<Filesystem>>> {
        return zipLiveData(sessions, filesystems)
    }

    fun deleteSessionById(id: Long) {
        GlobalScope.launch { anlDatabase.sessionDao().deleteSessionById(id) }
    }
}

class SessionListViewModelFactory(private val anlDatabase: AnlDatabase) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SessionListViewModel(anlDatabase) as T
    }
}