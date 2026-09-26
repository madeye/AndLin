package tech.anl.library.proot

import android.content.ContentResolver
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import android.webkit.MimeTypeMap
import tech.anl.library.proot.DroidFilesProtocol.Errno
import java.io.File
import java.io.FileNotFoundException

/**
 * File operations on the primary shared-storage volume, addressed by volume-relative path.
 * Methods return 0 or a positive errno.
 */
internal interface DroidFilesBackend {
    enum class Kind { FILE, DIRECTORY }

    fun kind(relative: String): Kind?
    fun list(relative: String): List<Dirents.Entry>?
    fun createFile(relative: String): Int
    fun mkdir(relative: String): Int
    fun delete(relative: String): Int
    fun openFile(relative: String, mode: String): ParcelFileDescriptor
    fun access(relative: String, accessMode: Int): Int
}

private const val R_OK = 4
private const val W_OK = 2
private const val X_OK = 1

/** Plain java.io access, for trees the app can reach directly (Download, all-files access). */
internal class DirectBackend(private val volumeRoot: File) : DroidFilesBackend {
    private fun file(relative: String) = File(volumeRoot, relative)

    override fun kind(relative: String): DroidFilesBackend.Kind? {
        val f = file(relative)
        return when {
            f.isDirectory -> DroidFilesBackend.Kind.DIRECTORY
            f.exists() -> DroidFilesBackend.Kind.FILE
            else -> null
        }
    }

    override fun list(relative: String): List<Dirents.Entry>? =
        file(relative).listFiles()?.map {
            Dirents.Entry(
                it.name,
                if (it.isDirectory) Dirents.DT_DIR else Dirents.DT_REG,
                Dirents.inodeFor("$relative/${it.name}"),
            )
        }

    override fun createFile(relative: String): Int = try {
        if (file(relative).createNewFile()) 0 else Errno.EEXIST
    } catch (e: java.io.IOException) {
        Errno.EACCES
    }

    override fun mkdir(relative: String): Int {
        val f = file(relative)
        return when {
            f.exists() -> Errno.EEXIST
            f.parentFile?.isDirectory != true -> Errno.ENOENT
            f.mkdir() -> 0
            else -> Errno.EACCES
        }
    }

    override fun delete(relative: String): Int {
        val f = file(relative)
        return when {
            !f.exists() -> Errno.ENOENT
            f.isDirectory && !f.list().isNullOrEmpty() -> Errno.ENOTEMPTY
            f.delete() -> 0
            else -> Errno.EACCES
        }
    }

    override fun openFile(relative: String, mode: String): ParcelFileDescriptor =
        ParcelFileDescriptor.open(file(relative), ParcelFileDescriptor.parseMode(mode))

    override fun access(relative: String, accessMode: Int): Int {
        val f = file(relative)
        return when {
            !f.exists() -> Errno.ENOENT
            accessMode and R_OK != 0 && !f.canRead() -> Errno.EACCES
            accessMode and W_OK != 0 && !f.canWrite() -> Errno.EACCES
            accessMode and X_OK != 0 && !f.isDirectory && !f.canExecute() -> Errno.EACCES
            else -> 0
        }
    }
}

/**
 * Storage Access Framework access through a persisted tree grant on ExternalStorageProvider,
 * whose document IDs are deterministic ("primary:<relative path>"), so no child lookups (and no
 * slow DocumentFile.findFile walks) are needed to resolve a path.
 */
internal class SafBackend(
    private val resolver: ContentResolver,
    private val treeUri: Uri,
) : DroidFilesBackend {

    private fun uri(relative: String): Uri =
        DocumentsContract.buildDocumentUriUsingTree(treeUri, DroidFilesGrants.documentIdFor(relative))

    private inline fun <T> query(uri: Uri, columns: Array<String>, block: (Cursor) -> T): T? = try {
        resolver.query(uri, columns, null, null, null)?.use(block)
    } catch (e: FileNotFoundException) {
        null
    } catch (e: IllegalArgumentException) {
        null // ExternalStorageProvider: "Missing file for ..." / not a descendant of the tree
    } catch (e: SecurityException) {
        null
    } catch (e: IllegalStateException) {
        null
    }

    override fun kind(relative: String): DroidFilesBackend.Kind? =
        query(uri(relative), arrayOf(Document.COLUMN_MIME_TYPE)) { c ->
            if (!c.moveToFirst()) null
            else if (c.getString(0) == Document.MIME_TYPE_DIR) DroidFilesBackend.Kind.DIRECTORY
            else DroidFilesBackend.Kind.FILE
        }

    override fun list(relative: String): List<Dirents.Entry>? {
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(
            treeUri, DroidFilesGrants.documentIdFor(relative),
        )
        return query(children, arrayOf(Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE)) { c ->
            val out = ArrayList<Dirents.Entry>(c.count)
            while (c.moveToNext()) {
                val name = c.getString(0) ?: continue
                if (name.isEmpty() || name.contains('/')) continue
                val type = if (c.getString(1) == Document.MIME_TYPE_DIR) Dirents.DT_DIR else Dirents.DT_REG
                out += Dirents.Entry(name, type, Dirents.inodeFor("$relative/$name"))
            }
            out
        }
    }

    private fun create(relative: String, mimeType: String): Int {
        val parent = GuestPathMapper.parentOf(relative) ?: return Errno.EACCES
        if (kind(parent) != DroidFilesBackend.Kind.DIRECTORY) return Errno.ENOENT
        if (kind(relative) != null) return Errno.EEXIST
        return try {
            DocumentsContract.createDocument(resolver, uri(parent), mimeType, GuestPathMapper.nameOf(relative))
                ?.let { 0 } ?: Errno.EACCES
        } catch (e: FileNotFoundException) {
            Errno.ENOENT
        } catch (e: SecurityException) {
            Errno.EACCES
        } catch (e: IllegalArgumentException) {
            Errno.EACCES // e.g. MediaProvider refusing a type in DCIM/Pictures
        } catch (e: IllegalStateException) {
            Errno.EACCES
        }
    }

    override fun createFile(relative: String): Int {
        // A MIME type that maps back to the name's own extension (or octet-stream) keeps the
        // display name exactly as given; anything else makes the provider append an extension.
        val ext = GuestPathMapper.nameOf(relative).substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
        return create(relative, mime)
    }

    override fun mkdir(relative: String): Int = create(relative, Document.MIME_TYPE_DIR)

    override fun delete(relative: String): Int {
        val kind = kind(relative) ?: return Errno.ENOENT
        // deleteDocument is recursive; rmdir semantics require an empty directory.
        if (kind == DroidFilesBackend.Kind.DIRECTORY && !list(relative).isNullOrEmpty()) {
            return Errno.ENOTEMPTY
        }
        return try {
            if (DocumentsContract.deleteDocument(resolver, uri(relative))) 0 else Errno.EACCES
        } catch (e: FileNotFoundException) {
            Errno.ENOENT
        } catch (e: RuntimeException) {
            Errno.EACCES
        }
    }

    override fun openFile(relative: String, mode: String): ParcelFileDescriptor =
        resolver.openFileDescriptor(uri(relative), mode)
            ?: throw FileNotFoundException("provider returned no descriptor for $relative")

    override fun access(relative: String, accessMode: Int): Int {
        val row = query(uri(relative), arrayOf(Document.COLUMN_MIME_TYPE, Document.COLUMN_FLAGS)) { c ->
            if (c.moveToFirst()) Pair(c.getString(0), c.getInt(1)) else null
        } ?: return Errno.ENOENT
        val isDir = row.first == Document.MIME_TYPE_DIR
        val flags = row.second
        if (accessMode and W_OK != 0) {
            val writable = if (isDir) {
                flags and Document.FLAG_DIR_SUPPORTS_CREATE != 0
            } else {
                flags and Document.FLAG_SUPPORTS_WRITE != 0
            }
            if (!writable) return Errno.EACCES
        }
        if (accessMode and X_OK != 0 && !isDir) return Errno.EACCES
        return 0
    }
}
