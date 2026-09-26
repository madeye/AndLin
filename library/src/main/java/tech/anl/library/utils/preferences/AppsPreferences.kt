package tech.anl.library.utils.preferences

import android.content.Context
import tech.anl.library.model.entities.App

class AppsPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("apps", Context.MODE_PRIVATE)

    fun setDistributionsList(distributionList: Set<String>) {
        with(prefs.edit()) {
            putStringSet("distributionsList", distributionList)
            apply()
        }
    }

    fun getDistributionsList(): Set<String> {
        return prefs.getStringSet("distributionsList", setOf()) ?: setOf()
    }
}