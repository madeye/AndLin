package tech.anl.library.utils

import android.content.SharedPreferences
import org.mockito.kotlin.* // ktlint-disable no-wildcard-imports
import org.junit.Assert.* // ktlint-disable no-wildcard-imports
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.mockito.Mock
import org.mockito.junit.MockitoJUnitRunner
import tech.anl.customlibrary.BuildConfig
import tech.anl.library.model.entities.ServiceType
import tech.anl.library.model.entities.Session
import java.io.File

@RunWith(MockitoJUnitRunner::class)
class LocalServerManagerTest {

    @get:Rule val tempFolder = TemporaryFolder()

    @Mock lateinit var mockBusyboxExecutor: BusyboxExecutor

    @Mock lateinit var mockLogger: Logger

    @Mock lateinit var mockProcess: Process

    @Mock lateinit var mockSharedPreferences: SharedPreferences

    private lateinit var sshPidFile: File

    private val filesystemId = 0L
    private val filesystemDirName = "0"
    private val fakePid = 100L

    // What the SSH launcher gets for the guest's hostname, hosts and resolv.conf with
    // default preferences: the defaults' "search Home" and nameservers, one per line.
    private val guestNetworkEnv = mapOf(
        "HOSTNAME" to BuildConfig.DEFAULT_HOSTNAME,
        "HOSTS" to "127.0.0.1 localhost\n127.0.0.1 ${BuildConfig.DEFAULT_HOSTNAME}",
        "RESOLV" to "${BuildConfig.DEFAULT_DNS_DOMAINS}\n${BuildConfig.DEFAULT_DNS_NAMESERVERS}\n",
    )

    private lateinit var localServerManager: LocalServerManager

    private fun createSshPidFile() {
        val folder = tempFolder.newFolder(filesystemDirName, "run")
        sshPidFile = File("${folder.path}/dropbear.pid")
        sshPidFile.createNewFile()
    }

    @Before
    fun setup() {
        whenever(mockProcess.toString()).thenReturn("pid=$fakePid],")

        localServerManager = LocalServerManager(tempFolder.root.path, mockBusyboxExecutor, mockSharedPreferences, mockLogger)
    }

    @Test
    fun `Calling startServer with an SSH session should use the appropriate command`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        val command = "/support/common/serverbox_startSSHServer.sh"

        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()))
                .thenReturn(OngoingExecution(mockProcess))

        createSshPidFile()
        assertTrue(sshPidFile.exists())

        val result = localServerManager.startServer(session)
        assertFalse(sshPidFile.exists())
        assertEquals(fakePid, result)
    }

    @Test
    fun `If starting an ssh server fails, an error is logged and -1 is returned`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        val command = "/support/common/serverbox_startSSHServer.sh"

        val reason = "reason"
        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                anyOrNull(),
                anyOrNull(),
                anyOrNull()
        ))
                .thenReturn(FailedExecution(reason))

        createSshPidFile()
        assertTrue(sshPidFile.exists())

        val result = localServerManager.startServer(session)

        assertFalse(sshPidFile.exists())
        assertEquals(-1, result)
        verify(mockLogger).addBreadcrumb(any())
    }

    @Test
    fun `Starting an SSH server passes the username and a localhost address by default`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh, username = "user")
        val command = "/support/common/serverbox_startSSHServer.sh"
        val env = hashMapOf("INITIAL_USERNAME" to "user", "INITIAL_PASSWORD" to "", "SERVERBOX_SSH_ADDRESS" to "127.0.0.1").apply { putAll(guestNetworkEnv) }

        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                eq(env),
                anyOrNull(),
                anyOrNull()))
                .thenReturn(OngoingExecution(mockProcess))

        createSshPidFile()

        val result = localServerManager.startServer(session)

        assertEquals(fakePid, result)
    }

    @Test
    fun `Starting an SSH server listens on LAN when the preference is set`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh, username = "user")
        val command = "/support/common/serverbox_startSSHServer.sh"
        whenever(mockSharedPreferences.getBoolean("pref_ssh_listen_on_lan", false)).thenReturn(true)
        val env = hashMapOf("INITIAL_USERNAME" to "user", "INITIAL_PASSWORD" to "", "SERVERBOX_SSH_ADDRESS" to "0.0.0.0").apply { putAll(guestNetworkEnv) }

        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                eq(env),
                anyOrNull(),
                anyOrNull()))
                .thenReturn(OngoingExecution(mockProcess))

        createSshPidFile()

        val result = localServerManager.startServer(session)

        assertEquals(fakePid, result)
    }

    @Test
    fun `Starting an SSH server includes authorized keys when the preference is set`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh, username = "user")
        val command = "/support/common/serverbox_startSSHServer.sh"
        whenever(mockSharedPreferences.getString("pref_ssh_authorized_keys", "")).thenReturn("ssh-ed25519 AAAA...")
        val env = hashMapOf(
                "INITIAL_USERNAME" to "user",
                "INITIAL_PASSWORD" to "",
                "SERVERBOX_SSH_ADDRESS" to "127.0.0.1",
                "SERVERBOX_AUTHORIZED_KEYS" to "ssh-ed25519 AAAA..."
        ).apply { putAll(guestNetworkEnv) }

        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                eq(env),
                anyOrNull(),
                anyOrNull()))
                .thenReturn(OngoingExecution(mockProcess))

        createSshPidFile()

        val result = localServerManager.startServer(session)

        assertEquals(fakePid, result)
    }

    @Test
    fun `Starting an SSH server restricts to key-only auth when the preference is set`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh, username = "user")
        val command = "/support/common/serverbox_startSSHServer.sh"
        whenever(mockSharedPreferences.getBoolean("pref_ssh_disable_password", false)).thenReturn(true)
        val env = hashMapOf(
                "INITIAL_USERNAME" to "user",
                "INITIAL_PASSWORD" to "",
                "SERVERBOX_SSH_ADDRESS" to "127.0.0.1",
                "SERVERBOX_SSH_KEYS_ONLY" to "1"
        ).apply { putAll(guestNetworkEnv) }

        whenever(mockBusyboxExecutor.executeProotCommand(
                eq(command),
                eq(filesystemDirName),
                eq(false),
                eq(env),
                anyOrNull(),
                anyOrNull()))
                .thenReturn(OngoingExecution(mockProcess))

        createSshPidFile()

        val result = localServerManager.startServer(session)

        assertEquals(fakePid, result)
    }

    @Test
    fun `Calling stop service uses the appropriate command`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        localServerManager.stopService(session)
        val command = "support/killProcTree.sh ${session.pid} -1"
        verify(mockBusyboxExecutor).executeScript(eq(command), anyOrNull())
    }

    @Test
    fun `If stop service fails, an error is logged`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        val command = "support/killProcTree.sh ${session.pid} -1"

        val reason = "reason"
        whenever(mockBusyboxExecutor.executeScript(eq(command), anyOrNull()))
                .thenReturn(FailedExecution("reason"))

        localServerManager.stopService(session)

        verify(mockLogger).addBreadcrumb(any())
    }

    @Test
    fun `Calls appropriate command to check if server is running, and returns the result`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        val command = "support/isServerInProcTree.sh -1"
        whenever(mockBusyboxExecutor.executeScript(eq(command), anyOrNull()))
                .thenReturn(SuccessfulExecution)
                .thenReturn(FailedExecution(""))

        val result1 = localServerManager.isServerRunning(session)
        val result2 = localServerManager.isServerRunning(session)

        assertTrue(result1)
        assertFalse(result2)
    }

    @Test
    fun `Logs an error and return false if isServerRunning causes an exception`() {
        val session = Session(0, filesystemId = filesystemId, serviceType = ServiceType.Ssh)
        val command = "support/isServerInProcTree.sh -1"
        val reason = "reason"
        whenever(mockBusyboxExecutor.executeScript(eq(command), anyOrNull()))
                .thenReturn(FailedExecution(reason))

        val result = localServerManager.isServerRunning(session)

        assertFalse(result)
        verify(mockLogger).addBreadcrumb(any())
    }
}
