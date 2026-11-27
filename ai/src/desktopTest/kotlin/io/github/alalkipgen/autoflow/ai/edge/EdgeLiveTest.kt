package io.github.alalkipgen.autoflow.ai.edge

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.codec.Wav
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking

/** Real Edge call (no key). Skipped unless `-PliveAi=true`; needs `ffmpeg` on PATH to read the mp3. */
class EdgeLiveTest {
    @Test
    fun burmeseVoicesSpeak() {
        if (System.getProperty("autoflow.liveAi") != "true") {
            println("EdgeLiveTest skipped (run with -PliveAi=true)")
            return
        }
        val config = assertIs<AiResult.Ok<ModelConfig>>(ModelConfig.parse(File("../config/models.json").readText())).value.tts.edge
        val socket = KtorEdgeSocket()
        val provider = EdgeTtsProvider(config, socket, ::ffmpegWav)
        config.voicesFor("my").forEach { v ->
            val r = runBlocking { provider.speak("မင်္ဂလာပါ။ ဒါက Auto Flow အသံ စမ်းသပ်မှု ဖြစ်ပါတယ်။", VoiceRef(v.id), "my") }
            val wav = assertIs<AiResult.Ok<ByteArray>>(r, "${v.id}: $r").value
            assertNotNull(Wav.parse(wav), v.id)
            println("EdgeLiveTest ${v.id}: ${wav.size} bytes")
        }
        socket.close()
    }

    private fun ffmpegWav(mp3: ByteArray): ByteArray? {
        val dir = kotlin.io.path.createTempDirectory("edge").toFile()
        val input = File(dir, "a.mp3").apply { writeBytes(mp3) }
        val out = File(dir, "a.wav")
        val p = ProcessBuilder("ffmpeg", "-hide_banner", "-nostdin", "-y", "-i", input.path, "-ac", "1", "-c:a", "pcm_s16le", out.path).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        return if (p.waitFor() == 0) out.readBytes().also { dir.deleteRecursively() } else null
    }
}
