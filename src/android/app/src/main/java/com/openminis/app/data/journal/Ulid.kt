package com.openminis.app.data.journal

import java.security.SecureRandom

/**
 * [T-today-tab] ULID (https://github.com/ulid/spec): 48-bit millisecond time
 * + 80 random bits, Crockford base32, 26 chars. Sorts by creation time as a
 * plain string, which is what journal entry ids need (docs/design/today/SPEC.md §4.4).
 */
object Ulid {
    private const val ALPHABET = "0123456789ABCDEFGHJKMNPQRSTVWXYZ"
    private val random = SecureRandom()

    fun generate(timeMs: Long = System.currentTimeMillis()): String {
        val out = CharArray(26)
        var t = timeMs
        for (i in 9 downTo 0) {
            out[i] = ALPHABET[(t and 31).toInt()]
            t = t ushr 5
        }
        val bytes = ByteArray(10).also(random::nextBytes)
        // 80 random bits → 16 base32 chars, 5 bits at a time.
        var acc = 0L
        var bits = 0
        var idx = 10
        for (b in bytes) {
            acc = (acc shl 8) or (b.toLong() and 0xFF)
            bits += 8
            while (bits >= 5) {
                bits -= 5
                out[idx++] = ALPHABET[((acc ushr bits) and 31).toInt()]
            }
        }
        return String(out)
    }

    fun isValid(id: String): Boolean =
        id.length == 26 && id.all { it in ALPHABET } && id[0] <= '7'
}
