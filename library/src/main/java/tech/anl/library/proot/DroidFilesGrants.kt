package tech.anl.library.proot

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Persisted Storage Access Framework tree grants, keyed by the volume-relative path the granted
 * tree covers ("Documents", "Music/Albums", or "" for the whole volume on Android 10 and older).
 *
 * Backed by SharedPreferences, so separate instances (server and consent activity) share state.
 */
class DroidFilesGrants(context: Context) {
    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Grant(val relativeRoot: String, val treeUri: Uri)

    fun all(): List<Grant> = prefs.all.mapNotNull { (key, value) ->
        (value as? String)?.let { Grant(key, Uri.parse(it)) }
    }

    /** The grant whose tree contains [relativePath], preferring the deepest one. */
    fun find(relativePath: String): Grant? {
        val roots = prefs.all.keys
        val root = coveringRoot(roots, relativePath) ?: return null
        val uri = prefs.getString(root, null) ?: return null
        return Grant(root, Uri.parse(uri))
    }

    fun put(relativeRoot: String, treeUri: Uri) {
        prefs.edit().putString(relativeRoot, treeUri.toString()).apply()
    }

    fun remove(relativeRoot: String) {
        prefs.edit().remove(relativeRoot).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    /** Drops grants whose persisted permission the user (or the system) has since revoked. */
    fun prune(resolver: ContentResolver) {
        val live = resolver.persistedUriPermissions
            .filter { it.isReadPermission && it.isWritePermission }
            .map { it.uri.toString() }
            .toSet()
        val editor = prefs.edit()
        for ((key, value) in prefs.all) {
            if (value !is String || value !in live) editor.remove(key)
        }
        editor.apply()
    }

    companion object {
        const val PREFS_NAME = "droid_files_grants"
        const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
        const val PRIMARY_VOLUME = "primary"

        /** Volume-relative root covered by a tree Uri, or null if it is not on primary storage. */
        fun relativeRootOf(treeUri: Uri): String? {
            if (treeUri.authority != EXTERNAL_STORAGE_AUTHORITY) return null
            val docId = try {
                DocumentsContract.getTreeDocumentId(treeUri)
            } catch (e: IllegalArgumentException) {
                return null
            }
            return relativeFromDocumentId(docId)
        }

        /** "primary:Documents/x" -> "Documents/x"; "primary:" -> ""; other volumes -> null. */
        fun relativeFromDocumentId(documentId: String): String? {
            val colon = documentId.indexOf(':')
            if (colon < 0 || documentId.substring(0, colon) != PRIMARY_VOLUME) return null
            return documentId.substring(colon + 1).trim('/')
        }

        fun documentIdFor(relativePath: String): String = "$PRIMARY_VOLUME:$relativePath"

        /** The deepest root in [roots] equal to, or an ancestor of, [relativePath]. */
        fun coveringRoot(roots: Collection<String>, relativePath: String): String? =
            roots.filter { root ->
                root.isEmpty() || relativePath == root || relativePath.startsWith("$root/")
            }.maxByOrNull { it.length }
    }
}
