package tech.anl.library.ui

import android.app.ActivityManager
import android.content.Context
import android.text.format.Formatter
import android.view.View
import android.widget.SeekBar
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.FragmentActivity
import tech.anl.library.R
import tech.anl.library.companion.VmLaunchOptions
import tech.anl.library.databinding.DiaCompanionVmLaunchOptionsBinding
import tech.anl.library.model.entities.ExecutionType
import tech.anl.library.utils.defaultSharedPreferences

/**
 * Per-launch choices for a VM session: share the device's storage (remembered as the default),
 * memory (1/2/4/8 GB, capped at ~60% of the device's RAM) and CPU (all cores or one).
 */
object VmLaunchOptionsDialog {
    private const val PREF_SHARE = "vm_launch_share_storage"
    private const val PREF_MEMORY_GB = "vm_launch_memory_gb"
    private const val PREF_ALL_CORES = "vm_launch_all_cores"
    private const val PREF_REMEMBER = "vm_launch_remember"
    private const val GB = 1024L * 1024 * 1024
    private val MEMORY_CHOICES_GB = listOf(1, 2, 4, 8)
    const val MAX_FRACTION_OF_DEVICE_RAM = 0.6

    /**
     * Shows the dialog (or, if the user chose "don't ask again", immediately returns the
     * remembered options) and delivers the result to [onChosen]. [onCancel] runs if dismissed.
     */
    fun show(
        activity: FragmentActivity,
        executionType: ExecutionType,
        onChosen: (VmLaunchOptions) -> Unit,
        onCancel: () -> Unit = {}
    ) {
        val prefs = activity.defaultSharedPreferences
        val choices = memoryChoicesGb(totalMemoryBytes(activity))
        if (prefs.getBoolean(PREF_REMEMBER, false)) {
            onChosen(current(activity, executionType, choices))
            return
        }
        if (activity.isFinishing || activity.isDestroyed) return

        val binding = DiaCompanionVmLaunchOptionsBinding.inflate(activity.layoutInflater)
        val isAvf = executionType == ExecutionType.AVF
        binding.companionVmShareStorage.isChecked = prefs.getBoolean(PREF_SHARE, false)
        binding.companionVmResources.visibility = if (isAvf) View.VISIBLE else View.GONE
        binding.companionVmQemuNote.visibility = if (isAvf) View.GONE else View.VISIBLE

        val savedGb = prefs.getInt(PREF_MEMORY_GB, defaultMemoryGb(choices))
        binding.companionVmMemory.max = choices.size - 1
        binding.companionVmMemory.progress = choices.indexOfLast { it <= savedGb }.coerceAtLeast(0)
        fun updateMemoryLabel() {
            binding.companionVmMemoryLabel.text = activity.getString(R.string.companion_vm_options_memory) + ": " +
                activity.getString(R.string.companion_vm_options_memory_value, choices[binding.companionVmMemory.progress])
        }
        updateMemoryLabel()
        binding.companionVmMemory.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) = updateMemoryLabel()
            override fun onStartTrackingTouch(seekBar: SeekBar?) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar?) = Unit
        })
        binding.companionVmMemoryNote.text = activity.getString(
            R.string.companion_vm_options_memory_limited, Formatter.formatShortFileSize(activity, totalMemoryBytes(activity))
        )
        if (prefs.getBoolean(PREF_ALL_CORES, true)) binding.companionVmCpuAll.isChecked = true else binding.companionVmCpuOne.isChecked = true

        var delivered = false
        AlertDialog.Builder(activity)
            .setTitle(R.string.companion_vm_options_title)
            .setView(binding.root)
            .setPositiveButton(R.string.companion_vm_options_start) { _, _ ->
                val gb = choices[binding.companionVmMemory.progress]
                val allCores = binding.companionVmCpuAll.isChecked
                val share = binding.companionVmShareStorage.isChecked
                prefs.edit()
                    .putBoolean(PREF_SHARE, share)
                    .putInt(PREF_MEMORY_GB, gb)
                    .putBoolean(PREF_ALL_CORES, allCores)
                    .putBoolean(PREF_REMEMBER, binding.companionVmRemember.isChecked)
                    .apply()
                delivered = true
                onChosen(toOptions(executionType, share, gb, allCores))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .setOnDismissListener { if (!delivered) onCancel() }
            .show()
    }

    /** Lets settings bring the dialog back after "don't ask again". */
    fun forgetRemembered(context: Context) {
        context.defaultSharedPreferences.edit().remove(PREF_REMEMBER).apply()
    }

    /** The saved choices without asking (e.g. for an app-launch that shouldn't prompt). */
    fun current(context: Context, executionType: ExecutionType, choices: List<Int> = memoryChoicesGb(totalMemoryBytes(context))): VmLaunchOptions {
        val prefs = context.defaultSharedPreferences
        val gb = prefs.getInt(PREF_MEMORY_GB, defaultMemoryGb(choices)).coerceAtMost(choices.last())
        return toOptions(executionType, prefs.getBoolean(PREF_SHARE, false), gb, prefs.getBoolean(PREF_ALL_CORES, true))
    }

    private fun toOptions(t: ExecutionType, share: Boolean, gb: Int, allCores: Boolean) =
        if (t == ExecutionType.AVF) VmLaunchOptions(share, gb * GB, allCores)
        else VmLaunchOptions(share, 0L, false) // QEMU ignores both

    /** Pure: the GB choices that fit in [MAX_FRACTION_OF_DEVICE_RAM] of [totalBytes]; never empty. */
    fun memoryChoicesGb(totalBytes: Long): List<Int> {
        val cap = totalBytes * MAX_FRACTION_OF_DEVICE_RAM
        return MEMORY_CHOICES_GB.filter { it * GB <= cap }.ifEmpty { listOf(1) }
    }

    fun defaultMemoryGb(choices: List<Int>): Int = choices.lastOrNull { it <= 2 } ?: choices.first()

    fun totalMemoryBytes(context: Context): Long {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return info.totalMem
    }
}
