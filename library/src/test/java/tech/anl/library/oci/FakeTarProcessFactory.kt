package tech.anl.library.oci

import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.PipedInputStream
import java.io.PipedOutputStream
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermission
import java.util.Collections
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

/**
 * A pure-Kotlin stand-in for `tar -x`, so extractor tests don't depend on the host tar's quirks.
 * It records every entry it receives and can mimic Android refusing link(2) ([failHardlinks]),
 * which makes it exit non-zero like toybox does.
 */
class FakeTarProcessFactory(private val failHardlinks: Boolean = false) : TarProcessFactory {
    val received: MutableList<TarEntry> = Collections.synchronizedList(ArrayList())

    override fun start(targetDir: File): Process = FakeTarProcess(targetDir)

    private inner class FakeTarProcess(private val root: File) : Process() {
        private val stdin = PipedOutputStream()
        private val source = PipedInputStream(stdin, 1 shl 16)
        private val stderrText = StringBuilder()
        private val exit = CompletableFuture<Int>()

        init {
            Thread {
                exit.complete(
                    try {
                        extractAll()
                    } catch (e: Exception) {
                        stderrText.append("fake tar: ${e.message}\n")
                        2
                    } finally {
                        try {
                            source.close()
                        } catch (e: Exception) {
                        }
                    },
                )
            }.apply { isDaemon = true }.start()
        }

        private fun extractAll(): Int {
            var status = 0
            val reader = TarStreamReader(source)
            val buffer = ByteArray(8192)
            while (true) {
                val entry = reader.next() ?: break
                received += entry
                val target = File(root, entry.path)
                target.parentFile.mkdirs()
                when (entry.type) {
                    TarEntryType.DIRECTORY -> {
                        target.mkdirs()
                        setMode(target, entry.mode)
                    }
                    TarEntryType.FILE -> {
                        Files.deleteIfExists(target.toPath())
                        target.outputStream().use { out ->
                            while (true) {
                                val n = reader.readData(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                            }
                        }
                        setMode(target, entry.mode)
                    }
                    TarEntryType.SYMLINK -> {
                        Files.deleteIfExists(target.toPath())
                        Files.createSymbolicLink(target.toPath(), File(entry.linkTarget).toPath())
                    }
                    TarEntryType.HARDLINK -> {
                        if (failHardlinks) {
                            stderrText.append("tar: can't link '${entry.path}' -> '${entry.linkTarget}': Permission denied\n")
                            status = 1
                        } else {
                            Files.deleteIfExists(target.toPath())
                            Files.createLink(target.toPath(), File(root, entry.linkTarget).toPath())
                        }
                    }
                    else -> {
                        stderrText.append("tar: can't create '${entry.path}'\n")
                        status = 1
                    }
                }
            }
            // Like a real process, read stdin to EOF so the writer never sees a broken pipe.
            while (source.read(buffer) >= 0) Unit
            return status
        }

        private fun setMode(file: File, mode: Int) {
            val perms = HashSet<PosixFilePermission>()
            PosixFilePermission.values().forEachIndexed { i, p -> if (mode and (0x100 shr i) != 0) perms += p }
            Files.setPosixFilePermissions(file.toPath(), perms)
        }

        override fun getOutputStream(): OutputStream = stdin
        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
        override fun getErrorStream(): InputStream = object : InputStream() {
            private var bytes: ByteArrayInputStream? = null
            private fun delegate(): ByteArrayInputStream {
                if (bytes == null) {
                    exit.get()
                    bytes = ByteArrayInputStream(stderrText.toString().toByteArray())
                }
                return bytes!!
            }
            override fun read(): Int = delegate().read()
            override fun read(b: ByteArray, off: Int, len: Int): Int = delegate().read(b, off, len)
        }
        override fun waitFor(): Int = exit.get()
        override fun waitFor(timeout: Long, unit: TimeUnit): Boolean = try {
            exit.get(timeout, unit)
            true
        } catch (e: java.util.concurrent.TimeoutException) {
            false
        }
        override fun exitValue(): Int = exit.getNow(null) ?: throw IllegalThreadStateException()
        override fun destroy() {
            source.close()
            exit.complete(143)
        }
    }
}
