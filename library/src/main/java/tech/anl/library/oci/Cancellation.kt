package tech.anl.library.oci

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Runs blocking I/O on [Dispatchers.IO] such that cancelling the caller also unblocks it.
 *
 * Pipe writes, socket reads and `Process.waitFor` don't react to coroutine cancellation, so when
 * the caller is cancelled [onCancel] is invoked to break the blocking call from the outside
 * (destroy the process, cancel the HTTP call). [block] should also call `ensureActive()` between
 * chunks of work.
 */
internal suspend fun <T> runCancellableIo(onCancel: () -> Unit, block: CoroutineScope.() -> T): T =
    coroutineScope {
        val work = async(Dispatchers.IO) { block() }
        try {
            work.await()
        } catch (e: CancellationException) {
            onCancel()
            throw e
        }
    }
