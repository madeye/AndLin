package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class RegistryMirrorTest {

    @Test
    fun `China time zones prefer the mirror and fall back to ghcr`() {
        assertEquals(listOf("ghcr.nju.edu.cn", "ghcr.io"), RegistryMirror.candidates("ghcr.io", "Asia/Shanghai"))
        assertEquals(listOf("ghcr.nju.edu.cn", "ghcr.io"), RegistryMirror.candidates("ghcr.io", "Asia/Urumqi"))
    }

    @Test
    fun `other time zones prefer ghcr`() {
        assertEquals(listOf("ghcr.io", "ghcr.nju.edu.cn"), RegistryMirror.candidates("ghcr.io", "America/Los_Angeles"))
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
        assertEquals("ghcr.nju.edu.cn/cypherpunkarmory/userland-ubuntu:latest", RegistryMirror.preferredImageRef(ref, "Asia/Shanghai"))
        assertEquals(ref, RegistryMirror.preferredImageRef(ref, "Europe/Berlin"))
    }
}
