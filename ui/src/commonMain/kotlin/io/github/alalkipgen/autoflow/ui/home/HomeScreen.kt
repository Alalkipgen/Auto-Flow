package io.github.alalkipgen.autoflow.ui.home

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
import io.github.alalkipgen.autoflow.core.recap.QuickRecapStage
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
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
            AfText("Home", Modifier.weight(1f), size = 20.sp, bold = true)
            AfButton("⚙ Settings", onOpenSettings, Modifier.testTag("home-settings"))
        }
        status?.let { AfText(it, muted = true, size = 12.sp) }

        // Quick Recap wizard card (5B-11)
        QuickRecapWizardCard(onQuickRecapStart)

        // Create shortcuts
        AfCard("Create") {
            WrapRow {
                AfButton("🎬 New Movie Recap", onNewRecap, kind = ButtonKind.Primary)
                AfButton("🎵 New Music Video", onNewMusicVideo)
            }
        }

        AfCard("Recent projects") {
            if (recent.isEmpty()) AfText("No projects yet. New ones show here; all of them are in the Projects tab.", muted = true)
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

    AfCard("⚡ Quick Recap") {
        when (step) {
            WizardStep.IDLE -> {
                AfText("Turn a movie into a recap in one tap — stops only for your approval.", muted = true, size = 12.sp)
                AfText("Needs subtitles or a transcript embedded in the movie.", muted = true, size = 11.sp)
                AfButton("Start Quick Recap…", { step = WizardStep.LENGTH }, kind = ButtonKind.Primary)
            }
            WizardStep.LENGTH -> {
                AfText("Target length", bold = true)
                WrapRow {
                    listOf(60_000L to "1 min", 180_000L to "3 min", 300_000L to "5 min", 600_000L to "10 min").forEach { (ms, label) ->
                        AfChip(label, targetMs == ms, onClick = { targetMs = ms })
                    }
                }
                WrapRow {
                    AfButton("Next: Style →", { step = WizardStep.STYLE }, kind = ButtonKind.Primary)
                    AfButton("Cancel", { step = WizardStep.IDLE })
                }
            }
            WizardStep.STYLE -> {
                AfText("Style", bold = true)
                WrapRow {
                    AfChip("Plain", style == RecapStyle.PLAIN, onClick = { style = RecapStyle.PLAIN })
                    AfChip("Commentary", style == RecapStyle.COMMENTARY, onClick = { style = RecapStyle.COMMENTARY })
                }
                WrapRow {
                    AfButton("Start →", { step = WizardStep.CONFIRM }, kind = ButtonKind.Primary)
                    AfButton("Back", { step = WizardStep.LENGTH })
                }
            }
            WizardStep.CONFIRM -> {
                val minLabel = "${targetMs / 60_000} min"
                AfText("Quick Recap · $minLabel · $style", bold = true)
                AfText("Scenes will be detected, AI picks scenes, Gemini writes the script. Stops at Story review — you approve or edit.", muted = true, size = 12.sp)
                AfText("Then TTS and render run as background jobs.", muted = true, size = 12.sp)
                WrapRow {
                    AfButton("Start", {
                        onStart(targetMs, style)
                        step = WizardStep.IDLE
                    }, kind = ButtonKind.Primary)
                    AfButton("Back", { step = WizardStep.STYLE })
                }
            }
        }
    }

    // Running stages display — shown when a quick recap job is active
    // Full impl wired to AiJobRunner in 5A-5; placeholder here.
    QuickRecapStageList()
}

@Composable
private fun QuickRecapStageList() {
    // Placeholder: in full impl this reads from AiJobRunner.jobs filtered by QUICK_RECAP kind
    // and shows each stage with progress/ETA.
    // Listed here as stub so the UI compiles and renders the section header.
}
