package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesTopBar
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import io.github.alalkipgen.autoflow.ui.theme.GlassShapes

/**
 * Voice & Audio (specs/quick-tools.md §6): the task chooser (Text → voice, Clean up sound, Video sound), then Text → voice with the text, its counter and part count; once the project exists also the voice
 * lock, tone chips and the parts list (V-1d) with play / redo and Speak (V-1e, [speak]); once every part is voiced Save m4a /
 * Save wav (V-1f, [export]). Clean up sound (V-2b, [clean]). Video sound (V-3b, [video]). [onBack] leaves the tool.
 */
@Composable
fun VoiceAudioScreen(model: VoiceAudioModel, session: ProjectSession, modifier: Modifier = Modifier, onBack: (() -> Unit)? = null,
    onOpened: () -> Unit = {}, speak: VoiceSpeakModel? = null, export: VoiceExportModel? = null,
    clean: VoiceCleanModel? = null, video: VideoSoundModel? = null) {
    val open by session.state.collectAsState()
    val s by model.state.collectAsState()
    val jobs by (speak?.jobs ?: remember { kotlinx.coroutines.flow.MutableStateFlow(emptyList()) }).collectAsState()
    val job = speak?.jobOf(s.dir, jobs)
    LaunchedEffect(open?.dir) { model.show() }
    LaunchedEffect(open?.project) { model.sync() }
    DisposableEffect(Unit) { onDispose { model.flush() } }
    val back: (() -> Unit)? = if (s.fresh && s.task != null) model::backToChooser else onBack
    // Group 6 A 1: the ambient background (Group 1), not the flat Background fill.
    Column(modifier.fillMaxSize().ambientBackground(), horizontalAlignment = Alignment.CenterHorizontally) {
        // 🆕 VA-1: the glass project bar, fixed above the scroll — back (the same rule), the project (or Voice & Audio while
        // fresh / on the chooser) and the task under it.
        val task = when (s.task) {
            VoiceTool.TASK_TEXT -> UiText.VA_TEXT_TO_VOICE
            VoiceTool.TASK_CLEAN -> UiText.VA_CLEAN
            VoiceTool.TASK_VIDEO_SOUND -> UiText.VA_VIDEO_SOUND
            else -> null
        }
        SubtitlesTopBar(open?.project?.name?.takeIf { !s.fresh } ?: tr(UiText.V3_VOICE_AUDIO),
            task?.let { My.breakSyllables(tr(it)) }, dirty = false, onBack = back, tag = "voice-top-bar", backTag = "voice-back")
        val title = open?.project?.name.orEmpty()
        val notice = @Composable {
            // Group 6 A 4: the notice = a red callout under the top bar.
            s.notice?.let { GlassCallout(AfIcons.Fill.XCircle, My.breakSyllables(it.text(LocalUiLanguage.current)), AutoFlowColors.Failed,
                textModifier = Modifier.testTag("voice-notice")) }
        }
        BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
            // 🆕 VA-5 (Group 6 I): from 840 dp Text → voice is two panes — left (≈ 46 %) the text, the voice card (open) and
            // the tone; right the Speak / Save panels and the parts (own scroll) with the sticky under them. Everything else
            // (and Text → voice before the project exists) is one column, at most 720 dp, centred.
            val wide = maxWidth >= 840.dp
            if (wide && s.task == VoiceTool.TASK_TEXT && !s.fresh) Row(Modifier.fillMaxSize().testTag("voice-wide")) {
                Column(Modifier.weight(.46f).fillMaxHeight().paneScroll(rememberScrollState()).padding(screenPadding).testTag("voice-scroll"),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    notice()
                    TextTop(model, s, job, wide = true)
                }
                Column(Modifier.weight(.54f).fillMaxHeight()) {
                    Column(Modifier.weight(1f).fillMaxWidth().paneScroll(rememberScrollState()).padding(screenPadding),
                        verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        TextBottom(model, s, speak, job, export, title)
                    }
                    TextSticky(model, s, speak, job, export)
                }
            } else Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally) {
                Column(Modifier.widthIn(max = if (wide) 720.dp else 900.dp).fillMaxWidth().weight(1f).paneScroll(rememberScrollState()).padding(screenPadding)
                    .testTag("voice-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    notice()
                    when (s.task) {
                        null -> Chooser(model, clean, video, onOpened)
                        VoiceTool.TASK_CLEAN -> if (clean != null) CleanStep(clean, open?.project?.voice, s.dir, export, title)
                        VoiceTool.TASK_VIDEO_SOUND -> if (video != null) VideoSoundStep(video, open?.project?.voice, s.dir, export, title)
                        VoiceTool.TASK_TEXT -> { TextTop(model, s, job, wide = false); if (!s.fresh) TextBottom(model, s, speak, job, export, title) }
                        else -> AfText(My.breakSyllables(tr(UiText.NEEDS_NEWER_APP)), muted = true)
                    }
                }
                Stickies(model, s, open?.project?.voice?.source != null, speak, job, export, clean, video, onOpened)
            }
        }
    }
}

/** The sticky bar of the task (as before): Continue · Clean's / Video sound's saves · Text → voice's Speak or Save. */
@Composable
private fun Stickies(model: VoiceAudioModel, s: VoiceAudioUi, hasSource: Boolean, speak: VoiceSpeakModel?, job: io.github.alalkipgen.autoflow.core.recap.AiJob?,
    export: VoiceExportModel?, clean: VoiceCleanModel?, video: VideoSoundModel?, onOpened: () -> Unit) {
        if (s.fresh && s.task == VoiceTool.TASK_TEXT) StickyActionBar(
            primary = StudioAction(My.breakSyllables(tr(UiText.VA_CONTINUE_TEXT)), { model.createWithText(onOpened) }, enabled = s.text.isNotBlank(),
                tag = "voice-continue", trailingIcon = AfIcons.Regular.ArrowRight),
        ) else if (s.task == VoiceTool.TASK_CLEAN && clean != null && hasSource) {
            val c by clean.state.collectAsState()
            val idle = c.busy == null || c.dir != s.dir
            // Group 6 G 5: both download-simple.
            StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { clean.save("m4a") }, enabled = idle, tag = "voice-clean-m4a", icon = AfIcons.Regular.DownloadSimple),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { clean.save("wav") }, enabled = idle, tag = "voice-clean-wav", icon = AfIcons.Regular.DownloadSimple))
        } else if (s.task == VoiceTool.TASK_VIDEO_SOUND && video != null && hasSource) {
            val v by video.state.collectAsState()
            val idle = v.busy == null || v.dir != s.dir
            val action = v.action.takeIf { v.dir == s.dir } ?: SOUND_ONLY
            // Group 6 H 6: Save wav / m4a download-simple; Make the video film-strip.
            if (action == SOUND_ONLY) StickyActionBar(
                primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { video.save("m4a") }, enabled = idle, tag = "video-sound-m4a", icon = AfIcons.Regular.DownloadSimple),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { video.save("wav") }, enabled = idle, tag = "video-sound-wav", icon = AfIcons.Regular.DownloadSimple))
            else StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.SUB_MAKE_VIDEO)), { video.save() },
                enabled = idle && v.dir == s.dir && v.sound != null, tag = "video-sound-make", icon = AfIcons.Regular.FilmStrip))
        } else if (s.task == VoiceTool.TASK_TEXT) TextSticky(model, s, speak, job, export)
}

/** Text → voice's sticky (Group 6 D 3): all voiced → Save wav · Save m4a; else Speak n parts + 🆕 VA-6 caption; none while a job is open. */
@Composable
private fun TextSticky(model: VoiceAudioModel, s: VoiceAudioUi, speak: VoiceSpeakModel?, job: io.github.alalkipgen.autoflow.core.recap.AiJob?,
    export: VoiceExportModel?) {
        if (job == null) {
            val n = s.rows.count { !it.voiced }
            // Group 6 D 3: all voiced → Save wav · Save m4a (download-simple); else Speak n parts (microphone) + 🆕 VA-6 "3 of 5 parts voiced".
            if (n == 0 && s.rows.isNotEmpty() && export != null) StickyActionBar(
                primary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_M4A)), { export.saveM4a() }, tag = "voice-save-m4a", icon = AfIcons.Regular.DownloadSimple),
                secondary = StudioAction(My.breakSyllables(tr(UiText.VA_SAVE_WAV)), { export.saveWav() }, tag = "voice-save-wav", icon = AfIcons.Regular.DownloadSimple),
            ) else if (speak != null) StickyActionBar(primary = StudioAction(My.breakSyllables(tr(UiText.VA_SPEAK_PARTS, "n" to n)), { speak.ask() }, enabled = n > 0,
                tag = "voice-speak", icon = AfIcons.Fill.Microphone),
                caption = My.breakSyllables(tr(UiText.VA_PARTS_CAPTION, "done" to s.rows.size - n, "n" to s.rows.size)).takeIf { s.rows.isNotEmpty() },
                captionTag = "voice-parts-caption")
        }
}

@Composable
private fun Chooser(model: VoiceAudioModel, clean: VoiceCleanModel?, video: VideoSoundModel?, onOpened: () -> Unit) {
    // Group 6 B 1: the title H2 (22 sp bold).
    AfText(My.breakSyllables(tr(UiText.VA_CHOOSE)), type = AfType.ScreenTitle)
    // B 2: three glass task cards (tile, title, info, caret-right); B 3: a task not built here = the planned card.
    TaskCard(AfIcons.Fill.Microphone, GlassColors.RecapPink, UiText.VA_TEXT_TO_VOICE, UiText.VA_TEXT_TO_VOICE_INFO,
        { model.choose(VoiceTool.TASK_TEXT) }, "voice-task-text")
    if (clean == null) PlannedTaskCard(AfIcons.Fill.Broom, UiText.VA_CLEAN, "voice-task-clean")
    else {
        TaskCard(AfIcons.Fill.Broom, GlassColors.RecapTeal, UiText.VA_CLEAN, UiText.VA_CLEAN_INFO, { clean.start(onOpened) }, "voice-task-clean")
        val c by clean.state.collectAsState()
        c.notice?.takeIf { c.dir == null }?.let { ChooserNotice(it, "voice-clean-notice") }
    }
    if (video == null) PlannedTaskCard(AfIcons.Fill.FileVideo, UiText.VA_VIDEO_SOUND, "voice-task-video")
    else {
        TaskCard(AfIcons.Fill.FileVideo, GlassColors.ExportBlue, UiText.VA_VIDEO_SOUND, UiText.VA_VIDEO_SOUND_INFO, { video.start(onOpened) },
            "voice-task-video")
        val v by video.state.collectAsState()
        v.notice?.takeIf { v.dir == null }?.let { ChooserNotice(it, "video-sound-notice") }
    }
}

/** One task of the chooser (Group 6 B 2): a glass card tinted with its tile colour (α .14), radius 20, min 96 dp. */
@Composable
private fun TaskCard(icon: ImageVector, colour: Color, title: UiText, info: UiText, onClick: () -> Unit, tag: String) {
    GlassCard(Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag(tag).semantics { role = Role.Button }, onClick = onClick, tint = colour,
        tintAlpha = .14f) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            FilledTile(icon, colour, size = 48.dp)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(My.breakSyllables(tr(title)), size = 16.sp, bold = true)
                AfText(My.breakSyllables(tr(info)), muted = true, size = 13.sp) // no 3-line cap: at font 2.0 the Burmese info needs more (never cut)
            }
            AfIcon(AfIcons.Regular.CaretRight, tint = AutoFlowColors.Muted, size = 20.dp)
        }
    }
}

/** A task not built here (B 3, the Group 3 planned look): dashed frame, grey tile with the task's icon, the Planned pill. */
@Composable
private fun PlannedTaskCard(icon: ImageVector, title: UiText, tag: String) {
    Row(Modifier.fillMaxWidth().heightIn(min = 96.dp).glassDashedBox(20.dp).padding(16.dp).testTag(tag).semantics { disabled() },
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FilledTile(icon, AutoFlowColors.Surface3, size = 48.dp, ink = AutoFlowColors.Muted)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            AfText(My.breakSyllables(tr(title)), size = 16.sp, bold = true, muted = true)
            StatusChip(StudioStatus.PLANNED)
        }
    }
}

/** A chooser notice right under its card (B 4): amber `warning`, or red `x-circle` when the file cannot be read / the step failed. */
@Composable
private fun ChooserNotice(notice: UiNotice, tag: String) {
    val failed = notice.key == UiText.VA_UNREADABLE || notice.key == UiText.RESULT_DETAIL
    GlassCallout(if (failed) AfIcons.Fill.XCircle else AfIcons.Fill.Warn, My.breakSyllables(notice.text(LocalUiLanguage.current)),
        if (failed) AutoFlowColors.Failed else AutoFlowColors.Amber, textModifier = Modifier.testTag(tag))
}

@Composable
private fun TextTop(model: VoiceAudioModel, s: VoiceAudioUi, job: io.github.alalkipgen.autoflow.core.recap.AiJob?, wide: Boolean) {
    val idle = job == null
    // Group 6 C 1: no H2 (the top bar's subtitle names the task); the glass text field, its count right-aligned under it.
    AfTextField(s.text, model::setText, tr(UiText.VA_PASTE), Modifier.fillMaxWidth().testTag("voice-text"), lines = 8, mono = false, enabled = idle,
        glass = true)
    val parts = remember(s.text) { model.parts(s.text) }
    AfText(My.breakSyllables(tr(UiText.VA_COUNT, "chars" to s.text.length, "n" to parts)), Modifier.fillMaxWidth().testTag("voice-count"),
        color = AutoFlowColors.Muted.copy(alpha = if (idle) 1f else .5f), size = 12.sp, textAlign = TextAlign.End)
    if (s.fresh) return
    VoicePicker(model, s, idle, wide)
    // C 4: the tone — a 14 sp bold label and glass filter chips (off while speaking).
    AfText(tr(UiText.VA_TONE), size = 14.sp, bold = true)
    WrapRow {
        TONES.forEach { (id, label) -> GlassFilterChip(My.breakSyllables(tr(label)), s.tone == id, { model.setTone(id) }, Modifier.testTag("voice-tone-$id"), enabled = idle) }
    }
}

/** Text → voice under the tone (or the right pane at ≥ 840 dp): the Speak and Save panels, then the parts card. */
@Composable
private fun TextBottom(model: VoiceAudioModel, s: VoiceAudioUi, speak: VoiceSpeakModel?, job: io.github.alalkipgen.autoflow.core.recap.AiJob?,
    export: VoiceExportModel?, title: String) {
    s.dir?.let { dir -> speak?.let { SpeakPanel(it, dir, job, s, model.edgeVoices()) } }
    s.dir?.let { dir -> export?.let { ExportPanel(it, dir, title) } }
    PartsCard(model, s, speak, job)
}

/**
 * Group 6 D 1–2: the parts card — the list-numbers tile (Amber), "5 parts" and ⓘ (VA-4: the take rule) — and one glass row
 * per part: the number tile, the text, the status pill (Voiced · Not voiced yet · Running for the part being spoken), and
 * for a voiced part play + speak again (40 dp icon buttons). The playing row has the cyan outline.
 */
@Composable
private fun PartsCard(model: VoiceAudioModel, s: VoiceAudioUi, speak: VoiceSpeakModel?, job: io.github.alalkipgen.autoflow.core.recap.AiJob?) {
    val idle = job == null
    val speaking = job?.takeIf { it.state == io.github.alalkipgen.autoflow.core.recap.AiJobState.RUNNING }?.items?.firstOrNull()
    var playing by remember { mutableStateOf<String?>(null) }
    var rule by remember { mutableStateOf(false) }
    val language = LocalUiLanguage.current
    GlassCard(Modifier.fillMaxWidth(), gap = 10.dp) {
        val title = tr(UiText.VA_PARTS, "n" to s.rows.size)
        val ruleState = tr(if (rule) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
        GlassCardHeader(title, { SoftTile(AfIcons.Fill.ListNumbers, AutoFlowColors.Amber, size = 30.dp) }, trailing = {
            GlassIconButton(AfIcons.Regular.Info, title, { rule = !rule },
                Modifier.testTag("voice-info-parts").semantics { stateDescription = ruleState },
                size = GlassShapes.IconButtonHeader, selected = rule)
        })
        if (rule) AfText(My.breakSyllables(tr(UiText.VA_TAKE_RULE)), muted = true, size = 12.sp)
        s.rows.forEachIndexed { i, row ->
            val shape = RoundedCornerShape(14.dp)
            val on = playing == row.id
            Row(Modifier.fillMaxWidth().clip(shape).background(Color.White.copy(alpha = .04f))
                .border(if (on) 1.5.dp else 1.dp, if (on) AutoFlowColors.Cyan.copy(alpha = .55f) else Color.White.copy(alpha = .08f), shape)
                .padding(12.dp).testTag("voice-part-${row.id}"), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val tint = if (row.voiced) AutoFlowColors.Green else AutoFlowColors.Muted
                // 28 dp; it grows with a large font (2.0) so the number is never cut.
                Box(Modifier.defaultMinSize(28.dp, 28.dp).clip(RoundedCornerShape(8.dp)).background(tint.copy(alpha = .16f)).padding(horizontal = 4.dp),
                    contentAlignment = Alignment.Center) {
                    AfText("${i + 1}", color = if (row.voiced) AutoFlowColors.Green else AutoFlowColors.Muted, size = 13.sp, mono = true, bold = true)
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfText(My.breakSyllables(row.text), size = 14.sp)
                    val (icon, tone, text) = when {
                        speaking == row.id -> Triple(AfIcons.Regular.Redo, AutoFlowColors.Cyan, StudioStatus.RUNNING.label(language))
                        row.voiced -> Triple(AfIcons.Fill.CheckCircle, AutoFlowColors.Green, tr(UiText.VA_VOICED))
                        else -> Triple(AfIcons.Regular.CircleDashed, AutoFlowColors.Muted, tr(UiText.VA_NOT_VOICED))
                    }
                    WrapRow(spacing = 8.dp) {
                        io.github.alalkipgen.autoflow.ui.shots.TonePill(tone, icon, text, Modifier.align(Alignment.CenterVertically)
                            .testTag("voice-part-${row.id}-status").semantics(mergeDescendants = true) {})
                        if (row.take != null) {
                            model.takePath(row.take)?.let { AudioPlayButton(it, on, { playing = if (on) null else row.id }, glass = true,
                                glassIcon = AfIcons.Fill.Play, playingIcon = AfIcons.Fill.Pause, playingTone = AutoFlowColors.Cyan) }
                            if (speak != null) GlassIconButton(AfIcons.Regular.Redo, tr(UiText.VA_REDO_PART), { speak.ask(setOf(row.id)) },
                                Modifier.testTag("voice-redo-${row.id}"), size = GlassShapes.IconButtonHeader, enabled = idle)
                        }
                    }
                }
            }
        }
    }
}

internal val TONES = listOf("calm" to UiText.VA_TONE_CALM, "lively" to UiText.VA_TONE_LIVELY, "news" to UiText.VA_TONE_NEWS,
    "story" to UiText.VA_TONE_STORY)

/**
 * 🆕 VA-2 the voice card (Group 6 C 2–3; Dubbing's Group 4 card): a summary row — the 44 dp avatar, "Nilar · Edge" and
 * under it "Edge voices · free" (Edge) or the model id (Gemini) — and a caret that opens the same controls as before, each
 * group under a small label: saved voices first (Library), the Burmese models (ⓘ = where they come from; none = the amber
 * callout), the Gemini voices, the Edge voices (ⓘ = the caveat). Closed at first when a voice is chosen and models exist;
 * while Speak runs the caret is off and the card stays closed. A tap saves the new lock.
 */
@Composable
private fun VoicePicker(model: VoiceAudioModel, s: VoiceAudioUi, idle: Boolean, wide: Boolean = false) {
    val lock = s.lock
    val edge = model.edgeVoices()
    val noModels = model.models.isEmpty()
    // VA-5: open by default on a computer (two panes).
    var expanded by remember(s.dir, wide) { mutableStateOf(wide || lock == null || noModels) }
    val open = expanded && idle
    val voiceTitle = tr(UiText.VA_VOICE)
    GlassCard(Modifier.fillMaxWidth(), gap = 12.dp) {
        Row(Modifier.fillMaxWidth().testTag("voice-summary"), verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(Modifier.size(44.dp).clip(CircleShape).background(if (lock != null) Brush.linearGradient(listOf(GlassColors.RecapPink, AutoFlowColors.Purple))
                else Brush.linearGradient(listOf(AutoFlowColors.Surface3, AutoFlowColors.Surface3))), contentAlignment = Alignment.Center) {
                AfIcon(AfIcons.Fill.UserSound, tint = if (lock != null) Color.White else AutoFlowColors.Muted, size = 22.dp)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(My.breakSyllables(lock?.let { voiceLabel(it, edge) } ?: voiceTitle), size = 15.sp, bold = true)
                when {
                    lock == null -> AfText(My.breakSyllables(tr(UiText.VA_NO_VOICE)), color = AutoFlowColors.Amber, size = 12.sp)
                    lock.provider == EdgeVoices.PROVIDER -> AfText(My.breakSyllables(tr(UiText.V3_EDGE_VOICES_SHORT)), muted = true, size = 12.sp)
                    else -> AfText(lock.model, muted = true, size = 12.sp, mono = true)
                }
            }
            val expandState = tr(if (open) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
            GlassIconButton(if (open) AfIcons.Regular.CaretUp else AfIcons.Regular.CaretDown, voiceTitle, { expanded = !expanded },
                Modifier.testTag("voice-expand").semantics { stateDescription = expandState },
                size = GlassShapes.IconButtonHeader, enabled = idle)
        }
        if (open) {
            Box(Modifier.fillMaxWidth().height(1.dp).background(Color.White.copy(alpha = .08f)))
            if (s.savedVoices.isNotEmpty()) {
                SmallLabel(tr(UiText.VA_SAVED_VOICES))
                WrapRow { s.savedVoices.forEach { v -> GlassFilterChip(v.name, lock == v.lock, { model.setLock(v.lock) }, Modifier.testTag("voice-saved-${v.id}"), enabled = idle) } }
            }
            // VA-4: the long models text behind ⓘ.
            InfoLabel(tr(UiText.V3_TTS_MODELS_SHORT), tr(UiText.BURMESE_TTS_MODELS), "voice-info-models")
            if (noModels) GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.BURMESE_MODELS_MISSING)), AutoFlowColors.Amber)
            else WrapRow {
                model.models.forEach { id ->
                    GlassFilterChip(id, lock?.model == id, { model.setLock(VoiceLock("gemini", id, lock?.voice ?: "Charon", "my")) }, Modifier.testTag("voice-model-$id"), enabled = idle)
                }
            }
            SmallLabel(tr(UiText.V3_GEMINI_VOICES))
            WrapRow {
                RecapModel.TTS_VOICES.forEach { (name, _) ->
                    val modelId = EdgeVoices.geminiModel(lock, model.models)
                    GlassFilterChip(My.breakSyllables("$name · ${ttsVoiceKind(name)}"), lock?.voice == name, { modelId?.let { model.setLock(VoiceLock("gemini", it, name, "my")) } },
                        Modifier.testTag("voice-voice-$name"), enabled = idle && modelId != null)
                }
            }
            edge?.let { EdgeVoiceChips(it, lock, idle, "voice", glass = true, infoTag = "voice-info-edge") { l -> model.setLock(l) } }
        }
    }
}

/** A group's small 12 sp muted label (Group 1 / 4 size). */
@Composable
private fun SmallLabel(text: String) = AfText(My.breakSyllables(text), muted = true, size = 12.sp)

/** VA-4: a small label with ⓘ at its end; ⓘ opens [hint] (the long helper text) under it. */
@Composable
internal fun InfoLabel(label: String, hint: String, tag: String?, modifier: Modifier = Modifier) {
    var shown by remember { mutableStateOf(false) }
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(2.dp)) {
            AfText(My.breakSyllables(label), Modifier.weight(1f, fill = false), muted = true, size = 12.sp)
            val infoState = tr(if (shown) UiText.V3_EXPANDED else UiText.V3_COLLAPSED)
            GlassIconButton(AfIcons.Regular.Info, label, { shown = !shown },
                (tag?.let { Modifier.testTag(it) } ?: Modifier).semantics { stateDescription = infoState },
                size = 28.dp, selected = shown)
        }
        if (shown) AfText(My.breakSyllables(hint), muted = true, size = 12.sp)
    }
}
