package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class GithubMirrorTest {
    private val url = "https://github.com/CypherpunkArmory/UserLAnd-Releases/releases/download/vm-latest/userland-vm.apk"

    @Test
    fun `China gets proxies first and github last`() {
        val candidates = GithubMirror.candidates(url, "Asia/Shanghai")
        assertEquals(GithubMirror.chinaProxies.map { it + url } + url, candidates)
        assertEquals("https://gh-proxy.com/$url", GithubMirror.preferred(url, "Asia/Shanghai"))
    }

    @Test
    fun `elsewhere github is used directly`() {
        assertEquals(listOf(url), GithubMirror.candidates(url, "Europe/London"))
    }

    @Test
    fun `non-github urls are never proxied`() {
        val other = "https://ghcr.io/v2/cypherpunkarmory/userland-alpine/manifests/latest"
        assertEquals(listOf(other), GithubMirror.candidates(other, "Asia/Shanghai"))
    }
}
