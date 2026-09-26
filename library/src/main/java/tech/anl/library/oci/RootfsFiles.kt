package tech.anl.library.oci

import androidx.annotation.RequiresApi
import java.io.IOException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.NoSuchFileException
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFilePermission

/**
 * Path handling for a rootfs being assembled on the host. Every operation uses lstat semantics
 * (never follows symlinks): the rootfs's symlinks are meant to be resolved inside proot, and
 * following an absolute one on the host would reach outside the rootfs.
 *
 * Needs java.nio.file, i.e. Android API 26+.
 */
@RequiresApi(26) // java.nio.file
internal object RootfsFiles {
    private val NOFOLLOW = arrayOf(LinkOption.NOFOLLOW_LINKS)

    /**
     * Normalizes an archive path to a clean relative path ("a/b/c"): strips leading "/" and "./",
     * "." components, duplicate and trailing slashes. Returns null if it contains ".." (the only
     * way a relative path can climb out of the rootfs). The rootfs itself is "".
     */
    fun normalize(archivePath: String): String? {
        val parts = ArrayList<String>()
        for (part in archivePath.split('/')) {
            when (part) {
                "", "." -> Unit
                ".." -> return null
                else -> parts.add(part)
            }
        }
        return parts.joinToString("/")
    }

    fun parentOf(path: String) = path.substringBeforeLast('/', "")

    fun join(parent: String, name: String) = if (parent.isEmpty()) name else "$parent/$name"

    fun lstat(path: Path): BasicFileAttributes? = try {
        Files.readAttributes(path, BasicFileAttributes::class.java, *NOFOLLOW)
    } catch (e: IOException) {
        null
    }

    fun exists(path: Path) = lstat(path) != null

    /** A directory, and not a symlink to one. */
    fun isRealDirectory(path: Path) = lstat(path)?.isDirectory == true

    /**
     * Maps a normalized relative path to a host path under [root], but only if every ancestor
     * component is a real directory (not a symlink, not missing). The final component itself is
     * not resolved, so it may be a symlink — callers act on the link, not its target.
     */
    fun resolveInside(root: Path, relative: String): Path? {
        if (relative.isEmpty()) return root
        var current = root
        val parts = relative.split('/')
        for (i in 0 until parts.size - 1) {
            current = current.resolve(parts[i])
            if (!isRealDirectory(current)) return null
        }
        return current.resolve(parts.last())
    }

    /** Deletes [path] and, if it is a real directory, everything below it. Never follows symlinks. */
    fun deleteRecursively(path: Path) {
        val attrs = lstat(path) ?: return
        if (attrs.isDirectory) {
            // A read-only directory (e.g. the image's /support) can't have entries removed.
            addOwnerPermissions(path, directory = true)
            Files.newDirectoryStream(path).use { children -> children.forEach { deleteRecursively(it) } }
        }
        try {
            Files.delete(path)
        } catch (e: NoSuchFileException) {
            // Already gone.
        }
    }

    /** Lists a real directory's children (empty if it isn't one). */
    fun children(dir: Path): List<Path> {
        if (!isRealDirectory(dir)) return emptyList()
        return Files.newDirectoryStream(dir).use { it.toList() }
    }

    /**
     * Adds u+w (and u+r, plus u+x on directories) to [path] and everything below it, skipping
     * symlinks. Used on /support, which the image ships read-only but proot needs writable.
     */
    fun makeOwnerWritable(path: Path) {
        val attrs = lstat(path) ?: return
        if (attrs.isSymbolicLink || !(attrs.isDirectory || attrs.isRegularFile)) return
        addOwnerPermissions(path, attrs.isDirectory)
        if (attrs.isDirectory) {
            Files.newDirectoryStream(path).use { children -> children.forEach { makeOwnerWritable(it) } }
        }
    }

    /**
     * Materializes a hard link that tar couldn't create: copies [source]'s content and mode to
     * [dest], or recreates the symlink if [source] is one. Returns false if [source] is unusable.
     */
    fun copyAsHardlink(source: Path, dest: Path): Boolean {
        val attrs = lstat(source) ?: return false
        Files.createDirectories(dest.parent)
        when {
            attrs.isSymbolicLink -> {
                Files.deleteIfExists(dest)
                Files.createSymbolicLink(dest, Files.readSymbolicLink(source))
            }
            attrs.isRegularFile -> {
                val temp = dest.resolveSibling(".${dest.fileName}.anl-link-tmp")
                try {
                    Files.copy(source, temp, StandardCopyOption.REPLACE_EXISTING)
                    Files.setPosixFilePermissions(temp, Files.getPosixFilePermissions(source, *NOFOLLOW))
                    Files.move(temp, dest, StandardCopyOption.REPLACE_EXISTING)
                } finally {
                    Files.deleteIfExists(temp)
                }
            }
            else -> return false
        }
        return true
    }

    private fun addOwnerPermissions(path: Path, directory: Boolean) {
        try {
            val perms = Files.getPosixFilePermissions(path, *NOFOLLOW)
            val wanted = perms + listOfNotNull(
                PosixFilePermission.OWNER_READ,
                PosixFilePermission.OWNER_WRITE,
                if (directory) PosixFilePermission.OWNER_EXECUTE else null,
            )
            if (wanted != perms) Files.setPosixFilePermissions(path, wanted)
        } catch (e: IOException) {
            // Best effort: the following delete/write reports the real problem if this mattered.
        } catch (e: UnsupportedOperationException) {
            // Non-POSIX filesystem; nothing to fix.
        }
    }
}
