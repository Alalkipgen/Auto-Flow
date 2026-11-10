package io.github.alalkipgen.autoflow.ai

import io.github.alalkipgen.autoflow.ai.codec.Png
import io.github.alalkipgen.autoflow.ai.fake.FakeImageProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTtsProvider
import io.github.alalkipgen.autoflow.core.model.Ratio
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import javax.sound.sampled.AudioSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

/** The pure-Kotlin encoders must produce files that standard JDK decoders accept. */
class FakeMediaDecodeTest {
    @Test
    fun imageIoDecodesFakePng() = runTest {
        val bytes = (FakeImageProvider().image("Shot 4", Ratio.R9_16) as AiResult.Ok).value
        val img = ImageIO.read(ByteArrayInputStream(bytes))
        assertEquals(180 to 320, img.width to img.height)
        val bg = img.getRGB(0, 0)
        val distinct = (0 until img.height).flatMap { y -> (0 until img.width).map { x -> img.getRGB(x, y) } }.toSet()
        assertEquals(2, distinct.size, "background + digit colour")
        assertTrue(bg in distinct)
    }

    @Test
    fun imageIoDecodesMultiBlockPng() {
        // 400×200 RGB = 240 200 raw bytes → four stored deflate blocks.
        val pixels = IntArray(400 * 200) { i -> (i % 400 * 255 / 399) shl 16 or (i / 400) }
        val img = ImageIO.read(ByteArrayInputStream(Png.encodeRgb(400, 200, pixels)))
        assertEquals(0xFF000000.toInt() or pixels[123 * 400 + 321], img.getRGB(321, 123))
    }

    @Test
    fun javaSoundReadsFakeWav() = runTest {
        val bytes = (FakeTtsProvider().speak("hello world", VoiceRef("v"), "en") as AiResult.Ok).value
        AudioSystem.getAudioInputStream(ByteArrayInputStream(bytes)).use { s ->
            assertEquals(24_000f, s.format.sampleRate)
            assertEquals(16, s.format.sampleSizeInBits)
            assertEquals(1, s.format.channels)
            assertEquals(24_000L * 660 / 1000, s.frameLength) // 11 chars × 60 ms
        }
    }
}
