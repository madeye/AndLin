package tech.anl.library.companion

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import androidx.core.content.ContextCompat
import tech.anl.customlibrary.BuildConfig as CustomBuildConfig
import tech.anl.library.utils.defaultSharedPreferences
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Runs the bundled PulseAudio as a network sound server for VM guests.
 *
 * Guests reach it on TCP 4713: the AVF guest via its default gateway (this device's address on the
 * AVF tap interface), the QEMU guest via SLIRP's 10.0.2.2 (which maps to 127.0.0.1). Clients are
 * anonymous but restricted by auth-ip-acl to loopback plus the VM tap subnets found on this device,
 * so nothing else on the network can play or record audio. Speaker and (optionally) microphone go
 * through the OpenSL ES sink/source modules, as in the proot path's execInProot.sh.
 */
class PulseAudioServer(context: Context) {
    private val context = context.applicationContext
    private val supportDir = File(this.context.filesDir, "support")
    private val logFile = File(this.context.filesDir, "pulse-vm.log")

    private var process: Process? = null
    private var runningAcl: String? = null

    /**
     * Starts the server, or keeps the running one when its ACL already covers the current VM
     * subnets. Returns false when PulseAudio could not be started.
     */
    @Synchronized
    fun start(): Boolean {
        val acl = buildAcl()
        val current = process
        if (current != null && current.isAliveCompat() && runningAcl == acl) return true
        stopLocked()

        val binary = File(supportDir, "pulseaudio")
        if (!binary.exists()) {
            Log.e(TAG, "pulseaudio support binary missing at $binary")
            return false
        }
        // A daemon from a proot session shares our runtime directory and port; replace it so the
        // VM subnet is admitted (proot sessions start their own again on next launch).
        runCatching { runAndWait(listOf(binary.absolutePath, "--kill"), 3) }

        val args = mutableListOf(
            binary.absolutePath,
            "--daemonize=no",
            "--use-pid-file=yes",
            "--exit-idle-time=-1",
            "--log-level=4",
            "--log-target=stderr",
            "--load=module-null-sink sink_name=virtspk sink_properties=device.description=Virtual_Speaker",
            "--load=module-native-protocol-tcp port=$PORT auth-anonymous=1 auth-ip-acl=$acl"
        )
        // default.pa already loads module-sles-sink (the speaker).
        if (microphoneAllowed()) args.add("--load=module-sles-source")
        return try {
            val pb = ProcessBuilder(args)
                .directory(supportDir)
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.to(logFile))
            pb.environment().putAll(environment())
            val p = pb.start()
            // A bad module/port makes pulseaudio exit almost immediately.
            if (p.waitForCompat(1500)) {
                Log.e(TAG, "pulseaudio exited with ${p.exitValue()}; see $logFile")
                false
            } else {
                process = p
                runningAcl = acl
                Log.i(TAG, "pulseaudio started on :$PORT, acl=$acl")
                true
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start pulseaudio", e)
            false
        }
    }

    @Synchronized
    fun stop() = stopLocked()

    val isRunning: Boolean @Synchronized get() = process?.isAliveCompat() == true

    private fun stopLocked() {
        process?.let { p ->
            p.destroy()
            if (!p.waitForCompat(2000)) p.destroyForciblyCompat()
        }
        process = null
        runningAcl = null
    }

    private fun environment(): Map<String, String> {
        val support = supportDir.absolutePath + "/"
        return mapOf(
            "PULSE_SCRIPT" to File(supportDir, "default.pa").absolutePath,
            "PULSE_CONFIG" to File(supportDir, "daemon.conf").absolutePath,
            "PULSE_DLPATH" to support,
            "XDG_DATA_HOME" to support,
            "XDG_CONFIG_HOME" to support,
            "XDG_STATE_HOME" to support,
            "TMPDIR" to support,
            "HOME" to support,
            "LD_LIBRARY_PATH" to supportDir.absolutePath
        )
    }

    private fun microphoneAllowed(): Boolean =
        isMicrophoneEnabled(context) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    private fun buildAcl(): String = aclFor(vmSubnets())

    private fun runAndWait(cmd: List<String>, seconds: Long) {
        val pb = ProcessBuilder(cmd).directory(supportDir).redirectErrorStream(true)
        pb.environment().putAll(environment())
        val p = pb.start()
        if (!p.waitForCompat(seconds * 1000)) p.destroy()
    }

    companion object {
        private const val TAG = "PulseAudioServer"
        const val PORT = 4713

        /** Preference keys the settings screen can expose; defaults come from the app's BuildConfig. */
        const val PREF_SOUND = "pref_sound_support"
        const val PREF_MICROPHONE = "pref_microphone_support"

        /** Interface-name prefixes of VM host-side networks (AVF's "avf_tap_fixed"). */
        private val VM_INTERFACE_PREFIXES = listOf("avf")

        @Volatile private var shared: PulseAudioServer? = null

        fun shared(context: Context): PulseAudioServer =
            shared ?: synchronized(this) { shared ?: PulseAudioServer(context).also { shared = it } }

        fun isSoundEnabled(context: Context): Boolean =
            context.defaultSharedPreferences.getBoolean(PREF_SOUND, CustomBuildConfig.DEFAULT_SOUND_SUPPORT)

        fun isMicrophoneEnabled(context: Context): Boolean =
            context.defaultSharedPreferences.getBoolean(PREF_MICROPHONE, CustomBuildConfig.DEFAULT_MICROPHONE_SUPPORT)

        /** "a.b.c.d/p" networks of the VM tap interfaces present right now. */
        fun vmSubnets(): List<String> = try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .filter { nif -> VM_INTERFACE_PREFIXES.any { nif.name.startsWith(it) } }
                .flatMap { it.interfaceAddresses }
                .mapNotNull { ia ->
                    val addr = ia.address as? Inet4Address ?: return@mapNotNull null
                    networkCidr(addr.address, ia.networkPrefixLength.toInt())
                }
                .distinct()
        } catch (e: Exception) {
            Log.w(TAG, "Could not enumerate network interfaces", e)
            emptyList()
        }

        /** Pure, for tests: loopback always, plus each VM subnet. */
        fun aclFor(subnets: List<String>): String = (listOf("127.0.0.1") + subnets).distinct().joinToString(";")

        fun networkCidr(address: ByteArray, prefix: Int): String? {
            if (address.size != 4 || prefix !in 8..32) return null
            val ip = address.fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }
            val mask = if (prefix == 0) 0L else (0xffffffffL shl (32 - prefix)) and 0xffffffffL
            val net = ip and mask
            return "${(net shr 24) and 0xff}.${(net shr 16) and 0xff}.${(net shr 8) and 0xff}.${net and 0xff}/$prefix"
        }
    }
}

// Process.isAlive/waitFor(timeout)/destroyForcibly are API 26+; minSdk is 24.
private fun Process.isAliveCompat(): Boolean = try { exitValue(); false } catch (e: IllegalThreadStateException) { true }

private fun Process.waitForCompat(timeoutMs: Long): Boolean {
    val deadline = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < deadline) {
        if (!isAliveCompat()) return true
        Thread.sleep(50)
    }
    return !isAliveCompat()
}

private fun Process.destroyForciblyCompat() {
    if (android.os.Build.VERSION.SDK_INT >= 26) destroyForcibly() else destroy()
}
