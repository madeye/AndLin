package tech.anl.library.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultCredentialsTest {
    private val validator = CredentialValidator()

    @Test
    fun `the default username is valid`() {
        assertTrue(validator.validateUsername(DefaultCredentials.USERNAME, arrayOf("root", "user")).credentialIsValid)
    }

    @Test
    fun `random passwords are 6 unambiguous characters, valid as login and VNC passwords`() {
        val passwords = (1..500).map { DefaultCredentials.randomPassword() }
        passwords.forEach { password ->
            assertEquals(6, password.length)
            assertTrue(password, password.all { it in 'a'..'z' || it in '2'..'9' })
            assertTrue(password, password.none { it in "01oli" })
            assertTrue(validator.validatePassword(password).credentialIsValid)
            assertTrue(validator.validateVncPassword(password).credentialIsValid)
        }
        assertTrue("passwords vary", passwords.toSet().size > 450)
    }
}
