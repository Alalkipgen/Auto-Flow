package io.github.alalkipgen.autoflow.ai.edge

/**
 * The rotating `Sec-MS-GEC` value the Edge "Read aloud" endpoint wants on every connection (Q-45 → (a), D-90): the
 * Windows file time of now, rounded down to 5 minutes, plus the trusted client token, hashed into
 * `<16 hex CS64>-<64 hex SHA-256 variant>`. A straight port of edge-tts 7.3 `drm.py` (`generate_sec_ms_gec_v2`); the
 * constants are Microsoft's, not settings. Unofficial: when Microsoft changes it again, calls fail with `http_403`
 * as a normal provider error, and Gemini TTS stays available.
 */
internal object EdgeToken {
    private const val WIN_EPOCH = 11_644_473_600L
    private const val M = 0xFFFFFFFFL

    /** [unixSeconds] = the device clock in seconds; [clientToken] from `config/models.json` `tts.edge`. */
    fun secMsGec(unixSeconds: Long, clientToken: String): String {
        var s = unixSeconds + WIN_EPOCH
        s -= s % 300
        val data = "${s * 10_000_000L}$clientToken".encodeToByteArray()
        val digest = sha256(data)
        val (lo, hi) = cs64(data, digest)
        return hexLe(lo) + hexLe(hi) + "-" + digest.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }.uppercase()
    }

    private fun hexLe(w: Long): String = (0 until 4).joinToString("") { ((w ushr (8 * it)) and 0xFF).toString(16).padStart(2, '0') }.uppercase()

    private val INITIAL = longArrayOf(0x6A09E667, 0xBB67AE85, 0x3C6EF372, 0xA54FF53A, 0x510E527F, 0x9B05688C, 0x1F83D9AB, 0x5BE0CD19)
    private val STATE = longArrayOf(0xFD9FBEA2, 0x91243CE7, 0xBE499094, 0xCD2E1E0A, 0x6C006F49, 0x04BD35AB, 0xFEB0A7E4, 0x03EFFBD2)
    private val SCHEDULE = longArrayOf(
        0xEB24BF9E, 0x1558691F, 0xA0C09EE2, 0x0CFA32BA, 0xA622EE8C, 0xD1DD9491, 0xC0DD25A0,
        0xF9E70AB6, 0xF0C31573, 0x7058CE4E, 0x7188E606, 0x00E5AB33, 0xD258B060, 0x72766564,
    )
    private val K = longArrayOf(
        0x428A2F98, 0x71374491, 0xB5C0FBCF, 0xE9B5DBA5, 0x3956C25B, 0x59F111F1, 0x923F82A4, 0xAB1C5ED5,
        0xD807AA98, 0x12835B01, 0x243185BE, 0x550C7DC3, 0x72BE5D74, 0x80DEB1FE, 0x9BDC06A7, 0xC19BF174,
        0xE49B69C1, 0xEFBE4786, 0x0FC19DC6, 0x240CA1CC, 0x2DE92C6F, 0x4A7484AA, 0x5CB0A9DC, 0x76F988DA,
        0x983E5152, 0xA831C66D, 0xB00327C8, 0xBF597FC7, 0xC6E00BF3, 0xD5A79147, 0x06CA6351, 0x14292967,
        0x27B70A85, 0x2E1B2138, 0x4D2C6DFC, 0x53380D13, 0x650A7354, 0x766A0ABB, 0x81C2C92E, 0x92722C85,
        0xA2BFE8A1, 0xA81A664B, 0xC24B8B70, 0xC76C51A3, 0xD192E819, 0xD6990624, 0xF40E3585, 0x106AA070,
        0x19A4C116, 0x1E376C08, 0x2748774C, 0x34B0BCB5, 0x391C0CB3, 0x4ED8AA4A, 0x5B9CCA4F, 0x682E6FF3,
        0x748F82EE, 0x78A5636F, 0x84C87814, 0x8CC70208, 0x90BEFFFA, 0xA4506CEB, 0xBEF9A3F7, 0xC67178F2,
    )
    private const val ADJ = 0xC9DC06A7L
    private val BLOCK_K = K.copyOf().also { it[14] = ADJ }
    private const val SIGMA0_ADJ = 0x059C0B80L
    private const val SIGMA1_ADJ = 0x000B92C0L
    private val CS64 = arrayOf(
        0xE82E91B1L to 0xB32700D0L, 0x44DE24C6L to 0x88DFF51DL, 0x3BEB65BCL to 0x29514038L, 0xC89C5933L to 0xB328CBDFL,
        0x8D1EC211L to 0xD405B4D5L, 0x314EF5D5L to 0x26E55F3CL, 0xE92491B1L to 0x607801C6L, 0xF0A5B9D5L to 0x82B2C1FBL,
        0x06064CF3L to 0xFA51A3DAL, 0xDA79FA60L to 0x207CC464L, 0xBED20560L to 0x3C3C74F3L, 0x89F9C9F8L to 0xD2D14B61L,
        0x35FA38C6L to 0xBB04A2C1L, 0xCB49E3C6L to 0xC5626167L,
    )
    private const val CS64_FACTOR = 0x82893933L
    private const val CS64_ADJ0 = 0xF2F90B1AL
    private const val CS64_ADJ1 = 0x0B1A0000L

    private fun rotr(v: Long, n: Int): Long = ((v ushr n) or (v shl (32 - n))) and M
    private fun rol16(v: Long): Long = (((v and M) shl 16) or ((v and M) ushr 16)) and M
    private fun sigma0(v: Long): Long = ((v ushr 7) xor (v shl 25) xor (v ushr 18) xor (v shl 14) xor (v ushr 3)) and M
    private fun sigma1(v: Long): Long = ((v ushr 17) xor (v shl 15) xor (v ushr 19) xor (v shl 13) xor (v ushr 10)) and M

    private fun expand(w: MutableList<Long>, first: Int = 16) {
        for (i in first until 64) w.add((sigma1(w[i - 2]) + w[i - 7] + sigma0(w[i - 15]) + w[i - 16]) and M)
    }

    private fun rounds(state: LongArray, w: List<Long>, first: Int = 0): LongArray {
        val k = if (first == 14) BLOCK_K else K
        var (a, b, c, d) = state.copyOfRange(0, 4)
        var (e, f, g, h) = state.copyOfRange(4, 8)
        for (i in first until 64) {
            val t1 = (h + (rotr(e, 6) xor rotr(e, 11) xor rotr(e, 25)) + (g xor (e and (f xor g))) + k[i] + w[i]) and M
            val t2 = ((rotr(a, 2) xor rotr(a, 13) xor rotr(a, 22)) + ((a and b) or (c and (a or b)))) and M
            h = g; g = f; f = e; e = (d + t1) and M; d = c; c = b; b = a; a = (t1 + t2) and M
        }
        return longArrayOf(a, b, c, d, e, f, g, h)
    }

    private fun be(b: ByteArray, at: Int, n: Int): Long = (0 until n).fold(0L) { acc, i -> (acc shl 8) or (b[at + i].toLong() and 0xFF) }
    private fun le(b: ByteArray, at: Int): Long = (0 until 4).fold(0L) { acc, i -> acc or ((b[at + i].toLong() and 0xFF) shl (8 * i)) }

    private fun init(d7: ByteArray): LongArray {
        val w = MutableList(14) { 0L }
        w.add(be(d7, 0, 3)); w.add(be(d7, 3, 4))
        for (i in 16 until 30) {
            var v = SCHEDULE[i - 16]
            if (i - 2 >= 14) v += if (i == 16) sigma1(w[i - 2]) xor SIGMA1_ADJ else sigma1(w[i - 2])
            if (i - 7 >= 14) v += w[i - 7]
            if (i - 15 >= 14) v += if (i == 29) sigma0(w[i - 15]) xor SIGMA0_ADJ else sigma0(w[i - 15])
            w.add(v and M)
        }
        w.add((sigma1(w[28]) + w[23] + sigma0(w[15]) + w[14] + ADJ - K[14]) and M)
        expand(w, 31)
        val r = rounds(STATE, w, 14)
        return LongArray(8) { (INITIAL[it] + r[it]) and M }
    }

    /** SHA-256 of Microsoft's 57-byte secret prefix + [data] (at least 7 bytes), the prefix state precomputed. */
    internal fun sha256(data: ByteArray): ByteArray {
        require(data.size >= 7) { "Token salt must contain at least seven bytes" }
        val length = 57L + data.size
        val zeros = (((55 - length) % 64) + 64) % 64
        val padded = data.copyOfRange(7, data.size) + byteArrayOf(0x80.toByte()) + ByteArray(zeros.toInt()) +
            ByteArray(8) { ((length * 8) ushr (8 * (7 - it))).toByte() }
        var state = init(data.copyOfRange(0, 7))
        for (off in padded.indices step 64) {
            val w = MutableList(16) { be(padded, off + 4 * it, 4) }
            expand(w)
            val r = rounds(state, w)
            state = LongArray(8) { (state[it] + r[it]) and M }
        }
        return ByteArray(32) { (state[it / 4] ushr (8 * (3 - it % 4))).toByte() }
    }

    internal fun cs64(data: ByteArray, digest: ByteArray): Pair<Long, Long> {
        val ka = le(digest, 0) or 1
        val kb = le(digest, 4) or 1
        val factors = longArrayOf((ka * CS64_FACTOR) and M, (kb * CS64_FACTOR) and M)
        val terms = CS64.mapIndexed { i, (coef, _) -> (coef * factors[i % 2]) and M }.toMutableList()
        val offsets = CS64.map { it.second }.toMutableList()
        val src = byteArrayOf(0) + data
        val padded = src + ByteArray((4 - src.size % 4) % 4)
        val words = MutableList(padded.size / 4) { le(padded, 4 * it) }
        if (words.size % 2 == 1) words.add(0L)
        words.forEachIndexed { i, word ->
            var term = word * (if (i % 2 == 0) ka else kb)
            var offset = rol16(word) * (if (i % 2 == 0) 0xEF0569FBL else 0xC31713DBL)
            if (i == 0) { term += CS64_ADJ0 * factors[0]; offset += CS64_ADJ1 }
            terms.add(term and M); offsets.add(offset and M)
        }
        var swapAcc = 0L; var swapLast = 0L; var state = 0L; var acc = 0L
        for (i in terms.indices step 2) {
            val x = terms[i]; val y = terms[i + 1]
            var t = rol16((offsets[i] + x) and M)
            var q = (t * 0x76F50F34L) and M
            swapAcc += (rol16(q) * 0xC3EFEA97L + q) and M
            t = rol16((offsets[i + 1] + y) and M)
            q = (t * 0x1881CE3CL) and M
            swapLast = (rol16(q) * 0x35BD1EC9L + q) and M
            swapAcc = (swapAcc + swapLast) and M
            state = (state * ka + x) and M
            state = (rol16(state) * 0xCF98B111L) and M
            state = (rol16(state) * 0x87085B9FL) and M
            state = (rol16(state) * 0x12CEB96DL) and M
            state = (rol16(state) * 0x257E1D83L) and M
            acc += state
            t = (state * kb + y) and M
            t = (rol16(t) * 0xA27416F5L) and M
            t = (rol16(t) * 0xD38396FFL) and M
            state = (rol16((rol16(t) * 0x7C932B89L) and M) * 0xBFA49F69L) and M
            acc = (acc + state) and M
        }
        return (swapLast xor state) to (swapAcc xor acc)
    }
}
