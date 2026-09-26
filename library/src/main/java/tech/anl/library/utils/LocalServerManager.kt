package tech.anl.library.utils

import android.content.SharedPreferences
import android.content.pm.PackageManager
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.R
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import java.io.File

class LocalServerManager(
        private val applicationFilesDirPath: String,
        private val busyboxExecutor: BusyboxExecutor,
        private val sharedPreferences: SharedPreferences,
        private val logger: Logger = LogcatLogger()
) {

    private val vncDisplayNumber = BuildConfig.VNC_DISPLAY

    fun Process.pid(): Long {
        return this.toString()
                .substringAfter("pid=")
                .substringBefore(",")
                .substringBefore("]")
                .trim().toLong()
    }

    private fun getProperty(name: String): String {
        var output = ""
        val proc = Runtime.getRuntime().exec("getprop ${name}")
        proc.inputStream.bufferedReader(Charsets.UTF_8).forEachLine { output += it }
        return output
    }

    fun startServer(session: Session): Long {
        return when (session.serviceType) {
            ServiceType.Ssh -> startSSHServer(session)
            ServiceType.Vnc -> startVNCServer(session)
            ServiceType.Xsdl -> setDisplayNumberAndStartTwm(session)
            else -> 0
        }
    }

    fun stopService(session: Session) {
        val command = "support/killProcTree.sh ${session.pid} ${session.serverPid()}"
        val result = busyboxExecutor.executeScript(command)
        if (result is FailedExecution) {
            val details = "func: stopService err: ${result.reason}"
            val breadcrumb = AnlBreadcrumb("LocalServerManager", BreadcrumbType.RuntimeError, details)
            logger.addBreadcrumb(breadcrumb)
        }
    }

    fun isServerRunning(session: Session): Boolean {
        val command = "support/isServerInProcTree.sh ${session.serverPid()}"
        // The server itself is run by a third-party, so we can consider this to always be true.
        // The third-party app is responsible for handling errors starting their server.
        if (session.serviceType == ServiceType.Xsdl) return true
        val result = busyboxExecutor.executeScript(command)
        return when (result) {
            is SuccessfulExecution -> true
            is FailedExecution -> {
                val details = "func: isServerRunning err: ${result.reason}"
                val breadcrumb = AnlBreadcrumb("LocalServerManager", BreadcrumbType.RuntimeError, details)
                logger.addBreadcrumb(breadcrumb)
                false
            }
            else -> false
        }
    }

    private fun deletePidFile(session: Session) {
        val pidFile = File(session.pidFilePath())
        if (pidFile.exists()) pidFile.delete()
    }

    private fun startSSHServer(session: Session): Long {
        val filesystemDirName = session.filesystemId.toString()
        deletePidFile(session)
        val command = "/support/common/${ServerBoxScripts.START_SSH_SERVER}"
        val env = HashMap<String, String>()
        env["INITIAL_USERNAME"] = session.username
        // Only used to create the user if the filesystem lacks it (see startSSHServer.sh).
        env["INITIAL_PASSWORD"] = session.password
        env["SERVERBOX_SSH_ADDRESS"] = if (sharedPreferences.getBoolean("pref_ssh_listen_on_lan", false)) "0.0.0.0" else "127.0.0.1"
        val authorizedKeys = sharedPreferences.getString("pref_ssh_authorized_keys", "").orEmpty().trim()
        if (authorizedKeys.isNotEmpty()) env["SERVERBOX_AUTHORIZED_KEYS"] = authorizedKeys
        // The app's terminal authenticates with its own generated key, so this can't lock it out.
        if (sharedPreferences.getBoolean("pref_ssh_disable_password", false)) env["SERVERBOX_SSH_KEYS_ONLY"] = "1"
        val result = busyboxExecutor.executeProotCommand(command, filesystemDirName, false, env = env)
        return when (result) {
            is OngoingExecution -> result.process.pid()
            is FailedExecution -> {
                val details = "func: startSshServer err: ${result.reason}"
                val breadcrumb = AnlBreadcrumb("LocalServerManager", BreadcrumbType.RuntimeError, details)
                logger.addBreadcrumb(breadcrumb)
                -1
            }
            else -> -1
        }
    }

    private fun startVNCServer(session: Session): Long {
        val filesystemDirName = session.filesystemId.toString()
        deletePidFile(session)
        val command = "/support/startVNCServer.sh"
        val env = HashMap<String, String>()
        env["HAS_CAMERA"] = sharedPreferences.getInt("camera_supported",0).toString()
        env["HAS_MICROPHONE"] = sharedPreferences.getInt("microphone_supported",0).toString()
        env["INITIAL_USERNAME"] = session.username
        env["INITIAL_VNC_PASSWORD"] = session.vncPassword
        env["VNC_DISPLAY"] = vncDisplayNumber
        env["DIMENSIONS"] = session.geometry
        env["VERSION_CODE"] = BuildConfig.VERSION_CODE
        env["VERSION_NAME"] = BuildConfig.VERSION_NAME
        if (sharedPreferences.getBoolean("pref_custom_hostname_enabled",false)) {
            env["HOSTNAME"] = sharedPreferences.getString("pref_hostname", BuildConfig.DEFAULT_HOSTNAME)!!
        } else {
            if (sharedPreferences.contains("unique_id"))
                env["HOSTNAME"] = sharedPreferences.getString("unique_id", "localhost")!!
            else
                env["HOSTNAME"] = BuildConfig.DEFAULT_HOSTNAME
        }
        env["HOSTS"] = "127.0.0.1 localhost\n127.0.0.1 ${env["HOSTNAME"]}"
        if (sharedPreferences.getBoolean("pref_custom_dns_enabled",false)) {
            env["RESOLV"] = sharedPreferences.getString("pref_dns", BuildConfig.DEFAULT_DNS_DOMAINS + "\n" + BuildConfig.DEFAULT_DNS_NAMESERVERS)!!
        } else {
            env["RESOLV"] = ""
            if (sharedPreferences.contains("search_domains")) {
                env["RESOLV"] += "search " + sharedPreferences.getString("search_domains", "Home")
            } else
                env["RESOLV"] += BuildConfig.DEFAULT_DNS_DOMAINS

            if (sharedPreferences.contains("current_dns0")) {
                env["RESOLV"] += "\nnameserver " + sharedPreferences.getString("current_dns0", "8.8.8.8")!!.removePrefix("/")
                if (sharedPreferences.contains("current_dns1"))
                    env["RESOLV"] += "\nnameserver " + sharedPreferences.getString("current_dns1", "8.8.4.4")!!.removePrefix("/")
            } else
                env["RESOLV"] += "\n" + BuildConfig.DEFAULT_DNS_NAMESERVERS
        }

        val result = busyboxExecutor.executeProotCommand(
                command,
                filesystemDirName,
                commandShouldTerminate = false,
                env = env)
        return when (result) {
            is OngoingExecution -> result.process.pid()
            is FailedExecution -> {
                val details = "func: startVncServer err: ${result.reason}"
                val breadcrumb = AnlBreadcrumb("LocalServerManager", BreadcrumbType.RuntimeError, details)
                logger.addBreadcrumb(breadcrumb)
                -1
            }
            else -> -1
        }
    }

    private fun setDisplayNumberAndStartTwm(session: Session): Long {
        val filesystemDirName = session.filesystemId.toString()
        deletePidFile(session)
        val command = "/support/startXSDLServer.sh"
        val env = HashMap<String, String>()
        env["INITIAL_USERNAME"] = session.username
        env["DISPLAY"] = ":4721"
        env["PULSE_SERVER"] = "127.0.0.1:4721"
        val result = busyboxExecutor.executeProotCommand(
                command,
                filesystemDirName,
                commandShouldTerminate = false,
                env = env)
        return when (result) {
            is OngoingExecution -> result.process.pid()
            is FailedExecution -> {
                val details = "func: setDisplayNumberAndStartTwm err: ${result.reason}"
                val breadcrumb = AnlBreadcrumb("LocalServerManager", BreadcrumbType.RuntimeError, details)
                logger.addBreadcrumb(breadcrumb)
                -1
            }
            else -> -1
        }
    }

    private fun Session.pidRelativeFilePath(): String {
        return when (this.serviceType) {
            ServiceType.Ssh -> "/run/dropbear.pid"
            ServiceType.Vnc -> "/home/${this.username}/.vnc/localhost:$vncDisplayNumber.pid"
            ServiceType.Xsdl -> "/tmp/xsdl.pidfile"
            else -> "error"
        }
    }

    private fun Session.pidFilePath(): String {
        return "$applicationFilesDirPath/${this.filesystemId}${this.pidRelativeFilePath()}"
    }

    private fun Session.serverPid(): Long {
        val pidFile = File(this.pidFilePath())
        if (!pidFile.exists()) return -1
        return try {
            pidFile.readText().trim().toLong()
        } catch (e: Exception) {
            -1
        }
    }
}