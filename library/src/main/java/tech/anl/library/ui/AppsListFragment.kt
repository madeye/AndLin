package tech.anl.library.ui

import android.app.AlertDialog
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.LinearLayoutManager
import android.view.LayoutInflater
import android.view.ViewGroup
import android.view.View
import android.view.Menu
import android.view.MenuItem
import android.view.MenuInflater
import androidx.core.os.bundleOf
import androidx.navigation.fragment.findNavController
import tech.anl.library.databinding.FragAppListBinding
import tech.anl.library.BuildConfig
import tech.anl.library.MainActivity
import tech.anl.library.R
import tech.anl.library.utils.DesktopSupport
import tech.anl.library.ServerService
import tech.anl.library.model.entities.App
import tech.anl.library.model.remote.GithubAppsFetcher
import tech.anl.library.model.repositories.AppsRepository
import tech.anl.library.model.repositories.RefreshStatus
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.utils.* // ktlint-disable no-wildcard-imports
import tech.anl.library.utils.preferences.AppsPreferences
import tech.anl.library.viewmodel.AppsListViewModel
import tech.anl.library.viewmodel.AppsListViewModelFactory

class AppsListFragment : Fragment(), AppsListAdapter.AppsClickHandler {

    private val fragAppListBinding: FragAppListBinding get() = FragAppListBinding.bind(requireView())

    interface AppSelection {
        fun appHasBeenSelected(app: App, autoStart: Boolean)
    }

    private val doOnAppSelection: AppSelection by lazy {
        activityContext
    }

    private lateinit var activityContext: MainActivity

    private val appsAdapter by lazy {
        AppsListAdapter(activityContext, this)
    }

    private var refreshStatus = RefreshStatus.INACTIVE

    private val appsPreferences by lazy {
        AppsPreferences(activityContext)
    }

    private val viewModel: AppsListViewModel by lazy {
        val anlDatabase = AnlDatabase.getInstance(activityContext)
        val appsDao = anlDatabase.appsDao()

        val githubFetcher = GithubAppsFetcher("${activityContext.filesDir}", activityContext.assets, activityContext.defaultSharedPreferences)

        val appsRepository = AppsRepository(appsDao, githubFetcher, appsPreferences, activityContext.defaultSharedPreferences)
        ViewModelProvider(this, AppsListViewModelFactory(appsRepository))                .get(AppsListViewModel::class.java)
    }

    private val appsObserver = Observer<List<App>> {
        it?.let { list ->
            // GUI-only apps (no CLI) need a desktop session, which is opt-in.
            val desktopEnabled = DesktopSupport.isEnabled(activityContext)
            appsAdapter.updateApps(list.filter { app -> desktopEnabled || app.supportsCli })
            fragAppListBinding.listApps.scrollToPosition(0)
            if (list.isEmpty() || appIsNewVersion()) {
                doRefresh()
            }
        }
    }

    private val activeAppsObserver = Observer<List<App>> {
        it?.let { list ->
            appsAdapter.updateActiveApps(list)
        }
    }

    private val refreshStatusObserver = Observer<RefreshStatus> {
        it?.let { newStatus ->
            refreshStatus = newStatus
            fragAppListBinding.swipeRefresh.isRefreshing = refreshStatus == RefreshStatus.ACTIVE

            if (refreshStatus == RefreshStatus.FAILED) showRefreshUnavailableDialog()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setHasOptionsMenu(true)
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        super.onCreateOptionsMenu(menu, inflater)
        inflater.inflate(R.menu.menu_refresh, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when {
            item.itemId == R.id.menu_item_refresh -> {
                fragAppListBinding.swipeRefresh.isRefreshing = true
                doRefresh()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onClick(app: App) {
        doOnAppSelection.appHasBeenSelected(app, false)
    }

    override fun createContextMenu(menu: Menu) {
        activityContext.menuInflater.inflate(R.menu.context_menu_apps, menu)
    }

    override fun onContextItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.menu_item_app_details -> showAppDetails(appsAdapter.contextMenuItem)
            R.id.menu_item_stop_app -> stopAppSession(appsAdapter.contextMenuItem)
            else -> super.onContextItemSelected(item)
        }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.frag_app_list, container, false)
    }

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)
        activityContext = requireActivity() as MainActivity
        viewModel.getAppsList().observe(viewLifecycleOwner, appsObserver)
        viewModel.getActiveApps().observe(viewLifecycleOwner, activeAppsObserver)
        viewModel.getRefreshStatus().observe(viewLifecycleOwner, refreshStatusObserver)

        registerForContextMenu(fragAppListBinding.listApps)
        fragAppListBinding.listApps.layoutManager = LinearLayoutManager(fragAppListBinding.listApps.context)
        fragAppListBinding.listApps.adapter = appsAdapter

        fragAppListBinding.swipeRefresh.setOnRefreshListener { doRefresh() }
        fragAppListBinding.swipeRefresh.setColorSchemeResources(
                R.color.holo_blue_light,
                R.color.holo_green_light,
                R.color.holo_orange_light,
                R.color.holo_red_light)
    }

    private fun doRefresh() {
        viewModel.refreshAppsList()
        setLatestUpdateAppVersion()
    }

    private fun showAppDetails(app: App): Boolean {
        val bundle = bundleOf("app" to app)
        this.findNavController().navigate(R.id.action_app_list_to_app_details, bundle)
        return true
    }

    private fun stopAppSession(app: App): Boolean {
        val serviceIntent = Intent(activityContext, ServerService::class.java)
                .putExtra("type", "stopApp")
                .putExtra("app", app)
        activityContext.startService(serviceIntent)
        return true
    }

    private fun showRefreshUnavailableDialog() {
        AlertDialog.Builder(activityContext)
                .setMessage(R.string.alert_network_required_for_refresh)
                .setTitle(R.string.general_error_title)
                .setPositiveButton(R.string.button_ok) {
                    dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun appIsNewVersion(): Boolean {
        val version = getAppVersion()
        val lastUpdatedVersion = activityContext.defaultSharedPreferences.getString("lastAppsUpdate", "")
        return version != lastUpdatedVersion
    }

    private fun setLatestUpdateAppVersion() {
        val version = getAppVersion()
        with(activityContext.defaultSharedPreferences.edit()) {
            putString("lastAppsUpdate", version)
            apply()
        }
    }

    private fun getAppVersion(): String {
        val info = activityContext.packageManager.getPackageInfo(activityContext.packageName, 0)
        return info.versionName ?: ""
    }
}
