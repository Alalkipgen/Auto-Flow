package io.github.alalkipgen.autoflow.ui.home

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.runtime.collectAsState
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiJobState
import org.koin.compose.getKoin
import io.github.alalkipgen.autoflow.core.recap.QuickRecapStage
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.etaSeconds
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors

/** Quick Recap wizard state for the UI (not persisted here). */
private enum class WizardStep { IDLE, LENGTH, STYLE, CONFIRM }

/**
 * Studio v2 Home screen (5A-6 / 5B-11, DESIGN.md §7 "Home").
 * Shows:
 * - Quick Recap wizard: movie → length chips (1·3·5·10 min) → style → Start
 * - Running jobs progress (placeholder until 5A-5 runner fully connected)
 * - Create: Recap · Music Video buttons
 */
@Composable
fun HomeScreen(
    onNewRecap: () -> Unit = {},
    onNewMusicVideo: () -> Unit = {},
    onQuickRecapStart: (targetMs: Long, style: RecapStyle) -> Unit = { _, _ -> },
    modifier: Modifier = Modifier,
    onOpenSettings: () -> Unit = {},
    /** AI provider status line (title bar text in the classic shell). */
    status: String? = null,
    recent: List<io.github.alalkipgen.autoflow.ui.projects.ProjectEntry> = emptyList(),
    onOpenProject: (dir: String) -> Unit = {},
) {
    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        androidx.compose.foundation.layout.Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            AfText(tr(UiText.HOME), Modifier.weight(1f), size = 20.sp, bold = true)
            AfButton(tr(UiText.SETTINGS), onOpenSettings, Modifier.testTag("home-settings"))
        }
        status?.let { AfText(it, muted = true, size = 12.sp) }

        // Quick Recap wizard card (5B-11)
        QuickRecapWizardCard(onQuickRecapStart)

        // Create shortcuts
        AfCard(tr(UiText.HOME_CREATE)) {
            WrapRow {
                AfButton(tr(UiText.HOME_NEW_RECAP), onNewRecap, kind = ButtonKind.Primary)
                AfButton(tr(UiText.HOME_NEW_MV), onNewMusicVideo)
            }
        }

        AfCard(tr(UiText.HOME_RECENT)) {
            if (recent.isEmpty()) AfText(tr(UiText.HOME_EMPTY), muted = true)
            recent.forEach { entry ->
                val kind = if (entry.mode == io.github.alalkipgen.autoflow.core.model.Mode.MOVIE_RECAP) "🎬" else "🎵"
                AfButton("$kind ${entry.name}", { onOpenProject(entry.path) }, Modifier.fillMaxWidth().testTag("recent-${entry.name}"))
            }
        }
    }
}

@Composable
private fun QuickRecapWizardCard(onStart: (Long, RecapStyle) -> Unit) {
    var step by remember { mutableStateOf(WizardStep.IDLE) }
    var targetMs by remember { mutableStateOf(300_000L) }
    var style by remember { mutableStateOf(RecapStyle.PLAIN) }

    AfCard(tr(UiText.HOME_QUICK)) {
        when (step) {
            WizardStep.IDLE -> {
                AfText(tr(UiText.HOME_QUICK_HINT), muted = true, size = 12.sp)
                AfText(tr(UiText.HOME_QUICK_SOURCE), muted = true, size = 11.sp)
                AfButton(tr(UiText.HOME_QUICK_START), { step = WizardStep.LENGTH }, kind = ButtonKind.Primary)
            }
            WizardStep.LENGTH -> {
                AfText(tr(UiText.LENGTH), bold = true)
                WrapRow {
                    listOf(60_000L, 180_000L, 300_000L, 600_000L).map { it to My.minutes(it / 60_000) }.forEach { (ms, label) ->
                        AfChip(label, targetMs == ms, onClick = { targetMs = ms })
                    }
                }
                WrapRow {
                    AfButton(tr(UiText.HOME_NEXT_STYLE), { step = WizardStep.STYLE }, kind = ButtonKind.Primary)
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
                    AfButton("${tr(UiText.START)} →", { step = WizardStep.CONFIRM }, kind = ButtonKind.Primary)
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
                    }, kind = ButtonKind.Primary)
                    AfButton(tr(UiText.BACK), { step = WizardStep.STYLE })
                }
            }
        }
    }

    // Running stages display — shown when a quick recap job is active
    // Full impl wired to AiJobRunner in 5A-5; placeholder here.
    QuickRecapStageList()
}

/**
 * Live Quick Recap stage list (5B-11): shows active/recent AI jobs of kind QUICK_RECAP (D-52).
 * Each row shows the project name, state, items left, and ETA.
 */
@Composable
private fun QuickRecapStageList() {
    val koin = getKoin()
    val runner = remember(koin) { koin.getOrNull<AiJobRunner>() }
    val jobs by (runner?.jobs?.collectAsState() ?: remember { mutableStateOf(emptyList()) })
    val active = jobs.filter { it.kind == AiJobKind.QUICK_RECAP || it.kind == AiJobKind.TTS_ALL }
        .filter { it.state != AiJobState.DONE && it.state != AiJobState.CANCELLED }
    if (active.isEmpty()) return
    AfCard(tr(UiText.HOME_ACTIVE)) {
        active.forEach { job ->
            val eta = job.etaSeconds()
            val etaText = if (eta != null && eta > 0) " · ~${eta}s" else ""
            val itemsLeft = job.items.size
            val stateLabel = when (job.state) {
                AiJobState.RUNNING -> tr(UiText.JOB_RUNNING)
                AiJobState.QUEUED -> tr(UiText.JOB_QUEUED)
                AiJobState.PAUSED -> tr(UiText.JOB_PAUSED)
                AiJobState.FAILED -> tr(UiText.JOB_FAILED)
                else -> job.state.name
            }
            val name = job.project.substringAfterLast('/').removeSuffix(".autoflow")
            AfText(
                tr(UiText.HOME_JOB_ROW, "stateLabel" to (stateLabel), "name" to (name), "value3" to (io.github.alalkipgen.autoflow.ui.jobs.aiKindLabel(job.kind, LocalUiLanguage.current)), "itemsLeft" to (itemsLeft), "etaText" to (etaText)),
                size = 12.sp,
                color = if (job.state == AiJobState.FAILED) AutoFlowColors.Amber else AutoFlowColors.Text,
            )
        }
    }
}

/** Burmese name of a Quick Recap style (5B-13); the saved value stays the enum. */
internal fun styleLabel(style: RecapStyle, language: UiLanguage = UiLanguage.ENGLISH): String = when (style) {
    RecapStyle.PLAIN -> UiText.STYLE_PLAIN.text(language)
    RecapStyle.COMMENTARY -> UiText.STYLE_COMMENTARY.text(language)
}
