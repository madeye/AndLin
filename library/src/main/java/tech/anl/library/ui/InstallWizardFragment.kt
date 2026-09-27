package tech.anl.library.ui

import android.Manifest
import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.os.bundleOf
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.fragment.app.viewModels
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import tech.anl.library.R
import tech.anl.library.adb.AdbClient
import tech.anl.library.adb.AdbJobs
import tech.anl.library.adb.AdbSetupFlow
import tech.anl.library.companion.CompanionApkVerifier
import tech.anl.library.companion.CompanionApp
import tech.anl.library.companion.CompanionChannel
import tech.anl.library.companion.CompanionDownloader
import tech.anl.library.companion.CompanionInstaller
import tech.anl.library.companion.CompanionState
import tech.anl.library.companion.PhantomProcessKiller
import tech.anl.library.databinding.FragInstallWizardBinding
import tech.anl.library.utils.DeclaredPermissions
import java.io.File

/**
 * Full-screen wizard for jobs that need ADB through Wireless debugging: installing or updating a
 * companion app (download, pair, install, grant), or switching off the phantom process killer.
 *
 * Show it with [show]; the host gets a fragment result under [REQUEST_KEY] with [RESULT_SUCCESS].
 * The work runs in a ViewModel so it survives the user switching to Settings and back.
 */
class InstallWizardFragment : DialogFragment() {

    enum class Job { INSTALL, UPDATE, UPDATE_REQUIRED, PHANTOM_FIX }

    private var _binding: FragInstallWizardBinding? = null
    private val binding get() = _binding!!
    private val viewModel: InstallWizardViewModel by viewModels()

    private val job: Job by lazy { Job.valueOf(requireArguments().getString(ARG_JOB)!!) }
    private val app: CompanionApp? by lazy { requireArguments().getString(ARG_APP)?.let { CompanionApp.valueOf(it) } }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        viewModel.start(job, app)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setStyle(STYLE_NO_TITLE, R.style.AppTheme)
        isCancelable = false
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragInstallWizardBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        // Full screen (non-floating theme), so edge-to-edge: keep the content clear of the bars and IME.
        SystemBarInsets.prepareFullScreenDialog(dialog?.window)
        SystemBarInsets.padForSystemBars(binding.root)
        binding.installWizardTitle.text = when (job) {
            Job.INSTALL -> getString(R.string.companion_wizard_title_install, appName())
            Job.UPDATE, Job.UPDATE_REQUIRED -> getString(R.string.companion_wizard_title_update, appName())
            Job.PHANTOM_FIX -> getString(R.string.companion_wizard_title_phantom)
        }
        binding.installWizardPair.setOnClickListener { submitCode() }
        binding.installWizardCode.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_DONE) { submitCode(); true } else false
        }
        binding.installWizardOpenSettings.setOnClickListener { openDeveloperOptions() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.ui.collect { render(it) }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    private fun appName(): String = when (app) {
        CompanionApp.VM -> getString(R.string.companion_name_vm)
        CompanionApp.QEMU -> getString(R.string.companion_name_qemu)
        null -> ""
    }

    private fun render(state: WizardUi) {
        val b = _binding ?: return
        val totalSteps = if (job == Job.PHANTOM_FIX) 3 else 4
        b.installWizardStep.text = getString(R.string.companion_wizard_step, state.step.number(job).coerceAtMost(totalSteps), totalSteps)
        b.installWizardBody.text = when (state.step) {
            WizardStep.CONSENT -> consentText()
            WizardStep.PAIR -> getString(R.string.companion_wizard_pair_instructions)
            else -> state.body ?: ""
        }
        b.installWizardStatus.visibility = if (state.status.isNullOrEmpty()) View.GONE else View.VISIBLE
        b.installWizardStatus.text = state.status
        when {
            state.progress == null -> b.installWizardProgress.visibility = View.GONE
            state.progress < 0 -> {
                b.installWizardProgress.visibility = View.VISIBLE
                b.installWizardProgress.isIndeterminate = true
            }
            else -> {
                b.installWizardProgress.visibility = View.VISIBLE
                b.installWizardProgress.isIndeterminate = false
                b.installWizardProgress.progress = state.progress
            }
        }
        b.installWizardCodeRow.visibility = if (state.showCodeInput) View.VISIBLE else View.GONE
        b.installWizardOpenSettings.visibility = if (state.step == WizardStep.PAIR) View.VISIBLE else View.GONE

        when (state.step) {
            WizardStep.CONSENT -> {
                val unsupported = unsupportedReason()
                if (unsupported != null) {
                    b.installWizardBody.text = unsupported
                    b.installWizardPrimary.visibility = View.GONE
                } else {
                    b.installWizardPrimary.visibility = View.VISIBLE
                    b.installWizardPrimary.setText(R.string.companion_wizard_continue)
                    b.installWizardPrimary.setOnClickListener { begin() }
                }
                b.installWizardSecondary.visibility = View.VISIBLE
                b.installWizardSecondary.setText(R.string.companion_wizard_cancel)
                b.installWizardSecondary.setOnClickListener { finish(false) }
            }
            WizardStep.DONE -> {
                b.installWizardPrimary.visibility = View.VISIBLE
                b.installWizardPrimary.setText(R.string.companion_wizard_close)
                b.installWizardPrimary.setOnClickListener { finish(true) }
                b.installWizardSecondary.visibility = View.GONE
            }
            WizardStep.FAILED -> {
                b.installWizardPrimary.visibility = View.VISIBLE
                b.installWizardPrimary.setText(R.string.companion_wizard_retry)
                b.installWizardPrimary.setOnClickListener { begin() }
                b.installWizardSecondary.visibility = View.VISIBLE
                b.installWizardSecondary.setText(R.string.companion_wizard_close)
                b.installWizardSecondary.setOnClickListener { finish(false) }
            }
            else -> {
                b.installWizardPrimary.visibility = View.GONE
                b.installWizardSecondary.visibility = View.VISIBLE
                b.installWizardSecondary.setText(R.string.companion_wizard_cancel)
                b.installWizardSecondary.setOnClickListener {
                    viewModel.cancel()
                    finish(false)
                }
            }
        }
        state.launchInstaller?.let { apk ->
            viewModel.consumeInstallerLaunch()
            launchSystemInstaller(apk)
        }
    }

    private fun consentText(): String = when (job) {
        Job.INSTALL -> getString(R.string.companion_wizard_consent_install, appName(), app!!.releaseAssetName) +
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) "\n\n" + getString(R.string.companion_wizard_legacy_install, appName()) else ""
        Job.UPDATE -> getString(R.string.companion_wizard_consent_update, appName())
        Job.UPDATE_REQUIRED -> getString(R.string.companion_wizard_consent_update_required, appName())
        Job.PHANTOM_FIX -> getString(R.string.companion_wizard_consent_phantom)
    }

    private fun unsupportedReason(): String? {
        val a = app
        return when {
            job == Job.PHANTOM_FIX && Build.VERSION.SDK_INT < Build.VERSION_CODES.R -> getString(R.string.companion_wizard_no_wireless_debugging)
            a != null && !DeclaredPermissions.canInstallCompanionApps(requireContext()) ->
                getString(R.string.companion_not_installable_message, appName())
            a != null && !CompanionInstaller.isSupportedOnThisDevice(requireContext(), a) -> getString(
                if (a == CompanionApp.VM) R.string.companion_wizard_unsupported_vm else R.string.companion_wizard_unsupported_qemu,
                appName()
            )
            else -> null
        }
    }

    private fun begin() {
        val needsAdb = job == Job.PHANTOM_FIX || Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
        if (needsAdb && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            // The pairing-code notification is the smooth path; the in-app field works without it.
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            viewModel.start(job, app)
        }
    }

    private fun submitCode() {
        val code = binding.installWizardCode.text?.toString().orEmpty()
        if (code.isNotBlank()) {
            AdbSetupFlow.submitPairingCode(code)
            binding.installWizardCode.setText("")
        }
    }

    private fun openDeveloperOptions() {
        val ctx = requireContext()
        val action = if (Settings.Global.getInt(ctx.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1) {
            Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS
        } else {
            Settings.ACTION_DEVICE_INFO_SETTINGS
        }
        try {
            startActivity(Intent(action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (e: ActivityNotFoundException) {
            startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }

    /** Pre-Android 11: no Wireless debugging, so hand the APK to the system installer. */
    @Suppress("DEPRECATION")
    private fun launchSystemInstaller(apk: File) {
        val ctx = requireContext()
        val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".provider.fileprovider", apk)
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(uri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            viewModel.fail(e.message ?: "No package installer")
        }
    }

    private fun finish(success: Boolean) {
        parentFragmentManager.setFragmentResult(
            REQUEST_KEY,
            bundleOf(RESULT_SUCCESS to success, RESULT_JOB to job.name, RESULT_APP to app?.name)
        )
        dismissAllowingStateLoss()
    }

    override fun onResume() {
        super.onResume()
        viewModel.onResumed()
    }

    companion object {
        const val TAG = "InstallWizardFragment"
        const val REQUEST_KEY = "tech.anl.library.install_wizard"
        const val RESULT_SUCCESS = "success"
        const val RESULT_JOB = "job"
        const val RESULT_APP = "app"
        private const val ARG_JOB = "job"
        private const val ARG_APP = "app"

        fun newInstance(job: Job, app: CompanionApp?): InstallWizardFragment {
            require(job == Job.PHANTOM_FIX || app != null) { "$job needs a companion app" }
            return InstallWizardFragment().apply {
                arguments = bundleOf(ARG_JOB to job.name, ARG_APP to app?.name)
            }
        }

        fun show(activity: FragmentActivity, job: Job, app: CompanionApp?) {
            val fm = activity.supportFragmentManager
            if (fm.findFragmentByTag(TAG) != null || fm.isStateSaved) return
            newInstance(job, app).show(fm, TAG)
        }

        /**
         * Call before starting a VM session. Returns true when the session may start now. Returns
         * false after showing the install wizard (not installed, or a blocking major update); the
         * caller should retry once [REQUEST_KEY] reports success. A routine update is offered at
         * most every few days without blocking the launch.
         */
        suspend fun ensureCompanionReady(activity: FragmentActivity, app: CompanionApp): Boolean {
            val installer = CompanionInstaller(activity)
            val state = installer.state(app, CompanionChannel.get(activity))
            if (!DeclaredPermissions.canInstallCompanionApps(activity)) return explainWithoutInstalling(activity, app, state)
            return when (state) {
                CompanionState.NOT_INSTALLED -> { show(activity, Job.INSTALL, app); false }
                CompanionState.UPDATE_REQUIRED -> { show(activity, Job.UPDATE_REQUIRED, app); false }
                CompanionState.UPDATE_AVAILABLE -> {
                    if (installer.shouldNudgeForUpdate(app) && !activity.isFinishing) {
                        installer.markUpdateNudged(app)
                        val name = activity.getString(if (app == CompanionApp.VM) R.string.companion_name_vm else R.string.companion_name_qemu)
                        AlertDialog.Builder(activity)
                            .setTitle(R.string.companion_update_available_title)
                            .setMessage(activity.getString(R.string.companion_update_available_message, name))
                            .setPositiveButton(R.string.companion_update_now) { _, _ -> show(activity, Job.UPDATE, app) }
                            .setNegativeButton(R.string.companion_update_later, null)
                            .show()
                    }
                    true
                }
                CompanionState.READY -> true
            }
        }

        /**
         * The Play build may not download or install apps, so it never shows the wizard for a
         * companion: a missing or too-old one is only explained, and an optional update is not
         * offered. A companion the user installed themselves keeps working.
         */
        private fun explainWithoutInstalling(activity: FragmentActivity, app: CompanionApp, state: CompanionState): Boolean {
            val (title, message) = when (state) {
                CompanionState.NOT_INSTALLED -> R.string.companion_not_installable_title to R.string.companion_not_installable_message
                CompanionState.UPDATE_REQUIRED -> R.string.companion_too_old_title to R.string.companion_too_old_message
                CompanionState.UPDATE_AVAILABLE, CompanionState.READY -> return true
            }
            if (!activity.isFinishing && !activity.isDestroyed) {
                val name = activity.getString(if (app == CompanionApp.VM) R.string.companion_name_vm else R.string.companion_name_qemu)
                AlertDialog.Builder(activity)
                    .setTitle(activity.getString(title, name))
                    .setMessage(activity.getString(message, name))
                    .setPositiveButton(android.R.string.ok, null)
                    .show()
            }
            return false
        }
    }
}

enum class WizardStep {
    CONSENT, DOWNLOAD, PAIR, INSTALL, DONE, FAILED;

    fun number(job: InstallWizardFragment.Job): Int = when (this) {
        CONSENT -> 1
        DOWNLOAD -> 2
        PAIR -> if (job == InstallWizardFragment.Job.PHANTOM_FIX) 2 else 3
        INSTALL -> if (job == InstallWizardFragment.Job.PHANTOM_FIX) 3 else 4
        DONE, FAILED -> if (job == InstallWizardFragment.Job.PHANTOM_FIX) 3 else 4
    }
}

data class WizardUi(
    val step: WizardStep = WizardStep.CONSENT,
    val body: String? = null,
    val status: String? = null,
    /** null = hidden, <0 = indeterminate, else percent. */
    val progress: Int? = null,
    val showCodeInput: Boolean = false,
    /** Set once to ask the fragment to open the system installer (pre-Android 11). */
    val launchInstaller: File? = null
)

class InstallWizardViewModel(application: Application) : AndroidViewModel(application) {
    private val ctx get() = getApplication<Application>()
    private val _ui = MutableStateFlow(WizardUi())
    val ui: StateFlow<WizardUi> = _ui.asStateFlow()
    private var work: Job? = null
    private var legacyInstall: Pair<CompanionApp, File>? = null

    fun start(job: InstallWizardFragment.Job, app: CompanionApp?) {
        if (work?.isActive == true) return
        AdbSetupFlow.reset()
        work = viewModelScope.launch {
            try {
                run(job, app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                fail(e.message ?: e.javaClass.simpleName)
            }
        }
    }

    fun cancel() {
        work?.cancel()
        AdbSetupFlow.cancelNotification(ctx)
    }

    fun fail(message: String) {
        _ui.value = WizardUi(step = WizardStep.FAILED, body = ctx.getString(R.string.companion_wizard_failed, message))
    }

    fun consumeInstallerLaunch() {
        _ui.value = _ui.value.copy(launchInstaller = null)
    }

    /** Back from the system installer (pre-Android 11 path): check whether it worked. */
    fun onResumed() {
        val (app, _) = legacyInstall ?: return
        if (tech.anl.library.companion.CompanionControlClient.isInstalled(ctx, app)) {
            legacyInstall = null
            CompanionInstaller(ctx).invalidate(app)
            _ui.value = WizardUi(step = WizardStep.DONE, body = ctx.getString(R.string.companion_wizard_done_install, name(app)))
        }
    }

    private fun name(app: CompanionApp) =
        ctx.getString(if (app == CompanionApp.VM) R.string.companion_name_vm else R.string.companion_name_qemu)

    private suspend fun run(job: InstallWizardFragment.Job, app: CompanionApp?) {
        val apk = if (job != InstallWizardFragment.Job.PHANTOM_FIX) download(app!!) else null

        if (apk != null && Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            legacyInstall = app!! to apk
            _ui.value = WizardUi(step = WizardStep.INSTALL, body = ctx.getString(R.string.companion_wizard_installing, name(app)),
                progress = -1, launchInstaller = apk)
            return
        }

        val adb = connectAdb()
        if (job == InstallWizardFragment.Job.PHANTOM_FIX) {
            _ui.value = WizardUi(step = WizardStep.INSTALL, body = ctx.getString(R.string.companion_wizard_applying_fix), progress = -1)
            val error = AdbJobs.disablePhantomProcessKiller(adb)
            if (error != null) return fail(error)
            PhantomProcessKiller.markFixApplied(ctx)
            _ui.value = WizardUi(step = WizardStep.DONE, body = ctx.getString(R.string.companion_wizard_done_phantom))
            return
        }

        val companion = app!!
        _ui.value = WizardUi(step = WizardStep.INSTALL, body = ctx.getString(R.string.companion_wizard_installing, name(companion)), progress = 0)
        AdbJobs.installCompanion(adb, apk!!) { sent, total ->
            if (total > 0) _ui.value = _ui.value.copy(progress = (sent * 100 / total).toInt())
        }?.let { return fail(it) }
        _ui.value = WizardUi(step = WizardStep.INSTALL, body = ctx.getString(R.string.companion_wizard_granting), progress = -1)
        val problems = AdbJobs.grantCompanionPermissions(adb, companion)
        apk.delete()
        CompanionInstaller(ctx).invalidate(companion)
        _ui.value = WizardUi(
            step = WizardStep.DONE,
            body = if (problems.isEmpty()) ctx.getString(R.string.companion_wizard_done_install, name(companion))
            else ctx.getString(R.string.companion_wizard_done_install_with_problems, name(companion), problems.joinToString("\n"))
        )
    }

    private suspend fun download(app: CompanionApp): File {
        val label = app.releaseAssetName
        _ui.value = WizardUi(step = WizardStep.DOWNLOAD, body = ctx.getString(R.string.companion_wizard_downloading, label), progress = -1)
        val dest = File(File(ctx.cacheDir, "companion"), label)
        val url = app.releaseUrl(CompanionChannel.get(ctx))
        try {
            return CompanionDownloader().download(
                url,
                dest,
                onProgress = { done, total ->
                    if (total > 0) {
                        val pct = (done * 100 / total).toInt()
                        _ui.value = _ui.value.copy(status = ctx.getString(R.string.companion_wizard_downloading_progress, label, pct), progress = pct)
                    }
                },
                accept = { file, from -> CompanionApkVerifier.verify(ctx, file, app, from) }
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            throw IllegalStateException(ctx.getString(R.string.companion_wizard_download_failed, label, e.message ?: ""), e)
        }
    }

    private suspend fun connectAdb(): AdbClient {
        _ui.value = WizardUi(step = WizardStep.PAIR, progress = -1)
        val watcher = viewModelScope.launch {
            AdbSetupFlow.stage.collect { stage ->
                val (status, codeInput) = when (stage) {
                    AdbSetupFlow.Stage.NeedWirelessDebugging -> ctx.getString(R.string.companion_wizard_state_need_wireless) to false
                    AdbSetupFlow.Stage.WaitingForPairingDialog -> ctx.getString(R.string.companion_wizard_state_waiting_dialog) to true
                    is AdbSetupFlow.Stage.WaitingForCode -> ctx.getString(
                        if (stage.wrongCodeBefore) R.string.companion_wizard_state_wrong_code else R.string.companion_wizard_state_waiting_code
                    ) to true
                    AdbSetupFlow.Stage.Pairing -> ctx.getString(R.string.companion_wizard_state_pairing) to false
                    AdbSetupFlow.Stage.Connecting -> ctx.getString(R.string.companion_wizard_state_connecting) to false
                    else -> null to false
                }
                if (_ui.value.step == WizardStep.PAIR) _ui.value = _ui.value.copy(status = status, showCodeInput = codeInput)
            }
        }
        try {
            return AdbSetupFlow.connect(ctx)
        } finally {
            watcher.cancel()
        }
    }

    override fun onCleared() {
        super.onCleared()
        AdbSetupFlow.cancelNotification(ctx)
    }
}
