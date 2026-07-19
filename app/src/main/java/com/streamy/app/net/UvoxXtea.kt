package com.streamy.app.net

/**
 * XTEA encipher used by the SHOUTcast 2 / Ultravox 2.1 auth handshake (message 0x1001).
 *
 * The server's cipher key (returned by 0x1009) is the obfuscation key; DNAS2 currently returns
 * the fixed string "foobar". The UID + AuthBlob fields are XTEA-enciphered, then emitted as
 * 8-hex-digit (32-bit) words so the ':'-separated auth payload never collides with a colon.
 * Ported verbatim (big-endian, 32 rounds, zero-padded key/data) from the protocol spec's C++.
 *
 * Pure — unit-tested against golden vectors captured from the reference implementation.
 */
object UvoxXtea {
    private const val MASK = 0xFFFFFFFFL
    private const val DELTA = 0x9E3779B9L

    fun encipher(data: ByteArray, key: ByteArray): String {
        val k = padKey(key)
        val padded: ByteArray =
            if (data.size % 8 == 0) data else data + ByteArray(8 - data.size % 8) { 0 }
        val sb = StringBuilder()
        var i = 0
        while (i < padded.size) {
            var v0 = u32(padded, i)
            var v1 = u32(padded, i + 4)
            var sum = 0L
            repeat(32) {
                v0 = (v0 + ((((shl(v1, 4) xor ushr(v1, 5)) + v1) xor (sum + k[(sum and 3).toInt()])) and MASK)) and MASK
                sum = (sum + DELTA) and MASK
                v1 = (v1 + ((((shl(v0, 4) xor ushr(v0, 5)) + v0) xor (sum + k[(ushru(sum, 11)).toInt()])) and MASK)) and MASK
            }
            sb.append("%08x".format(v0)).append("%08x".format(v1))
            i += 8
        }
        return sb.toString()
    }

    private fun padKey(key: ByteArray): LongArray {
        val k = if (key.size >= 16) key.copyOf(16) else key + ByteArray(16 - key.size) { 0 }
        return LongArray(4) { u32(k, it * 4) }
    }

    private fun u32(b: ByteArray, off: Int): Long =
        ((b[off].toLong() and 0xFF) shl 24) or
            ((b[off + 1].toLong() and 0xFF) shl 16) or
            ((b[off + 2].toLong() and 0xFF) shl 8) or
            (b[off + 3].toLong() and 0xFF)

    private fun shl(v: Long, n: Int): Long = (v shl n) and MASK
    private fun ushr(v: Long, n: Int): Long = v ushr n
    private fun ushru(v: Long, n: Int): Long = (v ushr n) and 3
}
