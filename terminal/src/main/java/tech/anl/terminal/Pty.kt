package tech.anl.terminal

import java.io.IOException

/** JNI bindings to libanl-terminal: pseudo-terminal creation and process control. */
internal object Pty {
    init {
        System.loadLibrary("anl-terminal")
    }

    const val SIGHUP = 1
    const val SIGKILL = 9

    /**
     * Forks [cmd] (resolved through PATH if not absolute) in a new session whose controlling
     * terminal is a fresh PTY. [argv] must include argv[0]. Returns `[pid, masterFd]`.
     */
    @JvmStatic
    @Throws(IOException::class)
    external fun createSubprocess(
        cmd: String,
        cwd: String,
        argv: Array<String>,
        envp: Array<String>,
        rows: Int,
        cols: Int,
    ): IntArray

    @JvmStatic
    external fun setPtyWindowSize(fd: Int, rows: Int, cols: Int, widthPx: Int, heightPx: Int)

    /** Blocks until [pid] exits; returns its exit code, or -signal if it was killed. */
    @JvmStatic
    external fun waitFor(pid: Int): Int

    @JvmStatic
    external fun close(fd: Int)

    @JvmStatic
    external fun sendSignal(pid: Int, signal: Int)
}
