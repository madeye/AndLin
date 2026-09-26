package tech.anl.library.utils

import android.content.SharedPreferences
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session

/**
 * How to reach a session's SSH server: shown on the Sessions tab and copied from it. PRoot
 * sessions always listen on [PORT] (see andlin/startSSHServer.sh); "Allow SSH from the network"
 * decides whether that is on every interface or only 127.0.0.1.
 */
data class SshServerInfo(
    val host: String,
    val port: Int,
    val username: String,
    val password: String,
    /** Listening on the network and a LAN address was found, so other devices can connect. */
    val reachableFromNetwork: Boolean,
    val keysOnly: Boolean,
) {
    val command: String get() = "ssh -p $port $username@$host"

    companion object {
        const val PORT = 2022
        const val LOCALHOST = "127.0.0.1"

        /** Null for sessions that aren't SSH servers. [lanAddress] is the device's LAN IPv4, if any. */
        fun forSession(session: Session, prefs: SharedPreferences, lanAddress: String?): SshServerInfo? {
            if (session.serviceType != ServiceType.Ssh) return null
            val listenOnLan = prefs.getBoolean("pref_ssh_listen_on_lan", false)
            val reachable = listenOnLan && lanAddress != null
            return SshServerInfo(
                host = if (reachable) lanAddress!! else LOCALHOST,
                port = PORT,
                username = session.username,
                password = session.password,
                reachableFromNetwork = reachable,
                keysOnly = prefs.getBoolean("pref_ssh_disable_password", false),
            )
        }
    }
}
