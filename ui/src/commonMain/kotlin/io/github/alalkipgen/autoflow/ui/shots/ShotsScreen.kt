package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.workflow.ShotStatus
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.VideoPreview
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes
import io.github.alalkipgen.autoflow.ui.i18n.My

/** Human-readable badge label + color for each [ShotStatus]. */
fun ShotStatus.badgeLabel(language: io.github.alalkipgen.autoflow.ui.i18n.UiLanguage = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH): String = when (this) {
    ShotStatus.DRAFT -> UiText.DRAFT.text(language)
    ShotStatus.PROMPT_READY -> "✓ Prompt"
    ShotStatus.NEEDS_FLOW -> "→ ${UiText.NEEDS_FLOW.text(language)}"
    ShotStatus.IMPORTED -> UiText.IMPORTED.text(language)
    ShotStatus.ACCEPTED -> "✓ ${UiText.ACCEPTED.text(language)}"
    ShotStatus.STALE -> "⚠ ${UiText.STALE.text(language)}"
}

fun ShotStatus.badgeColor(): Color = when (this) {
    ShotStatus.DRAFT -> AutoFlowColors.Muted
    ShotStatus.PROMPT_READY -> AutoFlowColors.Cyan
    ShotStatus.NEEDS_FLOW -> AutoFlowColors.Purple
    ShotStatus.IMPORTED -> AutoFlowColors.Amber
    ShotStatus.ACCEPTED -> AutoFlowColors.Green
    ShotStatus.STALE -> AutoFlowColors.Stale
}

/**
 * Shots screen: grid of shot rows with status badges + detail panel for the selected shot.
 * Compile Prompt is deterministic (no AI call); approve locks the prompt for Flow Handoff.
 */
@Composable
fun ShotsScreen(model: ShotsModel, modifier: Modifier = Modifier) {
    val state by model.state.collectAsState(model.current())
    val compact = LocalCompact.current

    // Phone: show detail as a separate "page" (replaces the grid)
    if (compact && state.detail != null) {
        Column(
            modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
            ShotDetailPanel(state.detail!!, model)
            AfButton(tr(UiText.BACK), { model.select(null) })
        }
        return
    }

    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 860.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AfText(tr(UiText.SHOTS), size = 18.sp, bold = true)
        when {
            state.projectName == null -> AfText(tr(UiText.OPEN_PROJECT_FIRST), muted = true)
            state.rows.isEmpty() -> AfText(tr(UiText.NO_SHOTS_STORYBOARD), muted = true)
            else -> {
                state.actionError?.let { AfText(it, color = AutoFlowColors.Amber) }
                if (!compact && state.detail != null) {
                    // Desktop: side-by-side
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.widthIn(min = 160.dp, max = 220.dp)) { ShotGrid(state, model) }
                        Column(Modifier.weight(1f)) { ShotDetailPanel(state.detail!!, model) }
                    }
                } else {
                    ShotGrid(state, model)
                }
            }
        }
    }
}

@Composable
private fun ShotGrid(state: ShotsUiState, model: ShotsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        state.rows.forEach { row ->
            ShotRowItem(row, selected = row.number == state.selected) { model.select(row.number) }
        }
    }
}

@Composable
internal fun ShotRowItem(row: ShotRow, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) AutoFlowColors.Card else AutoFlowColors.Panel
    val border = if (selected) AutoFlowColors.Purple else AutoFlowColors.Line
    Row(
        Modifier
            .fillMaxWidth()
            .clip(AutoFlowShapes.Card)
            .border(AutoFlowShapes.Border, border, AutoFlowShapes.Card)
            .background(bg)
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        AfText("Shot ${row.number}", bold = true, size = 13.sp)
        AfText(row.timecode, muted = true, size = 12.sp)
        Box(Modifier.weight(1f)) {
            AfText(My.breakSyllables(row.lyric.ifBlank { "—" }), muted = row.lyric.isBlank(), size = 12.sp)
        }
        StatusBadge(row.status)
    }
}

@Composable
internal fun StatusBadge(status: ShotStatus) {
    Box(
        Modifier
            .clip(AutoFlowShapes.Card)
            .background(status.badgeColor().copy(alpha = 0.18f))
            .border(AutoFlowShapes.Border, status.badgeColor().copy(alpha = 0.5f), AutoFlowShapes.Card)
            .padding(horizontal = 7.dp, vertical = 3.dp),
    ) {
        AfText(status.badgeLabel(io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage.current), color = status.badgeColor(), size = 11.sp)
    }
}

@Composable
private fun ShotDetailPanel(detail: ShotDetail, model: ShotsModel) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfText("Shot ${detail.number}", size = 16.sp, bold = true)
            AfText(detail.timecode, muted = true, size = 13.sp)
            StatusBadge(detail.status)
        }
        detail.lyric.takeIf { it.isNotBlank() }?.let { AfText(My.breakSyllables(it), size = 13.sp) }

        AfText(tr(UiText.SHOT_PASS, "pass" to (passLabel(detail.pass, LocalUiLanguage.current))), muted = true, size = 12.sp)

        // Video versions (version browser, Draft / Final)
        if (detail.versions.isNotEmpty()) VersionBrowser(detail, model)

        // Storyboard spec summary
        if (detail.spec != null) {
            SectionLabel(tr(UiText.STORYBOARD_SPEC))
            SpecField(tr(UiText.SCENE_MY), detail.spec.sceneMy)
            SpecField(tr(UiText.FIELD_SUBJECT), detail.spec.subjectEn)
            SpecField(tr(UiText.FIELD_ACTION), detail.spec.actionEn)
            SpecField(tr(UiText.FIELD_SETTING), detail.spec.settingEn)
            SpecField(tr(UiText.FIELD_CAMERA), detail.spec.cameraEn)
            SpecField(tr(UiText.FIELD_MOOD), detail.spec.moodEn)
            SpecField(tr(UiText.FIELD_LIGHTING), detail.spec.lightingEn)
        } else {
            AfText(tr(UiText.GENERATE_STORYBOARD_FIRST), muted = true, size = 13.sp)
        }

        PromptSection(detail, model)
    }
}

/** Prompt (Veo / Flow): compiled text, approve, recompile — Shots detail and the Studio v2 shot sheet (5B-12). */
@Composable
internal fun PromptSection(detail: ShotDetail, model: ShotsModel) {
    var confirmCompile by remember(detail.number) { mutableStateOf(false) }
    SectionLabel(tr(UiText.FLOW_PROMPT))
    when {
        detail.promptText != null -> {
            Box(
                Modifier.fillMaxWidth()
                    .clip(AutoFlowShapes.Card)
                    .background(AutoFlowColors.Background)
                    .border(AutoFlowShapes.Border, AutoFlowColors.Line, AutoFlowShapes.Card)
                    .padding(10.dp),
            ) {
                AfText(detail.promptText, size = 12.sp, mono = true)
            }
            val approveLabel = if (detail.approvedPrompt) "✓ ${tr(UiText.APPROVED)}" else "✓ Prompt ${tr(UiText.APPROVE)}"
            WrapRow {
                AfButton(approveLabel,
                    { model.approvePrompt(detail.number, !detail.approvedPrompt) },
                    kind = if (detail.approvedPrompt) ButtonKind.Primary else ButtonKind.Secondary,
                )
                if (confirmCompile) {
                    AfButton(tr(UiText.RECOMPILE_YES), {
                        confirmCompile = false
                        model.compilePrompt(detail.number)
                    }, kind = ButtonKind.Danger)
                    AfButton(tr(UiText.CANCEL), { confirmCompile = false })
                } else {
                    AfButton(tr(UiText.RECOMPILE), { confirmCompile = true }, kind = ButtonKind.Secondary)
                }
            }
        }
        detail.compileError != null -> {
            AfText(tr(UiText.COMPILE_ERROR, "compileError" to (detail.compileError)), color = AutoFlowColors.Amber, size = 12.sp)
        }
        detail.spec != null -> {
            AfText(tr(UiText.PROMPT_NOT_COMPILED), muted = true, size = 12.sp)
            AfButton(tr(UiText.COMPILE_PROMPT), { model.compilePrompt(detail.number) }, kind = ButtonKind.Primary)
        }
        else -> AfText(tr(UiText.STORYBOARD_FIRST), muted = true, size = 12.sp)
    }
}

@Composable
internal fun VersionBrowser(detail: ShotDetail, model: ShotsModel) {
    SectionLabel(versionsTitle(detail.versions.size, LocalUiLanguage.current))
    detail.versions.firstOrNull { it.id == detail.previewing }?.path?.let { path -> key(path) { VideoPreview(path) } }
    detail.versions.asReversed().forEach { v ->
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            AfText(versionLine(v, io.github.alalkipgen.autoflow.ui.i18n.LocalUiLanguage.current), size = 12.sp, bold = v.id == detail.previewing,
                color = if (v.accepted) AutoFlowColors.Green else AutoFlowColors.Text)
            WrapRow {
                if (v.id != detail.previewing && v.path != null) {
                    AfButton(tr(UiText.PREVIEW_VERSION), { model.preview(detail.number, v.id) }, kind = ButtonKind.Secondary)
                }
                if (!v.accepted) {
                    AfButton(tr(UiText.USE_VIDEO_VERSION), { model.acceptVersion(detail.number, v.id) }, kind = ButtonKind.Primary)
                }
            }
        }
    }
    if (detail.versions.firstOrNull { it.accepted }?.pass == Pass.DRAFT) {
        AfText(tr(UiText.DRAFT_ACCEPTED_HINT), color = AutoFlowColors.Amber, size = 12.sp)
    }
}

internal fun passLabel(pass: Pass, language: UiLanguage = UiLanguage.ENGLISH): String = (if (pass == Pass.FINAL) UiText.PASS_FINAL else UiText.PASS_DRAFT).text(language)

internal fun versionsTitle(n: Int, language: UiLanguage = UiLanguage.ENGLISH): String = UiText.VIDEO_VERSION_COUNT.text(language, "n" to n)

/** `v2 · Final (Quality) · 2026-11-12 10:00 · ✓ Accepted`. */
internal fun versionLine(v: VideoVersion, language: io.github.alalkipgen.autoflow.ui.i18n.UiLanguage = io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.ENGLISH): String = buildList {
    add("v${v.version}")
    v.pass?.let { add(passLabel(it, language)) }
    if (v.importedAt.isNotBlank()) add(v.importedAt)
    if (v.accepted) add("✓ ${UiText.ACCEPTED.text(language)}")
}.joinToString(" · ")

internal const val PREVIEW_VERSION_BUTTON = "▶ ကြည့်မယ်"
/** Accepts this one version (the Flow handoff card's "✓ ဗွီဒီယို လက်ခံမယ်" accepts the newest import). */
internal const val USE_VERSION_BUTTON = "✓ ဒီဗားရှင်း လက်ခံမယ်"
internal const val DRAFT_ACCEPTED_HINT =
    "The accepted video is a Draft (Fast). For the final cut: Flow Handoff → Pass: Final (Quality), import the new video, then use it here."

@Composable
private fun SectionLabel(text: String) {
    AfText(text, size = 11.sp, muted = true)
}

@Composable
private fun SpecField(label: String, value: String) {
    if (value.isBlank()) return
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        AfText("$label:", muted = true, size = 12.sp)
        AfText(value, size = 12.sp)
    }
}
