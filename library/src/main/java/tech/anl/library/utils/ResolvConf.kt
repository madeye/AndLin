package tech.anl.library.utils

/**
 * Builds the guest's /etc/resolv.conf from the DNS servers Android reported for the active
 * network. musl (Alpine) and glibc only use addresses they can reach: link-local IPv6 servers
 * with a scope ("fe80::1%wlan0") are dropped, IPv4 goes first, and at most [MAX_NAMESERVERS]
 * are kept. With fewer than two usable servers, [fallbackNameservers] fill in after them: glibc
 * asks nameservers in order, and the network's own resolver is the one most likely to answer.
 */
object ResolvConf {
    const val MAX_NAMESERVERS = 3

    fun build(servers: List<String>, searchDomains: String?, fallbackNameservers: List<String>): String {
        val usable = servers.map { it.trim().removePrefix("/") }
            .filter { it.isNotEmpty() && '%' !in it && !it.lowercase().startsWith("fe80:") }
            .distinct()
            .sortedBy { if (':' in it) 1 else 0 } // stable: IPv4 first, otherwise Android's order
        val nameservers = (if (usable.size >= 2) usable else usable + fallbackNameservers)
            .distinct().take(MAX_NAMESERVERS)
        val lines = mutableListOf<String>()
        searchDomains?.trim()?.takeIf { it.isNotEmpty() }?.let { lines += "search $it" }
        nameservers.forEach { lines += "nameserver $it" }
        return lines.joinToString("\n") + "\n"
    }

    /** Nameserver addresses out of resolv.conf-style text ("nameserver 8.8.8.8\nnameserver …"). */
    fun nameserversIn(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.startsWith("nameserver ") }.map { it.removePrefix("nameserver ").trim() }
}
