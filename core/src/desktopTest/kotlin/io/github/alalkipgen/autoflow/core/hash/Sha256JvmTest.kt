package io.github.alalkipgen.autoflow.core.hash

import java.security.MessageDigest
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals

/** Cross-checks the pure-Kotlin SHA-256 against the JDK around every padding boundary. */
class Sha256JvmTest {
    @Test
    fun matchesJdk() {
        val random = Random(42)
        for (size in (0..200) + listOf(1_000, 4_096, 65_537)) {
            val bytes = random.nextBytes(size)
            assertContentEquals(MessageDigest.getInstance("SHA-256").digest(bytes), Sha256.digest(bytes), "size $size")
        }
    }
}
