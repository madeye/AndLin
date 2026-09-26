package tech.anl.library.utils

import java.security.SecureRandom
import java.util.Random

/** Suggested login for a new filesystem: the user can change both before continuing. */
object DefaultCredentials {
    const val USERNAME = "serverbox"
    const val PASSWORD_LENGTH = 6

    // Lowercase letters and digits minus look-alikes (0/o, 1/l/i), so it can be read off the
    // Sessions tab and typed on another device. 6 characters also suits VNC's 6-8 limit.
    private const val ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789"

    fun randomPassword(random: Random = SecureRandom()): String =
        (1..PASSWORD_LENGTH).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
}
