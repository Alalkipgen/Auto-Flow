package io.github.alalkipgen.autoflow.ui.home

import io.github.alalkipgen.autoflow.ui.projects.label
import io.github.alalkipgen.autoflow.ui.jobs.JobRunCard
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.ui.components.*
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.jobs.*
import io.github.alalkipgen.autoflow.ui.projects.ProjectEntry
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Transient wizard state only; the existing Quick Recap engine owns creation and approval. */
private enum class WizardStep { IDLE, LENGTH, STYLE, CONFIRM }

/** Home never probes/decodes media or estimates project progress. Jobs uses the identical projection. */
@Composable
fun HomeScreen(
    onNewRecap: () -> Unit = {},
    onNewMusicVideo: () -> Unit = {},
    onQuickRecapStart: (targetMs: Long, style: RecapStyle) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    status: String? = null,
    notice: String? = null,
    recent: List<ProjectEntry> = emptyList(),
    onOpenProject: (dir: String) -> Unit = {},
    jobs: List<JobRow> = rememberJobRows(),
    onViewJobs: () -> Unit = {},
    continueStep: String? = null,
    continueProjectPath: String? = null,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit = { _, _ -> },
    jobActions: io.github.alalkipgen.autoflow.ui.jobs.JobsActions? = null,
    /** Opens the picker for a new Subtitles project; null keeps the tile Planned (flag `tools.subtitles` off, D-84). */
    onNewSubtitles: (() -> Unit)? = null,
    /** Opens the Voice & Audio chooser; null keeps the tile Planned (flag `tools.voiceAudio` off, D-84). */
    onNewVoice: (() -> Unit)? = null,
    /** Opens the picker for a new Dubbing project; null keeps the tile Planned (flag `labs.dubbing` off, D-84). */
    onNewDubbing: (() -> Unit)? = null,
) {
    Box(modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
        Column(Modifier.widthIn(max = 900.dp).fillMaxSize().paneScroll(rememberScrollState()).padding(screenPadding)
            .testTag("home-scroll"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            WrapRow(Modifier.testTag("home-header")) {
                AfText(tr(UiText.V3_APP_NAME), size = 24.sp, bold = true)
                AfButton(tr(UiText.SETTINGS_TITLE), onOpenSettings, Modifier.testTag("home-settings"))
            }
            status?.let { AfText(My.breakSyllables(it), muted = true) }
            notice?.let { AfText(My.breakSyllables(it), color = AutoFlowColors.Failed) }
            QuickRecapWizardCard(onQuickRecapStart, onNewRecap, backHandler)
            if (recent.isEmpty()) AfText(My.breakSyllables(tr(UiText.HOME_EMPTY)), muted = true)
            recent.maxByOrNull { it.lastOpenedAt }?.let { entry ->
                AfCard(tr(UiText.V3_CONTINUE), Modifier.testTag("home-continue")) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        HomeModeIcon(entry.mode)
                        AfText(tr(entry.mode.label()), muted = true)
                    }
                    AfButton(entry.name, { onOpenProject(entry.path) }, Modifier.fillMaxWidth().testTag("recent-${entry.name}"))
                    if (entry.path == continueProjectPath) continueStep?.let {
                        AfText(My.breakSyllables(it), Modifier.testTag("home-continue-step"), muted = true)
                    }
                }
            }
            AfCard(tr(UiText.HOME_CREATE)) {
                AfButton(tr(UiText.HOME_NEW_RECAP), onNewRecap, Modifier.fillMaxWidth().testTag("home-new-recap"))
                AfButton(tr(UiText.HOME_NEW_MV), onNewMusicVideo, Modifier.fillMaxWidth().testTag("home-new-mv"))
                if (onNewDubbing != null) AfButton(tr(UiText.V3_DUBBING), onNewDubbing, Modifier.fillMaxWidth().testTag("home-dubbing"))
                else PlannedTile(tr(UiText.V3_DUBBING), modifier = Modifier.testTag("home-dubbing"))
            }
            SectionCard(tr(UiText.V3_QUICK_TOOLS)) {
                AfText(My.breakSyllables(tr(UiText.V3_TOOLS_IN_PROJECT)), muted = true)
                if (onNewVoice != null) AfButton(tr(UiText.V3_VOICE_AUDIO), onNewVoice, Modifier.fillMaxWidth().testTag("home-voice-tool"))
                else PlannedTile(tr(UiText.V3_VOICE_AUDIO), modifier = Modifier.testTag("home-voice-tool"))
                if (onNewSubtitles != null) AfButton(tr(UiText.V3_SUBTITLES), onNewSubtitles, Modifier.fillMaxWidth().testTag("home-subtitles-tool"))
                else PlannedTile(tr(UiText.V3_SUBTITLES), modifier = Modifier.testTag("home-subtitles-tool"))
            }
            val running = jobs.filter { it.running }
            if (running.isNotEmpty()) SectionCard(tr(UiText.JOB_RUNNING), Modifier.testTag("home-running")) {
                running.forEachIndexed { i, row ->
                    JobRunCard(row, Modifier.testTag("home-job-$i")) {
                        if (row.canCancel && jobActions?.canCancel(row) == true) {
                            AfButton(tr(UiText.CANCEL), { jobActions.cancel(row) }, Modifier.testTag("home-job-cancel-$i"), kind = ButtonKind.Danger)
                        }
                    }
                }
                AfButton(tr(UiText.V3_VIEW_JOBS), onViewJobs, Modifier.testTag("home-view-jobs"))
            }
        }
    }
}

/** A decorative fallback next to the full localized mode label; never depends on the system emoji font. */
@Composable
private fun HomeModeIcon(mode: Mode) {
    Canvas(Modifier.size(20.dp).testTag("home-mode-${mode.name}")) {
        val u = size.width / 20f
        when (mode) {
            Mode.MOVIE_RECAP -> {
                drawRoundRect(AutoFlowColors.Purple, Offset(2 * u, 4 * u), Size(16 * u, 12 * u), CornerRadius(2 * u))
                for (x in listOf(5f, 15f)) for (y in listOf(7f, 13f)) {
                    drawCircle(AutoFlowColors.Panel, u, Offset(x * u, y * u))
                }
            }
            Mode.MUSIC_VIDEO -> {
                drawLine(AutoFlowColors.Cyan, Offset(10 * u, 13 * u), Offset(10 * u, 4 * u), 2 * u)
                drawLine(AutoFlowColors.Cyan, Offset(10 * u, 4 * u), Offset(16 * u, 6 * u), 2 * u)
                drawOval(AutoFlowColors.Cyan, Offset(4 * u, 11 * u), Size(7 * u, 5 * u))
            }
            Mode.SUBTITLES -> {
                drawRoundRect(AutoFlowColors.Green, Offset(2 * u, 4 * u), Size(16 * u, 12 * u), CornerRadius(2 * u))
                drawLine(AutoFlowColors.Panel, Offset(5 * u, 10 * u), Offset(15 * u, 10 * u), 1.5f * u)
                drawLine(AutoFlowColors.Panel, Offset(7 * u, 13 * u), Offset(13 * u, 13 * u), 1.5f * u)
            }
            Mode.VOICE_AUDIO -> { // three sound bars
                for ((x, h) in listOf(6f to 6f, 10f to 12f, 14f to 8f)) {
                    drawLine(AutoFlowColors.Amber, Offset(x * u, (10 - h / 2) * u), Offset(x * u, (10 + h / 2) * u), 2.5f * u)
                }
            }
            Mode.DUBBING -> { // a speech bubble
                drawRoundRect(AutoFlowColors.Cyan, Offset(2 * u, 3 * u), Size(16 * u, 11 * u), CornerRadius(3 * u))
                drawLine(AutoFlowColors.Cyan, Offset(6 * u, 13 * u), Offset(5 * u, 18 * u), 2 * u)
            }
        }
    }
}

@Composable
private fun QuickRecapWizardCard(onStart: (Long, RecapStyle) -> Unit, onStepByStep: () -> Unit,
    backHandler: @Composable (Boolean, () -> Unit) -> Unit,
) {
    var step by remember { mutableStateOf(WizardStep.IDLE) }
    var targetMs by remember { mutableStateOf(300_000L) }
    var style by remember { mutableStateOf(RecapStyle.PLAIN) }

    backHandler(step != WizardStep.IDLE) { step = WizardStep.entries[(step.ordinal - 1).coerceAtLeast(0)] }
    AfCard(tr(UiText.HOME_QUICK), selected = true) {
        when (step) {
            WizardStep.IDLE -> {
                AfText(My.breakSyllables(tr(UiText.V3_APPROVAL_BEFORE_VOICE)), muted = true)
                AfText(My.breakSyllables(tr(UiText.HOME_QUICK_SOURCE)), muted = true)
                AfButton(tr(UiText.HOME_QUICK_START), { step = WizardStep.LENGTH }, Modifier.fillMaxWidth().testTag("home-quick-start"), kind = ButtonKind.Primary)
                AfButton(tr(UiText.V3_STEP_BY_STEP), onStepByStep, Modifier.testTag("home-step-by-step"))
            }
            WizardStep.LENGTH -> {
                AfText(tr(UiText.LENGTH), bold = true)
                WrapRow {
                    listOf(60_000L, 180_000L, 300_000L, 600_000L).map { it to My.minutes(it / 60_000) }.forEach { (ms, label) ->
                        AfChip(label, targetMs == ms, onClick = { targetMs = ms })
                    }
                }
                WrapRow {
                    AfButton(tr(UiText.HOME_NEXT_STYLE), { step = WizardStep.STYLE }, Modifier.testTag("quick-next-style"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.CANCEL), { step = WizardStep.IDLE })
                }
            }
            WizardStep.STYLE -> {
                AfText(tr(UiText.STYLE), bold = true)
                WrapRow {
                    AfChip(styleLabel(RecapStyle.PLAIN, LocalUiLanguage.current), style == RecapStyle.PLAIN, onClick = { style = RecapStyle.PLAIN })
                    AfChip(styleLabel(RecapStyle.COMMENTARY, LocalUiLanguage.current), style == RecapStyle.COMMENTARY, onClick = { style = RecapStyle.COMMENTARY })
                }
                WrapRow {
                    AfButton("${tr(UiText.START)} →", { step = WizardStep.CONFIRM }, Modifier.testTag("quick-confirm"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { step = WizardStep.LENGTH })
                }
            }
            WizardStep.CONFIRM -> {
                val minLabel = My.minutes(targetMs / 60_000)
                AfText(tr(UiText.HOME_CONFIRM, "minLabel" to (minLabel), "styleLabelstyle" to (styleLabel(style, LocalUiLanguage.current))), bold = true)
                AfText(tr(UiText.HOME_CONFIRM_HINT, "APPROVE" to (tr(UiText.APPROVE)), "EDIT" to (tr(UiText.EDIT))), muted = true, size = 12.sp)
                AfText(tr(UiText.HOME_BACKGROUND), muted = true, size = 12.sp)
                WrapRow {
                    AfButton(tr(UiText.START), {
                        onStart(targetMs, style)
                        step = WizardStep.IDLE
                    }, Modifier.testTag("quick-create"), kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { step = WizardStep.STYLE })
                }
            }
        }
    }

}

/** The saved style remains an enum; project contents are never translated. */
internal fun styleLabel(style: RecapStyle, language: UiLanguage = UiLanguage.ENGLISH): String = when (style) {
    RecapStyle.PLAIN -> UiText.STYLE_PLAIN.text(language)
    RecapStyle.COMMENTARY -> UiText.STYLE_COMMENTARY.text(language)
}
