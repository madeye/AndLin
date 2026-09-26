package tech.anl.library.utils

import android.content.SharedPreferences
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session

class SshServerInfoTest {
    private val sshSession = Session(1, filesystemId = 1, serviceType = ServiceType.Ssh, username = "userland", password = "secret")

    private fun prefs(lan: Boolean = false, keysOnly: Boolean = false): SharedPreferences = mock<SharedPreferences>().also {
        whenever(it.getBoolean("pref_ssh_listen_on_lan", false)).thenReturn(lan)
        whenever(it.getBoolean("pref_ssh_disable_password", false)).thenReturn(keysOnly)
    }

    @Test
    fun `a LAN-listening server is reached on the LAN address`() {
        val info = SshServerInfo.forSession(sshSession, prefs(lan = true), "192.168.0.100")!!

        assertEquals("ssh -p 2022 userland@192.168.0.100", info.command)
        assertTrue(info.reachableFromNetwork)
        assertEquals("secret", info.password)
    }

    @Test
    fun `a localhost-only server, or one with no LAN address, is reached on 127_0_0_1`() {
        val local = SshServerInfo.forSession(sshSession, prefs(lan = false), "192.168.0.100")!!
        assertEquals("ssh -p 2022 userland@127.0.0.1", local.command)
        assertFalse(local.reachableFromNetwork)

        val offline = SshServerInfo.forSession(sshSession, prefs(lan = true), null)!!
        assertEquals("127.0.0.1", offline.host)
        assertFalse(offline.reachableFromNetwork)
    }

    @Test
    fun `keys-only is reported and non-SSH sessions have no info`() {
        assertTrue(SshServerInfo.forSession(sshSession, prefs(keysOnly = true), null)!!.keysOnly)
        assertNull(SshServerInfo.forSession(sshSession.copy(serviceType = ServiceType.Vnc), prefs(), null))
    }
}
