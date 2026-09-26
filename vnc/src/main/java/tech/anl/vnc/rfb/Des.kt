package tech.anl.vnc.rfb

/**
 * A small, self-contained DES block cipher (encryption only), used for VNC
 * Authentication. Implemented here rather than via javax.crypto because DES
 * provider availability varies across Android releases.
 */
class Des(key: ByteArray) {
    private val subKeys = LongArray(16)

    init {
        require(key.size == 8) { "DES key must be 8 bytes" }
        val k56 = permute(toLong(key, 0), PC1, 64)
        var c = (k56 ushr 28) and MASK28
        var d = k56 and MASK28
        for (round in 0 until 16) {
            val s = SHIFTS[round]
            c = ((c shl s) or (c ushr (28 - s))) and MASK28
            d = ((d shl s) or (d ushr (28 - s))) and MASK28
            subKeys[round] = permute((c shl 28) or d, PC2, 56)
        }
    }

    /** Encrypts one 8-byte block of [input] at [inOff] into [output] at [outOff]. */
    fun encryptBlock(input: ByteArray, inOff: Int, output: ByteArray, outOff: Int) {
        val ip = permute(toLong(input, inOff), IP, 64)
        var l = ip ushr 32
        var r = ip and MASK32
        for (round in 0 until 16) {
            val next = l xor feistel(r, subKeys[round])
            l = r
            r = next
        }
        val out = permute((r shl 32) or l, FP, 64)
        for (i in 0 until 8) {
            output[outOff + i] = (out ushr (56 - 8 * i)).toByte()
        }
    }

    /** Encrypts [data] (a multiple of 8 bytes) in ECB mode. */
    fun encryptEcb(data: ByteArray): ByteArray {
        require(data.size % 8 == 0) { "ECB input must be a multiple of 8 bytes" }
        val out = ByteArray(data.size)
        var off = 0
        while (off < data.size) {
            encryptBlock(data, off, out, off)
            off += 8
        }
        return out
    }

    private fun feistel(r: Long, k: Long): Long {
        val e = permute(r, E, 32) xor k
        var out = 0L
        for (i in 0 until 8) {
            val six = ((e ushr (42 - 6 * i)) and 0x3F).toInt()
            val row = ((six and 0x20) ushr 4) or (six and 1)
            val col = (six ushr 1) and 0xF
            out = (out shl 4) or SBOX[i][row * 16 + col].toLong()
        }
        return permute(out, P, 32)
    }

    companion object {
        private const val MASK28 = 0xFFFFFFFL
        private const val MASK32 = 0xFFFFFFFFL

        private fun toLong(b: ByteArray, off: Int): Long {
            var v = 0L
            for (i in 0 until 8) v = (v shl 8) or (b[off + i].toLong() and 0xFF)
            return v
        }

        private fun permute(src: Long, table: IntArray, srcBits: Int): Long {
            var out = 0L
            for (pos in table) {
                out = (out shl 1) or ((src ushr (srcBits - pos)) and 1L)
            }
            return out
        }

        private val IP = intArrayOf(
            58, 50, 42, 34, 26, 18, 10, 2, 60, 52, 44, 36, 28, 20, 12, 4,
            62, 54, 46, 38, 30, 22, 14, 6, 64, 56, 48, 40, 32, 24, 16, 8,
            57, 49, 41, 33, 25, 17, 9, 1, 59, 51, 43, 35, 27, 19, 11, 3,
            61, 53, 45, 37, 29, 21, 13, 5, 63, 55, 47, 39, 31, 23, 15, 7
        )
        private val FP = intArrayOf(
            40, 8, 48, 16, 56, 24, 64, 32, 39, 7, 47, 15, 55, 23, 63, 31,
            38, 6, 46, 14, 54, 22, 62, 30, 37, 5, 45, 13, 53, 21, 61, 29,
            36, 4, 44, 12, 52, 20, 60, 28, 35, 3, 43, 11, 51, 19, 59, 27,
            34, 2, 42, 10, 50, 18, 58, 26, 33, 1, 41, 9, 49, 17, 57, 25
        )
        private val E = intArrayOf(
            32, 1, 2, 3, 4, 5, 4, 5, 6, 7, 8, 9, 8, 9, 10, 11,
            12, 13, 12, 13, 14, 15, 16, 17, 16, 17, 18, 19, 20, 21, 20, 21,
            22, 23, 24, 25, 24, 25, 26, 27, 28, 29, 28, 29, 30, 31, 32, 1
        )
        private val P = intArrayOf(
            16, 7, 20, 21, 29, 12, 28, 17, 1, 15, 23, 26, 5, 18, 31, 10,
            2, 8, 24, 14, 32, 27, 3, 9, 19, 13, 30, 6, 22, 11, 4, 25
        )
        private val PC1 = intArrayOf(
            57, 49, 41, 33, 25, 17, 9, 1, 58, 50, 42, 34, 26, 18,
            10, 2, 59, 51, 43, 35, 27, 19, 11, 3, 60, 52, 44, 36,
            63, 55, 47, 39, 31, 23, 15, 7, 62, 54, 46, 38, 30, 22,
            14, 6, 61, 53, 45, 37, 29, 21, 13, 5, 28, 20, 12, 4
        )
        private val PC2 = intArrayOf(
            14, 17, 11, 24, 1, 5, 3, 28, 15, 6, 21, 10,
            23, 19, 12, 4, 26, 8, 16, 7, 27, 20, 13, 2,
            41, 52, 31, 37, 47, 55, 30, 40, 51, 45, 33, 48,
            44, 49, 39, 56, 34, 53, 46, 42, 50, 36, 29, 32
        )
        private val SHIFTS = intArrayOf(1, 1, 2, 2, 2, 2, 2, 2, 1, 2, 2, 2, 2, 2, 2, 1)
        private val SBOX = arrayOf(
            intArrayOf(
                14, 4, 13, 1, 2, 15, 11, 8, 3, 10, 6, 12, 5, 9, 0, 7,
                0, 15, 7, 4, 14, 2, 13, 1, 10, 6, 12, 11, 9, 5, 3, 8,
                4, 1, 14, 8, 13, 6, 2, 11, 15, 12, 9, 7, 3, 10, 5, 0,
                15, 12, 8, 2, 4, 9, 1, 7, 5, 11, 3, 14, 10, 0, 6, 13
            ),
            intArrayOf(
                15, 1, 8, 14, 6, 11, 3, 4, 9, 7, 2, 13, 12, 0, 5, 10,
                3, 13, 4, 7, 15, 2, 8, 14, 12, 0, 1, 10, 6, 9, 11, 5,
                0, 14, 7, 11, 10, 4, 13, 1, 5, 8, 12, 6, 9, 3, 2, 15,
                13, 8, 10, 1, 3, 15, 4, 2, 11, 6, 7, 12, 0, 5, 14, 9
            ),
            intArrayOf(
                10, 0, 9, 14, 6, 3, 15, 5, 1, 13, 12, 7, 11, 4, 2, 8,
                13, 7, 0, 9, 3, 4, 6, 10, 2, 8, 5, 14, 12, 11, 15, 1,
                13, 6, 4, 9, 8, 15, 3, 0, 11, 1, 2, 12, 5, 10, 14, 7,
                1, 10, 13, 0, 6, 9, 8, 7, 4, 15, 14, 3, 11, 5, 2, 12
            ),
            intArrayOf(
                7, 13, 14, 3, 0, 6, 9, 10, 1, 2, 8, 5, 11, 12, 4, 15,
                13, 8, 11, 5, 6, 15, 0, 3, 4, 7, 2, 12, 1, 10, 14, 9,
                10, 6, 9, 0, 12, 11, 7, 13, 15, 1, 3, 14, 5, 2, 8, 4,
                3, 15, 0, 6, 10, 1, 13, 8, 9, 4, 5, 11, 12, 7, 2, 14
            ),
            intArrayOf(
                2, 12, 4, 1, 7, 10, 11, 6, 8, 5, 3, 15, 13, 0, 14, 9,
                14, 11, 2, 12, 4, 7, 13, 1, 5, 0, 15, 10, 3, 9, 8, 6,
                4, 2, 1, 11, 10, 13, 7, 8, 15, 9, 12, 5, 6, 3, 0, 14,
                11, 8, 12, 7, 1, 14, 2, 13, 6, 15, 0, 9, 10, 4, 5, 3
            ),
            intArrayOf(
                12, 1, 10, 15, 9, 2, 6, 8, 0, 13, 3, 4, 14, 7, 5, 11,
                10, 15, 4, 2, 7, 12, 9, 5, 6, 1, 13, 14, 0, 11, 3, 8,
                9, 14, 15, 5, 2, 8, 12, 3, 7, 0, 4, 10, 1, 13, 11, 6,
                4, 3, 2, 12, 9, 5, 15, 10, 11, 14, 1, 7, 6, 0, 8, 13
            ),
            intArrayOf(
                4, 11, 2, 14, 15, 0, 8, 13, 3, 12, 9, 7, 5, 10, 6, 1,
                13, 0, 11, 7, 4, 9, 1, 10, 14, 3, 5, 12, 2, 15, 8, 6,
                1, 4, 11, 13, 12, 3, 7, 14, 10, 15, 6, 8, 0, 5, 9, 2,
                6, 11, 13, 8, 1, 4, 10, 7, 9, 5, 0, 15, 14, 2, 3, 12
            ),
            intArrayOf(
                13, 2, 8, 4, 6, 15, 11, 1, 10, 9, 3, 14, 5, 0, 12, 7,
                1, 15, 13, 8, 10, 3, 7, 4, 12, 5, 6, 11, 0, 14, 9, 2,
                7, 11, 4, 1, 9, 12, 14, 2, 0, 6, 10, 13, 15, 3, 5, 8,
                2, 1, 14, 7, 4, 10, 8, 13, 15, 12, 9, 0, 3, 5, 6, 11
            )
        )
    }
}

/** VNC Authentication (security type 2) helpers. */
object VncAuth {
    /**
     * Builds the DES key VNC uses from a password: the first 8 bytes (Latin-1),
     * zero padded, with the bits of every byte mirrored.
     */
    fun keyFromPassword(password: String): ByteArray {
        val key = ByteArray(8)
        val pw = password.toByteArray(Charsets.ISO_8859_1)
        for (i in 0 until minOf(8, pw.size)) key[i] = reverseBits(pw[i])
        return key
    }

    /** Encrypts the 16-byte server challenge with [password]. */
    fun response(password: String, challenge: ByteArray): ByteArray {
        require(challenge.size == 16) { "VNC challenge must be 16 bytes" }
        return Des(keyFromPassword(password)).encryptEcb(challenge)
    }

    private fun reverseBits(b: Byte): Byte {
        var v = b.toInt() and 0xFF
        var r = 0
        for (i in 0 until 8) {
            r = (r shl 1) or (v and 1)
            v = v ushr 1
        }
        return r.toByte()
    }
}
