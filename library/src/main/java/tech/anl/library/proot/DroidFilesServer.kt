package tech.anl.library.proot

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import android.util.Log
import tech.anl.library.proot.DroidFilesProtocol.Errno
import tech.anl.library.proot.DroidFilesProtocol.Op
import tech.anl.library.ui.DroidFilesPermissionActivity
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.ThreadFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/**
 * Serves PRoot's `--droid_files` extension (protocol: [DroidFilesProtocol]) by performing the
 * guest's /sdcard file operations through the Storage Access Framework.
 *
 * Listens on `supportDir/droid_files_socket` (guest `/support/common/droid_files_socket`) and
 * writes getdents pages to `supportDir/droid_files_getdents`. [supportDir] must therefore be the
 * directory the launch script binds to `/support/common` (`filesDir/support`).
 *
 * Top-level directories without a grant trigger one ACTION_OPEN_DOCUMENT_TREE prompt (see
 * [getUri]); a refusal is remembered for the lifetime of this server.
 */
class DroidFilesServer(
    context: Context,
    private val supportDir: File,
    private val grants: DroidFilesGrants,
) {
    private val appContext: Context = context.applicationContext
    private val resolver = appContext.contentResolver

    /** word_t of the PRoot binary == pointer size of this (app) process. */
    private val wordSize = if (Process.is64Bit()) 8 else 4
    private val isX86 = Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true
    /** Legacy getdents only reaches an arm64 tracer from a 32-bit guest; elsewhere it is native. */
    private val legacyDirentLongSize =
        if (Build.SUPPORTED_ABIS.firstOrNull() == "arm64-v8a") 4 else wordSize

    @Suppress("DEPRECATION")
    private val volumeRoot: File = Environment.getExternalStorageDirectory()
    private val mapper = GuestPathMapper(GuestPathMapper.DEFAULT_PREFIXES + volumeRoot.absolutePath)
    private val directBackend = DirectBackend(volumeRoot)

    val socketFile = File(supportDir, DroidFilesProtocol.SOCKET_NAME)
    val getdentsFile = File(supportDir, DroidFilesProtocol.GETDENTS_NAME)

    /**
     * Shows the consent UI. Defaults to starting [DroidFilesPermissionActivity] with
     * FLAG_ACTIVITY_NEW_TASK; returns false if it could not be shown. Replace it to, e.g., post a
     * notification when background activity starts are blocked.
     */
    @Volatile
    var consentPrompter: (Intent) -> Boolean = { intent ->
        try {
            appContext.startActivity(intent)
            true
        } catch (e: RuntimeException) {
            Log.w(TAG, "cannot show the storage consent prompt", e)
            false
        }
    }

    private val declinedTops: MutableSet<String> = Collections.newSetFromMap(ConcurrentHashMap())
    @Volatile private var quietUntil = 0L

    private val getdentsLock = ReentrantLock()
    private val getdentsPages = ConcurrentHashMap<String, List<ByteArray>>()

    private var bindSocket: LocalSocket? = null
    private var serverSocket: LocalServerSocket? = null
    private var workers: ExecutorService? = null
    @Volatile private var running = false

    @Synchronized
    fun start() {
        if (running) return
        supportDir.mkdirs()
        socketFile.delete() // a stale socket file from a previous process makes bind() fail
        grants.prune(resolver)

        // Public-API route to a filesystem-namespace listening socket: LocalServerSocket(String)
        // only does the abstract namespace, so bind a LocalSocket to a FILESYSTEM address and
        // wrap its fd; LocalServerSocket(FileDescriptor) calls listen() itself.
        val socket = LocalSocket(LocalSocket.SOCKET_STREAM)
        try {
            socket.bind(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            serverSocket = LocalServerSocket(socket.fileDescriptor)
        } catch (e: IOException) {
            socket.close()
            throw e
        }
        bindSocket = socket
        workers = Executors.newCachedThreadPool(daemonThreads())
        running = true
        val server = serverSocket!!
        thread(name = "droid-files-accept", isDaemon = true) { acceptLoop(server) }
    }

    @Synchronized
    fun stop() {
        if (!running) return
        running = false
        bindSocket?.let { s ->
            try { Os.shutdown(s.fileDescriptor, OsConstants.SHUT_RDWR) } catch (e: ErrnoException) { }
        }
        // Wake accept() in case shutdown() did not.
        try {
            LocalSocket().use {
                it.connect(LocalSocketAddress(socketFile.absolutePath, LocalSocketAddress.Namespace.FILESYSTEM))
            }
        } catch (e: IOException) { }
        try { serverSocket?.close() } catch (e: IOException) { }
        try { bindSocket?.close() } catch (e: IOException) { }
        serverSocket = null
        bindSocket = null
        workers?.shutdownNow()
        workers = null
        socketFile.delete()
        getdentsPages.clear()
    }

    val isRunning: Boolean get() = running

    /**
     * The persisted tree Uri covering top-level shared-storage directory [topLevelDir]
     * ("Documents"), asking the user for it (once per server lifetime) if there is none yet.
     * Blocks for up to [CONSENT_TIMEOUT_MS] -- never call it on the main thread.
     */
    fun getUri(topLevelDir: String): Uri? {
        grants.find(topLevelDir)?.let { return it.treeUri }
        if (topLevelDir in declinedTops) return null
        if (System.currentTimeMillis() < quietUntil) return null
        val outcome = DroidFilesConsent.await(topLevelDir, CONSENT_TIMEOUT_MS) {
            consentPrompter(DroidFilesPermissionActivity.intent(appContext, topLevelDir))
        }
        return when (outcome) {
            is DroidFilesConsent.Outcome.Granted -> grants.find(topLevelDir)?.treeUri ?: outcome.treeUri
            DroidFilesConsent.Outcome.Declined -> {
                declinedTops += topLevelDir
                // A walk over /sdcard would otherwise go straight on to the next directory's prompt.
                quietUntil = System.currentTimeMillis() + DECLINE_QUIET_MS
                null
            }
            DroidFilesConsent.Outcome.Busy, DroidFilesConsent.Outcome.TimedOut -> null
        }
    }

    /** The existing grant for [topLevelDir], without prompting. */
    fun grantedUri(topLevelDir: String): Uri? = grants.find(topLevelDir)?.treeUri

    /** Forget "declined" answers so the next access prompts again (e.g. from a settings screen). */
    fun resetDeclined() {
        declinedTops.clear()
        quietUntil = 0L
    }

    // ---------------------------------------------------------------------------------------

    private fun acceptLoop(server: LocalServerSocket) {
        while (running) {
            val client = try {
                server.accept()
            } catch (e: IOException) {
                if (running) Log.w(TAG, "accept failed", e)
                break
            }
            if (!running) {
                client.close()
                break
            }
            val pool = workers
            if (pool == null) {
                client.close()
                break
            }
            try {
                pool.execute { serve(client) }
            } catch (e: java.util.concurrent.RejectedExecutionException) {
                client.close()
            }
        }
    }

    private fun serve(client: LocalSocket) {
        client.use {
            try {
                if (client.peerCredentials.uid != Process.myUid()) {
                    Log.w(TAG, "rejecting connection from uid ${client.peerCredentials.uid}")
                    return
                }
                client.soTimeout = REQUEST_READ_TIMEOUT_MS
                val bytes = ByteArray(DroidFilesProtocol.requestSize(wordSize))
                if (!readFully(client.inputStream, bytes)) return
                val request = DroidFilesProtocol.parseRequest(bytes, wordSize)
                handle(client, request)
            } catch (e: IOException) {
                Log.w(TAG, "droid_files connection failed", e)
            } catch (e: RuntimeException) {
                Log.e(TAG, "droid_files request failed", e)
            }
        }
    }

    private fun handle(client: LocalSocket, request: DroidFilesRequest) {
        val relative = mapper.toRelative(request.path)
        if (relative == null) {
            sendStatus(client, Errno.ENOENT)
            return
        }
        val backend = backendFor(relative)
        if (backend == null) {
            sendStatus(client, Errno.EACCES)
            return
        }
        when (request.sysCall) {
            Op.OPEN, Op.OPENAT, Op.CREAT -> replyWithFd(client) { open(backend, relative, request.flags) }
            Op.FSTATAT -> replyWithFd(client) { statFd(backend, relative) }
            Op.MKDIR, Op.MKDIRAT -> sendStatus(client, backend.mkdir(relative))
            Op.UNLINK -> sendStatus(
                client,
                if (backend.kind(relative) == DroidFilesBackend.Kind.DIRECTORY) Errno.EISDIR
                else backend.delete(relative),
            )
            // AT_REMOVEDIR is not forwarded, so unlinkat removes files and empty directories alike.
            Op.UNLINKAT -> sendStatus(client, backend.delete(relative))
            Op.FACCESSAT -> sendStatus(client, backend.access(relative, request.flags))
            Op.GETDENTS, Op.GETDENTS64 -> getdents(client, backend, relative, request)
            else -> sendStatus(client, Errno.ENOSYS)
        }
    }

    private fun backendFor(relative: String): DroidFilesBackend? {
        val top = GuestPathMapper.topOf(relative)
        // Download's root cannot be picked as a tree on Android 11+, and the app may create and
        // read its own files there directly; Android/ is never forwarded by PRoot.
        if (top == "Download" || top.startsWith("Android") || hasDirectAccess()) return directBackend
        grants.find(relative)?.let { return SafBackend(resolver, it.treeUri) }
        getUri(top) ?: return null
        return grants.find(relative)?.let { SafBackend(resolver, it.treeUri) }
    }

    private fun hasDirectAccess(): Boolean = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> Environment.isExternalStorageManager()
        Build.VERSION.SDK_INT == Build.VERSION_CODES.Q ->
            Environment.isExternalStorageLegacy() && hasWritePermission()
        else -> hasWritePermission()
    }

    private fun hasWritePermission() =
        appContext.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
            PackageManager.PERMISSION_GRANTED

    private sealed class FdResult {
        class Ok(val pfd: ParcelFileDescriptor) : FdResult()
        class Err(val errno: Int) : FdResult()
    }

    private fun replyWithFd(client: LocalSocket, produce: () -> FdResult) {
        val result = try {
            produce()
        } catch (e: FileNotFoundException) {
            FdResult.Err(Errno.ENOENT)
        } catch (e: SecurityException) {
            FdResult.Err(Errno.EACCES)
        } catch (e: IllegalArgumentException) {
            FdResult.Err(Errno.EINVAL)
        } catch (e: IllegalStateException) {
            FdResult.Err(Errno.EIO)
        }
        when (result) {
            is FdResult.Err -> sendStatus(client, result.errno)
            is FdResult.Ok -> result.pfd.use { pfd ->
                // Status and fd go out back to back: PRoot does not retry an EAGAIN recvmsg().
                val out = client.outputStream
                out.write(DroidFilesProtocol.encodeStatus(0, wordSize))
                client.setFileDescriptorsForSend(arrayOf(pfd.fileDescriptor))
                out.write(byteArrayOf(DroidFilesProtocol.FD_CARRIER_BYTE))
                client.setFileDescriptorsForSend(null)
            }
        }
    }

    private fun open(backend: DroidFilesBackend, relative: String, flags: Int): FdResult {
        val plan = OpenFlags.plan(flags, isX86, wSafe = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        when (backend.kind(relative)) {
            DroidFilesBackend.Kind.DIRECTORY -> {
                if (plan.exclusive) return FdResult.Err(Errno.EEXIST)
                if (plan.write) return FdResult.Err(Errno.EISDIR)
                return directoryFd(relative)
            }
            DroidFilesBackend.Kind.FILE -> {
                if (plan.exclusive) return FdResult.Err(Errno.EEXIST)
                if (plan.directory) return FdResult.Err(Errno.ENOTDIR)
            }
            null -> {
                if (!plan.create || plan.directory) return FdResult.Err(Errno.ENOENT)
                val status = backend.createFile(relative)
                if (status != 0 && !(status == Errno.EEXIST && !plan.exclusive)) return FdResult.Err(status)
            }
        }
        val pfd = backend.openFile(relative, plan.mode)
        if (plan.appendViaFcntl) {
            try {
                val fd = pfd.fileDescriptor
                val current = Os.fcntlInt(fd, OsConstants.F_GETFL, 0)
                Os.fcntlInt(fd, OsConstants.F_SETFL, current or OsConstants.O_APPEND)
            } catch (e: ErrnoException) {
                Log.w(TAG, "cannot set O_APPEND on $relative", e)
            }
        }
        return FdResult.Ok(pfd)
    }

    private fun statFd(backend: DroidFilesBackend, relative: String): FdResult =
        when (backend.kind(relative)) {
            null -> FdResult.Err(Errno.ENOENT)
            DroidFilesBackend.Kind.DIRECTORY -> directoryFd(relative)
            DroidFilesBackend.Kind.FILE -> FdResult.Ok(backend.openFile(relative, "r"))
        }

    /**
     * A directory fd for the guest. The real directory is opened (the app may open, just not
     * list, shared-storage directories), so that fstat() reports real metadata and PRoot's
     * readlink() of it lands under /sdcard's host path -- which is what routes the guest's later
     * getdents() on it back here. A private placeholder is the fallback.
     */
    private fun directoryFd(relative: String): FdResult {
        val real = File(volumeRoot, relative)
        try {
            return FdResult.Ok(ParcelFileDescriptor.open(real, ParcelFileDescriptor.MODE_READ_ONLY))
        } catch (e: FileNotFoundException) {
            Log.w(TAG, "cannot open $real directly, handing out a placeholder", e)
        }
        val placeholder = File(appContext.cacheDir, "droid_files_dirs/$relative")
        placeholder.mkdirs()
        return FdResult.Ok(ParcelFileDescriptor.open(placeholder, ParcelFileDescriptor.MODE_READ_ONLY))
    }

    private fun getdents(
        client: LocalSocket,
        backend: DroidFilesBackend,
        relative: String,
        request: DroidFilesRequest,
    ) {
        val key = "${request.sysCall}:$relative"
        val offset = request.sysargs[0]
        if (offset < 0 || offset > Int.MAX_VALUE) {
            getdentsPages.remove(key)
            sendStatus(client, DroidFilesProtocol.STATUS_GETDENTS_EOF.toInt())
            return
        }
        val pageIndex = offset.toInt()
        var pages = if (pageIndex == 0) null else getdentsPages[key]
        if (pages == null) {
            when (backend.kind(relative)) {
                null -> return sendStatus(client, Errno.ENOENT)
                DroidFilesBackend.Kind.FILE -> return sendStatus(client, Errno.ENOTDIR)
                DroidFilesBackend.Kind.DIRECTORY -> Unit
            }
            val children = backend.list(relative) ?: return sendStatus(client, Errno.EACCES)
            val entries = Dirents.withDotEntries(relative, children)
            pages = if (request.sysCall == Op.GETDENTS64) {
                Dirents.paginate(entries) { e, off -> Dirents.record64(e, off) }
            } else {
                Dirents.paginate(entries) { e, off -> Dirents.recordLegacy(e, off, legacyDirentLongSize) }
            }
            getdentsPages[key] = pages
        }
        if (pageIndex >= pages.size) {
            getdentsPages.remove(key)
            sendStatus(client, DroidFilesProtocol.STATUS_GETDENTS_EOF.toInt())
            return
        }
        // One shared page file: hold it until PRoot has read it, which it does before closing
        // the socket, so wait for EOF on the connection.
        getdentsLock.withLock {
            getdentsFile.writeBytes(pages[pageIndex])
            sendStatus(client, 0)
            try {
                client.soTimeout = GETDENTS_HOLD_TIMEOUT_MS
                val sink = ByteArray(16)
                while (client.inputStream.read(sink) >= 0) Unit
            } catch (e: IOException) {
                // timeout or reset: PRoot has either read the page or given up on it
            }
        }
    }

    private fun sendStatus(client: LocalSocket, errno: Int) {
        client.outputStream.write(DroidFilesProtocol.encodeStatus(errno.toLong(), wordSize))
    }

    private fun readFully(input: InputStream, into: ByteArray): Boolean {
        var read = 0
        while (read < into.size) {
            val n = input.read(into, read, into.size - read)
            if (n < 0) return false
            read += n
        }
        return true
    }

    private fun daemonThreads(): ThreadFactory {
        val count = AtomicInteger()
        return ThreadFactory { r ->
            Thread(r, "droid-files-${count.incrementAndGet()}").apply { isDaemon = true }
        }
    }

    companion object {
        private const val TAG = "DroidFilesServer"
        const val CONSENT_TIMEOUT_MS = 2 * 60 * 1000L
        private const val DECLINE_QUIET_MS = 30 * 1000L
        private const val REQUEST_READ_TIMEOUT_MS = 10 * 1000
        private const val GETDENTS_HOLD_TIMEOUT_MS = 5 * 1000
    }
}
