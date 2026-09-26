package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupLogTest {
    @Test
    fun `A new step adds a header and details follow it`() {
        val log = SetupLog()
        log.append("Setting up filesystem", "Resolving ghcr.io/madeye/serverbox-alpine:latest")
        log.append("Setting up filesystem", "Downloading layer 1/2 (0%)")

        assertEquals(
            "==> Setting up filesystem\nResolving ghcr.io/madeye/serverbox-alpine:latest\nDownloading layer 1/2 (0%)",
            log.text()
        )
    }

    @Test
    fun `A percentage update replaces the previous line`() {
        val log = SetupLog()
        log.append("Setting up filesystem", "Downloading layer 1/2 (10%)")
        assertTrue(log.append("Setting up filesystem", "Downloading layer 1/2 (55%)"))
        log.append("Setting up filesystem", "Downloading layer 2/2 (0%)")

        assertEquals(
            "==> Setting up filesystem\nDownloading layer 1/2 (55%)\nDownloading layer 2/2 (0%)",
            log.text()
        )
    }

    @Test
    fun `Repeating the same step and details changes nothing`() {
        val log = SetupLog()
        log.append("Downloading", "1 out of 3")
        assertFalse(log.append("Downloading", "1 out of 3"))
        assertFalse(log.append("Downloading", ""))
    }

    @Test
    fun `Multi-line details become separate lines and old lines are dropped past the limit`() {
        val log = SetupLog(maxLines = 3)
        log.append("Extracting", "a\nb\n\nc")

        assertEquals("a\nb\nc", log.text())
    }

    @Test
    fun `Lines without a percentage are never merged`() {
        assertFalse(SetupLog.isProgressUpdate("Extracting bin/ls", "Extracting bin/sh"))
        assertTrue(SetupLog.isProgressUpdate("Extracting filesystem (5%)", "Extracting filesystem (100%)"))
    }

    @Test
    fun `Clearing starts over, including the step header`() {
        val log = SetupLog()
        log.append("Downloading", "x")
        log.clear()
        log.append("Downloading", "")

        assertEquals("==> Downloading", log.text())
    }
}
