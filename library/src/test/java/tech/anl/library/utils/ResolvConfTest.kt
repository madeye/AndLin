package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class ResolvConfTest {
    private val fallback = listOf("8.8.8.8", "8.8.4.4")

    @Test
    fun `drops scoped link-local servers and falls back when too few remain`() {
        // What a phone on an IPv6 home network reported: AliDNS v6 and the router's link-local.
        val conf = ResolvConf.build(listOf("/2400:3200::1", "/fe80::fac9:3ff:fe43:7faa%wlan0"), null, fallback)
        assertEquals("nameserver 2400:3200::1\nnameserver 8.8.8.8\nnameserver 8.8.4.4\n", conf)
    }

    @Test
    fun `IPv4 goes first, keeps Android's order otherwise, caps at three and adds search domains`() {
        val conf = ResolvConf.build(
            listOf("2001:db8::53", "192.168.0.1", "1.1.1.1", "9.9.9.9"),
            "lan home",
            fallback,
        )
        assertEquals("search lan home\nnameserver 192.168.0.1\nnameserver 1.1.1.1\nnameserver 9.9.9.9\n", conf)
    }

    @Test
    fun `no servers at all uses the fallback`() {
        assertEquals("nameserver 8.8.8.8\nnameserver 8.8.4.4\n", ResolvConf.build(emptyList(), " ", fallback))
        assertEquals(fallback, ResolvConf.nameserversIn("search Home\nnameserver 8.8.8.8\nnameserver 8.8.4.4"))
    }
}
