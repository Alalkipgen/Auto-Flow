package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.platform.*
import kotlinx.coroutines.runBlocking
import java.awt.image.BufferedImage
import java.io.File
import java.security.MessageDigest
import javax.imageio.ImageIO
import kotlin.math.PI
import kotlin.math.sin

/** Synthetic, offline fixture shared by the native-player test and the owner backup. No provider/API calls. */
internal object QaRecapFixture {
    data class Built(val dir: File, val project: Project, val story: RecapStory)

    fun create(root: File, name: String = "QA Landscape", ratio: Ratio = Ratio.R16_9,
               looks: List<Look> = emptyList(), background: BackgroundMode = BackgroundMode.COLOUR): Built {
        val fs = JvmFs(); val store = ProjectStore(fs); val dir = File(root, "$name.autoflow")
        require(!dir.exists()) { "Fixture never overwrites an existing project: $dir" }
        val project = (store.create(dir.path, name, Mode.MOVIE_RECAP) as CoreResult.Ok).value
        val movie = File(dir, "source/movie.mp4").apply { parentFile.mkdirs() }
        val patches = listOf("0xff0000", "0x00ff00", "0x0000ff", "0xffff00", "0xff00ff").mapIndexed { i, colour ->
            "drawbox=x=0:y=0:w=80:h=60:color=$colour:t=fill:enable='gte(t,${i * 2})*lt(t,${i * 2 + 2})'"
        }.joinToString(",")
        val process = ProcessBuilder(System.getenv("AUTOFLOW_FFMPEG") ?: "ffmpeg", "-v", "error", "-nostdin", "-y",
            "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25", "-t", "10", "-vf", patches,
            "-c:v", "libx264", "-pix_fmt", "yuv420p", "-g", "25", movie.path).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        check(process.waitFor() == 0) { "Fixture ffmpeg failed: ${log.takeLast(2000)}" }
        val story = RecapStory(source = "manual", blocks = listOf(
            RecapBlock("stable-a", listOf(RecapClip(2_000, 4_000), RecapClip(6_000, 8_000)), "FIRST"),
            RecapBlock("stable-b", listOf(RecapClip(4_000, 5_500)), "SECOND"),
        ))
        val artifacts = ArtifactStore(fs, dir.path)
        val script = (artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json") as CoreResult.Ok).value
        val takes = story.blocks.mapIndexed { i, block ->
            val wav = PcmWav.encode(16_000, FloatArray(16_000) { n -> (0.15 * sin(2 * PI * (440 + i * 220) * n / 16_000)).toFloat() })
            val audio = (artifacts.put(wav, ArtifactKind.AUDIO, "wav") as CoreResult.Ok).value
            BlockTake(block.id, audio.id, TakeSource.RECORDED, durationMs = 1_000, spokenHash = SpokenText.hash(block))
        }
        val png = java.io.ByteArrayOutputStream().also { out ->
            val bitmap = BufferedImage(64, 48, BufferedImage.TYPE_INT_RGB)
            for (y in 0 until 48) for (x in 0 until 64) bitmap.setRGB(x, y, if ((x / 8 + y / 8) % 2 == 0) 0xff8000 else 0x800080)
            check(ImageIO.write(bitmap, "png", out))
        }.toByteArray()
        val image = (artifacts.put(png, ArtifactKind.IMAGE, "png") as CoreResult.Ok).value
        val layers = listOf(
            Layer("qa-background", LayerType.BACKGROUND, params = LayerParams(mode = background, colour = "#203040", image = image.id.value)),
            Layer("qa-blur", LayerType.BLUR, listOf(.10f, .25f, .25f, .20f), 1_000, 3_000, LayerParams(sigma = 8f)),
            Layer("qa-image", LayerType.IMAGE, listOf(.55f, .10f, .25f, .30f), 0, 2_000, LayerParams(image = image.id.value, opacity = .8f)),
            Layer("qa-text", LayerType.TEXT, listOf(.15f, .65f, .70f, .20f), 500, 4_500, LayerParams(textMy = "QA မြန်မာစာ")),
            Layer("qa-border", LayerType.BORDER, fromMs = 2_000, toMs = 5_000, params = LayerParams(px = 4, colour = "#00FFFF")),
        )
        val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", movie.length(), 10_000, 320, 180, hasAudio = false),
            story = script.id, storyApproved = true, takes = takes, chosenTakes = takes.associate { it.block to it.audio }, layers = layers, looks = looks,
            visual = RecapVisual(outputRatio = ratio, masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect))))
        val saved = (store.save(dir.path, project.copy(recap = recap)) as CoreResult.Ok).value
        return Built(dir, saved, story)
    }
}

/** Build tool only, on the desktop-test classpath; not shipped in app binaries. */
fun main(args: Array<String>) = runBlocking {
    val output = File(args.single()).absoluteFile.apply { mkdirs() }
    val projects = File(output, "projects").apply { require(!exists()) { "Use a fresh qaFixtureDir; never replace projects" }; mkdirs() }
    val fixtures = listOf(
        QaRecapFixture.create(projects),
        QaRecapFixture.create(projects, "QA Portrait", Ratio.R9_16, listOf(Look.MIRROR, Look.ZOOM, Look.FILL), BackgroundMode.BLUR),
        QaRecapFixture.create(projects, "QA Square", Ratio.R1_1, listOf(Look.ZOOM), BackgroundMode.IMAGE),
    )
    val backup = File(output, "AutoFlow-5A8-synthetic-backup.zip")
    backup.outputStream().use { ProjectZip.write(ProjectZip.plan(fixtures.map { BackupProject(it.dir.path, it.dir.name) }), it, "2026-11-16T00:00:00Z") }
    File(output, "EXPECTED.txt").writeText("""
        Auto-Flow 5A-8 synthetic checkpoint fixture (no network/providers/paid services)
        Restore AutoFlow-5A8-synthetic-backup.zip through the existing Android backup restore.
        Desktop: open a projects/*.autoflow folder. Never replace an owner's real project.
        This checkpoint is NOT queue-complete: native output-layer Compose rendering/S10 expansion remains open.

        All projects: approved native-v2 story, stable-a/stable-b, valid 1-second 440/660 Hz recorded WAV takes.
        No voice-audio playback is claimed for the muted source-picture player.
        Output timeline: [0,2000) -> source [2000,4000); [2000,4000) -> source [6000,8000);
                         [4000,5500) -> source [4000,5500). Duration 5500 ms, no first-clip loop.
        Captions: FIRST [200,1200), SECOND [4200,5200); blank elsewhere (test away from frame boundaries).
        Source-picture paused seeks: 150 ms -> green patch/no caption; 500 -> green/FIRST;
                                     2500 -> yellow/no caption; 4500 -> blue/SECOND.
        The upper-left 25% x 33% source patch encodes source time. Looks are deliberately NOT shown in source mode.
        Native layers are OUTPUT-FRAME rects; the older bottom mask is MOVIE-PICTURE, intentionally pre-Looks in placement mode.
        List order: qa-background, qa-blur, qa-image, qa-text, qa-border; actual captions are last.
        qa-background: whole-frame behind fitted picture, ignores rect; colour #203040 (landscape), blur (portrait), checker image (square).
        qa-blur: [0.10,0.25,0.25,0.20], sigma 8, [1000,3000] inclusive activation.
        qa-image: [0.55,0.10,0.25,0.30], checker PNG, opacity .8, [0,2000]; export keeps image aspect using rect width.
        qa-text: [0.15,0.65,0.70,0.20], QA + Burmese, [500,4500]; at list position, not the captions pass.
        qa-border: whole frame, rect ignored, cyan 4 px, [2000,5000].
        Landscape: 16:9, Looks off. Portrait: 9:16 Mirror/Zoom/Fill. Square: 1:1 Zoom, image background.
        Until native Compose output rendering is implemented, check those layer/Looks results using Exact Frame / export,
        NOT the legacy placement / source-picture surfaces. Do not mark the missing native parity row passed.
        Edit, cancel, save/reopen, reorder and remove layers; source image bytes must remain unchanged on removal.
        Repeat large-font Exact Frame reachability and project-switch/background/foreground checks in PHONE-QA.md.
        Content/timing are deterministic; project IDs/revision timestamps and encoded bytes may vary by build/toolchain.
        SHA256SUMS records the actual distributed files, not an assertion of byte-identical builds.
    """.trimIndent() + "\n")
    val sums = output.walkTopDown().filter { it.isFile && it.name != "SHA256SUMS" }.sortedBy { it.relativeTo(output).invariantSeparatorsPath }.joinToString("\n") { file ->
        val hash = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
        "$hash  ${file.relativeTo(output).invariantSeparatorsPath}"
    }
    File(output, "SHA256SUMS").writeText(sums + "\n")
    println("Offline QA backup: ${backup.name}; ${fixtures.size} native-v2 projects; actual-file SHA256SUMS generated")
}
