package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistryMirrorTest {

    @Test
    fun `China time zones prefer the mirrors in order and fall back to ghcr`() {
        val expected = listOf("ghcr.linkos.org", "ghcr.chenby.cn", "ghcr.nju.edu.cn", "ghcr.io")
        assertEquals(expected, RegistryMirror.candidates("ghcr.io", "Asia/Shanghai"))
        assertEquals(expected, RegistryMirror.candidates("ghcr.io", "Asia/Urumqi"))
    }

    @Test
    fun `other time zones prefer ghcr`() {
        assertEquals(
            listOf("ghcr.io", "ghcr.linkos.org", "ghcr.chenby.cn", "ghcr.nju.edu.cn"),
            RegistryMirror.candidates("ghcr.io", "America/Los_Angeles")
        )
        // Hong Kong and Taipei reach ghcr.io fine.
        assertEquals("ghcr.io", RegistryMirror.candidates("ghcr.io", "Asia/Hong_Kong").first())
    }

    @Test
    fun `non-ghcr registries are left alone`() {
        assertEquals(listOf("registry-1.docker.io"), RegistryMirror.candidates("registry-1.docker.io", "Asia/Shanghai"))
    }

    @Test
    fun `image refs are rewritten only when the mirror is preferred`() {
        val ref = "ghcr.io/cypherpunkarmory/userland-ubuntu:latest"
        assertEquals("ghcr.linkos.org/cypherpunkarmory/userland-ubuntu:latest", RegistryMirror.preferredImageRef(ref, "Asia/Shanghai"))
        assertEquals(ref, RegistryMirror.preferredImageRef(ref, "Europe/Berlin"))
    }

    @Test
    fun `image ref candidates cover every registry in order`() {
        val ref = "ghcr.io/madeye/serverbox-alpine:latest"
        assertEquals(
            listOf(
                "ghcr.linkos.org/madeye/serverbox-alpine:latest",
                "ghcr.chenby.cn/madeye/serverbox-alpine:latest",
                "ghcr.nju.edu.cn/madeye/serverbox-alpine:latest",
                ref,
            ),
            RegistryMirror.imageRefCandidates(ref, "Asia/Shanghai")
        )
        assertEquals(listOf("docker.io/library/alpine:3"), RegistryMirror.imageRefCandidates("docker.io/library/alpine:3", "Asia/Shanghai"))
    }
}
