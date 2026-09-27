package tech.anl.library.companion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.anl.library.ui.VmLaunchOptionsDialog

class CompanionInputValidatorTest {
    private fun valid(u: String = "user", p: String = "pass", v: String = "vncpass", g: String = "1280x720") =
        CompanionInputValidator.validate(u, p, v, g) == CompanionInputValidator.Result.Valid

    @Test fun `accepts ordinary credentials`() {
        assertTrue(valid())
        assertTrue(valid(p = "!@#\$%^&*()_+=,./?<>:"))
        assertTrue(valid(p = "")) // companion substitutes its default
    }

    @Test fun `rejects quote newline and NUL anywhere`() {
        for (bad in listOf("a'b", "a\nb", "a\rb", "a\u0000b")) {
            assertFalse(bad, valid(p = bad))
            assertFalse(bad, valid(v = bad))
            assertFalse(bad, valid(u = bad))
        }
    }

    @Test fun `rejects odd usernames`() {
        assertFalse(valid(u = ""))
        assertFalse(valid(u = "Root"))
        assertFalse(valid(u = "1user"))
        assertFalse(valid(u = "us er"))
        assertTrue(valid(u = "_svc-user1"))
    }

    @Test fun `geometry must be WxH digits`() {
        assertTrue(valid(g = "1024x768"))
        assertTrue(valid(g = "10000x99"))
        assertFalse(valid(g = "1024X768"))
        assertFalse(valid(g = "1x768"))
        assertFalse(valid(g = "1024x768 "))
        assertFalse(valid(g = "123456x768"))
        assertFalse(valid(g = "1024x768';reboot;'"))
        assertFalse(valid(g = ""))
    }
}

class CompanionVersionsTest {
    @Test fun `parse and compare`() {
        assertEquals(listOf(2, 10, 1), CompanionVersions.parse("2.10.1"))
        assertEquals(listOf(2, 2), CompanionVersions.parse("v2.2-next"))
        assertNull(CompanionVersions.parse("abc"))
        assertTrue(CompanionVersions.compare(listOf(2, 10), listOf(2, 9, 9)) > 0)
        assertEquals(0, CompanionVersions.compare(listOf(2, 0), listOf(2)))
    }

    @Test fun `classification`() {
        assertEquals(CompanionState.NOT_INSTALLED, CompanionVersions.classify(null, null, "2.2"))
        assertEquals(CompanionState.READY, CompanionVersions.classify("2.2", 26, "2.2"))
        assertEquals(CompanionState.READY, CompanionVersions.classify("2.3", 27, "2.2"))
        assertEquals(CompanionState.UPDATE_AVAILABLE, CompanionVersions.classify("2.2", 26, "2.3"))
        assertEquals(CompanionState.UPDATE_AVAILABLE, CompanionVersions.classify("2.2", 26, "2.2.1"))
        assertEquals(CompanionState.UPDATE_REQUIRED, CompanionVersions.classify("1.9", 20, "2.0"))
        assertEquals(CompanionState.READY, CompanionVersions.classify("3.0", 30, "2.9"))
        // Unknown latest version: never block.
        assertEquals(CompanionState.READY, CompanionVersions.classify("2.2", 26, null))
        assertEquals(CompanionState.READY, CompanionVersions.classify("2.2", 26, "<html>"))
    }

    @Test fun `bare versionCode in version txt compares against the installed versionCode`() {
        assertEquals(CompanionState.UPDATE_AVAILABLE, CompanionVersions.classify("1.0", 1, "2"))
        assertEquals(CompanionState.READY, CompanionVersions.classify("1.0", 2, "2"))
        assertEquals(CompanionState.READY, CompanionVersions.classify("2.2", 26, "26"))
    }

    @Test fun `nudge throttle`() {
        val day = 24L * 60 * 60 * 1000
        assertTrue(CompanionVersions.shouldNudge(0, 10 * day, 3 * day))
        assertFalse(CompanionVersions.shouldNudge(9 * day, 10 * day, 3 * day))
        assertTrue(CompanionVersions.shouldNudge(6 * day, 10 * day, 3 * day))
        assertTrue("clock went backwards", CompanionVersions.shouldNudge(20 * day, 10 * day, 3 * day))
    }
}

class CompanionChannelTest {
    @Test fun `release builds ignore the override`() {
        assertEquals("latest", CompanionChannel.resolve("latest", false, "next"))
    }

    @Test fun `debug builds honour a known override`() {
        assertEquals("next", CompanionChannel.resolve("latest", true, "next"))
        assertEquals("latest", CompanionChannel.resolve("next", true, "latest"))
        assertEquals("next", CompanionChannel.resolve("next", true, null))
        assertEquals("next", CompanionChannel.resolve("next", true, "bogus"))
        assertEquals("latest", CompanionChannel.resolve("", false, null))
    }

    @Test fun `release urls`() {
        assertEquals(
            "https://github.com/CypherpunkArmory/UserLAnd-Releases/releases/download/vm-next/userland-vm.apk",
            CompanionApp.VM.releaseUrl("next")
        )
        assertEquals(
            "https://github.com/CypherpunkArmory/UserLAnd-Releases/releases/download/qemu-latest/version.txt",
            CompanionApp.QEMU.versionUrl("latest")
        )
    }
}

class CompanionMiscLogicTest {
    @Test fun `pulse acl covers loopback and vm subnets`() {
        assertEquals("127.0.0.1", PulseAudioServer.aclFor(emptyList()))
        assertEquals("127.0.0.1;192.168.0.0/24", PulseAudioServer.aclFor(listOf("192.168.0.0/24")))
        assertEquals("192.168.0.0/24", PulseAudioServer.networkCidr(byteArrayOf(192.toByte(), 168.toByte(), 0, 1), 24))
        assertEquals("10.0.0.0/8", PulseAudioServer.networkCidr(byteArrayOf(10, 1, 2, 3), 8))
        assertNull(PulseAudioServer.networkCidr(byteArrayOf(10, 1, 2, 3), 0))
    }

    @Test fun `memory choices are capped at 60 percent of device RAM`() {
        val gb = 1024L * 1024 * 1024
        assertEquals(listOf(1, 2, 4), VmLaunchOptionsDialog.memoryChoicesGb(8 * gb))
        assertEquals(listOf(1, 2, 4, 8), VmLaunchOptionsDialog.memoryChoicesGb(16 * gb))
        assertEquals(listOf(1), VmLaunchOptionsDialog.memoryChoicesGb(1 * gb))
        assertEquals(2, VmLaunchOptionsDialog.defaultMemoryGb(listOf(1, 2, 4)))
        assertEquals(1, VmLaunchOptionsDialog.defaultMemoryGb(listOf(1)))
    }

    @Test fun `phantom process killer detection`() {
        assertFalse(PhantomProcessKiller.isActive(30, null, null, false))
        assertTrue(PhantomProcessKiller.isActive(31, null, null, false))
        assertFalse(PhantomProcessKiller.isActive(31, null, null, true))
        assertTrue(PhantomProcessKiller.isActive(34, null, null, false))
        assertTrue(PhantomProcessKiller.isActive(34, "true", null, true))
        assertFalse(PhantomProcessKiller.isActive(34, "false", null, false))
        assertFalse(PhantomProcessKiller.isActive(33, "0", null, false))
        assertFalse(PhantomProcessKiller.isActive(34, null, 2147483647, false))
    }

    @Test fun `start params omit a null shared path`() {
        val p = CompanionStartParams("ssh", "u", "p", "v", "800x600", "", 1, false, null, true, 0, false)
        assertFalse(p.toFields("1").containsValue(null) && CompanionJson.encode(p.toFields("1")).contains("sharedPath"))
        assertTrue(CompanionJson.encode(p.copy(sharedPath = "/storage/emulated/0").toFields("1")).contains("\"sharedPath\":\"/storage/emulated/0\""))
    }
}
