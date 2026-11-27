package io.github.alalkipgen.autoflow.ui.dubbing

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.TextChoice
import io.github.alalkipgen.autoflow.media.audio.DubCue
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.PreviewTrack
import io.github.alalkipgen.autoflow.ui.subtitles.Playhead
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePlayer
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlePreview

/**
 * F-6 (D-95): the dubbed video before Export, on Dub script, Voice and Export — the video with the movie's sound at
 * its mix level (muted when Mute), every spoken line's take where the mix puts it, and the Burmese subtitles as the burn
 * draws them ([captions] off on Export when Burmese subtitles are off). Nothing is rendered; the mix's exact levels,
 * the stems and the burn itself are Export's.
 */
@Composable
internal fun DubPreview(export: DubExportModel, open: OpenProject?, doc: SubtitleDoc, playhead: Playhead, player: SubtitlePlayer,
    captions: Boolean = true) {
    val video = open?.project?.subtitles?.video ?: return
    val ex by export.state.collectAsState()
    val voice = remember(open.dir, open.project, doc) { export.previewVoice(open, doc) }
    val burned = remember(open.dir, open.project, doc, ex.size, ex.fit, captions) {
        if (captions) export.previewCaptions(open, doc) else emptyList()
    }
    Column(Modifier.fillMaxWidth().testTag("dub-preview-box"),
        verticalArrangement = Arrangement.spacedBy(4.dp)) {
        AfText(My.breakSyllables(tr(UiText.DUB_PREVIEW_HINT, "n" to voice.size)), Modifier.testTag("dub-preview-hint"), muted = true, size = 12.sp)
        SubtitlePreview(joinPath(open.dir, video.file), video, doc.cues, TextChoice.BURMESE, playhead, player, heightShare = 0.3f,
            tag = "dub", burned = burned, volume = export.previewOriginal(open)) { at, playing -> DubVoiceSound(voice, at, playing) }
    }
}

/** The take under the playhead, kept on the preview clock ([PreviewTrack]); silent between lines. */
@Composable
internal fun DubVoiceSound(cues: List<DubCue>, positionMs: Long, playing: Boolean) {
    val cue = DubPreviewClock.at(cues, positionMs) ?: return
    key(cue.file, cue.atMs) {
        PreviewTrack(cue.file, DubPreviewClock.offset(cue, positionMs), playing, cue.speed.toFloat(), 1f, loop = false)
    }
}

/** Where the mix plays a take: from [DubCue.atMs] for [DubCue.maxMs] of output, its lead skipped, at its speed. */
internal object DubPreviewClock {
    fun at(cues: List<DubCue>, positionMs: Long): DubCue? = cues.lastOrNull { positionMs >= it.atMs && positionMs < it.atMs + it.maxMs }
    fun offset(cue: DubCue, positionMs: Long): Long = cue.leadMs + ((positionMs - cue.atMs).coerceAtLeast(0) * cue.speed).toLong()
}
