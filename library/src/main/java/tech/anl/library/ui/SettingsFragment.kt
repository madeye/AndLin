package tech.anl.library.ui

import android.content.Context
import android.widget.Toast
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.view.View
import androidx.preference.*
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.R
import tech.anl.library.utils.DesktopSupport
import tech.anl.library.ui.PhantomProcessKillerPrompt
import tech.anl.library.utils.NetworkAddresses
import tech.anl.library.utils.ProotDebugLogger
import tech.anl.library.utils.AnlFiles
import tech.anl.library.utils.defaultSharedPreferences

class SettingsFragment : PreferenceFragmentCompat() {

    private val prootDebugLogger by lazy {
        val anlFiles = AnlFiles(requireActivity(), requireActivity().applicationInfo.nativeLibraryDir)
        ProotDebugLogger(requireActivity().defaultSharedPreferences, anlFiles)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        addPreferencesFromResource(R.xml.preferences)

        val deleteFilePreference: Preference = findPreference("pref_proot_delete_debug_file")!!
        deleteFilePreference.setOnPreferenceClickListener {
            prootDebugLogger.deleteLogs()
            true
        }

        val clearAutoStartPreference: Preference = findPreference("pref_clear_auto_start")!!
        clearAutoStartPreference.setOnPreferenceClickListener {
            val prefs = requireActivity().getSharedPreferences("apps", Context.MODE_PRIVATE)
            with(prefs.edit()) {
                remove("AutoApp")
                apply()
                true
            }
        }

        val hideSessionsFilesystemsPreference: CheckBoxPreference = findPreference("pref_hide_sessions_filesystems")!!
        hideSessionsFilesystemsPreference.setOnPreferenceChangeListener { preference, newValue ->
            if (newValue is Boolean) {
                val bottomNavView = requireActivity().findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
                    R.id.bottom_nav_view
                )
                if (newValue) {
                    bottomNavView.visibility = View.GONE
                } else {
                    bottomNavView.visibility = View.VISIBLE
                }
            }
            true
        }
        if (!DesktopSupport.isBuiltIn) {
            preferenceScreen.removePreference(findPreference<Preference>("pref_desktop_category")!!)
        }
        updateSshConnectionInfo()
        findPreference<Preference>("pref_phantom_process_fix")!!.setOnPreferenceClickListener {
            PhantomProcessKillerPrompt.showFix(requireActivity())
            true
        }
        findPreference<Preference>("pref_battery_optimization")!!.setOnPreferenceClickListener {
            requestBatteryOptimizationExemption()
            true
        }
        findPreference<Preference>("pref_ssh_listen_on_lan")!!.setOnPreferenceChangeListener { _, newValue ->
            updateSshConnectionInfo(newValue as Boolean)
            true
        }
        if (BuildConfig.HIDE_APP_PREFS) hidePrefs()
    }

    private fun updateSshConnectionInfo(
        listenOnLan: Boolean = preferenceManager.sharedPreferences!!.getBoolean("pref_ssh_listen_on_lan", false)
    ) {
        val info = findPreference<Preference>("pref_ssh_connection_info")!!
        val address = if (listenOnLan) NetworkAddresses.lanAddress() else null
        info.summary = if (address != null) {
            getString(R.string.pref_ssh_connection_info_lan, address)
        } else {
            getString(R.string.pref_ssh_connection_info_local)
        }
    }

    private fun requestBatteryOptimizationExemption() {
        if (BackgroundRunPrompt.isIgnoringBatteryOptimizations(requireContext())) {
            Toast.makeText(requireContext(), R.string.pref_battery_optimization_done, Toast.LENGTH_SHORT).show()
            return
        }
        BackgroundRunPrompt.requestExemption(requireActivity())
    }

    private fun hidePrefs() {
        val appCategory = findPreference<PreferenceGroup>("pref_app_category")!!
        listOf(
            "pref_default_nav_location", "pref_clear_auto_start", "pref_hide_sessions_filesystems",
            "pref_hide_distributions", "pref_custom_hostname_enabled", "pref_hostname",
            "pref_custom_apps_enabled", "pref_apps", "pref_custom_filesystem_enabled", "pref_filesystem"
        ).forEach { key -> findPreference<Preference>(key)?.let { appCategory.removePreference(it) } }
        findPreference<Preference>("pref_desktop_category")?.let { preferenceScreen.removePreference(it) }
        preferenceScreen.removePreference(findPreference<Preference>("pref_proot_category")!!)
    }

    override fun setDivider(divider: Drawable?) {
        super.setDivider(ColorDrawable(Color.TRANSPARENT))
    }

    override fun setDividerHeight(height: Int) {
        super.setDividerHeight(0)
    }
}