package tech.anl.library.model.remote

import org.junit.Assert.assertEquals
import org.junit.Test

class ReleaseForRepoTest {
    private val perDistro = "alpine:tags/v0.0.9,arch:tags/v0.0.6,debian:tags/v0.0.15,ubuntu:tags/v0.0.21,kali:tags/v0.0.10"

    @Test
    fun `per-distribution entries are matched case-insensitively`() {
        assertEquals("tags/v0.0.21", releaseForRepo(perDistro, "Ubuntu"))
        assertEquals("tags/v0.0.9", releaseForRepo(perDistro, "alpine"))
    }

    @Test
    fun `unlisted repos fall back to the latest release`() {
        assertEquals("latest", releaseForRepo(perDistro, "Support"))
    }

    @Test
    fun `a single release applies to every repo`() {
        assertEquals("tags/v7.7.9", releaseForRepo("tags/v7.7.9", "Debian"))
    }
}
