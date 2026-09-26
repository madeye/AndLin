package tech.anl.library.proot

import android.net.Uri
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Hands the result of one ACTION_OPEN_DOCUMENT_TREE prompt from
 * [tech.anl.library.ui.DroidFilesPermissionActivity] back to the server workers waiting on it.
 *
 * Only one prompt exists at a time: concurrent requests for the same top-level directory share
 * it, requests for a different one get [Outcome.Busy] instead of queueing a second prompt (a
 * recursive walk over /sdcard must not stack up one picker per directory).
 */
object DroidFilesConsent {
    const val EXTRA_TOP = "tech.anl.library.proot.extra.TOP_LEVEL_DIR"

    sealed class Outcome {
        data class Granted(val treeUri: Uri) : Outcome()
        object Declined : Outcome()
        object Busy : Outcome()
        object TimedOut : Outcome()
    }

    private class Pending(val top: String) {
        val latch = CountDownLatch(1)
        @Volatile var uri: Uri? = null
    }

    private val lock = Any()
    private var active: Pending? = null

    /**
     * Blocks until the prompt for [top] completes. [launch] is called once, by whichever caller
     * created the prompt, and returns false if the prompt could not be shown.
     */
    fun await(top: String, timeoutMs: Long, launch: () -> Boolean): Outcome {
        val pending: Pending
        val created: Boolean
        synchronized(lock) {
            val current = active
            when {
                current == null -> {
                    pending = Pending(top); active = pending; created = true
                }
                current.top == top -> {
                    pending = current; created = false
                }
                else -> return Outcome.Busy
            }
        }
        if (created) {
            val launched = try { launch() } catch (e: RuntimeException) { false }
            if (!launched) complete(top, null)
        }
        val done = try {
            pending.latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        if (!done) {
            synchronized(lock) { if (active === pending) active = null }
            return Outcome.TimedOut
        }
        return pending.uri?.let { Outcome.Granted(it) } ?: Outcome.Declined
    }

    /** Called by the consent activity; a null [treeUri] means the user backed out. */
    fun complete(top: String, treeUri: Uri?) {
        synchronized(lock) {
            val current = active ?: return
            if (current.top != top) return
            active = null
            current.uri = treeUri
            current.latch.countDown()
        }
    }

    fun isPrompting(): Boolean = synchronized(lock) { active != null }
}
