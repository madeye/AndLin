package tech.anl.library.proot

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/** One `kill` line of a PRoot kill report. */
data class KillRecord(
    val vpid: Long,
    val pid: Int,
    val signal: Int,
    /** "external" (nobody in the guest asked for it) or "guest". */
    val origin: String,
    val unixTime: Long,
)

data class KillReportResult(
    /** Every PRoot that started in the report either wrote its clean-exit line or was stopped by the app. */
    val sessionEndedCleanly: Boolean,
    /** SIGKILLs of tracees that no guest kill(2)/tkill(2)/tgkill(2) accounts for, excluding the app's own teardown. */
    val externalKills: List<KillRecord>,
    /** A PRoot started and never wrote its clean-exit line, and the app did not stop it. */
    val prootItselfKilled: Boolean,
)

/**
 * Reader for the file PRoot's `--kill-report=<path>` extension appends to
 * (`src/extension/kill_report/kill_report.c`).
 *
 * ## Format
 * PRoot opens the path (a HOST path -- PRoot opens it itself, it is not translated) with
 * `O_WRONLY|O_CREAT|O_APPEND`, never truncates it, and fsyncs every line:
 * ```
 * start pid=<proot pid> unix=<epoch s>                                     at PRoot startup
 * kill vpid=<n> pid=<tracee pid> signal=9 origin=<external|guest> unix=<s> per SIGKILLed tracee
 * clean pid=<proot pid> unix=<s>                                           atexit(), i.e. normal exit only
 * ```
 * Only deaths by SIGKILL are recorded. `origin=guest` means a tracee issued a matching kill(2),
 * tkill(2) or tgkill(2) for that pid+signal; `origin=external` means nothing in the guest did, i.e.
 * the host killed it (Android's phantom process killer, lmkd, ... -- or this app!). A `start`
 * without a `clean` means PRoot itself died from a signal (it ignores SIGTERM/SIGINT/SIGHUP, so in
 * practice SIGKILL), or is still running. A line PRoot was killed while writing has no trailing
 * newline and is discarded.
 *
 * ## The app's own teardown
 * PRoot cannot tell the app's own `kill -9` / `Process.destroy()` apart from the phantom process
 * killer: both show up as `origin=external`, and killing PRoot itself leaves an unfinished
 * `start`. Callers must therefore:
 *  1. call [markDeliberateStop] on the report file BEFORE killing any session process on purpose
 *     (stopping a session, restarting it, app shutdown). Everything after that marker, up to the
 *     next `start`, is ignored by [parse];
 *  2. parse only once the session's PRoot has exited (a live PRoot has no `clean` line yet), and
 *     only while the app process itself is still alive -- if the app was killed, its children died
 *     with it and an unfinished `start` says nothing about the phantom process killer. So on a
 *     cold start, [rotate] (or delete) a leftover report instead of trusting it;
 *  3. [rotate] the file before the next session so old evidence is not reported twice.
 */
object KillReport {
    /** Line the app appends (see [markDeliberateStop]); PRoot never writes it. */
    const val DELIBERATE_STOP = "app-stop"

    fun parse(file: File): KillReportResult {
        val text = try {
            if (file.exists()) file.readText() else ""
        } catch (e: IOException) {
            ""
        }
        return parse(text)
    }

    fun parse(text: String): KillReportResult {
        val lines = text.split('\n')
        // The last element is either "" (text ended with a newline) or a truncated line.
        val complete = lines.dropLast(1)

        val unfinished = LinkedHashSet<Int>()
        val external = ArrayList<KillRecord>()
        var stopped = false

        for (line in complete) {
            val words = line.trim().split(' ').filter { it.isNotEmpty() }
            if (words.isEmpty()) continue
            val fields = words.drop(1).mapNotNull { word ->
                val eq = word.indexOf('=')
                if (eq <= 0) null else word.substring(0, eq) to word.substring(eq + 1)
            }.toMap()
            when (words[0]) {
                "start" -> {
                    val pid = fields["pid"]?.toIntOrNull() ?: continue
                    unfinished += pid
                    stopped = false
                }
                "clean" -> {
                    val pid = fields["pid"]?.toIntOrNull() ?: continue
                    unfinished -= pid
                }
                DELIBERATE_STOP -> {
                    stopped = true
                    unfinished.clear()
                }
                "kill" -> {
                    val record = KillRecord(
                        vpid = fields["vpid"]?.toLongOrNull() ?: continue,
                        pid = fields["pid"]?.toIntOrNull() ?: continue,
                        signal = fields["signal"]?.toIntOrNull() ?: continue,
                        origin = fields["origin"] ?: continue,
                        unixTime = fields["unix"]?.toLongOrNull() ?: continue,
                    )
                    if (record.origin == "external" && !stopped) external += record
                }
            }
        }
        return KillReportResult(
            sessionEndedCleanly = unfinished.isEmpty(),
            externalKills = external,
            prootItselfKilled = unfinished.isNotEmpty(),
        )
    }

    /**
     * True only if the report holds evidence that the host killed part of the session: a tracee
     * SIGKILLed with no guest request for it, or a PRoot that died without its clean-exit line --
     * neither covered by a [markDeliberateStop]. See the class docs for when this is meaningful.
     */
    fun wasKilledByHost(result: KillReportResult): Boolean =
        result.externalKills.isNotEmpty() || result.prootItselfKilled

    /** Appends the deliberate-stop marker. Call before the app kills session processes itself. */
    fun markDeliberateStop(file: File, nowSeconds: Long = System.currentTimeMillis() / 1000) {
        if (!file.exists()) return
        try {
            // O_APPEND, like PRoot, so the marker never tears one of its lines.
            FileOutputStream(file, true).use {
                it.write("$DELIBERATE_STOP unix=$nowSeconds\n".toByteArray())
                it.fd.sync()
            }
        } catch (e: IOException) {
            // Worst case a deliberate stop is later misreported; nothing better to do here.
        }
    }

    /** Moves the report aside (to `<name>.prev`) so the next session starts from an empty one. */
    fun rotate(file: File) {
        if (!file.exists()) return
        val previous = File(file.parentFile, file.name + ".prev")
        previous.delete()
        if (!file.renameTo(previous)) file.delete()
    }
}
