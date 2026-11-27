package io.github.alalkipgen.autoflow.ui.preview

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.ui.components.ProjectShortcutHost
import io.github.alalkipgen.autoflow.ui.components.ProjectTopBar
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleCue
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.audio.DubCue
import io.github.alalkipgen.autoflow.media.playback.MovieRequest
import io.github.alalkipgen.autoflow.media.render.BatteryReader
import io.github.alalkipgen.autoflow.media.render.BatteryReading
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.Overlay
import io.github.alalkipgen.autoflow.media.render.ProjectRender
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.dubbing.DubPreviewClock
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.export.MusicPreviewClock
import io.github.alalkipgen.autoflow.ui.export.MusicVideoPreview
import io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.ProjectPanes
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.subtitles.Playhead
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePreview
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.util.Base64
import kotlin.test.*
import kotlinx.coroutines.MainScope

/** F-6 (D-95): the previews before a render — Dub / Subtitles burn, the phone Preview page and the Music Video page. */
@OptIn(ExperimentalTestApi::class)
class ToolPreviewTest {
    @Test fun theDubPreviewPlaysEachTakeInItsSlotFromItsLeadAtItsSpeed() {
        val a = DubCue("/a.wav", 1_000, 120, 1_900)
        val b = DubCue("/b.wav", 3_000, 0, 2_900, 1.04)
        val cues = listOf(a, b)
        assertNull(DubPreviewClock.at(cues, 999))
        assertEquals(a, DubPreviewClock.at(cues, 1_000))
        assertEquals(620, DubPreviewClock.offset(a, 1_500))
        assertNull(DubPreviewClock.at(cues, 2_900), "the slot ends; silence until the next line")
        assertEquals(b, DubPreviewClock.at(cues, 4_000))
        assertEquals(1_040, DubPreviewClock.offset(b, 4_000))
    }

    @Test fun theBurnPreviewDrawsTheBurnOverlayUnderThePlayheadAndFollowsTheVolume() = runDesktopComposeUiTest(width = 412, height = 760) {
        val playhead = Playhead()
        val requests = mutableListOf<MovieRequest>()
        val volumeState = androidx.compose.runtime.mutableStateOf(0.4f)
        val burned = listOf(Overlay("ဒီည ထွက်သွားရမယ်။", 1_000, 3_000, "", 54, box = true),
            Overlay("မိုးမရွာခင်", 4_000, 6_000, "", 54, strokeWidth = 4))
        setContent {
            CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                SubtitlePreview("/m.mp4", SourceVideo("source/m.mp4", 9, 8_000, 1920, 1080, 25.0),
                    listOf(SubtitleCue("c1", 1_000, 3_000, "We leave tonight.", textMy = "plain")), TextChoice.BURMESE, playhead,
                    { _, r, m, _ -> requests += r; Box(m) }, tag = "burn", burned = burned, volume = volumeState.value)
            }
        }
        onNodeWithTag("burn-burned-line").assertDoesNotExist()
        playhead.seek(1_500)
        waitForIdle()
        onNodeWithTag("burn-burned-line").assertExists()
        onNodeWithText("ဒီည ထွက်သွားရမယ်။").assertExists()
        onNodeWithText("plain").assertDoesNotExist() // the burn's own text, not the editor's plain line
        assertFalse(requests.last().muted)
        assertEquals(0.4f, requests.last().volume)
        playhead.seek(4_500)
        waitForIdle()
        onNodeWithText("မိုးမရွာခင်").assertExists() // the outline look: one readable text, the stroke copy is hidden
        playhead.seek(3_500)
        waitForIdle()
        onNodeWithTag("burn-burned-line").assertDoesNotExist()
        volumeState.value = 0f
        waitForIdle()
        assertTrue(requests.last().muted, "Mute on Voice mutes the preview's movie sound")
    }

    @Test fun phonesOpenThePreviewAsASecondPageFromTheTopBar() = runDesktopComposeUiTest(width = 412, height = 700) {
        setContent {
            CompositionLocalProvider(LocalUiLanguage provides UiLanguage.BURMESE) {
                ProjectShortcutHost { Column {
                    ProjectTopBar("Rain", 3, "Voice", {})
                    ProjectPanes(412f, { AfText("list") }, { AfText("editor") }, { AfText("preview") })
                } }
            }
        }
        onNodeWithTag("project-single-pane").assertExists()
        onNodeWithText("editor").assertExists()
        onNodeWithText("preview").assertDoesNotExist()
        onNodeWithTag("project-page-edit").assertDoesNotExist()
        onNodeWithTag("project-page-preview").assertContentDescriptionEquals(UiText.PROJECT_PAGE_PREVIEW.text(UiLanguage.BURMESE))
            .assertHeightIsAtLeast(48.dp).performMouseInput { click() }
        onNodeWithText("preview").assertIsDisplayed()
        onNodeWithText("editor").assertDoesNotExist()
        onNodeWithTag("project-preview-page").assertExists()
        onNodeWithTag("project-page-edit").assertContentDescriptionEquals(UiText.EDIT.text(UiLanguage.BURMESE)).performMouseInput { click() }
        onNodeWithText("editor").assertExists()
        onNodeWithText("list").assertDoesNotExist()
    }

    @Test fun aTopBarWithoutPanesOrAWideWindowHasNoPageToggle() = runDesktopComposeUiTest(width = 900, height = 600) {
        var wide by mutableStateOf(true)
        setContent {
            ProjectShortcutHost { Column {
                ProjectTopBar("Rain", 3, "Voice", {})
                ProjectPanes(if (wide) 900f else 412f, { AfText("list") }, { AfText("editor") }, { AfText("preview") })
            } }
        }
        onNodeWithTag("project-page-preview").assertDoesNotExist()
        wide = false
        waitForIdle()
        onNodeWithTag("project-page-preview").performMouseInput { click() }
        onNodeWithText("preview").assertExists()
        wide = true
        waitForIdle()
        onNodeWithTag("project-page-edit").assertDoesNotExist()
        onNodeWithTag("project-page-preview").assertDoesNotExist()
        wide = false
        waitForIdle()
        onNodeWithText("editor").assertExists() // leaving the phone layout returns to Edit
    }

    @Test fun wideWindowsKeepThePreviewPaneAndNoPageChips() = runDesktopComposeUiTest(width = 900, height = 600) {
        setContent { ProjectPanes(900f, { AfText("list") }, { AfText("editor") }, { AfText("preview") }) }
        onNodeWithText("preview").assertExists()
        onNodeWithText("editor").assertExists()
        onNodeWithTag("project-page-preview").assertDoesNotExist()
    }

    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val settings = SettingsStore(fs, "/a/settings.json")
    private val dir = "/p/Rain.autoflow"
    private val font: ByteArray = Base64.getMimeDecoder().decode(
        javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())

    /** Shot 1 accepted with a lyric, shot 2 pending; a song and a Burmese font. Returns the accepted video's file. */
    private fun musicVideo(): String {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        val art = (session.artifacts()!!.put(byteArrayOf(1, 2, 3), ArtifactKind.VIDEO, "mp4", 1) as CoreResult.Ok).value
        session.save(p.copy(shots = listOf(
            Shot(1, 0, 8_000, lyricsMy = "မိုးတွေရွာနေတယ်", acceptedVideo = art.id),
            Shot(2, 8_000, 5_000, lyricsMy = ""))))
        fs.createDirectories("/cache")
        fs.writeDurably("/cache/s.mp3", byteArrayOf(7))
        session.importSong("/cache/s.mp3")
        fs.createDirectories("/fonts")
        fs.writeDurably("/fonts/noto.ttf", font)
        settings.update { it.copy(fontPaths = listOf("/fonts/noto.ttf")) }
        return "$dir/${ArtifactStore.ARTIFACTS_DIR}/${art.file}"
    }

    private fun model() = ExportModel(session, settings, fs, null, null, BatteryReader { BatteryReading(80, charging = false) }, MainScope())

    @Test fun theMusicPreviewIsTheRendersOwnGraphWithAbsoluteFilesAndNothingSaved() {
        val m = model()
        assertNull(m.musicPreview(), "no project")
        val video = musicVideo()
        val before = session.open!!.project
        val preview = assertNotNull(m.musicPreview())
        assertEquals(listOf(8_000L, 5_000L), preview.graph.clips.map { it.durationMs })
        assertEquals(video, preview.files[preview.graph.clips[0].artifact!!])
        assertEquals(ProjectRender.pendingLabel(2), preview.graph.clips[1].label)
        assertEquals(listOf("မိုးတွေရွာနေတယ်"), preview.graph.overlays.map { it.text.replace("\n", "") })
        assertEquals(session.songPath(), preview.song)
        assertEquals(before, session.open!!.project, "a preview never saves a timeline")
        assertEquals(listOf(0L, 8_000L), MusicPreviewClock.starts(preview.graph.clips))
        assertEquals(0, MusicPreviewClock.index(listOf(0L, 8_000L), 7_999))
        assertEquals(1, MusicPreviewClock.index(listOf(0L, 8_000L), 8_000))
        assertEquals(0, MusicPreviewClock.index(MusicPreviewClock.starts(listOf(Clip(null, trimOutMs = 1_000))), 0))
    }

    @Test fun theMusicPreviewPlaysTheShotsAndLyricsOnOneClockWithoutARender() = runDesktopComposeUiTest(width = 412, height = 860) {
        val video = musicVideo()
        val m = model()
        val requests = mutableListOf<Pair<String, MovieRequest>>()
        setContent {
            CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                Column { MusicVideoPreview(m) { path, r, mod, _ -> requests += path to r; Box(mod) } }
            }
        }
        waitUntil(timeoutMillis = 5_000) { onAllNodesWithTag("mv-preview-video").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("mv-preview-hint").assertExists()
        assertEquals(video, requests.last().first)
        assertTrue(requests.last().second.muted, "the render drops the clips' sound for the song")
        assertFalse(requests.last().second.playing)
        onNode(hasText("မိုး", substring = true) and hasAnyAncestor(hasTestTag("mv-preview-lyric"))).assertExists()
        mainClock.autoAdvance = false
        onNodeWithTag("mv-preview-play").performMouseInput { click() }
        mainClock.advanceTimeBy(1_000)
        assertTrue(requests.last().second.playing)
        mainClock.advanceTimeBy(8_000)
        onNodeWithTag("mv-preview-card-label").assertTextEquals(ProjectRender.pendingLabel(2))
        onNode(hasText("မိုး", substring = true) and hasAnyAncestor(hasTestTag("mv-preview-lyric"))).assertDoesNotExist()
        onNodeWithTag("mv-preview-time").assert(hasText("#2", substring = true))
        mainClock.advanceTimeBy(6_000)
        onNodeWithTag("mv-preview-play").assertTextEquals(My.breakSyllables(UiText.SUB_PLAY.text(UiLanguage.ENGLISH)))
        assertTrue(runCatching { fs.list("$dir/exports") }.getOrDefault(emptyList()).isEmpty(), "nothing was rendered")
    }
}
