package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * Settings (DESIGN.md §3, PLAN 1C): AI key and mode, defaults, ffmpeg / VLC, fonts, model config; [backup] adds
 * "Back up all projects" (Android, D-27), [info] adds About.
 */
@Composable
fun SettingsScreen(model: SettingsModel, modifier: Modifier = Modifier, info: AppInfo? = null, backup: BackupModel? = null) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.refreshUsage() }
    LaunchedEffect(backup) { backup?.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp).widthIn(max = 760.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText("Settings", size = 18.sp, bold = true)
        state.message?.let { AfText(it, color = AutoFlowColors.Cyan) }
        AiCard(state, model, scope)
        DefaultsCard(state.settings, model)
        ToolsCard(state, model, scope)
        FontsCard(state.settings.fontPaths, model, scope)
        ModelsCard(state, model, scope)
        backup?.let { BackupCard(it) }
        info?.let { AboutCard(it) }
    }
}

/** D-27: the projects live in app storage that an uninstall deletes, so the zip goes to Download/AutoFlow. */
@Composable
private fun BackupCard(model: BackupModel) {
    val state by model.state.collectAsState()
    AfCard("Backup") {
        AfText(
            "Projects are saved in this app's storage. Uninstalling Auto Flow deletes them, so back up first: " +
                "the zip goes to Download/AutoFlow and stays there after an uninstall.",
            muted = true,
        )
        AfText(if (state.projects == 1) "1 project" else "${state.projects} projects", muted = true, size = 12.sp)
        val progress = state.progress
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (progress != null) {
                AfText("Backing up… ${(progress.fraction * 100).toInt()} %", Modifier.testTag("backup-progress"))
                AfButton("Cancel", model::cancel, kind = ButtonKind.Danger)
            } else {
                AfButton("Back up all projects", model::start, kind = ButtonKind.Primary, enabled = state.projects > 0)
                if (state.last != null) AfButton("Share…", model::share)
            }
        }
        state.message?.let { AfText(it, color = AutoFlowColors.Cyan, size = 12.sp) }
    }
}

/** Version line; on Android a long press opens the Self-test (PLAN S6), so it stays out of the way of normal use. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutCard(info: AppInfo) {
    AfCard("About") {
        val open = info.onVersionLongPress
        AfText(
            "Auto Flow ${info.version}",
            Modifier.testTag("about-version").then(if (open != null) Modifier.combinedClickable(onClick = {}, onLongClick = open) else Modifier),
        )
        if (open != null) AfText("Long-press the version to open the Self-test.", muted = true, size = 12.sp)
    }
}

@Composable
private fun AiCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    val hasKey = state.keyState == KeyState.SET
    AfCard("AI providers") {
        AfSwitch(
            "Use fake AI providers (offline, no key needed)",
            checked = state.settings.useFakeProviders,
            onChange = model::setUseFakes,
        )
        AfText(
            when (state.keyState) {
                KeyState.SET -> "Gemini API key: saved ${state.maskedKey.orEmpty()}"
                KeyState.MISSING -> "Gemini API key: not saved"
                KeyState.UNREADABLE -> "Gemini API key: saved, but it cannot be read. Save it again."
                KeyState.UNSUPPORTED -> "Gemini API key: keys are stored with Windows DPAPI, so saving needs Windows."
            },
            muted = true,
        )
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfTextField(input, { input = it }, "Paste a Gemini API key", Modifier.weight(1f), secret = true)
            AfButton("Save", { model.saveKey(input); input = "" }, kind = ButtonKind.Primary, enabled = input.isNotBlank())
            AfButton(
                if (state.testing) "Testing…" else "Test",
                { scope.launch { model.testKey() } },
                enabled = hasKey && !state.testing,
            )
            AfButton("Remove", model::removeKey, kind = ButtonKind.Danger, enabled = hasKey)
        }
        AfText("Test sends one short request to Gemini, even in fake mode.", muted = true, size = 12.sp)
        val total = state.usageToday.values.sum()
        AfText(
            "Today: $total request" + (if (total == 1) "" else "s") +
                state.usageToday.entries.joinToString(prefix = if (total > 0) " (" else "", postfix = if (total > 0) ")" else "") {
                    "${it.key} ${it.value}"
                },
            muted = true,
        )
    }
}

@Composable
private fun DefaultsCard(settings: AppSettings, model: SettingsModel) {
    AfCard("New project defaults") {
        AfText("Ratio", muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Ratio.entries.forEach { r -> AfChip(r.label, r == settings.defaultRatio, { model.setDefaultRatio(r) }) }
        }
        AfText("Clip length", muted = true)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SettingsModel.CLIP_LENGTHS_MS.forEach { ms ->
                AfChip("${ms / 1000}s", ms == settings.defaultClipMs, { model.setDefaultClipMs(ms) })
            }
        }
    }
}

@Composable
private fun ToolsCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    AfCard("Tools") {
        PathRow("ffmpeg", state.settings.ffmpegPath, "Path to ffmpeg.exe", model::setFfmpegPath) {
            AfButton("Detect", model::detectFfmpeg)
            AfButton("Browse…", { scope.launch { model.browseFfmpeg() } })
        }
        PathRow("VLC folder (preview playback)", state.settings.vlcPath, "Auto-detect", model::setVlcPath) {
            AfButton("Browse…", { scope.launch { model.browseVlc() } })
        }
        if (state.vlcDir != null) {
            AfText("Using VLC in ${state.vlcDir}", muted = true, size = 12.sp)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText("VLC 3.x not found: video playback is off.", color = AutoFlowColors.Amber, size = 12.sp)
                AfButton("Install VLC", model::openVlcDownload, kind = ButtonKind.Flow)
            }
        }
    }
}

@Composable
private fun FontsCard(fonts: List<String>, model: SettingsModel, scope: CoroutineScope) {
    AfCard("Fonts (lyrics and title overlays)") {
        if (fonts.isEmpty()) AfText("No fonts added.", muted = true)
        fonts.forEach { path ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText(path, Modifier.weight(1f), mono = true, size = 12.sp)
                AfButton("Remove", { model.removeFont(path) })
            }
        }
        AfButton("Add fonts…", { scope.launch { model.addFonts() } })
    }
}

@Composable
private fun ModelsCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    AfCard("Model config") {
        AfText("In use: ${state.models.source}", muted = true)
        state.models.problem?.let { AfText(it, color = AutoFlowColors.Amber, size = 12.sp) }
        PathRow("Override models.json (applies after restart)", state.settings.modelsConfigPath, "Bundled config", model::setModelsConfigPath) {
            AfButton("Browse…", { scope.launch { model.browseModelsConfig() } })
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).background(AutoFlowColors.Background)
                .verticalScroll(rememberScrollState()).padding(10.dp),
        ) {
            AfText(state.models.text, mono = true, size = 12.sp, muted = true)
        }
    }
}

/** Label + editable path; "Apply" appears once the text differs from the saved value, and an empty value clears it. */
@Composable
private fun PathRow(
    label: String,
    saved: String?,
    placeholder: String,
    onApply: (String?) -> Unit,
    actions: @Composable RowScope.() -> Unit,
) {
    var text by remember(saved) { mutableStateOf(saved.orEmpty()) }
    AfText(label, muted = true)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AfTextField(text, { text = it }, placeholder, Modifier.weight(1f))
        if (text != saved.orEmpty()) AfButton("Apply", { onApply(text) }, kind = ButtonKind.Primary)
        actions()
    }
}
