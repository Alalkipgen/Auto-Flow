package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.SceneSpan
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.Transcript as AiTranscript
import io.github.alalkipgen.autoflow.ai.TranscriptSegment
import io.github.alalkipgen.autoflow.ai.Usage
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.RecapMovie
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.Transcript
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.media.tool.ToolExec
import io.github.alalkipgen.autoflow.media.tool.ToolRunner
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.ProcessRunner
import io.github.alalkipgen.autoflow.platform.ToolLocator
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking

/**
 * 5B-1 with a real ffmpeg and the production sound cutter (MediaTools → SceneAudio): "Transcribe picked scenes" of a
 * real 40 s movie sends one request whose sound is only the two picked scenes (their summed length ±150 ms, AAC in an
 * MP4 container, 16 kHz mono), and the answer is saved in movie time. Gemini is a fake; `AUTOFLOW_EXPECT_FFMPEG=1`
 * (CI) turns a missing ffmpeg into a failure.
 */
class TranscribeScenesFfmpegTest {
    private fun works(bin: String) = runCatching {
        val p = ProcessBuilder(bin, "-hide_banner", "-version").redirectErrorStream(true).start()
        p.inputStream.readAllBytes(); p.waitFor(30, TimeUnit.SECONDS) && p.exitValue() == 0
    }.getOrDefault(false)

    private val ffmpeg: String? by lazy { listOfNotNull(System.getenv("AUTOFLOW_FFMPEG"), "ffmpeg").firstOrNull(::works) }
    private val ffprobe: String? by lazy {
        listOfNotNull(System.getenv("AUTOFLOW_FFPROBE"), ffmpeg?.let { File(File(it).parentFile ?: File("."), "ffprobe").path }, "ffprobe")
            .firstOrNull(::works)
    }

    private val tools = ToolRunner { cmd, wd, timeout, keep, onLine ->
        when (val r = ProcessRunner.run(cmd, File(wd), timeout, keep, onLine)) {
            is ProcessRunner.Exec.Done -> ToolExec.Done(r.exitCode, r.output, r.tail, r.wallMs, r.timedOut)
            is ProcessRunner.Exec.CannotStart -> ToolExec.CannotStart(r.message)
        }
    }

    private fun sh(vararg cmd: String): String {
        val p = ProcessBuilder(*cmd).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        assertTrue(p.waitFor(120, TimeUnit.SECONDS), "timed out: ${cmd.joinToString(" ")}")
        assertEquals(0, p.exitValue(), "${cmd.joinToString(" ")}\n$out")
        return out
    }

    @Test
    fun realSceneSoundIsSentAndTheLinesAreSavedInMovieTime() = runBlocking {
        val ff = ffmpeg; val fp = ffprobe
        if (ff == null || fp == null) {
            if (System.getenv("AUTOFLOW_EXPECT_FFMPEG") == "1") fail("AUTOFLOW_EXPECT_FFMPEG=1 but no ffmpeg / ffprobe")
            println("SKIP TranscribeScenesFfmpegTest: no ffmpeg / ffprobe binary"); return@runBlocking
        }
        val root = Files.createTempDirectory("af-transcribe").toFile()
        try {
            val fs = JvmFs()
            val store = ProjectStore(fs)
            val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "projects.json").path), current)
            val settings = SettingsStore(fs, File(root, "settings.json").path)
            val dir = File(root, "R.autoflow").path
            val p = (store.create(dir, "R", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            File(dir, "source").mkdirs()
            val movie = File(dir, "source/movie.mp4")
            sh(ff, "-hide_banner", "-loglevel", "error", "-f", "lavfi", "-i", "testsrc2=size=320x180:rate=25:duration=40",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=40", "-c:v", "libx264", "-preset", "ultrafast", "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-shortest", movie.path)
            val recap = RecapV2(
                rightsConfirmed = true,
                movie = RecapMovie("source/movie.mp4", movie.length(), 40_000, 320, 180),
                scenes = listOf(RecapScene(0, 5_000), RecapScene(5_000, 12_500, picked = true), RecapScene(12_500, 30_000), RecapScene(30_000, 36_000, picked = true)),
            )
            current.set(OpenProject(dir, (store.save(dir, p.copy(recap = recap)) as CoreResult.Ok).value))
            val locator = object : ToolLocator {
                override fun findFfmpeg(): String = ff
                override fun findFfprobe(): String = fp
                override fun findVlc(): String? = null
                override fun isVlcDir(dir: String): Boolean = false
            }
            var sound: ByteArray? = null
            var spans: List<SceneSpan> = emptyList()
            var mime = ""
            val provider = object : TranscribeProvider {
                override suspend fun transcribe(audio: ByteArray, lang: String, mime: String, lines: List<String>): AiResult<AiTranscript> = error("not used")
                override suspend fun transcribeScenes(audio: ByteArray, mime: String, scenes: List<SceneSpan>, lang: String?): AiResult<List<List<TranscriptSegment>>> {
                    sound = audio; spans = scenes; mimeOf(mime)
                    return AiResult.Ok(scenes.map { listOf(TranscriptSegment(it.startMs + 500, it.startMs + 2_000, "line at ${it.startMs}")) }, Usage("fake"))
                }
                private fun mimeOf(m: String) { mime = m }
            }
            val m = RecapModel(session, fs, MediaTools(settings, locator, tools), null, CoroutineScope(Dispatchers.Default), transcriber = provider)
            m.transcribeScenes()
            assertEquals(TranscribeConfirm(scenes = 2, requests = 1, audioMs = 13_500), m.state.value.transcribe.confirm)
            assertNotNull(m.transcribeScenes(confirmed = true)).join()
            assertTrue(m.state.value.messageOk, m.state.value.message)

            val bytes = assertNotNull(sound, "one request was sent")
            assertEquals("audio/mp4", mime)
            assertEquals(listOf(SceneSpan(0, 7_500), SceneSpan(7_500, 13_500)), spans)
            val sent = File(root, "sent.m4a").apply { writeBytes(bytes) }
            val streams = sh(fp, "-v", "error", "-show_entries", "stream=codec_type,codec_name,sample_rate,channels", "-of", "csv=p=0", sent.path)
            assertEquals(listOf("aac,audio,16000,1"), streams.trim().lines().map { it.trim() }.filter { it.isNotEmpty() }, "sound only")
            val length = (sh(fp, "-v", "error", "-show_entries", "format=duration", "-of", "default=nw=1:nk=1", sent.path).trim().toDouble() * 1000).toLong()
            println("TranscribeScenesFfmpegTest: sent ${bytes.size} bytes, $length ms of sound for 13500 ms of scenes")
            assertTrue(abs(length - 13_500) <= 150, "sent sound $length ms")
            assertTrue(File(dir, ".cache/transcribe").listFiles()!!.any { it.name.endsWith(".m4a") })

            val saved = assertNotNull((store.open(dir) as CoreResult.Ok).value.recap!!.sources.transcript)
            val tx = CoreJson.decodeFromString(Transcript.serializer(), (ArtifactStore(fs, dir).readBytes(saved) as CoreResult.Ok).value.decodeToString())
            assertEquals(listOf(5_500L to 7_000L, 30_500L to 32_000L), tx.lines.map { it.startMs to it.endMs }, "movie time")
        } finally {
            root.deleteRecursively()
        }
    }
}
