package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Test

class FilesystemImagesTest {
    @Test
    fun `server flavor uses ServerBox's headless images`() {
        assertEquals("ghcr.io/madeye/andlin-ubuntu:latest", FilesystemImages.imageRef("ubuntu", "server", "latest"))
    }

    @Test
    fun `desktop flavors use the UserLAnd images`() {
        assertEquals("ghcr.io/cypherpunkarmory/userland-debian:latest", FilesystemImages.imageRef("debian", "default", "latest"))
        assertEquals("ghcr.io/cypherpunkarmory/userland-kali_xfce:latest", FilesystemImages.imageRef("Kali", "XFCE", "latest"))
    }

    @Test
    fun `per-distribution tags are honoured`() {
        val tags = "ubuntu:20260921,debian:20260901"
        assertEquals("ghcr.io/madeye/andlin-ubuntu:20260921", FilesystemImages.imageRef("ubuntu", "server", tags))
        assertEquals("ghcr.io/madeye/andlin-alpine:latest", FilesystemImages.imageRef("alpine", "server", tags))
    }
}
