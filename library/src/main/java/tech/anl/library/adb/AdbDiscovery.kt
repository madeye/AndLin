package tech.anl.library.adb

import android.content.Context
import android.provider.Settings
import android.util.Log
import io.github.muntashirakon.adb.android.AdbMdns
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface

/** A wireless-debugging service advertised over mDNS. */
data class AdbEndpoint(val address: InetAddress, val port: Int)

/**
 * mDNS discovery (NsdManager, via libadb's AdbMdns) of this device's own wireless-debugging
 * services: `_adb-tls-pairing._tcp` exists only while the "Pair device with pairing code" dialog is
 * open; `_adb-tls-connect._tcp` while wireless debugging is on.
 */
object AdbDiscovery {
    private const val TAG = "AdbDiscovery"
    const val PAIRING = "_adb-tls-pairing._tcp"
    const val CONNECT = "_adb-tls-connect._tcp"

    /** Endpoints of [serviceType] on this device, as they appear and change. */
    fun services(context: Context, serviceType: String): Flow<AdbEndpoint> = callbackFlow {
        val mdns = AdbMdns(context.applicationContext, serviceType) { address, port ->
            if (address != null && port > 0) trySend(AdbEndpoint(address, port))
        }
        mdns.start()
        awaitClose { runCatching { mdns.stop() } }
    }.filter { isThisDevice(it.address) }.distinctUntilChanged()

    suspend fun findOnce(context: Context, serviceType: String, timeoutMs: Long): AdbEndpoint? =
        withTimeoutOrNull(timeoutMs) { services(context, serviceType).firstOrNull() }

    /** Other phones on the same Wi-Fi advertise the same services; only ours can be used. */
    fun isThisDevice(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        return try {
            NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                .flatMap { it.inetAddresses.toList() }
                .any { it == address }
        } catch (e: Exception) {
            Log.w(TAG, "Could not list interfaces; accepting $address", e)
            true
        }
    }

    /** Wireless debugging's master switch (Settings.Global "adb_wifi_enabled", API 30+). */
    fun isWirelessDebuggingEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    fun isDeveloperOptionsEnabled(context: Context): Boolean =
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1

    /** Addresses to try connecting on, loopback first (adbd listens on all interfaces). */
    fun connectHosts(endpoint: AdbEndpoint?): List<String> {
        val hosts = mutableListOf("127.0.0.1")
        val a = endpoint?.address
        if (a is Inet4Address && !a.isLoopbackAddress) hosts.add(a.hostAddress!!)
        return hosts
    }
}
