package tech.anl.library.companion

/**
 * The companions paste username/password/vncPassword/geometry into single-quoted guest shell
 * commands without escaping (e.g. `echo '<user>:<pass>' | chpasswd`), so anything that could
 * break out of the quotes must be rejected before it is sent.
 */
object CompanionInputValidator {
    private val GEOMETRY = Regex("""\d{2,5}x\d{2,5}""")
    // POSIX portable-ish user names; what useradd accepts without --badname on most distros.
    private val USERNAME = Regex("""[a-z_][a-z0-9_-]{0,31}""")
    private const val MAX_SECRET_LENGTH = 128

    sealed class Result {
        object Valid : Result()
        data class Invalid(val field: String, val reason: String) : Result() {
            val message: String get() = "${this.field} $reason"
        }
    }

    fun hasForbiddenCharacters(value: String): Boolean =
        value.any { it == '\'' || it == '\n' || it == '\r' || it == '\u0000' }

    fun isValidGeometry(geometry: String): Boolean = GEOMETRY.matches(geometry)

    fun validate(username: String, password: String, vncPassword: String, geometry: String): Result {
        if (username.isEmpty()) return Result.Invalid("Username", "must not be empty")
        if (hasForbiddenCharacters(username) || !USERNAME.matches(username)) {
            return Result.Invalid(
                "Username",
                "may only contain lowercase letters, digits, '_' and '-', and must start with a letter or '_'"
            )
        }
        listOf("Password" to password, "VNC password" to vncPassword).forEach { (name, value) ->
            if (hasForbiddenCharacters(value)) {
                return Result.Invalid(name, "must not contain single quotes, line breaks or NUL characters")
            }
            if (value.length > MAX_SECRET_LENGTH) return Result.Invalid(name, "is too long")
        }
        if (!isValidGeometry(geometry)) {
            return Result.Invalid("Screen size", "must look like 1280x720 (got \"${geometry.take(20)}\")")
        }
        return Result.Valid
    }
}
