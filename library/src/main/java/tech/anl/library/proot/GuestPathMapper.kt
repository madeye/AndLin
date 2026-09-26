package tech.anl.library.proot

/**
 * Maps the guest paths PRoot sends (`/sdcard/Documents/a/b`) to a path relative to the primary
 * shared-storage volume (`Documents/a/b`), whose first segment is the top-level directory a SAF
 * grant is asked for.
 *
 * Several spellings reach the same volume depending on how the session binds it (and on which
 * binding PRoot's detranslation picks), so every known alias is accepted.
 */
class GuestPathMapper(prefixes: Collection<String> = DEFAULT_PREFIXES) {
    private val prefixes: List<String> = prefixes
        .map { it.trimEnd('/') }
        .filter { it.isNotEmpty() }
        .distinct()
        .sortedByDescending { it.length }

    /**
     * @return the normalized volume-relative path ("Documents/x"), or null when [guestPath] is not
     *  strictly below the shared-storage root or escapes it with "..".
     */
    fun toRelative(guestPath: String): String? {
        if (!guestPath.startsWith("/")) return null
        val normalized = normalize(guestPath) ?: return null
        for (prefix in prefixes) {
            if (normalized.length > prefix.length + 1 &&
                normalized.startsWith(prefix) &&
                normalized[prefix.length] == '/'
            ) {
                return normalized.substring(prefix.length + 1)
            }
        }
        return null
    }

    companion object {
        val DEFAULT_PREFIXES = listOf(
            "/sdcard",
            "/storage/emulated/0",
            "/storage/self/primary",
            "/storage/internal",
            "/mnt/sdcard",
        )

        /** Top-level directory of a volume-relative path ("Documents/x" -> "Documents"). */
        fun topOf(relative: String): String = relative.substringBefore('/')

        /** Parent of a volume-relative path, or null for a top-level entry. */
        fun parentOf(relative: String): String? =
            if (relative.contains('/')) relative.substringBeforeLast('/') else null

        fun nameOf(relative: String): String = relative.substringAfterLast('/')

        /** Collapses "//", "." and ".."; null if ".." climbs above "/". */
        fun normalize(absolute: String): String? {
            val out = ArrayDeque<String>()
            for (part in absolute.split('/')) {
                when (part) {
                    "", "." -> Unit
                    ".." -> if (out.isEmpty()) return null else out.removeLast()
                    else -> out.addLast(part)
                }
            }
            return "/" + out.joinToString("/")
        }
    }
}
