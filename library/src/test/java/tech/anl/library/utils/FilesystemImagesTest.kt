package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FilesystemImagesTest {
    @Test
    fun `server flavor uses ServerBox's headless images`() {
        assertEquals("ghcr.io/madeye/serverbox-ubuntu:latest", FilesystemImages.imageRef("ubuntu", "server", "latest"))
    }

    @Test
    fun `legacy desktop flavors keep resolving to the UserLAnd images they were built from`() {
        assertEquals("ghcr.io/cypherpunkarmory/userland-debian:latest", FilesystemImages.imageRef("debian", "default", "latest"))
        assertEquals("ghcr.io/cypherpunkarmory/userland-kali_xfce:latest", FilesystemImages.imageRef("Kali", "XFCE", "latest"))
    }

    @Test
    fun `coding agent images resolve like distributions`() {
        assertEquals("ghcr.io/madeye/serverbox-claude:latest", FilesystemImages.imageRef("claude", "server", "latest"))
        assertEquals("ghcr.io/madeye/serverbox-codex:latest", FilesystemImages.imageRef("Codex", "server", "latest"))
    }

    @Test
    fun `coding agent images exist for 64-bit ABIs only`() {
        assertTrue(FilesystemImages.isAvailableFor("claude", "arm64-v8a"))
        assertTrue(FilesystemImages.isAvailableFor("codex", "x86_64"))
        assertFalse(FilesystemImages.isAvailableFor("claude", "armeabi-v7a"))
        assertFalse(FilesystemImages.isAvailableFor("Codex", "x86"))
        assertTrue(FilesystemImages.isAvailableFor("debian", "armeabi-v7a"))
    }

    @Test
    fun `per-distribution tags are honoured`() {
        val tags = "ubuntu:20260921,debian:20260901"
        assertEquals("ghcr.io/madeye/serverbox-ubuntu:20260921", FilesystemImages.imageRef("ubuntu", "server", tags))
        assertEquals("ghcr.io/madeye/serverbox-alpine:latest", FilesystemImages.imageRef("alpine", "server", tags))
    }
}
