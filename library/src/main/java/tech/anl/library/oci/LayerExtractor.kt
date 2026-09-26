package tech.anl.library.oci

import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ensureActive
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Path
import java.util.zip.GZIPInputStream

/** Starts a tar process that extracts an uncompressed tar stream from its stdin into [targetDir]. */
fun interface TarProcessFactory {
    fun start(targetDir: File): Process
}

/**
 * Runs an external tar: `<command> -C <targetDir>`, reading the archive from stdin.
 *
 * The flags matter on Android: `-p` restores the archive's modes exactly (otherwise the app's
 * 077 umask would be applied), `-o` skips chown (an app can't give files away anyway).
 */
class CommandTarProcessFactory(private val command: List<String>) : TarProcessFactory {
    override fun start(targetDir: File): Process =
        ProcessBuilder(command + listOf("-C", targetDir.absolutePath))
            .directory(targetDir)
            .start()

    companion object {
        /** Android's built-in toybox tar (reads stdin when no -f is given). */
        fun toybox(toybox: String = "/system/bin/toybox") =
            CommandTarProcessFactory(listOf(toybox, "tar", "-x", "-p", "-o"))

        /** A desktop tar (bsdtar or GNU tar), e.g. for tests and tooling on a workstation. */
        fun hostTar(tar: String = "/usr/bin/tar") =
            CommandTarProcessFactory(listOf(tar, "-x", "-p", "-o", "-f", "-"))
    }
}

/**
 * Applies one OCI image layer to a rootfs directory.
 *
 * The layer (gzip-compressed or plain tar) is decompressed here and re-serialized, entry by entry,
 * into the stdin of a tar process ([tarFactory]). Passing the stream through our own tar parser —
 * without buffering it — lets us:
 *  - drop entries an app can't or shouldn't create: device nodes, FIFOs, anything under the
 *    [excludes] directories, files in [excludes] that already exist, and unsafe paths ("..",
 *    paths through symlinks);
 *  - remove OCI whiteout markers (`.wh.<name>`, `.wh..wh..opq`) from the stream and apply them
 *    ourselves afterwards, so they only delete lower-layer content;
 *  - make every directory owner-writable so tar can fill read-only directories (toybox chmods a
 *    directory as soon as it creates it), and drop setuid/setgid bits;
 *  - know every hard link, so links that Android's SELinux policy refused (link(2) on app data)
 *    are replaced by copies — otherwise e.g. /usr/bin/perl silently vanishes on Debian.
 *
 * Needs java.nio.file, i.e. Android API 26+.
 */
@RequiresApi(26) // java.nio.file
class LayerExtractor(
    private val tarFactory: TarProcessFactory,
    private val excludes: List<String> = DEFAULT_EXCLUDES,
    /** Receives non-fatal diagnostics (rejected entries, tolerated tar errors), e.g. for logcat. */
    private val warn: (String) -> Unit = {},
) {
    companion object {
        /**
         * Entries ending in "/" exclude everything below that directory (the directory itself is
         * still created, as a mount point for proot). Other entries name files that are left
         * alone if they already exist, because the app writes them.
         */
        val DEFAULT_EXCLUDES = listOf("dev/", "proc/", "sys/", "etc/hosts", "etc/hostname", "etc/resolv.conf")

        private const val WHITEOUT_PREFIX = ".wh."
        private const val OPAQUE_MARKER = ".wh..wh..opq"
        private const val BUFFER_SIZE = 64 * 1024
        private const val STDERR_TAIL = 4096
        private const val SETID_BITS = 0xc00 // 06000
        private const val OWNER_RWX = 0x1c0 // 0700
    }

    private val excludedDirs = excludes.filter { it.endsWith("/") }
    private val keepIfPresent = excludes.filterNot { it.endsWith("/") }.mapNotNull { RootfsFiles.normalize(it) }

    /**
     * Extracts [layerFile] into [rootfsDir] (created if missing). [onPercent] reports how much of
     * the (compressed) layer file has been consumed, from a background thread.
     *
     * Cancellation kills the tar process; the rootfs is then left partially updated and the layer
     * should be extracted again.
     */
    suspend fun extract(layerFile: File, rootfsDir: File, onPercent: (Int) -> Unit) {
        rootfsDir.mkdirs()
        val root = rootfsDir.absoluteFile.toPath()
        val preserved = keepIfPresent.filterTo(HashSet()) { RootfsFiles.exists(root.resolve(it)) }
        val plan = LayerPlan(root, preserved)

        val process = tarFactory.start(rootfsDir)
        val stderr = StreamTail(process.errorStream, STDERR_TAIL)
        val stdout = StreamTail(process.inputStream, STDERR_TAIL)

        val exitCode = runCancellableIo(onCancel = { process.destroyForcibly() }) {
            try {
                streamLayer(layerFile, plan, process, onPercent)
            } catch (e: OciException) {
                process.destroyForcibly() // a problem with the layer itself
                throw e
            } catch (e: IOException) {
                // Usually tar died and closed its stdin (EPIPE), or the gzip data is corrupt.
                process.destroyForcibly()
                process.waitFor()
                stderr.join()
                throw OciException("Extracting ${layerFile.name} failed: ${e.message}; tar: ${stderr.text()}", e)
            } catch (e: Throwable) {
                process.destroyForcibly()
                throw e
            }
            process.waitFor()
        }
        stderr.join()
        stdout.join()

        runCancellableIo(onCancel = {}) { plan.finish(exitCode, stderr.text()) }
        onPercent(100)
    }

    private fun CoroutineScope.streamLayer(layerFile: File, plan: LayerPlan, process: Process, onPercent: (Int) -> Unit) {
        val total = layerFile.length().coerceAtLeast(1)
        var lastPercent = -1
        val counted = CountingInputStream(FileInputStream(layerFile))
        fun report() {
            val percent = (counted.count * 100 / total).toInt().coerceIn(0, 99)
            if (percent != lastPercent) {
                lastPercent = percent
                onPercent(percent)
            }
        }

        openDecompressed(counted).use { input ->
            val reader = TarStreamReader(input)
            val stdin = BufferedOutputStream(process.outputStream, BUFFER_SIZE)
            val writer = TarStreamWriter(stdin)
            val buffer = ByteArray(BUFFER_SIZE)

            while (true) {
                coroutineContext.ensureActive()
                val entry = reader.next() ?: break
                val forward = plan.admit(entry) ?: continue
                writer.putEntry(forward.path, forward.type, forward.linkTarget, forward.size, forward.mode, forward.mtime)
                while (true) {
                    val n = reader.readData(buffer)
                    if (n < 0) break
                    writer.write(buffer, 0, n)
                    if (n == buffer.size) {
                        coroutineContext.ensureActive()
                        report()
                    }
                }
                writer.closeEntry()
                report()
            }
            writer.finish()
            stdin.close()
        }
    }

    private fun openDecompressed(counted: InputStream): InputStream {
        val buffered = BufferedInputStream(counted, BUFFER_SIZE)
        buffered.mark(4)
        val magic = ByteArray(4)
        val n = buffered.read(magic)
        buffered.reset()
        return when {
            n >= 2 && magic[0] == 0x1f.toByte() && magic[1] == 0x8b.toByte() ->
                // GZIPInputStream also handles multi-member (concatenated) gzip files.
                BufferedInputStream(GZIPInputStream(buffered, BUFFER_SIZE), BUFFER_SIZE)
            n >= 4 && magic[0] == 0x28.toByte() && magic[1] == 0xb5.toByte() && magic[2] == 0x2f.toByte() && magic[3] == 0xfd.toByte() -> {
                buffered.close()
                throw OciException("zstd-compressed layers are not supported")
            }
            else -> buffered // an uncompressed tar layer
        }
    }

    /**
     * What one layer does to the rootfs, collected while streaming it: the paths it creates,
     * its whiteouts and hard links. Applied to the disk in [finish] once tar has exited.
     */
    private inner class LayerPlan(private val root: Path, private val preserved: Set<String>) {
        /** Every path this layer writes, plus all their ancestors. */
        private val layerPaths = HashSet<String>()
        /** Type of each path this layer writes explicitly (the last entry wins). */
        private val layerTypes = HashMap<String, TarEntryType>()
        /** Ancestor prefixes already known to be real directories. */
        private val safeDirs = HashSet<String>()
        private val hardlinks = ArrayList<Pair<String, String>>()
        private val whiteouts = LinkedHashSet<String>()
        private val opaqueDirs = LinkedHashSet<String>()
        private val rejected = ArrayList<String>()

        /** Decides whether [entry] is forwarded to tar, and in what (normalized) form. */
        fun admit(entry: TarEntry): TarEntry? {
            val path = RootfsFiles.normalize(entry.path)
            if (path == null) return reject(entry.path, "path escapes the rootfs")
            if (path.isEmpty()) return null // the rootfs directory itself: leave its mode alone

            val name = path.substringAfterLast('/')
            val parent = RootfsFiles.parentOf(path)
            if (name.startsWith(WHITEOUT_PREFIX)) {
                when {
                    name == OPAQUE_MARKER -> opaqueDirs.add(parent)
                    // Other ".wh..wh.*" names are aufs bookkeeping, not whiteouts.
                    name.startsWith(WHITEOUT_PREFIX + WHITEOUT_PREFIX) -> Unit
                    else -> {
                        val hidden = name.removePrefix(WHITEOUT_PREFIX)
                        if (hidden == "." || hidden == "..") return reject(path, "invalid whiteout")
                        whiteouts.add(RootfsFiles.join(parent, hidden))
                    }
                }
                return null
            }

            when (entry.type) {
                TarEntryType.CHAR_DEVICE, TarEntryType.BLOCK_DEVICE, TarEntryType.FIFO, TarEntryType.OTHER -> return null
                else -> Unit
            }
            if (excludedDirs.any { path.startsWith(it) }) return null
            if (path in preserved) return null
            if (!parentIsSafe(parent)) return reject(path, "parent directory is a symlink or not a directory")

            var linkTarget = entry.linkTarget
            if (entry.type == TarEntryType.HARDLINK) {
                linkTarget = RootfsFiles.normalize(entry.linkTarget)?.takeIf { it.isNotEmpty() }
                    ?: return reject(path, "hard link target '${entry.linkTarget}' escapes the rootfs")
                hardlinks.add(path to linkTarget)
            }

            replaceConflictingLowerEntry(path, entry.type)
            record(path, entry.type)

            // Drop setuid/setgid (04000/02000), keep the sticky bit. Directories get u+rwx so tar
            // (and later layers) can write inside them even if the image made them read-only.
            var mode = entry.mode and SETID_BITS.inv() and 0xfff
            if (entry.type == TarEntryType.DIRECTORY) mode = mode or OWNER_RWX
            return entry.copy(path = path, linkTarget = linkTarget, mode = mode)
        }

        private fun reject(path: String, reason: String): TarEntry? {
            if (rejected.size < 20) rejected.add("$path ($reason)")
            return null
        }

        /**
         * Every ancestor must be a directory — created by this layer, or a real (non-symlink)
         * directory on disk. Writing through a symlink could land outside the rootfs.
         */
        private fun parentIsSafe(parent: String): Boolean {
            if (parent.isEmpty() || parent in safeDirs) return true
            var prefix = ""
            for (part in parent.split('/')) {
                prefix = RootfsFiles.join(prefix, part)
                if (prefix in safeDirs) continue
                val typeInLayer = layerTypes[prefix]
                val ok = if (typeInLayer != null) {
                    typeInLayer == TarEntryType.DIRECTORY
                } else {
                    val attrs = RootfsFiles.lstat(root.resolve(prefix))
                    attrs == null || attrs.isDirectory // missing ancestors are created by tar as directories
                }
                if (!ok) return false
                safeDirs.add(prefix)
            }
            return true
        }

        /**
         * If a lower layer left something of the other kind (directory vs. non-directory) at
         * [path], remove it first: tar can't replace a non-empty directory with a file, and would
         * follow a symlink where this layer wants a directory. Nothing below [path] has been
         * forwarded yet, so this can't race with tar.
         */
        private fun replaceConflictingLowerEntry(path: String, type: TarEntryType) {
            if (path in layerPaths) return
            val target = root.resolve(path)
            val attrs = RootfsFiles.lstat(target) ?: return
            if (attrs.isDirectory != (type == TarEntryType.DIRECTORY)) RootfsFiles.deleteRecursively(target)
        }

        private fun record(path: String, type: TarEntryType) {
            layerTypes[path] = type
            if (type != TarEntryType.DIRECTORY && path in safeDirs) {
                // A directory replaced by a file or symlink is no longer a safe ancestor.
                safeDirs.remove(path)
                safeDirs.removeAll { it.startsWith("$path/") }
            }
            var current = path
            while (current.isNotEmpty() && layerPaths.add(current)) current = RootfsFiles.parentOf(current)
        }

        /** Post-tar fixups: hard link fallback, verification, whiteouts, /support permissions. */
        fun finish(exitCode: Int, tarErrors: String) {
            if (rejected.isNotEmpty()) warn("Skipped unsafe layer entries: ${rejected.joinToString()}")
            val danglingLinks = materializeHardlinks()
            if (danglingLinks.isNotEmpty()) {
                warn("Hard links with no usable target: ${danglingLinks.take(10).joinToString()}")
            }

            val missing = layerTypes.keys.filter { it !in danglingLinks && !RootfsFiles.exists(root.resolve(it)) }
            if (missing.isNotEmpty()) {
                throw OciException(
                    "tar exited with $exitCode and ${missing.size} entries are missing " +
                        "(e.g. ${missing.take(5).joinToString()}); tar: $tarErrors",
                )
            }
            // Anything else tar complained about (ownership, timestamps, the links we just
            // replaced with copies) doesn't affect the result.
            if (exitCode != 0) warn("tar exited with $exitCode, but every entry is in place: $tarErrors")

            applyWhiteouts()
            RootfsFiles.makeOwnerWritable(root.resolve("support"))
        }

        /**
         * Replaces hard links tar failed to create with copies of their targets (in archive order,
         * so links to links work). Returns the links whose target doesn't exist either.
         */
        private fun materializeHardlinks(): Set<String> {
            val dangling = HashSet<String>()
            for ((path, target) in hardlinks) {
                val dest = RootfsFiles.resolveInside(root, path) ?: continue
                if (RootfsFiles.exists(dest)) continue
                val source = RootfsFiles.resolveInside(root, target)
                if (source == null || !RootfsFiles.copyAsHardlink(source, dest)) dangling.add(path)
            }
            return dangling
        }

        private fun applyWhiteouts() {
            for (path in whiteouts) {
                if (path in preserved) continue
                val target = RootfsFiles.resolveInside(root, path) ?: continue
                if (path in layerPaths) {
                    // This layer also writes here: only its own content survives.
                    pruneToLayer(path)
                } else {
                    RootfsFiles.deleteRecursively(target)
                }
            }
            for (dir in opaqueDirs) pruneToLayer(dir)
        }

        /** Deletes everything under [dir] that this layer didn't write (opaque-directory semantics). */
        private fun pruneToLayer(dir: String) {
            val hostDir = RootfsFiles.resolveInside(root, dir) ?: return
            if (!RootfsFiles.isRealDirectory(hostDir)) return
            for (child in RootfsFiles.children(hostDir)) {
                val relative = RootfsFiles.join(dir, child.fileName.toString())
                when {
                    relative in layerPaths -> if (RootfsFiles.isRealDirectory(child)) pruneToLayer(relative)
                    relative in preserved -> Unit
                    else -> RootfsFiles.deleteRecursively(child)
                }
            }
        }
    }
}

/** Counts bytes read through it; used for progress on the compressed layer. */
private class CountingInputStream(input: InputStream) : FilterInputStream(input) {
    @Volatile var count = 0L
        private set

    override fun read(): Int = super.read().also { if (it >= 0) count++ }

    override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) count += it }

    override fun skip(n: Long): Long = super.skip(n).also { count += it }
}

/** Drains a process stream on a daemon thread, keeping the last [limit] bytes for error messages. */
private class StreamTail(private val stream: InputStream, private val limit: Int) {
    private val buffer = java.io.ByteArrayOutputStream()
    private val thread = Thread {
        val chunk = ByteArray(4096)
        try {
            while (true) {
                val n = stream.read(chunk)
                if (n < 0) break
                synchronized(buffer) {
                    buffer.write(chunk, 0, n)
                    if (buffer.size() > limit * 2) {
                        val tail = buffer.toByteArray().copyOfRange(buffer.size() - limit, buffer.size())
                        buffer.reset()
                        buffer.write(tail)
                    }
                }
            }
        } catch (e: IOException) {
            // Process gone.
        }
    }.apply {
        isDaemon = true
        name = "tar-output"
        start()
    }

    fun join() = thread.join(5_000)

    fun text(): String = synchronized(buffer) {
        val bytes = buffer.toByteArray()
        String(bytes, maxOf(0, bytes.size - limit), minOf(bytes.size, limit), Charsets.UTF_8).trim()
    }
}
