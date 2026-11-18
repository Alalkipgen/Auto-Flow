package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.*
import io.github.alalkipgen.autoflow.media.audio.PcmWav
import io.github.alalkipgen.autoflow.platform.*
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import kotlin.math.*
import kotlin.test.*

/** Actual generated audio, never placeholder/random waveform bars. No providers, keys or decoder skips. */
internal object TakeWaveformFixture {
    fun create(root: File, seconds: Int = 1): QaRecapFixture.Built {
        val f = QaRecapFixture.create(root)
        val samples = FloatArray(16_000 * seconds) { n ->
            val position = n.toDouble() / (16_000 * seconds)
            val amplitude = when { position < .20 -> 0.0; position < .45 -> .15; position < .80 -> .75; else -> .04 }
            (amplitude * sin(2 * PI * 440 * n / 16_000)).toFloat()
        }
        return addSelected(f, PcmWav.encode(16_000, samples), "wav", seconds * 1_000L)
    }
    fun addSelected(f: QaRecapFixture.Built, bytes: ByteArray, extension: String, durationMs: Long): QaRecapFixture.Built {
        val fs = JvmFs(); val r = f.project.recap!!; val block = f.story.blocks.first()
        val audio = (ArtifactStore(fs, f.dir.path).put(bytes, ArtifactKind.AUDIO, extension) as CoreResult.Ok).value
        val take = BlockTake(block.id, audio.id, TakeSource.RECORDED, durationMs = durationMs, spokenHash = SpokenText.hash(block))
        val project = (ProjectStore(fs).save(f.dir.path, f.project.copy(recap = r.copy(takes = r.takes + take,
            chosenTakes = r.chosenTakes + (block.id to take.audio)))) as CoreResult.Ok).value
        return f.copy(project = project)
    }
    fun session(f: QaRecapFixture.Built): Pair<ProjectSession, CurrentProject> {
        val fs = JvmFs(); val current = CurrentProject().apply { set(OpenProject(f.dir.path, f.project)) }
        return ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, File(f.dir.parentFile, "index.json").path), current) to current
    }
    fun source(f: QaRecapFixture.Built): RecapTakeWaveforms.Source {
        val inspector = RecapVoiceInspector(session(f).first, JvmFs(), { Lexicon.EMPTY }, {})
        val request = inspector.begin(f.story.blocks.first().id)!!
        return RecapTakeWaveforms.Source.selected(request, inspector.history(request)!!.single { it.selected })!!
    }
    fun media(root: File, commands: MutableList<List<String>> = mutableListOf()) =
        MediaTools(SettingsStore(JvmFs(), File(root, "settings.json").path), DesktopToolLocator(JvmFs()),
            StudioThumbnailsLayoutTest.tools(commands))
    fun bytes(dir: File) = StudioThumbnailsLayoutTest.bytes(dir)
}

class TakeWaveformFfmpegTest {
    @Test fun actualFfmpegDecodesTheSelectedArtifactAndPreservesQuietLoudAndSilentTimeRegions() = runBlocking {
        val root = Files.createTempDirectory("take-waveform-real").toFile()
        val f = TakeWaveformFixture.create(root); val before = TakeWaveformFixture.bytes(f.dir)
        val source = TakeWaveformFixture.source(f); val commands = mutableListOf<List<String>>()
        val store = RecapTakeWaveforms(JvmFs(), TakeWaveformFixture.media(root, commands), File(root, "cache").path)
        val e = assertNotNull(store.load(source))
        assertEquals(8_000, e.samples); assertEquals(128, e.peaks.size); assertEquals(1_000L, e.durationMs)
        fun peak(i: Int) = maxOf(abs(e.peaks[i].low), abs(e.peaks[i].high))
        assertEquals(0, peak(10)); assertTrue(peak(40) in 4_000..6_000)
        assertTrue(peak(80) in 23_000..26_000); assertTrue(peak(115) in 1_000..1_800)
        assertEquals(source.file, commands.single().let { it[it.indexOf("-i") + 1] })
        assertEquals(e, store.load(source)); assertEquals(1, commands.size)
        assertEquals(before, TakeWaveformFixture.bytes(f.dir), "all project/artifact bytes stay immutable")
        println("TAKE_WAVEFORM_REAL: selected=${source.audioId} samples=${e.samples} bins=${e.peaks.size} silent=${peak(10)} low=${peak(40)} high=${peak(80)} tail=${peak(115)}; ffmpeg=real")
    }

    @Test fun realSilentAudioRemainsExactlyFlatAndCompressedAudioStillComesFromRealDecoding() = runBlocking {
        val root = Files.createTempDirectory("take-waveform-codecs").toFile()
        val f = TakeWaveformFixture.create(root)
        val originalSource = TakeWaveformFixture.source(f) // Capture before a deliberate new selected-take revision.
        val silent = TakeWaveformFixture.addSelected(f, PcmWav.encode(16_000, FloatArray(16_000)), "wav", 1_000)
        val media = TakeWaveformFixture.media(root); val store = RecapTakeWaveforms(JvmFs(), media, File(root, "cache").path)
        val flat = assertNotNull(store.load(TakeWaveformFixture.source(silent)))
        assertEquals(8_000, flat.samples); assertTrue(flat.peaks.all { it.low == 0 && it.high == 0 })
        val encoded = File(root, "compressed.m4a")
        val process = ProcessBuilder(media.ffmpeg()!!, "-v", "error", "-nostdin", "-y", "-i",
            originalSource.file, "-c:a", "aac", encoded.path).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        assertEquals(0, process.waitFor(), log)
        val compressed = TakeWaveformFixture.addSelected(silent, encoded.readBytes(), "m4a", 1_000)
        val before = TakeWaveformFixture.bytes(compressed.dir)
        val e = assertNotNull(store.load(TakeWaveformFixture.source(compressed)))
        assertTrue(e.samples in 8_000..9_000); assertTrue(e.peaks.any { it.high > 20_000 })
        assertTrue(e.peaks.take(12).all { abs(it.high) < 100 && abs(it.low) < 100 })
        assertEquals(before, TakeWaveformFixture.bytes(compressed.dir))
        println("TAKE_WAVEFORM_REAL: silent=flat bins=${flat.peaks.size}; codec=AAC samples=${e.samples}; ffmpeg=real")
    }

    @Test fun actualDecoderRejectsCorruptOrNoAudioInputInsteadOfInventingBars() = runBlocking {
        val root = Files.createTempDirectory("take-waveform-bad").toFile(); val f = TakeWaveformFixture.create(root)
        val corrupt = TakeWaveformFixture.addSelected(f, "not an audio file".encodeToByteArray(), "wav", 1_000)
        val before = TakeWaveformFixture.bytes(corrupt.dir)
        val store = RecapTakeWaveforms(JvmFs(), TakeWaveformFixture.media(root), File(root, "cache").path)
        assertNull(store.load(TakeWaveformFixture.source(corrupt)))
        assertEquals(before, TakeWaveformFixture.bytes(corrupt.dir))
        // Valid video-only media is not silently treated as a successful silent audio decode.
        val noAudio = TakeWaveformFixture.addSelected(corrupt, File(f.dir, "source/movie.mp4").readBytes(), "mp4", 1_000)
        val afterSetup = TakeWaveformFixture.bytes(noAudio.dir)
        assertNull(store.load(TakeWaveformFixture.source(noAudio)))
        assertEquals(afterSetup, TakeWaveformFixture.bytes(noAudio.dir))
        println("TAKE_WAVEFORM_REAL: corrupt=fallback no-audio=fallback; ffmpeg=real")
    }

    @Test fun sourceUsesOnlyCoreSelectedTakeAndFullProjectGuardRejectsLateHistory() {
        val root = Files.createTempDirectory("take-waveform-identity").toFile(); val f = TakeWaveformFixture.create(root)
        val (session, current) = TakeWaveformFixture.session(f)
        val inspector = RecapVoiceInspector(session, JvmFs(), { Lexicon.EMPTY }, {})
        val request = inspector.begin(f.story.blocks.first().id)!!
        val entries = inspector.history(request)!!; val selected = entries.single { it.selected }
        assertEquals(f.project.recap!!.takeFor(request.blockId)!!.audio.value,
            RecapTakeWaveforms.Source.selected(request, selected)!!.audioId)
        val old = entries.single { !it.selected }
        assertNull(RecapTakeWaveforms.Source.selected(request, old))
        assertNull(RecapTakeWaveforms.Source.selected(request, old.copy(selected = true)))
        assertNull(RecapTakeWaveforms.Source.selected(request, selected.copy(file = null)))
        assertNull(RecapTakeWaveforms.Source.selected(request.copy(blockId = "other"), selected))
        assertTrue(inspector.current(request))
        val changed = (ProjectStore(JvmFs()).save(f.dir.path, f.project.copy(name = "Changed revision")) as CoreResult.Ok).value
        assertFalse(inspector.current(request), "on-disk revision must invalidate even before the UI refresh")
        current.set(OpenProject(f.dir.path, changed)); assertFalse(inspector.current(request))
        assertNull(inspector.history(request))
        current.set(OpenProject("/another/folder", changed)); assertFalse(inspector.current(request))
    }
}