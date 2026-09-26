package tech.anl.terminal

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.FileInputStream
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class PtyInstrumentedTest {

    @Test
    fun spawnsProcessOnPtyWithWindowSizeAndExitCode() {
        val (pid, fd) = Pty.createSubprocess(
            "/system/bin/sh", "/",
            arrayOf("sh", "-c", "stty size; tty; echo \$FOO; exit 3"),
            arrayOf("FOO=bar", "PATH=/system/bin"), 30, 100,
        ).let { it[0] to it[1] }
        val pfd = ParcelFileDescriptor.adoptFd(fd)
        val out = StringBuilder()
        val input = FileInputStream(pfd.fileDescriptor)
        val buf = ByteArray(4096)
        try {
            while (true) {
                val n = input.read(buf)
                if (n <= 0) break
                out.append(String(buf, 0, n))
            }
        } catch (_: IOException) {
        }
        assertEquals(3, Pty.waitFor(pid))
        pfd.close()
        val text = out.toString()
        assertTrue(text, text.contains("30 100"))
        assertTrue(text, text.contains("/dev/pts/"))
        assertTrue(text, text.contains("bar"))
    }

    @Test
    fun sessionShowsExitPrompt() {
        val finished = CountDownLatch(1)
        lateinit var session: TerminalSession
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session = TerminalSessions.getOrCreate(
                TerminalSpec("/system/bin/sh", listOf("-c", "echo hello"), emptyList(), "/", "t", "test-key", banner = "Banner line"),
            )
            session.addListener(object : TerminalSession.Listener {
                override fun onSessionFinished(session: TerminalSession) = finished.countDown()
            })
            if (session.isFinished) finished.countDown()
        }
        assertTrue(finished.await(10, TimeUnit.SECONDS))
        val text = synchronized(session.emulator) { session.emulator.getAllText() }
        assertTrue(text, text.startsWith("Banner line\nhello"))
        assertTrue(text, text.contains("[Process completed (code 0) - press Enter]"))
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            session.writeInput("\r")
            assertEquals(null, TerminalSessions.get("test-key"))
        }
    }
}
