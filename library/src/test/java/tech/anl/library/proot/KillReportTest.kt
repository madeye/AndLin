package tech.anl.library.proot

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class KillReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun `clean session with guest kills is not a host kill`() {
        val r = KillReport.parse(
            "start pid=100 unix=1\n" +
                "kill vpid=5 pid=205 signal=9 origin=guest unix=2\n" +
                "clean pid=100 unix=3\n",
        )
        assertTrue(r.sessionEndedCleanly)
        assertFalse(r.prootItselfKilled)
        assertTrue(r.externalKills.isEmpty())
        assertFalse(KillReport.wasKilledByHost(r))
    }

    @Test
    fun `external kills are reported`() {
        val r = KillReport.parse(
            "start pid=100 unix=1\n" +
                "kill vpid=7 pid=207 signal=9 origin=external unix=20\n" +
                "kill vpid=8 pid=208 signal=9 origin=guest unix=21\n" +
                "clean pid=100 unix=30\n",
        )
        assertTrue(r.sessionEndedCleanly)
        assertEquals(listOf(KillRecord(7, 207, 9, "external", 20)), r.externalKills)
        assertTrue(KillReport.wasKilledByHost(r))
    }

    @Test
    fun `missing clean marker means proot itself was killed`() {
        val r = KillReport.parse("start pid=100 unix=1\nkill vpid=2 pid=3 signal=9 origin=guest unix=2\n")
        assertFalse(r.sessionEndedCleanly)
        assertTrue(r.prootItselfKilled)
        assertTrue(KillReport.wasKilledByHost(r))
    }

    @Test
    fun `truncated last line is ignored`() {
        val r = KillReport.parse(
            "start pid=100 unix=1\n" +
                "clean pid=100 unix=3\n" +
                "kill vpid=9 pid=209 signal=9 origin=exter",
        )
        assertTrue(r.externalKills.isEmpty())
        assertTrue(r.sessionEndedCleanly)
        // a truncated clean line does not count either
        val r2 = KillReport.parse("start pid=100 unix=1\nclean pid=100 un")
        assertTrue(r2.prootItselfKilled)
    }

    @Test
    fun `garbage lines are skipped`() {
        val r = KillReport.parse("\n\nstart pid=x\nkill vpid=1\nwhatever\nstart pid=1 unix=1\nclean pid=1 unix=2\n")
        assertTrue(r.sessionEndedCleanly)
        assertTrue(r.externalKills.isEmpty())
    }

    @Test
    fun `app teardown after the stop marker is not blamed on the host`() {
        val r = KillReport.parse(
            "start pid=100 unix=1\n" +
                "app-stop unix=10\n" +
                "kill vpid=7 pid=207 signal=9 origin=external unix=11\n",
        )
        assertTrue(r.sessionEndedCleanly)
        assertFalse(r.prootItselfKilled)
        assertTrue(r.externalKills.isEmpty())
        assertFalse(KillReport.wasKilledByHost(r))
    }

    @Test
    fun `a new start after a stop counts again`() {
        val r = KillReport.parse(
            "start pid=100 unix=1\napp-stop unix=10\n" +
                "kill vpid=7 pid=207 signal=9 origin=external unix=11\n" +
                "start pid=300 unix=20\n" +
                "kill vpid=4 pid=304 signal=9 origin=external unix=21\n",
        )
        assertEquals(1, r.externalKills.size)
        assertEquals(304, r.externalKills[0].pid)
        assertTrue(r.prootItselfKilled)
    }

    @Test
    fun `interleaved proot processes are tracked by pid`() {
        val r = KillReport.parse(
            "start pid=1 unix=1\nstart pid=2 unix=1\nclean pid=2 unix=2\nclean pid=1 unix=3\n",
        )
        assertTrue(r.sessionEndedCleanly)
    }

    @Test
    fun `file helpers`() {
        val f = tmp.newFile("kill_report.txt")
        f.writeText("start pid=100 unix=1\n")
        KillReport.markDeliberateStop(f, nowSeconds = 5)
        f.appendText("kill vpid=7 pid=207 signal=9 origin=external unix=6\n")
        assertFalse(KillReport.wasKilledByHost(KillReport.parse(f)))

        KillReport.rotate(f)
        assertFalse(f.exists())
        assertTrue(tmp.root.resolve("kill_report.txt.prev").exists())

        val missing = KillReport.parse(tmp.root.resolve("nope"))
        assertTrue(missing.sessionEndedCleanly)
        assertFalse(KillReport.wasKilledByHost(missing))
    }
}
