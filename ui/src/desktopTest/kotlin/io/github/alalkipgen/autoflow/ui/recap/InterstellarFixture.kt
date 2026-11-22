package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.platform.JvmFs
import java.awt.Color
import java.awt.Font
import java.awt.GradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import javax.imageio.ImageIO

/**
 * The Group 2 sample's demo project (A `src/gen6.js`): "Interstellar", Interstellar.2014.mkv (2:49:03, 16:9), 12 approved
 * blocks of Burmese narration, output 4:12 with block starts at the sample's ticks, voice lock Gemini · Kore, Style layers
 * 1 Image (logo) · 2 Text · 3 Border, burned captions. The movie is made offline by ffmpeg from the sample's eight demo
 * pictures (`recap-demo/<scene>.jpg.b64`, base64 like the bundled fonts, one per scene range); screenshots only, no provider calls.
 */
internal object InterstellarFixture {
    data class Built(val dir: File, val project: Project, val story: RecapStory, val sceneAt: (Long) -> String)

    const val MOVIE_MS = 10_143_000L
    val SCENES = listOf("inter", "corn", "dust", "rocket", "ocean", "ice", "ship", "tess")

    /** n · scene picture · clips (movie ms) · status (FITS, TIMING = 1.4 s too long, NO_VOICE, SCRIPT_CHANGED, OTHER_VOICE) · text. */
    private class Demo(val scene: String, val clips: List<Pair<Long, Long>>, val status: String, val text: String)
    private fun at(h: Int, m: Int, s: Int) = ((h * 3600L) + m * 60L + s) * 1000L
    private val BLOCKS = listOf(
        Demo("corn", listOf(at(0, 0, 42) to at(0, 0, 51), at(0, 3, 10) to at(0, 3, 10) + 8_640), "FITS",
            "ကမ္ဘာမြေမှာ အစာ ရှားပါးလာပြီ၊ ဖုန်မုန်တိုင်းတွေက နေ့တိုင်း တိုက်ခတ်နေတယ်။"),
        Demo("dust", listOf(at(0, 12, 40) to at(0, 12, 40) + 22_680), "FITS",
            "Cooper နဲ့ သမီး Murph က စာကြည့်ခန်းထဲက ထူးဆန်းတဲ့ အမှတ်အသားတွေကို တွေ့တယ်။"),
        Demo("rocket", listOf(at(0, 31, 5) to at(0, 31, 5) + 18_760), "TIMING",
            "NASA ရဲ့ လျှို့ဝှက် အခြေစိုက်စခန်းမှာ Cooper က နောက်ဆုံး ခရီးကို လက်ခံလိုက်တယ်။"),
        Demo("ship", listOf(at(0, 38, 44) to at(0, 38, 55), at(0, 41, 10) to at(0, 41, 10) + 11_680), "SCRIPT_CHANGED",
            "Endurance ယာဉ်က Saturn ဂြိုဟ်နားက wormhole ဆီ ထွက်ခွာသွားတယ်။"),
        Demo("ocean", listOf(at(0, 58, 2) to at(0, 58, 2) + 20_160), "NO_VOICE",
            "ရေဂြိုဟ်ပေါ်မှာ တစ်နာရီဟာ ကမ္ဘာမြေရဲ့ ခုနစ်နှစ်နဲ့ ညီမျှတယ်။"),
        Demo("ice", listOf(at(1, 24, 10) to at(1, 24, 10) + 22_680), "OTHER_VOICE",
            "Dr. Mann ရဲ့ ရေခဲဂြိုဟ်က သူတို့ မျှော်လင့်ထားသလို မဟုတ်ခဲ့ဘူး။"),
        Demo("inter", listOf(at(1, 35, 0) to at(1, 35, 0) + 20_160), "FITS",
            "Gargantua တွင်းနက်ကြီးနားမှာ အချိန်က ပိုနှေးကွေးသွားတယ်။"),
        Demo("tess", listOf(at(1, 52, 10) to at(1, 52, 10) + 20_160), "FITS",
            "Cooper က tesseract ထဲကနေ သမီး Murph ဆီ သတင်းပို့ဖို့ ကြိုးစားတယ်။"),
        Demo("dust", listOf(at(2, 10, 0) to at(2, 10, 0) + 22_680), "FITS",
            "Murph က နာရီလက်တံထဲက ဒေတာနဲ့ ပုစ္ဆာကို ဖြေရှင်းလိုက်တယ်။"),
        Demo("ship", listOf(at(2, 25, 0) to at(2, 25, 0) + 20_160), "FITS",
            "လူသားတွေ အာကာသ စခန်းကြီးပေါ်မှာ နေထိုင်လာနိုင်ပြီ။"),
        Demo("corn", listOf(at(2, 35, 0) to at(2, 35, 0) + 22_680), "FITS",
            "အသက်ကြီးသွားတဲ့ Murph နဲ့ Cooper ပြန်ဆုံကြတယ်။"),
        Demo("inter", listOf(at(2, 44, 0) to at(2, 44, 0) + 20_160), "FITS",
            "Cooper က Amelia ဆီ နောက်ဆုံး ခရီးကို ထပ်ထွက်သွားတယ်။"),
    )
    /** The sample's unpicked scenes (SC 3, 5, 8). */
    private val UNPICKED = listOf(Triple("dust", at(0, 7, 55), at(0, 8, 12)), Triple("corn", at(0, 21, 18), at(0, 21, 40)),
        Triple("tess", at(0, 52, 10), at(0, 52, 36)))

    private val ranges: List<Triple<String, Long, Long>> by lazy {
        (BLOCKS.flatMap { b -> b.clips.map { Triple(b.scene, it.first, it.second) } } + UNPICKED).sortedBy { it.second }
    }
    /** The demo picture at movie time [ms] (outside every scene: the black hole). */
    fun sceneAt(ms: Long): String = ranges.firstOrNull { ms >= it.second - 1_000 && ms < it.third + 1_000 }?.first ?: "inter"

    fun picture(scene: String): ByteArray = java.util.Base64.getMimeDecoder()
        .decode(requireNotNull(InterstellarFixture::class.java.getResourceAsStream("/recap-demo/$scene.jpg.b64")) { scene }.readBytes())

    fun create(root: File, name: String = "Interstellar", ratio: Ratio = Ratio.R16_9): Built {
        val fs = JvmFs(); val store = ProjectStore(fs); val dir = File(root, "$name.autoflow")
        require(!dir.exists()) { "Fixture never overwrites an existing project: $dir" }
        val project = (store.create(dir.path, name, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val movie = File(dir, "source/Interstellar.2014.mkv").apply { parentFile.mkdirs() }
        movie(movie)
        val story = RecapStory(source = RecapStory.SOURCE_GEMINI, blocks = BLOCKS.mapIndexed { i, b ->
            RecapBlock("b${i + 1}", b.clips.map { RecapClip(it.first, it.second) }, b.text)
        })
        val artifacts = ArtifactStore(fs, dir.path)
        val script = (artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value
        val lock = VoiceLock("gemini", "gemini-2.5-flash-preview-tts", "Kore", "my")
        val takes = story.blocks.zip(BLOCKS).mapNotNull { (block, demo) ->
            if (demo.status == "NO_VOICE") return@mapNotNull null
            val ms = if (demo.status == "TIMING") block.durationMs + 1_000 else block.durationMs - 2_000
            val wav = PcmWav.encode(8_000, FloatArray((ms * 8).toInt()))
            val audio = (artifacts.put(wav, ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value
            val spoken = if (demo.status == "SCRIPT_CHANGED") SpokenText.hash(block.copy(textMy = block.textMy + " (old)")) else SpokenText.hash(block)
            BlockTake(block.id, audio.id, TakeSource.TTS, lock.provider, lock.model, if (demo.status == "OTHER_VOICE") "Puck" else lock.voice,
                durationMs = ms, spokenHash = spoken)
        }
        val logo = (artifacts.put(logo(), ArtifactKind.IMAGE, "png") as CoreResult.Ok).value
        val layers = listOf(
            Layer("image-1", LayerType.IMAGE, listOf(.87f, .06f, .08f, .142f), params = LayerParams(image = logo.id.value)),
            Layer("text-2", LayerType.TEXT, listOf(.05f, .06f, .42f, .09f), params = LayerParams(textMy = "INTERSTELLAR · RECAP")),
            Layer("border-3", LayerType.BORDER, params = LayerParams(px = 6, colour = "#F5D06F")),
        )
        val scenes = (BLOCKS.flatMap { b -> b.clips.map { RecapScene(it.first, it.second, picked = true) } } +
            UNPICKED.map { RecapScene(it.second, it.third) }).sortedBy { it.startMs }
        val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/Interstellar.2014.mkv", movie.length(), MOVIE_MS, 1920, 1080,
            hasAudio = true), scenes = scenes, story = script.id, storyApproved = true, voiceLock = lock, takes = takes,
            chosenTakes = takes.associate { it.block to it.audio }, layers = layers, visual = RecapVisual(outputRatio = ratio))
        val saved = (store.save(dir.path, project.copy(recap = recap)) as CoreResult.Ok).value
        return Built(dir, saved, story, ::sceneAt)
    }

    /** One frame per second for 2:49:03: each scene range shows its picture, the rest the black hole. */
    private fun movie(out: File) {
        val work = File(out.parentFile, ".demo").apply { mkdirs() }
        SCENES.forEach { File(work, "$it.jpg").writeBytes(picture(it)) }
        val list = StringBuilder()
        var at = 0L
        fun hold(scene: String, until: Long) {
            if (until <= at) return
            list.append("file '${File(work, "$scene.jpg").path}'\nduration ${(until - at) / 1000.0}\n"); at = until
        }
        ranges.forEach { (scene, from, to) -> hold("inter", from - 1_000); hold(scene, to + 1_000) }
        hold("inter", MOVIE_MS)
        list.append("file '${File(work, "inter.jpg").path}'\n")
        val concat = File(work, "list.txt").apply { writeText(list.toString()) }
        val process = ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-nostdin", "-y", "-f", "concat", "-safe", "0",
            "-i", concat.path, "-vf", "fps=1,format=yuv420p", "-t", "${MOVIE_MS / 1000}", "-c:v", "libx264", "-preset", "ultrafast",
            "-tune", "stillimage", "-g", "20", out.path).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Demo movie ffmpeg failed: ${log.takeLast(2000)}" }
        work.deleteRecursively()
    }

    /** The sample's round "AF" logo (Purple → Cyan). */
    private fun logo(): ByteArray {
        val image = BufferedImage(128, 128, BufferedImage.TYPE_INT_ARGB)
        val g = image.createGraphics()
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g.paint = GradientPaint(0f, 0f, Color(0x8B5CF6), 128f, 128f, Color(0x22D3EE))
        g.fillOval(0, 0, 128, 128)
        g.color = Color.WHITE
        g.font = Font(Font.SANS_SERIF, Font.BOLD, 46)
        val m = g.fontMetrics
        g.drawString("AF", (128 - m.stringWidth("AF")) / 2, (128 - m.height) / 2 + m.ascent)
        g.dispose()
        return java.io.ByteArrayOutputStream().also { check(ImageIO.write(image, "png", it)) }.toByteArray()
    }
}
