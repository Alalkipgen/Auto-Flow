package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.SubtitleTrack
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.media.probe.MediaInfo
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.test.runTest

/** S-2b source (specs/quick-tools.md §5): a text track or an `.srt` becomes the first `st`; nothing else is saved. */
class SubtitleSourceTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "/app/projects.json")
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, index, current)
    private val picker = FakePicker()
    private val dir = "/p/Clip.autoflow"
    private val tracks = listOf(SubtitleTrack(2, "subrip", "eng"), SubtitleTrack(3, "hdmv_pgs_subtitle", "bur"))
    private val srt = "1\n00:00:01,000 --> 00:00:02,500\nWe have to leave tonight.\n\n2\n00:00:03,000 --> 00:00:04,000\nဒီည ထွက်သွားရမယ်။\n"
    private val extracted = mutableListOf<Pair<String, Int>>()

    init {
        val created = (store.create(dir, "Clip", Mode.SUBTITLES) as CoreResult.Ok).value
        val video = SourceVideo("source/movie.mp4", 9, 60_000, 1920, 1080, 25.0, hasAudio = true)
        current.set(OpenProject(dir, (store.save(dir, created.copy(subtitles = SubtitlesTool(video = video))) as CoreResult.Ok).value))
    }

    private fun model(scope: CoroutineScope, trackText: String? = srt, failure: UiNotice? = null) = SubtitlesModel(session, fs,
        DashboardModel(fs, store, index, SettingsStore(fs, "/app/s.json"), picker, current), picker, scope,
        probe = { MediaInfo(durationMs = 60_000, width = 1920, height = 1080, fps = 25.0, videoCodec = "h264", audioCodec = "aac", subtitleTracks = tracks) },
        extract = { video, i, out ->
            extracted += video to i
            failure ?: null.also { trackText?.let { fs.writeDurably(out, it.encodeToByteArray()) } }
        })

    private fun pickSrt(bytes: ByteArray) { fs.createDirectories("/dl"); fs.writeDurably("/dl/subs.srt", bytes); picker.files = listOf("/dl/subs.srt") }
    private fun currentId(): ArtifactId? = (store.open(dir) as CoreResult.Ok).value.subtitles?.current
    private fun doc(id: ArtifactId) = CoreJson.decodeFromString(SubtitleDoc.serializer(),
        (ArtifactStore(fs, dir).readBytes(id) as CoreResult.Ok).value.decodeToString())
    private fun stFiles() = if (fs.exists("$dir/artifacts")) fs.list("$dir/artifacts").filter { it.substringAfterLast('/').startsWith("st_") } else emptyList()

    @Test
    fun anSrtBecomesTheFirstVersionAndAnotherImportKeepsIt() = runTest {
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        pickSrt(bom + srt.flatMap { listOf((it.code and 0xFF).toByte(), (it.code shr 8).toByte()) }.toByteArray())
        val m = model(this)
        m.importSrt()!!.join()
        val first = currentId()!!
        assertEquals(UiNotice(UiText.SUB_SAVED_SRT, "n" to 2, "encoding" to "UTF-16 LE"), m.state.value.notice)
        assertTrue(m.state.value.ok)
        assertNull(m.state.value.warning)
        assertEquals(listOf("We have to leave tonight.", "ဒီည ထွက်သွားရမယ်။"), doc(first).cues.map { it.text })
        assertEquals(SubtitleDoc.SOURCE_SRT to null, doc(first).source to doc(first).derivedFrom)
        assertEquals(doc(first), m.state.value.doc)

        pickSrt(srt.replace("tonight", "now").encodeToByteArray())
        m.importSrt()!!.join()
        val second = currentId()!!
        assertEquals(first, doc(second).derivedFrom) // a new immutable version; the first stays
        assertEquals("UTF-8", m.state.value.notice?.arguments?.get("encoding"))
        assertEquals(2, stFiles().count { it.endsWith(".json") && !it.endsWith(".meta.json") })
    }

    @Test
    fun emptyBrokenAndCancelledFilesSaveNothing() = runTest {
        val m = model(this)
        pickSrt("\n\n".encodeToByteArray())
        m.importSrt()!!.join()
        assertEquals(UiText.SUB_SRT_EMPTY, m.state.value.notice?.key)
        pickSrt((srt + "\n3\n00:00:05,000 -> 00:00:06,000\nbad arrow\n").encodeToByteArray())
        m.importSrt()!!.join()
        assertEquals(UiNotice(UiText.SUB_SRT_BROKEN, "n" to 3), m.state.value.notice)
        picker.files = emptyList()
        m.importSrt()!!.join()
        assertNull(currentId())
        assertEquals(emptyList(), stFiles())
    }

    @Test
    fun zawgyiIsSavedWithAWarningAndNotConverted() = runTest {
        val zawgyi = "1\n00:00:01,000 --> 00:00:02,000\n\u1031\u107E\u1000\u102C\u1004\u1039\n"
        pickSrt(zawgyi.encodeToByteArray())
        val m = model(this)
        m.importSrt()!!.join()
        assertEquals(UiText.SUB_ZAWGYI, m.state.value.warning?.key)
        assertEquals("\u1031\u107E\u1000\u102C\u1004\u1039", doc(currentId()!!).cues.single().text)
    }

    @Test
    fun aTextTrackIsExtractedAndAPictureTrackOnlyExplains() = runTest {
        val m = model(this)
        assertNull(m.useTrack(tracks[1]))
        assertEquals(UiText.SUB_PICTURE_TRACK, m.state.value.notice?.key)
        assertEquals(emptyList(), extracted)

        m.useTrack(tracks[0])!!.join()
        assertEquals(listOf("$dir/source/movie.mp4" to 2), extracted)
        assertEquals(UiNotice(UiText.SUB_SAVED_TRACK, "n" to 2, "track" to 2), m.state.value.notice)
        val d = doc(currentId()!!)
        assertEquals("track:2" to "eng", d.source to d.lang)
        assertEquals(listOf("c1", "c2") to 3, d.cues.map { it.id } to d.nextId)
    }

    @Test
    fun theDefaultTrackIsBurmeseTextElseTheFirstText() {
        val eng = SubtitleTrack(4, "ass", "eng")
        val bur = SubtitleTrack(5, "subrip", "mya")
        assertEquals(5, SubtitlesModel.defaultTrack(listOf(tracks[1], eng, bur)))
        assertEquals(4, SubtitlesModel.defaultTrack(listOf(tracks[1], eng)))
        assertNull(SubtitlesModel.defaultTrack(listOf(tracks[1])))
    }

    @Test
    fun aFailedOrEmptyTrackSavesNothing() = runTest {
        model(this, failure = UiNotice(UiText.SUBTITLE_EXTRACT_FAILED, "detail" to "Invalid data")).let { m ->
            m.useTrack(tracks[0])!!.join()
            assertEquals(UiNotice(UiText.SUBTITLE_EXTRACT_FAILED, "detail" to "Invalid data"), m.state.value.notice)
        }
        model(this, trackText = "").let { m ->
            m.useTrack(tracks[0])!!.join()
            assertEquals(UiText.SUBTITLE_TRACK_EMPTY, m.state.value.notice?.key)
        }
        assertNull(currentId())
    }

    @Test
    fun refreshReadsTracksAndTheCurrentVersion() = runTest {
        val m = model(this)
        m.refresh()!!.join()
        assertEquals(tracks to true, m.state.value.tracks to m.state.value.tracksRead)
        assertNull(m.state.value.doc)
        m.useTrack(tracks[0])!!.join()
        val reopened = model(this)
        reopened.refresh()!!.join()
        assertEquals(doc(currentId()!!), reopened.state.value.doc)

        val lost = ArtifactId("st_000000000000")
        session.update(dir, current.state.value!!.project.id) { it.copy(subtitles = it.subtitles!!.copy(current = lost)) }
        model(this).let { r -> r.refresh()!!.join(); assertEquals(UiText.SUB_DOC_UNREADABLE, r.state.value.notice?.key); assertNull(r.state.value.doc) }
    }
}
