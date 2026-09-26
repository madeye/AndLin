package tech.anl.vnc.rfb

import org.junit.Assert.assertArrayEquals
import org.junit.Test
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

class DesTest {
    private fun hex(s: String): ByteArray = s.chunked(2).map { it.toInt(16).toByte() }.toByteArray()

    private val challenge = hex("00112233445566778899aabbccddeeff")

    @Test
    fun classicDesVector() {
        // FIPS-style textbook vector, confirmed with `openssl enc -des-ecb`.
        val out = Des(hex("133457799BBCDFF1")).encryptEcb(hex("0123456789ABCDEF"))
        assertArrayEquals(hex("85e813540f0ab405"), out)
    }

    @Test
    fun vncKeyBitReversal() {
        assertArrayEquals(hex("0e86ceceeef64e26"), VncAuth.keyFromPassword("password"))
        assertArrayEquals(hex("ae36860000000000"), VncAuth.keyFromPassword("ula"))
        // Only the first 8 characters count.
        assertArrayEquals(hex("8c4ccc2cac6cec1c"), VncAuth.keyFromPassword("12345678abc"))
    }

    @Test
    fun vncResponsesMatchOpenSsl() {
        // Expected values: openssl enc -des-ecb -provider legacy -K <reversed key> -nopad
        assertArrayEquals(hex("b7b9c87777661a7a2299733209bfdfce"), VncAuth.response("password", challenge))
        assertArrayEquals(hex("8900ab918725f96bcc08d6055a6db4ed"), VncAuth.response("ula", challenge))
        assertArrayEquals(hex("8b9603ea1b649430d595c4247bd2bb95"), VncAuth.response("12345678abc", challenge))
        assertArrayEquals(hex("74f4ae777aa431e89ca4757b2414f4d7"), VncAuth.response("", challenge))
    }

    @Test
    fun matchesJcaDesOnRandomInputs() {
        val rnd = java.util.Random(42)
        repeat(200) {
            val key = ByteArray(8).also { rnd.nextBytes(it) }
            val data = ByteArray(16).also { rnd.nextBytes(it) }
            val jca = Cipher.getInstance("DES/ECB/NoPadding")
            jca.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "DES"))
            assertArrayEquals(jca.doFinal(data), Des(key).encryptEcb(data))
        }
    }
}
