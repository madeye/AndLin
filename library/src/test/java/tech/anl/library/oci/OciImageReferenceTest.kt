package tech.anl.library.oci

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OciImageReferenceTest {
    @Test
    fun `parses GHCR references with and without tags`() {
        assertEquals(
            OciImageReference("ghcr.io", "cypherpunkarmory/userland-ubuntu_xfce", "20260921"),
            OciImageReference.parse("ghcr.io/cypherpunkarmory/userland-ubuntu_xfce:20260921"),
        )
        val latest = OciImageReference.parse("ghcr.io/cypherpunkarmory/userland-alpine")
        assertEquals("latest", latest.tag)
        assertEquals("ghcr.io/cypherpunkarmory/userland-alpine:latest", latest.toString())
    }

    @Test
    fun `parses digests, ports and Docker Hub shorthand`() {
        val digest = "sha256:" + "a".repeat(64)
        val byDigest = OciImageReference.parse("localhost:5000/team/app:ignored@$digest")
        assertEquals("localhost:5000", byDigest.registry)
        assertEquals("team/app", byDigest.repository)
        assertTrue(byDigest.isDigest)
        assertEquals("localhost:5000/team/app@$digest", byDigest.toString())

        assertEquals(OciImageReference("docker.io", "library/ubuntu", "24.04"), OciImageReference.parse("ubuntu:24.04"))
        assertEquals(OciImageReference("docker.io", "someone/tool", "latest"), OciImageReference.parse("someone/tool"))
    }

    @Test(expected = IllegalArgumentException::class)
    fun `rejects malformed repositories`() {
        OciImageReference.parse("ghcr.io/Bad/../name")
    }
}
