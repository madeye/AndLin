package tech.anl.library.ui

import android.os.Build
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import tech.anl.library.R
import tech.anl.library.companion.PhantomProcessKiller

/**
 * Offers the one-time phantom-process-killer fix (through [InstallWizardFragment]'s ADB flow).
 *
 * After a proot session dies, it asks whether the session crashed instead of announcing a setting:
 * the user knows whether they closed it, we don't always.
 */
object PhantomProcessKillerPrompt {

    /**
     * Call when a proot session ended without the user stopping it. [deathWasSelfInflicted] must be
     * true when the app itself tore the session down (stop button, app teardown, its own process
     * dying): nothing is asked then. Asks at most once a day and never after "Don't ask again".
     */
    fun maybeOfferAfterSessionDeath(activity: FragmentActivity, deathWasSelfInflicted: Boolean) {
        if (deathWasSelfInflicted) return
        if (activity.isFinishing || activity.isDestroyed) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return // no Wireless debugging to fix it with
        if (!PhantomProcessKiller.isActive(activity)) return
        if (!PhantomProcessKiller.mayAsk(activity)) return
        PhantomProcessKiller.markAsked(activity)

        AlertDialog.Builder(activity)
            .setTitle(R.string.companion_phantom_ask_title)
            .setMessage(R.string.companion_phantom_ask_message)
            .setPositiveButton(R.string.companion_phantom_ask_yes) { _, _ -> explainAndOffer(activity) }
            .setNegativeButton(R.string.companion_phantom_ask_no, null)
            .setNeutralButton(R.string.companion_phantom_ask_never) { _, _ -> PhantomProcessKiller.setDontAskAgain(activity) }
            .show()
    }

    /** Settings entry point: explains and offers the fix, or says it isn't needed. */
    fun showFix(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        if (!PhantomProcessKiller.isActive(activity)) {
            AlertDialog.Builder(activity)
                .setMessage(R.string.companion_phantom_not_needed)
                .setPositiveButton(android.R.string.ok, null)
                .show()
            return
        }
        explainAndOffer(activity)
    }

    private fun explainAndOffer(activity: FragmentActivity) {
        if (activity.isFinishing || activity.isDestroyed) return
        AlertDialog.Builder(activity)
            .setTitle(R.string.companion_phantom_explain_title)
            .setMessage(R.string.companion_phantom_explain_message)
            .setPositiveButton(R.string.companion_phantom_fix_now) { _, _ ->
                InstallWizardFragment.show(activity, InstallWizardFragment.Job.PHANTOM_FIX, null)
            }
            .setNegativeButton(R.string.companion_phantom_not_now, null)
            .show()
    }
}
