package tech.anl.library.utils

import android.content.Context
import android.util.Log
import tech.anl.library.viewmodel.IllegalState

sealed class BreadcrumbType {
    // These types should override toString with return values of < 15 characters so that they
    // are easily identified in the Sentry UI.
    object ReceivedIntent : BreadcrumbType() {
        override fun toString(): String {
            return "Intent received"
        }
    }
    object SubmittedEvent : BreadcrumbType() {
        override fun toString(): String {
            return "Event submitted"
        }
    }
    object ReceivedEvent : BreadcrumbType() {
        override fun toString(): String {
            return "Event received"
        }
    }
    object ObservedState : BreadcrumbType() {
        override fun toString(): String {
            return "State observed"
        }
    }
    object RuntimeError : BreadcrumbType() {
        override fun toString(): String {
            return "Runtime error"
        }
    }
}

data class AnlBreadcrumb(
    val originatingClass: String,
    val type: BreadcrumbType,
    val details: String
)

interface Logger {
    fun initialize(context: Context? = null)

    fun addBreadcrumb(breadcrumb: AnlBreadcrumb)

    fun addExceptionBreadcrumb(err: Exception)

    fun sendIllegalStateLog(state: IllegalState)

    fun sendEvent(message: String)
}

/**
 * Crash reporting was removed along with Sentry; breadcrumbs and events now only go to logcat so
 * they still show up in bug reports.
 */
class LogcatLogger : Logger {
    private val tag = "UserLAnd"

    override fun initialize(context: Context?) = Unit

    override fun addBreadcrumb(breadcrumb: AnlBreadcrumb) {
        Log.d(tag, "${breadcrumb.originatingClass}: ${breadcrumb.type}: ${breadcrumb.details}")
    }

    override fun addExceptionBreadcrumb(err: Exception) {
        Log.w(tag, "Exception breadcrumb", err)
    }

    override fun sendIllegalStateLog(state: IllegalState) {
        Log.e(tag, "Illegal state: ${state.javaClass.simpleName}")
    }

    override fun sendEvent(message: String) {
        Log.i(tag, message)
    }
}
