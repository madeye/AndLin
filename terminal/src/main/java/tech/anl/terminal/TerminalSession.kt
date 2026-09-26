package tech.anl.terminal

import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.util.Log
import tech.anl.terminal.emulator.TerminalClient
import tech.anl.terminal.emulator.TerminalEmulator
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.CopyOnWriteArraySet
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * A child process attached to a PTY, plus the emulator that renders its output.
 *
 * Output is read and parsed on a background thread; listeners are notified on the main
 * thread. Access to [emulator] must be synchronized on the emulator itself.
 */
class TerminalSession internal constructor(val spec: TerminalSpec) {
    interface Listener {
        fun onScreenUpdated(session: TerminalSession) {}
        fun onTitleChanged(session: TerminalSession) {}
        fun onSessionFinished(session: TerminalSession) {}
        fun onClipboardSet(session: TerminalSession, text: String) {}
        fun onBell(session: TerminalSession) {}
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<Listener>()
    private val updatePending = AtomicBoolean(false)
    private val writeQueue = LinkedBlockingQueue<ByteArray>()

    val emulator = TerminalEmulator(DEFAULT_ROWS, DEFAULT_COLS, client = object : TerminalClient {
        override fun write(data: ByteArray) = this@TerminalSession.write(data)
        override fun onTitleChanged(title: String) = post { listeners.forEach { it.onTitleChanged(this@TerminalSession) } }
        override fun onClipboardSet(text: String) = post { listeners.forEach { it.onClipboardSet(this@TerminalSession, text) } }
        override fun onBell() = post { listeners.forEach { it.onBell(this@TerminalSession) } }
        override fun onColorsChanged() = notifyScreenUpdated()
    })

    @Volatile
    private var pid = -1
    private var pfd: ParcelFileDescriptor? = null
    private var output: FileOutputStream? = null

    @Volatile
    private var closed = false

    /** True once the process has exited and the "press Enter" prompt is showing. */
    var isFinished = false
        private set
    var exitCode: Int? = null
        private set

    val isRunning: Boolean get() = pid > 0 && exitCode == null

    val title: String
        get() = synchronized(emulator) { emulator.title }.ifEmpty { spec.title }

    fun addListener(listener: Listener) = listeners.add(listener)
    fun removeListener(listener: Listener) = listeners.remove(listener)

    internal fun start(rows: Int = DEFAULT_ROWS, cols: Int = DEFAULT_COLS) {
        synchronized(emulator) {
            emulator.resize(rows, cols)
            spec.banner?.let { emulator.append(it.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n") }
        }
        val env = LinkedHashMap<String, String>()
        env["TERM"] = "xterm-256color"
        env["COLORTERM"] = "truecolor"
        for (entry in spec.environment) {
            val eq = entry.indexOf('=')
            if (eq > 0) env[entry.substring(0, eq)] = entry.substring(eq + 1)
        }
        val argv = arrayOf(spec.executable) + spec.arguments
        val result = try {
            Pty.createSubprocess(
                spec.executable,
                spec.workingDir,
                argv,
                env.map { "${it.key}=${it.value}" }.toTypedArray(),
                rows,
                cols,
            )
        } catch (e: IOException) {
            Log.e(TAG, "Failed to start ${spec.executable}", e)
            synchronized(emulator) { emulator.append("Failed to start ${spec.executable}: ${e.message}\r\n") }
            onProcessExited(-1)
            return
        }
        pid = result[0]
        val descriptor = ParcelFileDescriptor.adoptFd(result[1])
        pfd = descriptor
        output = FileOutputStream(descriptor.fileDescriptor)

        val reader = Thread({ readLoop(FileInputStream(descriptor.fileDescriptor)) }, "TermReader[$pid]")
        reader.start()
        Thread({ writeLoop() }, "TermWriter[$pid]").start()
        Thread({
            val code = Pty.waitFor(pid)
            // Let the reader drain whatever the process printed last.
            reader.join(READER_DRAIN_MS)
            post { onProcessExited(code) }
        }, "TermWaiter[$pid]").start()
    }

    private fun readLoop(input: FileInputStream) {
        val buffer = ByteArray(8192)
        try {
            while (true) {
                val n = input.read(buffer)
                if (n <= 0) break
                synchronized(emulator) { emulator.append(buffer, 0, n) }
                notifyScreenUpdated()
            }
        } catch (_: IOException) {
            // EIO once the slave side is closed: normal end of stream.
        }
    }

    private fun writeLoop() {
        try {
            while (true) {
                val data = writeQueue.take()
                if (data === POISON) break
                output?.write(data)
            }
        } catch (_: IOException) {
        } catch (_: InterruptedException) {
        }
    }

    /** Sends raw bytes to the process. Safe from any thread. */
    fun write(data: ByteArray) {
        if (data.isEmpty() || closed || exitCode != null) return
        writeQueue.offer(data)
    }

    /**
     * Sends user keyboard input. After the process has exited, Enter closes the session
     * instead.
     */
    fun writeInput(data: ByteArray) {
        if (isFinished) {
            if (data.any { it == '\r'.code.toByte() || it == '\n'.code.toByte() }) {
                TerminalSessions.remove(this)
            }
            return
        }
        write(data)
    }

    fun writeInput(text: String) = writeInput(text.toByteArray(Charsets.UTF_8))

    fun resize(rows: Int, cols: Int, widthPx: Int, heightPx: Int) {
        synchronized(emulator) { emulator.resize(rows, cols) }
        val fd = pfd?.fd ?: return
        if (!closed && exitCode == null) Pty.setPtyWindowSize(fd, rows, cols, widthPx, heightPx)
        notifyScreenUpdated()
    }

    private fun onProcessExited(code: Int) {
        exitCode = code
        writeQueue.offer(POISON)
        closeDescriptor()
        if (closed) return
        synchronized(emulator) {
            emulator.append("\r\n\u001b[0m[Process completed (code $code) - press Enter]")
        }
        isFinished = true
        notifyScreenUpdated()
        listeners.forEach { it.onSessionFinished(this) }
        TerminalSessions.notifyChanged()
    }

    /** Terminates the process without the exit prompt. Called via [TerminalSessions]. */
    internal fun close() {
        if (closed) return
        closed = true
        val target = pid
        if (target > 0 && exitCode == null) {
            Pty.sendSignal(target, Pty.SIGHUP)
            mainHandler.postDelayed({ if (exitCode == null) Pty.sendSignal(target, Pty.SIGKILL) }, KILL_DELAY_MS)
        }
        writeQueue.offer(POISON)
    }

    private fun closeDescriptor() {
        try {
            pfd?.close()
        } catch (_: IOException) {
        }
        pfd = null
        output = null
    }

    private fun notifyScreenUpdated() {
        if (updatePending.compareAndSet(false, true)) {
            mainHandler.post {
                updatePending.set(false)
                listeners.forEach { it.onScreenUpdated(this) }
            }
        }
    }

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    companion object {
        private const val TAG = "TerminalSession"
        const val DEFAULT_ROWS = 24
        const val DEFAULT_COLS = 80
        private const val READER_DRAIN_MS = 1000L
        private const val KILL_DELAY_MS = 1500L
        private val POISON = ByteArray(0)
    }
}

/** Process-wide registry of live sessions, keyed by [TerminalSpec.sessionKey]. Main thread only. */
object TerminalSessions {
    fun interface Observer {
        fun onSessionsChanged()
    }

    private val sessions = LinkedHashMap<String, TerminalSession>()
    private val observers = CopyOnWriteArraySet<Observer>()

    val all: List<TerminalSession> get() = sessions.values.toList()
    val isEmpty: Boolean get() = sessions.isEmpty()

    fun get(key: String): TerminalSession? = sessions[key]

    /**
     * Returns the running session for [spec]'s key, or starts a new one. A session whose
     * process already exited is replaced, so relaunching gets a fresh connection.
     */
    fun getOrCreate(spec: TerminalSpec): TerminalSession {
        sessions[spec.sessionKey]?.let { existing ->
            if (!existing.isFinished) return existing
            sessions.remove(spec.sessionKey)
            existing.close()
        }
        val session = TerminalSession(spec)
        sessions[spec.sessionKey] = session
        session.start()
        notifyChanged()
        return session
    }

    /** Closes [session] (killing the process if still running) and forgets it. */
    fun remove(session: TerminalSession) {
        session.close()
        if (sessions[session.spec.sessionKey] === session) sessions.remove(session.spec.sessionKey)
        notifyChanged()
    }

    fun closeAll() {
        val list = sessions.values.toList()
        sessions.clear()
        list.forEach { it.close() }
        notifyChanged()
    }

    fun addObserver(observer: Observer) = observers.add(observer)
    fun removeObserver(observer: Observer) = observers.remove(observer)

    internal fun notifyChanged() = observers.forEach { it.onSessionsChanged() }
}
