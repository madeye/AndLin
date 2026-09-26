package tech.anl.library

import tech.anl.customlibrary.BuildConfig
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.*
import android.preference.PreferenceScreen
import android.speech.SpeechRecognizer.isRecognitionAvailable
import android.util.DisplayMetrics
import android.view.*
import android.view.animation.AlphaAnimation
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProvider
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.navigation.NavController
import androidx.navigation.Navigation
import androidx.navigation.findNavController
import androidx.navigation.ui.NavigationUI
import androidx.navigation.ui.NavigationUI.setupWithNavController
import androidx.preference.Preference
import com.google.android.material.textfield.TextInputEditText
import com.google.gson.Gson
import tech.anl.library.databinding.ActivityMainBinding
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tech.anl.library.model.entities.App
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.withContext
import tech.anl.library.companion.CompanionApp
import tech.anl.library.companion.VmLaunchOptions
import tech.anl.library.ui.BackgroundRunPrompt
import tech.anl.library.ui.InstallWizardFragment
import tech.anl.library.ui.PhantomProcessKillerPrompt
import tech.anl.library.ui.VmLaunchOptionsDialog
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.model.entities.FilesystemFlavor
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import tech.anl.library.model.entities.toServiceType
import tech.anl.library.model.remote.GithubApiClient
import tech.anl.library.model.repositories.AssetRepository
import tech.anl.library.model.repositories.DownloadMetadata
import tech.anl.library.model.repositories.AnlDatabase
import tech.anl.library.model.state.*
import tech.anl.library.ui.AppsListFragment
import tech.anl.library.ui.FilesystemListFragment
import tech.anl.library.ui.SessionListFragment
import tech.anl.library.utils.*
import tech.anl.library.utils.preferences.*
import tech.anl.library.viewmodel.*
import java.lang.reflect.Method
import java.net.NetworkInterface
import java.util.*

class MainActivity : AppCompatActivity(), SessionListFragment.SessionSelection, AppsListFragment.AppSelection, FilesystemListFragment.FilesystemListProgress {

    private val activityMainBinding: ActivityMainBinding get() = ActivityMainBinding.bind((findViewById<android.view.ViewGroup>(android.R.id.content)).getChildAt(0))

    val className = "MainActivity"

    private var progressBarIsVisible = false
    private val setupLog = SetupLog()
    // Set when an operation finishes, so the next one starts with an empty log.
    private var setupLogIsStale = false
    private var currentFragmentDisplaysProgressDialog = false
    private var autoStarted = false

    private val logger = LogcatLogger()
    private val anlFiles by lazy { AnlFiles(this, this.applicationInfo.nativeLibraryDir) }
    private val busyboxExecutor by lazy {
        val prootDebugLogger = ProotDebugLogger(this.defaultSharedPreferences, anlFiles)
        BusyboxExecutor(anlFiles, prootDebugLogger)
    }

    private val navController: NavController by lazy {
        findNavController(R.id.nav_host_fragment)
    }

    private val notificationManager by lazy {
        NotificationConstructor(this)
    }

    private val userFeedbackPrompter by lazy {
        UserFeedbackPrompter(this, findViewById(R.id.layout_user_prompt_insert))
    }

    private val optInPrompter by lazy {
        CollectionOptInPrompter(this, findViewById(R.id.layout_user_prompt_insert))
    }

    val billingManager by lazy {
        BillingManager(
            this,
            contributionPrompter.onEntitledSubPurchases,
            contributionPrompter.onEntitledInAppPurchases,
            contributionPrompter.onPurchase,
            contributionPrompter.onSubscriptionSupportedChecked
        )
    }

    private val contributionPrompter by lazy {
        ContributionPrompter(this, findViewById(R.id.layout_user_prompt_insert))
    }

    private val downloadBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1)
            if (id == -1L) return
            else viewModel.submitCompletedDownloadId(id)
        }
    }

    private val serverServiceBroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            intent.getStringExtra("type")?.let { intentType ->
                val breadcrumb = AnlBreadcrumb(className, BreadcrumbType.ReceivedIntent, intentType)
                logger.addBreadcrumb(breadcrumb)
                when (intentType) {
                    "sessionActivated" -> handleSessionHasBeenActivated()
                    "vmProgress" -> updateProgressBar(
                        getString(R.string.progress_start_step),
                        intent.getStringExtra("message") ?: ""
                    )
                    "vmFailed" -> {
                        killProgressBar()
                        AlertDialog.Builder(this@MainActivity)
                            .setTitle(R.string.general_error_title)
                            .setMessage(intent.getStringExtra("message") ?: "")
                            .setPositiveButton(R.string.button_ok) { dialog, _ -> dialog.dismiss() }
                            .show()
                    }
                    "sessionDied" -> PhantomProcessKillerPrompt.maybeOfferAfterSessionDeath(
                        this@MainActivity,
                        deathWasSelfInflicted = !intent.getBooleanExtra("killedByHost", false)
                    )
                    "dialog" -> {
                        val type = intent.getStringExtra("dialogType") ?: ""
                        showDialog(type)
                    }
                    else -> {}
                }
            }
        }
    }

    private val stateObserver = Observer<State> {
        val breadcrumb = AnlBreadcrumb(className, BreadcrumbType.ObservedState, "$it")
        logger.addBreadcrumb(breadcrumb)
        it?.let { state ->
            handleStateUpdate(state)
        }
    }

    private val viewModel: MainActivityViewModel by lazy {
        val anlDatabase = AnlDatabase.getInstance(this)

        val assetPreferences = AssetPreferences(this)
        val githubApiClient = GithubApiClient(anlFiles)
        val assetRepository = AssetRepository(
            filesDir.path,
            anlFiles,
            assetPreferences,
            defaultSharedPreferences,
            githubApiClient
        )

        val filesystemManager = FilesystemManager(anlFiles, busyboxExecutor)
        val storageCalculator = StorageCalculator(StatFs(filesDir.path))

        val downloadManager = getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
        val downloadManagerWrapper = DownloadManagerWrapper(downloadManager)
        val assetDownloader = AssetDownloader(assetPreferences, downloadManagerWrapper, anlFiles)

        val appsStartupFsm = AppsStartupFsm(anlDatabase, filesystemManager, anlFiles, { DesktopSupport.isEnabled(this) })
        val sessionStartupFsm = SessionStartupFsm(
            anlDatabase,
            assetRepository,
            filesystemManager,
            assetDownloader,
            storageCalculator,
            OciFilesystemSetup(this, anlFiles)
        )
        ViewModelProvider(this, MainActivityViewModelFactory(appsStartupFsm, sessionStartupFsm))                .get(MainActivityViewModel::class.java)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else {
            if (intent != null) {
                checkForAppIntent(intent)
            }
            autoStart()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        setSupportActionBar(activityMainBinding.toolbar)
        notificationManager.createServiceNotificationChannel() // Android O requirement

        setNavStartDestination()
        setProgressDialogNavListeners()

        setupWithNavController(activityMainBinding.bottomNavView, navController)

        val promptViewHolder = findViewById<ViewGroup>(R.id.layout_user_prompt_insert)
        if (userFeedbackPrompter.viewShouldBeShown() && BuildConfig.ASK_FOR_FEEDBACK) {
            userFeedbackPrompter.showView()
        }

        if (optInPrompter.viewShouldBeShown() && BuildConfig.HAS_LOGGER) {
            optInPrompter.showView()
        }

        if (contributionPrompter.viewShouldBeShown() && BuildConfig.ASK_FOR_CONTRIBUTION) {
            contributionPrompter.showView()
        }

        //handleQWarning()

        if (optInPrompter.userHasOptedIn()) {
            logger.initialize(this)
        }

        viewModel.getState().observe(this, stateObserver)

        if (intent?.type.equals("settings"))
            navController.navigate(R.id.settings_fragment)
        else {
            checkForAppIntent(intent)
            autoStart()
        }
    }

    private fun checkForAppIntent(intent: Intent) {
        var app: App
        val prefs = getSharedPreferences("apps", Context.MODE_PRIVATE)
        if (intent.extras != null) {
            if (intent.extras!!.getParcelable<App>("app") != null) {
                app = intent.extras!!.getParcelable<App>("app")!!
                with(prefs.edit()) {
                    val gson = Gson()
                    val json= gson.toJson(app)
                    putString("AutoApp", json)
                    apply()
                }
            }
        }
    }

    private fun setNavStartDestination() {
        val userPreference = defaultSharedPreferences.getString("pref_default_nav_location", "Apps")
        val graph = navController.navInflater.inflate(R.navigation.nav_graph)
        graph.setStartDestination(when (userPreference) {
            getString(R.string.sessions) -> R.id.session_list_fragment
            else -> R.id.app_list_fragment
        })
        navController.graph = graph
        val bottomNavView = this.findViewById<com.google.android.material.bottomnavigation.BottomNavigationView>(
            R.id.bottom_nav_view
        )
        if (defaultSharedPreferences.getBoolean(
                "pref_hide_sessions_filesystems",
                BuildConfig.DEFAULT_HIDE_SESSIONS_FILESYSTEMS
            )){
            bottomNavView.visibility = View.GONE
        } else {
            bottomNavView.visibility = View.VISIBLE
        }
    }

    private fun setProgressDialogNavListeners() {
        navController.addOnDestinationChangedListener { _, destination, _ ->
            currentFragmentDisplaysProgressDialog =
                    destination.label == getString(R.string.sessions) ||
                            destination.label == getString(R.string.apps) ||
                            destination.label == getString(R.string.filesystems)
            // Only hidden while on another tab: the operation (and its log) carries on.
            if (!currentFragmentDisplaysProgressDialog) killProgressBar(keepLog = true)
            else if (progressBarIsVisible) displayProgressBar()
        }
    }

    private fun handleQWarning() {
        val handler = QWarningHandler(
            this.getSharedPreferences(
                QWarningHandler.prefsString,
                Context.MODE_PRIVATE
            ), anlFiles
        )
        if (handler.messageShouldBeDisplayed()) {
            AlertDialog.Builder(this)
                    .setTitle(R.string.q_warning_title)
                    .setMessage(R.string.q_warning_message)
                    .setPositiveButton(R.string.button_ok) { dialog, _ ->
                        dialog.dismiss()
                    }
                    .setNeutralButton(R.string.wiki) { dialog, _ ->
                        dialog.dismiss()
                        sendWikiIntent()
                    }
                    .create().show()
            handler.messageHasBeenDisplayed()
        }
    }

    override fun onSupportNavigateUp() = navController.navigateUp()

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        menuInflater.inflate(R.menu.menu_options, menu)
        return true
    }

    private fun getMacAddr(): String? {
        try {
            val all: List<NetworkInterface> = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nif in all) {
                if (!nif.getName().equals("wlan0", true)) continue
                val macBytes: ByteArray = nif.getHardwareAddress() ?: return ""
                val res1 = StringBuilder()
                for (b in macBytes) {
                    res1.append(String.format("%02X:", b))
                }
                if (res1.length > 0) {
                    res1.deleteCharAt(res1.length - 1)
                }
                return res1.toString().replace(":", "")
            }
        } catch (ex: java.lang.Exception) {
        }
        return UUID.randomUUID().toString()
    }

    private fun getCameraInfo() {
        val recognitionServiceAvailable = isRecognitionAvailable(this)
        with(defaultSharedPreferences.edit()) {
            putInt(
                "camera_supported",
                if (packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)) 1 else 0
            )
            putInt(
                "microphone_supported",
                if (recognitionServiceAvailable && packageManager.hasSystemFeature(
                        PackageManager.FEATURE_MICROPHONE
                    )
                ) 1 else 0
            )
            apply()
        }
    }

    private fun getNetInfo() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            val re1 = "^\\d+(\\.\\d+){3}$".toRegex()
            val re2 = "^[0-9a-f]+(:[0-9a-f]*)+:[0-9a-f]+$".toRegex()
            val SystemProperties = Class.forName("android.os.SystemProperties")
            val method: Method = SystemProperties.getMethod(
                "get",
                *arrayOf<Class<*>>(String::class.java)
            )
            val props = arrayOf("net.dns1", "net.dns2", "dhcp.wlan0.domain", "net.hostname")
            for (i in props.indices) {
                val v = method.invoke(null, props[i]) as String
                if (i < 2) {
                    if (v != null && (v.matches(re1) || v.matches(re2))) {
                        with(defaultSharedPreferences.edit()) {
                            putString("current_dns${i}", v)
                            apply()
                        }
                    }
                } else if ((i == 2) && (v != null && !v.trim().isEmpty())) {
                    with(defaultSharedPreferences.edit()) {
                        putString("search_domains", v.replace(",", " "))
                        apply()
                    }
                } else if (i == 3) {
                    if (!defaultSharedPreferences.contains("unique_id")) {
                        with(defaultSharedPreferences.edit()) {
                            if (v != null && !v.trim().isEmpty())
                                putString("unique_id", v)
                            else
                                putString("unique_id", "android-" + UUID.randomUUID().toString())
                            apply()
                        }
                    }
                }
            }
        } else {
            val connectivityManager = ContextCompat.getSystemService(
                this,
                ConnectivityManager::class.java
            )
            if (connectivityManager != null) {
                val currentNetwork = connectivityManager.getActiveNetwork()
                if (currentNetwork != null) {
                    val linkProperties = connectivityManager.getLinkProperties(currentNetwork)
                    if (linkProperties != null) {
                        val dnsServers = linkProperties.dnsServers
                        val searchDomains = linkProperties.domains
                        with(defaultSharedPreferences.edit()) {
                            if (dnsServers.size > 0)
                                putString("current_dns0", dnsServers[0].toString())
                            if (dnsServers.size > 1)
                                putString("current_dns1", dnsServers[1].toString())
                            // All of them: the first two may be unusable in the guest (see ResolvConf).
                            putString("current_dns_all", dnsServers.joinToString(" ") { it.hostAddress ?: "" })
                            if (searchDomains != null && !searchDomains.trim().isEmpty())
                                putString("search_domains", searchDomains.replace(",", " "))
                            apply()
                        }
                    }
                }
            }
            if (!defaultSharedPreferences.contains("unique_id")) {
                with(defaultSharedPreferences.edit()) {
                    putString("unique_id", "android-" + getMacAddr())
                    apply()
                }
            }
        }
    }

    private fun autoStart() {
        val prefs = getSharedPreferences("apps", Context.MODE_PRIVATE)
        val json = prefs.getString("AutoApp", " ")
        if (json != null)
            if (json.compareTo(" ") != 0) {
                val gson = Gson()
                val autoApp = gson.fromJson(json, App::class.java)
                autoStarted=true
                appHasBeenSelected(autoApp, true)
            }
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this)
                .registerReceiver(
                    serverServiceBroadcastReceiver,
                    IntentFilter(ServerService.SERVER_SERVICE_RESULT)
                )
        // Sent by the system's download provider, so it must be exported.
        ContextCompat.registerReceiver(
            this,
            downloadBroadcastReceiver,
            IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
            ContextCompat.RECEIVER_EXPORTED
        )
    }

    override fun onResume() {
        super.onResume()
        if (BuildConfig.ASK_FOR_CONTRIBUTION) {
            billingManager.querySubPurchases()
            billingManager.queryInAppPurchases()
        }
        viewModel.handleOnResume()
        BackgroundRunPrompt.maybeOffer(this)
    }

    override fun onDestroy() {
        if (BuildConfig.ASK_FOR_CONTRIBUTION) {
            billingManager.destroy()
        }
        super.onDestroy()
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        if (item.itemId == R.id.terms_and_conditions) {
            val intent = Intent(
                "android.intent.action.VIEW",
                Uri.parse("https://github.com/madeye/AndLin/blob/master/LICENSE")
            )
            startActivity(intent)
        }
        if (item.itemId == R.id.option_wiki) {
            sendWikiIntent()
        }
        if (item.itemId == R.id.clear_support_files) {
            displayClearSupportFilesDialog()
        }
        return NavigationUI.onNavDestinationSelected(
            item,
            Navigation.findNavController(this, R.id.nav_host_fragment)
        ) ||
                super.onOptionsItemSelected(item)
    }

    private fun sendWikiIntent() {
        val intent = Intent(
            "android.intent.action.VIEW",
            Uri.parse("https://github.com/madeye/AndLin#readme")
        )
        startActivity(intent)
    }

    override fun onStop() {
        super.onStop()

        LocalBroadcastManager.getInstance(this)
                .unregisterReceiver(serverServiceBroadcastReceiver)
        unregisterReceiver(downloadBroadcastReceiver)
    }

    override fun appHasBeenSelected(app: App, autoStart: Boolean) {
        getNetInfo()
        getCameraInfo()
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(appToContinue = app)
            return
        }
        viewModel.submitAppSelection(app, autoStart)
    }

    override fun sessionHasBeenSelected(session: Session) {
        getNetInfo()
        getCameraInfo()
        if (!PermissionHandler.permissionsAreGranted(this)) {
            PermissionHandler.showPermissionsNecessaryDialog(this)
            viewModel.waitForPermissions(sessionToContinue = session)
            return
        }
        viewModel.submitSessionSelection(session)
    }

    private fun handleStateUpdate(newState: State) {
        return when (newState) {
            is WaitingForInput -> {
                killProgressBar()
            }
            is CanOnlyStartSingleSession -> {
                showToast(R.string.single_session_supported)
                viewModel.handleUserInputCancelled()
            }
            is SessionCanBeStarted -> {
                prepareSessionForStart(newState.session)
            }
            is SessionCanBeRestarted -> {
                restartRunningSession(newState.session)
            }
            is IllegalState -> {
                handleIllegalState(newState)
            }
            is UserInputRequiredState -> {
                handleUserInputState(newState)
            }
            is ProgressBarUpdateState -> {
                handleProgressBarUpdateState(newState)
            }
        }
    }

    private fun prepareSessionForStart(session: Session) {
        val step = getString(R.string.progress_start_step)
        val details = ""
        updateProgressBar(step, details)

        // TODO: Alert user when defaulting to VNC
        // TODO: Is this even possible?
        if (session.serviceType is ServiceType.Xsdl && Build.VERSION.SDK_INT > Build.VERSION_CODES.O_MR1) {
            session.serviceType = ServiceType.Vnc
        }

        when (session.serviceType) {
            ServiceType.Xsdl -> {
                viewModel.lastSelectedSession = session
                sendXsdlIntentToSetDisplayNumberAndExpectResult()
            }
            ServiceType.Vnc -> {
                setVncResolution(session)
                startSession(session)
            }
            else -> startSession(session)
        }
    }

    private fun setVncResolution(session: Session) {
        val deviceDimensions = DeviceDimensions()
        val windowManager = applicationContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

        val orientation = applicationContext.resources.configuration.orientation
        deviceDimensions.saveDeviceDimensions(
            windowManager,
            DisplayMetrics(),
            orientation,
            defaultSharedPreferences
        )
        session.geometry = deviceDimensions.getScreenResolution()
    }

    private fun startSession(session: Session) {
        lifecycleScope.launch {
            val filesystem = withContext(Dispatchers.IO) {
                AnlDatabase.getInstance(this@MainActivity).filesystemDao().getFilesystemById(session.filesystemId)
            }
            val executionType = filesystem?.executionType ?: ExecutionType.PROOT
            if (!executionType.isVm) {
                launchSessionService(session, null)
                return@launch
            }
            // A VM session needs its companion app installed and current, and per-launch choices.
            val companion = CompanionApp.forExecutionType(executionType)!!
            if (!InstallWizardFragment.ensureCompanionReady(this@MainActivity, companion)) {
                viewModel.handleUserInputCancelled()
                return@launch
            }
            VmLaunchOptionsDialog.show(
                this@MainActivity,
                executionType,
                onChosen = { options -> launchSessionService(session, options) },
                onCancel = { viewModel.handleUserInputCancelled() }
            )
        }
    }

    private fun launchSessionService(session: Session, vmOptions: VmLaunchOptions?) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "start")
                .putExtra("session", session)
        vmOptions?.let { serviceIntent.putExtra("vmOptions", it) }
        startService(serviceIntent)
        if (autoStarted) {
            Handler(Looper.getMainLooper()).postDelayed({
                finish()
            }, 2000)
        }
    }

    /*
    XSDL has a different flow than starting SSH/VNC session.  It sends an intent to XSDL with
        with a display value.  Then XSDL sends an intent to open ServerBox signalling
        that it has an xserver listening.  We set the initial display number as an environment variable
        then start a twm process to connect to XSDL's xserver.
    */
    private fun sendXsdlIntentToSetDisplayNumberAndExpectResult() {
        try {
            val xsdlIntent = Intent(Intent.ACTION_MAIN, Uri.parse("x11://give.me.display:4721"))
            val setDisplayRequestCode = 1
            startActivityForResult(xsdlIntent, setDisplayRequestCode)
        } catch (e: Exception) {
            val appPackageName = "x.org.server"
            try {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("market://details?id=$appPackageName")
                    )
                )
            } catch (error: android.content.ActivityNotFoundException) {
                startActivity(
                    Intent(
                        Intent.ACTION_VIEW,
                        Uri.parse("https://play.google.com/store/apps/details?id=$appPackageName")
                    )
                )
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        data?.let {
                val session = viewModel.lastSelectedSession
                val result = data.getStringExtra("run") ?: ""
                if (session.serviceType == ServiceType.Xsdl && result.isNotEmpty()) {
                    startSession(session)
                }
        }
    }

    private fun restartRunningSession(session: Session) {
        val serviceIntent = Intent(this, ServerService::class.java)
                .putExtra("type", "restartRunningSession")
                .putExtra("session", session)
        startService(serviceIntent)
    }

    private fun handleSessionHasBeenActivated() {
        viewModel.handleSessionHasBeenActivated()
        killProgressBar()
    }

    private fun showToast(resId: Int) {
        val content = getString(resId)
        Toast.makeText(this, content, Toast.LENGTH_LONG).show()
    }

    private fun handleUserInputState(state: UserInputRequiredState) {
        return when (state) {
            is LowStorageAcknowledgementRequired -> {
                displayLowStorageDialog()
            }
            is FilesystemCredentialsRequired -> {
                if (BuildConfig.USE_DEFAULT_CREDS) {
                    viewModel.submitFilesystemCredentials(
                        BuildConfig.DEFAULT_USERNAME,
                        BuildConfig.DEFAULT_SSH_PASSWORD,
                        BuildConfig.DEFAULT_VNC_PASSWORD
                    )
                } else
                    getCredentials()
            }
            is FilesystemFlavorSelectionRequired -> {
                getFilesystemFlavor(state.flavors, state.executionTypes)
            }
            is AppServiceTypePreferenceRequired -> {
                // ServerBox sessions are SSH terminals unless desktop sessions are switched on.
                if (!DesktopSupport.isEnabled(this))
                    viewModel.submitAppServiceType(ServiceType.Ssh)
                else if (BuildConfig.USE_DEFAULT_SERVICE_TYPE)
                    viewModel.submitAppServiceType(BuildConfig.DEFAULT_LAUNCH_TYPE.toServiceType())
                else
                    getServiceTypePreference()
            }
            is LargeDownloadRequired -> {
                if (wifiIsEnabled()) {
                    viewModel.startAssetDownloads(state.downloadRequirements)
                    return
                }
                displayNetworkChoicesDialog(state.downloadRequirements)
            }
            is ActiveSessionsMustBeDeactivated -> {
                displayGenericErrorDialog(
                    R.string.general_error_title,
                    R.string.deactivate_sessions
                )
            }
        }
    }

    private fun handleIllegalState(state: IllegalState) {
        val stateDescription = IllegalStateHandler.getLocalizationData(state).getString(this)
        val displayMessage = getString(R.string.illegal_state_github_message, stateDescription)

        AlertDialog.Builder(this)
                .setMessage(displayMessage)
                .setTitle(R.string.illegal_state_title)
                .setPositiveButton(R.string.button_ok) { dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    // TODO sealed classes?
    private fun showDialog(dialogType: String) {
        when (dialogType) {
            "unhandledSessionServiceType" -> {
                displayGenericErrorDialog(
                    R.string.general_error_title,
                    R.string.illegal_state_unhandled_session_service_type
                )
            }
            "desktopUnavailable" ->
                Toast.makeText(this, R.string.desktop_unavailable, Toast.LENGTH_LONG).show()
            "playStoreMissingForClient" ->
                displayGenericErrorDialog(
                    R.string.alert_need_client_app_title,
                    R.string.alert_need_client_app_message
                )
        }
    }

    private fun displayClearSupportFilesDialog() {
        AlertDialog.Builder(this)
                .setMessage(R.string.alert_clear_support_files_message)
                .setTitle(R.string.alert_clear_support_files_title)
                .setPositiveButton(R.string.alert_clear_support_files_clear_button) { dialog, _ ->
                    handleClearSupportFiles()
                    dialog.dismiss()
                }
                .setNeutralButton(R.string.button_cancel) { dialog, _ ->
                    dialog.dismiss()
                }
                .create().show()
    }

    private fun handleClearSupportFiles() {
        val appsPreferences = AppsPreferences(this)
        val assetDirectoryNames = appsPreferences.getDistributionsList().plus("support")
        val assetFileClearer = AssetFileClearer(anlFiles, assetDirectoryNames, busyboxExecutor)
        CoroutineScope(Dispatchers.Main).launch { viewModel.handleClearSupportFiles(assetFileClearer) }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (PermissionHandler.permissionsWereGranted(requestCode, grantResults)) {
            viewModel.permissionsHaveBeenGranted()
        } else {
            PermissionHandler.showPermissionsNecessaryDialog(this)
        }
    }

    private fun handleProgressBarUpdateState(state: ProgressBarUpdateState) {
        return when (state) {
            is StartingSetup -> {
                val step = getString(R.string.progress_start_step)
                updateProgressBar(step, "")
            }
            is FetchingAssetLists -> {
                val step = getString(R.string.progress_fetching_asset_lists)
                updateProgressBar(step, "")
            }
            is CheckingForAssetsUpdates -> {
                val step = getString(R.string.progress_checking_for_required_updates)
                updateProgressBar(step, "")
            }
            is DownloadProgress -> {
                val step = getString(R.string.progress_downloading)
                val details = getString(
                    R.string.progress_downloading_out_of,
                    state.numComplete,
                    state.numTotal
                )
                updateProgressBar(step, details)
            }
            is CopyingDownloads -> {
                val step = getString(R.string.progress_copying_downloads)
                updateProgressBar(step, "")
            }
            is VerifyingFilesystem -> {
                val step = getString(R.string.progress_verifying_assets)
                updateProgressBar(step, "")
            }
            is VerifyingAvailableStorage -> {
                val step = getString(R.string.progress_verifying_sufficient_storage)
                updateProgressBar(step, "")
            }
            is FilesystemExtractionStep -> {
                // The raw line: the setup terminal shows it under the step's own header.
                updateProgressBar(getString(R.string.progress_setting_up_filesystem), state.extractionTarget)
            }
            is ClearingSupportFiles -> {
                val step = getString(R.string.progress_clearing_support_files)
                updateProgressBar(step, "")
            }
            is ProgressBarOperationComplete -> {
                killProgressBar()
            }
        }
    }

    override fun updateFilesystemExportProgress(details: String) {
        val step = getString(R.string.progress_exporting_filesystem)
        updateProgressBar(step, details)
    }

    override fun updateFilesystemDeleteProgress() {
        val step = getString(R.string.progress_deleting_filesystem)
        updateProgressBar(step, "")
    }

    override fun stopProgressFromFilesystemList() {
        killProgressBar()
    }

    private fun displayProgressBar() {
        if (!currentFragmentDisplaysProgressDialog) return

        if (!progressBarIsVisible) {
            val inAnimation = AlphaAnimation(0f, 1f)
            inAnimation.duration = 200
            activityMainBinding.layoutProgress.animation = inAnimation

            activityMainBinding.layoutProgress.visibility = View.VISIBLE
            activityMainBinding.layoutProgress.isFocusable = true
            activityMainBinding.layoutProgress.isClickable = true
            progressBarIsVisible = true
        }
    }

    private fun updateProgressBar(step: String, details: String) {
        displayProgressBar()

        activityMainBinding.textSessionListProgressStep.text = step
        activityMainBinding.textSessionListProgressDetails.text = details
        if (setupLogIsStale) {
            setupLog.clear()
            setupLogIsStale = false
        }
        if (setupLog.append(step, details)) showSetupLog()
    }

    private fun showSetupLog() {
        val scroll = activityMainBinding.scrollSetupLog
        val log = activityMainBinding.textSetupLog
        // Follow the output only while the user hasn't scrolled up to read something.
        val atBottom = !scroll.canScrollVertically(1)
        log.text = setupLog.text()
        // Not fullScroll(FOCUS_DOWN): that focuses the (selectable) log, which scrolls it back to
        // its top. The post runs after the layout pass the new text triggers.
        if (atBottom) scroll.post { scroll.scrollTo(0, log.bottom) }
    }

    private fun killProgressBar(keepLog: Boolean = false) {
        if (!keepLog) setupLogIsStale = true
        val outAnimation = AlphaAnimation(1f, 0f)
        outAnimation.duration = 200
        activityMainBinding.layoutProgress.animation = outAnimation
        activityMainBinding.layoutProgress.visibility = View.GONE
        activityMainBinding.layoutProgress.isFocusable = false
        activityMainBinding.layoutProgress.isClickable = false
        progressBarIsVisible = false
    }

    private fun wifiIsEnabled(): Boolean {
        val connectivityManager = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        for (network in connectivityManager.allNetworks) {
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) return true
        }
        return false
    }

    private fun displayNetworkChoicesDialog(downloadsToContinue: List<DownloadMetadata>) {
        val builder = AlertDialog.Builder(this)
        builder.setMessage(R.string.alert_wifi_disabled_message)
                .setTitle(R.string.alert_wifi_disabled_title)
                .setPositiveButton(R.string.alert_wifi_disabled_continue_button) { dialog, _ ->
                    dialog.dismiss()
                    viewModel.startAssetDownloads(downloadsToContinue)
                }
                .setNegativeButton(R.string.alert_wifi_disabled_turn_on_wifi_button) { dialog, _ ->
                    dialog.dismiss()
                    startActivity(Intent(WifiManager.ACTION_PICK_WIFI_NETWORK))
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setNeutralButton(R.string.alert_wifi_disabled_cancel_button) { dialog, _ ->
                    dialog.dismiss()
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .setOnCancelListener {
                    viewModel.handleUserInputCancelled()
                    killProgressBar()
                }
                .create()
                .show()
    }

    private fun getCredentials() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = this.layoutInflater.inflate(R.layout.dia_app_credentials, null)
        // Suggested defaults; the user can change them before continuing.
        val suggestedPassword = DefaultCredentials.randomPassword()
        dialogView.findViewById<TextInputEditText>(R.id.text_input_username).setText(DefaultCredentials.USERNAME)
        dialogView.findViewById<TextInputEditText>(R.id.text_input_password).setText(suggestedPassword)
        dialogView.findViewById<TextInputEditText>(R.id.text_input_vnc_password).setText(suggestedPassword)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener {
            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val username = customDialog.find<TextInputEditText>(R.id.text_input_username).text.toString()
                val password = customDialog.find<TextInputEditText>(R.id.text_input_password).text.toString()
                val vncPassword = customDialog.find<TextInputEditText>(R.id.text_input_vnc_password).text.toString()

                if (validateCredentials(username, password, vncPassword)) {
                    customDialog.dismiss()
                    viewModel.submitFilesystemCredentials(username, password, vncPassword)
                }
            }
        }
        customDialog.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }
        customDialog.show()
    }

    private fun displayLowStorageDialog() {
        displayGenericErrorDialog(
            R.string.alert_storage_low_title,
            R.string.alert_storage_low_message
        ) {
            viewModel.lowAvailableStorageAcknowledged()
        }
    }

    // TODO refactor the names here
    // TODO could this dialog share a layout with the apps details page somehow?
    private fun getFilesystemFlavor(flavors: List<FilesystemFlavor>, executionTypes: List<ExecutionType>) {
        val padding = (16 * resources.displayMetrics.density).toInt()
        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(padding * 3 / 2, padding, padding * 3 / 2, 0)
        }
        val entitled = contributionPrompter.isEntitledToProFeatures()
        fun label(text: String, paid: Boolean) =
            if (paid && !entitled) getString(R.string.pro_feature_label, text) else text

        val flavorGroup = RadioGroup(this)
        flavors.forEachIndexed { index, flavor ->
            flavorGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = flavor
                text = label(flavor.displayName, flavor.isPaid)
                isChecked = index == 0
            })
        }
        if (flavors.size > 1) {
            content.addView(TextView(this).apply { setText(R.string.filesystem_flavor_title) })
            content.addView(flavorGroup)
        }

        val executionGroup = RadioGroup(this)
        executionTypes.forEachIndexed { index, type ->
            executionGroup.addView(RadioButton(this).apply {
                id = View.generateViewId()
                tag = type
                text = label(getString(type.displayNameRes()), type.isVm)
                isChecked = index == 0
            })
        }
        if (executionTypes.size > 1) {
            content.addView(TextView(this).apply {
                setText(R.string.execution_type_title)
                setPadding(0, padding, 0, 0)
            })
            content.addView(executionGroup)
        }

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.filesystem_setup_title)
            .setView(content)
            .setCancelable(true)
            .setPositiveButton(R.string.button_continue, null)
            .create()
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                val flavor = flavorGroup.findViewById<RadioButton>(flavorGroup.checkedRadioButtonId)?.tag as? FilesystemFlavor
                    ?: flavors.first()
                val executionType = executionGroup.findViewById<RadioButton>(executionGroup.checkedRadioButtonId)?.tag as? ExecutionType
                    ?: ExecutionType.PROOT
                if ((flavor.isPaid || executionType.isVm) && !entitled) {
                    Toast.makeText(this, R.string.pro_feature_required, Toast.LENGTH_LONG).show()
                    contributionPrompter.showView()
                    return@setOnClickListener
                }
                dialog.dismiss()
                viewModel.submitFilesystemFlavor(flavor.name, executionType)
            }
        }
        dialog.setOnCancelListener { viewModel.handleUserInputCancelled() }
        dialog.show()
    }

    private fun ExecutionType.displayNameRes(): Int = when (this) {
        ExecutionType.PROOT -> R.string.execution_type_proot
        ExecutionType.AVF -> R.string.execution_type_avf
        ExecutionType.QEMU -> R.string.execution_type_qemu
    }

    private fun getServiceTypePreference() {
        val dialog = AlertDialog.Builder(this)
        val dialogView = layoutInflater.inflate(R.layout.dia_app_select_client, null)
        dialog.setView(dialogView)
        dialog.setCancelable(true)
        dialog.setPositiveButton(R.string.button_continue, null)
        val customDialog = dialog.create()

        customDialog.setOnShowListener {
            val sshTypePreference = customDialog.find<RadioButton>(R.id.ssh_radio_button)
            val vncTypePreference = customDialog.find<RadioButton>(R.id.vnc_radio_button)
            val xsdlTypePreference = customDialog.find<RadioButton>(R.id.xsdl_radio_button)

            // XSDL is gone; the built-in VNC viewer is the only desktop client.
            xsdlTypePreference.visibility = View.GONE
            customDialog.findViewById<TextView>(R.id.text_xsdl_version_supported_description)?.visibility = View.GONE

            if (!viewModel.lastSelectedApp.supportsCli) {
                sshTypePreference.isEnabled = false
                sshTypePreference.alpha = 0.5f
            }

            customDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                customDialog.dismiss()
                val selectedType = when {
                    sshTypePreference.isChecked -> ServiceType.Ssh
                    vncTypePreference.isChecked -> ServiceType.Vnc
                    else -> ServiceType.Unselected
                }
                viewModel.submitAppServiceType(selectedType)
            }
        }
        customDialog.setOnCancelListener {
            viewModel.handleUserInputCancelled()
        }

        customDialog.show()
    }

    private fun validateCredentials(username: String, password: String, vncPassword: String): Boolean {
        val blacklistedUsernames = this.resources.getStringArray(R.array.blacklisted_usernames)
        val validator = CredentialValidator()

        val usernameCredentials = validator.validateUsername(username, blacklistedUsernames)
        val passwordCredentials = validator.validatePassword(password)
        val vncPasswordCredentials = validator.validateVncPassword(vncPassword)

        return when {
            !usernameCredentials.credentialIsValid -> {
                Toast.makeText(this, usernameCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !passwordCredentials.credentialIsValid -> {
                Toast.makeText(this, passwordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            !vncPasswordCredentials.credentialIsValid -> {
                Toast.makeText(this, vncPasswordCredentials.errorMessageId, Toast.LENGTH_LONG).show()
                false
            }
            else -> true
        }
    }

}