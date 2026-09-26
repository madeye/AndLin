package tech.anl.library.viewmodel

import androidx.lifecycle.LiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.* // ktlint-disable no-wildcard-imports
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.model.entities.Filesystem
import tech.anl.library.model.entities.Session
import kotlin.coroutines.CoroutineContext

class SessionEditViewModel(private val anlDatabase: AnlDatabase) : ViewModel(), CoroutineScope {

    private val job = Job()
    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Main + job

    override fun onCleared() {
        job.cancel()
        super.onCleared()
    }

    private val filesystems: LiveData<List<Filesystem>> by lazy {
        anlDatabase.filesystemDao().getAllFilesystems()
    }

    fun getAllFilesystems(): LiveData<List<Filesystem>> {
        return filesystems
    }

    fun insertSession(session: Session, coroutineScope: CoroutineScope = this) = coroutineScope.launch {
        withContext(Dispatchers.IO) {
            anlDatabase.sessionDao().insertSession(session)
        }
    }

    fun updateSession(session: Session, coroutineScope: CoroutineScope = this) = coroutineScope.launch {
        withContext(Dispatchers.IO) {
            anlDatabase.sessionDao().updateSession(session)
        }
    }
}

class SessionEditViewmodelFactory(private val anlDatabase: AnlDatabase) : ViewModelProvider.NewInstanceFactory() {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return SessionEditViewModel(anlDatabase) as T
    }
}