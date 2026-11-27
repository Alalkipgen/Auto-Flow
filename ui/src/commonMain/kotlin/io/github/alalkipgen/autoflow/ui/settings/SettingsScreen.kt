package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import io.github.alalkipgen.autoflow.ui.i18n.*
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
import io.github.alalkipgen.autoflow.ui.jobs.JobRow
import io.github.alalkipgen.autoflow.ui.jobs.JobRunCard
import io.github.alalkipgen.autoflow.ui.jobs.JobSource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.ui.jobs.aiKindLabel
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AudioPlayButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AfTextField
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.FieldRow
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

const val TEST_TTS = "🔊 Test Gemini TTS (S5)"

/**
 * Settings (DESIGN.md §3, PLAN 1C): AI key and mode, defaults, ffmpeg / VLC, fonts, model config; [backup] adds
 * "Back up all projects" (Android, D-27), [info] adds About.
 */
@Composable
fun SettingsScreen(model: SettingsModel, modifier: Modifier = Modifier, info: AppInfo? = null, backup: BackupModel? = null,
    onOpenLexicon: (() -> Unit)? = null,
    runner: AiJobRunner? = null,
) {
    AppLanguage(model.appSettings) { SettingsContent(model, modifier, info, backup, onOpenLexicon, runner) }
}

@Composable
private fun SettingsContent(model: SettingsModel, modifier: Modifier, info: AppInfo?, backup: BackupModel?, onOpenLexicon: (() -> Unit)?, runner: AiJobRunner?) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.refresh(); model.refreshUsage() }
    LaunchedEffect(backup) { backup?.refresh() }
    Box(modifier.fillMaxSize().background(AutoFlowColors.Background), contentAlignment = Alignment.TopCenter) {
    Column(
        Modifier.widthIn(max = 900.dp).fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        AfText(tr(UiText.SETTINGS_TITLE), Modifier.testTag("settings-heading"), size = 24.sp, bold = true)
        LanguageCard(model)
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it), color = AutoFlowColors.Cyan) }
        AiCard(state, model, scope)
        ModelsCard(state, model, scope)
        onOpenLexicon?.let { open ->
            AfCard(tr(UiText.V3_PRONUNCIATION)) {
                AfButton(tr(UiText.V3_OPEN_LEXICON), open, Modifier.fillMaxWidth().testTag("settings-lexicon"))
            }
        }
        DefaultsCard(state.settings, model)
        FontsCard(state.settings.fontPaths, model.builtInFontPaths, model, scope)
        ToolsCard(state, model, scope)
        backup?.let { BackupCard(it) }
        PreviewFeaturesCard(model, initiallyExpanded = false)
        DiagnosticsCard(state, model, info, runner)
        info?.let { AboutCard(it) }
    }
    }
}

/** Shares SettingsStore.state with App and the separate Self-test activity. */
@Composable
fun LanguageCard(model: SettingsModel) {
    val settings by model.appSettings.collectAsState()
    AfCard(tr(UiText.LANGUAGE), Modifier.testTag("settings-language")) {
        AfText(tr(UiText.LANGUAGE_HINT), muted = true)
        WrapRow {
            UiLanguage.entries.forEach { language ->
                AfChip(language.nativeName, UiLanguage.fromCode(settings.uiLanguage) == language,
                    { model.setUiLanguage(language) }, Modifier.heightIn(min = 48.dp).testTag("language-${language.code}"))
            }
        }
    }
}

/** D-27: the projects live in app storage that an uninstall deletes, so the zip goes to Download/AutoFlow. */
@Composable
private fun BackupCard(model: BackupModel) {
    val state by model.state.collectAsState()
    SectionCard(tr(UiText.BACKUP), Modifier.testTag("settings-backup"), initiallyExpanded = false) {
        AfText(
            tr(UiText.BACKUP_STORAGE_HINT) +
                tr(UiText.BACKUP_DOWNLOAD_HINT),
            muted = true,
        )
        AfText(if (state.projects == 1) tr(UiText.ONE_PROJECT) else tr(UiText.MANY_PROJECTS, "projects" to (state.projects)), muted = true, size = 12.sp)
        val progress = state.progress
        WrapRow {
            if (progress != null) {
                AfText(tr(UiText.BACKUP_PROGRESS, "toInt" to ((progress.fraction * 100).toInt())), Modifier.testTag("backup-progress"))
                AfButton(tr(UiText.CANCEL), model::cancel, kind = ButtonKind.Danger)
            } else {
                AfButton(tr(UiText.BACKUP_ALL), model::start, kind = ButtonKind.Primary, enabled = state.projects > 0)
                if (state.last != null) AfButton(tr(UiText.SHARE), model::share)
                AfButton(tr(UiText.RESTORE_BACKUP), model::restore, kind = ButtonKind.Secondary)
            }
        }
        state.message?.let { AfText(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it), color = AutoFlowColors.Cyan, size = 12.sp) }
    }
}

/** Visible entry to real build/tool readouts and the same on-device Self-test as version long-press. */
@Composable
private fun DiagnosticsCard(state: SettingsUiState, model: SettingsModel, info: AppInfo?, runner: AiJobRunner?) {
    SectionCard(tr(UiText.V3_DIAGNOSTICS), Modifier.testTag("settings-diagnostics"), initiallyExpanded = false) {
        LaunchedEffect(model) { model.checkFfmpeg() }
        AfText(info?.version ?: tr(UiText.V3_BUILD_UNAVAILABLE), Modifier.testTag("diagnostics-build"), mono = true)
        AfText(state.ffmpegNotice?.text(LocalUiLanguage.current) ?: state.ffmpegCheck
            ?: tr(if (state.ffmpegFound) UiText.V3_TOOL_DETECTED else UiText.FFMPEG_NOT_FOUND), muted = true)
        if (!state.toolsBundled) AfText(state.vlcDir?.let { tr(UiText.VLC_USING, "vlcDir" to it) }
            ?: tr(UiText.VLC_MISSING), muted = true)
        val runs = runner?.jobs?.collectAsState()?.value.orEmpty()
        AfText(tr(UiText.LAST_RUN_TIMINGS), Modifier.testTag("diagnostics-run-timings"), bold = true)
        val recent = runs.filter { it.timings.isNotEmpty() }.maxByOrNull { it.timingsUpdatedAt ?: Long.MIN_VALUE }
        if (recent == null) AfText(tr(UiText.NO_RUN_TIMINGS), muted = true)
        else {
            AfText(aiKindLabel(recent.kind, LocalUiLanguage.current), muted = true)
            for ((stage, samples) in recent.timings.groupBy { it.stage }) {
                val values = "${samples.sumOf { it.ms }} ms · ×${samples.size}" +
                    (samples.mapNotNull { it.bytes }.takeIf { it.isNotEmpty() }?.let { " · ${it.sum()} B" } ?: "")
                AfText("${tr(stage.timingLabel())} · $values", muted = true)
            }
        }
        AfButton(tr(UiText.SELF_TEST), { info?.onVersionLongPress?.invoke() }, Modifier.testTag("settings-self-test"),
            enabled = info?.onVersionLongPress != null)
        if (info?.onVersionLongPress == null) AfText(My.breakSyllables(tr(UiText.V3_SELFTEST_ANDROID)), muted = true)
    }
}

private fun AiTimingStage.timingLabel() = when (this) {
    AiTimingStage.QUEUE -> UiText.TIMING_QUEUE
    AiTimingStage.MEDIA_PREP -> UiText.TIMING_MEDIA
    AiTimingStage.PACER -> UiText.TIMING_PACER
    AiTimingStage.UPLOAD -> UiText.TIMING_UPLOAD
    AiTimingStage.FIRST_BYTE -> UiText.TIMING_FIRST_BYTE
    AiTimingStage.RESPONSE -> UiText.TIMING_RESPONSE
    AiTimingStage.RETRY_WAIT -> UiText.TIMING_RETRY
    AiTimingStage.PARSE -> UiText.TIMING_PARSE
    AiTimingStage.SAVE -> UiText.SAVE
    AiTimingStage.UI_UPDATE -> UiText.TIMING_UI
}

/** Version line; on Android a long press opens the Self-test (PLAN S6), so it stays out of the way of normal use. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutCard(info: AppInfo) {
    SectionCard(tr(UiText.ABOUT), Modifier.testTag("settings-about"), initiallyExpanded = false) {
        val open = info.onVersionLongPress
        AfText(
            "Auto Flow ${info.version}",
            Modifier.testTag("about-version").then(if (open != null) Modifier.combinedClickable(onClick = {}, onLongClick = open) else Modifier),
        )
        if (open != null) AfText(tr(UiText.SELFTEST_OPEN_HINT), muted = true, size = 12.sp)
    }
}

@Composable
private fun AiCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    val hasKey = state.keyState == KeyState.SET
    SectionCard(tr(UiText.V3_AI_KEYS), Modifier.testTag("settings-ai"), initiallyExpanded = false) {
        AfSwitch(
            tr(UiText.USE_FAKE_AI),
            checked = state.settings.useFakeProviders,
            onChange = model::setUseFakes,
        )
        AfText(
            when (state.keyState) {
                KeyState.SET -> tr(UiText.KEY_SAVED_MASKED, "orEmpty" to (state.maskedKey.orEmpty()))
                KeyState.MISSING -> tr(UiText.KEY_NOT_SAVED)
                KeyState.UNREADABLE -> tr(UiText.KEY_UNREADABLE)
                KeyState.UNSUPPORTED -> tr(UiText.KEY_UNSUPPORTED)
            },
            muted = true,
        )
        FieldRow(
            field = { AfTextField(input, { input = it }, tr(UiText.KEY_PASTE), it, secret = true) },
            actions = {
                AfButton(tr(UiText.SAVE), { model.saveKey(input); input = "" }, kind = ButtonKind.Primary, enabled = input.isNotBlank())
                AfButton(
                    tr(UiText.TEST),
                    { scope.launch { model.testKey() } },
                    enabled = hasKey && !state.testing,
                )
                AfButton(tr(UiText.REMOVE), model::removeKey, kind = ButtonKind.Danger, enabled = hasKey)
            },
        )
        state.geminiTest?.takeIf { it.label == UiText.JOB_TEST_GEMINI_KEY }?.let {
            GeminiDiagnosticCard(it, model)
        }
        AfText(tr(UiText.KEY_TEST_HINT), muted = true, size = 12.sp)
        if (model.canTestTts) {
            var playing by remember { mutableStateOf(false) }
            WrapRow {
                AfButton(tr(UiText.TEST_TTS), { scope.launch { model.testTts() } }, enabled = hasKey && !state.testing)
                state.ttsSample?.let { AudioPlayButton(it, playing, { playing = !playing }) }
            }
            state.geminiTest?.takeIf { it.label == UiText.TEST_TTS }?.let {
                GeminiDiagnosticCard(it, model)
            }
            AfText(tr(UiText.TTS_TEST_HINT), muted = true, size = 12.sp)
        }
        if (model.chatboxOffered) ChatboxRows(state, model, scope)
        val total = state.usageToday.values.sum()
        AfText(
            tr(UiText.TODAY_CALLS_FIRST, "total" to (total)) + (if (LocalUiLanguage.current == UiLanguage.ENGLISH && total != 1) "s" else "") + tr(UiText.TODAY_CALLS_RETRIES) +
                state.usageToday.entries.joinToString(prefix = if (total > 0) ": " else "") {
                    "${it.key} ×${it.value}"
                },
            muted = true,
        )
    }
}

/** F-4 (D-93): the Chatbox AI license key next to the Gemini key, and which service answers text and Transcribe calls. */
@Composable
private fun ChatboxRows(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    val hasKey = state.chatboxKeyState == KeyState.SET
    AfText(tr(UiText.CHATBOX_AI), Modifier.testTag("settings-chatbox"))
    AfText(
        when (state.chatboxKeyState) {
            KeyState.SET -> tr(UiText.CHATBOX_KEY_SAVED_MASKED, "orEmpty" to state.maskedChatboxKey.orEmpty())
            KeyState.MISSING -> tr(UiText.CHATBOX_KEY_NOT_SAVED)
            KeyState.UNREADABLE, KeyState.UNSUPPORTED -> tr(UiText.CHATBOX_KEY_PROBLEM)
        },
        muted = true,
    )
    FieldRow(
        field = { AfTextField(input, { input = it }, tr(UiText.CHATBOX_KEY_PASTE), it.testTag("chatbox-key-field"), secret = true) },
        actions = {
            AfButton(tr(UiText.SAVE), { model.saveChatboxKey(input); input = "" }, Modifier.testTag("chatbox-key-save"),
                kind = ButtonKind.Primary, enabled = input.isNotBlank())
            AfButton(tr(UiText.TEST), { scope.launch { model.testChatboxKey() } }, Modifier.testTag("chatbox-key-test"),
                enabled = hasKey && !state.testing)
            AfButton(tr(UiText.REMOVE), model::removeChatboxKey, Modifier.testTag("chatbox-key-remove"), kind = ButtonKind.Danger, enabled = hasKey)
        },
    )
    state.geminiTest?.takeIf { it.label == UiText.JOB_TEST_CHATBOX_KEY }?.let { GeminiDiagnosticCard(it, model) }
    AfText(tr(UiText.CHATBOX_KEY_HINT), muted = true, size = 12.sp)
    ServiceChips(tr(UiText.AI_SERVICE_TEXT), state.settings.textService, hasKey, "text-service", model::setTextService)
    ServiceChips(tr(UiText.AI_SERVICE_TRANSCRIBE), state.settings.transcribeService, hasKey, "transcribe-service", model::setTranscribeService)
    AfText(tr(UiText.CHATBOX_TRANSCRIBE_HINT), muted = true, size = 12.sp)
}

@Composable
private fun ServiceChips(label: String, service: String, chatboxReady: Boolean, tag: String, onPick: (String) -> Unit) {
    val chatbox = service == io.github.alalkipgen.autoflow.ai.registry.AiService.CHATBOX
    AfText(label, muted = true)
    WrapRow {
        AfChip("Gemini", !chatbox, { onPick(io.github.alalkipgen.autoflow.ai.registry.AiService.GEMINI) }, Modifier.testTag("$tag-gemini"))
        AfChip("Chatbox AI", chatbox, { onPick(io.github.alalkipgen.autoflow.ai.registry.AiService.CHATBOX) }, Modifier.testTag("$tag-chatbox"),
            enabled = chatboxReady || chatbox)
    }
}

@Composable
private fun GeminiDiagnosticCard(run: GeminiDiagnosticRun, model: SettingsModel) {
    JobRunCard(JobRow(JobSource.AI, tr(run.label), canCancel = true, jobId = run.id, timedRun = true, indeterminate = true),
        Modifier.testTag("gemini-diagnostic-run"), headerAction = {
            AfButton(tr(UiText.CANCEL), { model.cancelGeminiTest(run.id) }, kind = ButtonKind.Danger)
        })
}

@Composable
private fun DefaultsCard(settings: AppSettings, model: SettingsModel) {
    SectionCard(tr(UiText.V3_EXPORT_DEFAULTS), Modifier.testTag("settings-defaults"), initiallyExpanded = false) {
        AfText(tr(UiText.RATIO), muted = true)
        WrapRow {
            Ratio.entries.forEach { r -> AfChip(r.label, r == settings.defaultRatio, { model.setDefaultRatio(r) }) }
        }
        AfText(tr(UiText.CLIP_LENGTH), muted = true)
        WrapRow {
            SettingsModel.CLIP_LENGTHS_MS.forEach { ms ->
                AfChip("${ms / 1000}s", ms == settings.defaultClipMs, { model.setDefaultClipMs(ms) })
            }
        }
    }
}

@Composable
private fun FfmpegCheckRow(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    LaunchedEffect(model, state.settings.ffmpegPath) { model.checkFfmpeg() }
    WrapRow {
        state.ffmpegCheck?.let { AfText(state.ffmpegNotice?.text(LocalUiLanguage.current) ?: it, size = 12.sp, color = if (state.ffmpegCheckWarn) AutoFlowColors.Amber else AutoFlowColors.Muted) }
        AfButton(tr(UiText.CHECK_FFMPEG), { scope.launch { model.checkFfmpeg() } })
    }
}

@Composable
private fun ToolsCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    SectionCard(tr(UiText.TOOLS), Modifier.testTag("settings-tools"), initiallyExpanded = false) {
        if (state.toolsBundled) {
            // Android (D-23): ffmpeg is inside the APK; there is nothing to point at or install.
            if (state.ffmpegFound) {
                AfText(tr(UiText.FFMPEG_BUNDLED), muted = true)
                FfmpegCheckRow(state, model, scope)
            } else {
                AfText(tr(UiText.FFMPEG_MISSING), color = AutoFlowColors.Amber)
            }
        } else {
            PathRow("ffmpeg", state.settings.ffmpegPath, tr(UiText.FFMPEG_PATH), model::setFfmpegPath) {
                AfButton(tr(UiText.DETECT), model::detectFfmpeg)
                AfButton(tr(UiText.BROWSE), { scope.launch { model.browseFfmpeg() } })
            }
            FfmpegCheckRow(state, model, scope)
            PathRow(tr(UiText.VLC_FOLDER), state.settings.vlcPath, tr(UiText.AUTO_DETECT), model::setVlcPath) {
                AfButton(tr(UiText.BROWSE), { scope.launch { model.browseVlc() } })
            }
            if (state.vlcDir != null) {
                AfText(tr(UiText.VLC_USING, "vlcDir" to (state.vlcDir)), muted = true, size = 12.sp)
            } else {
                WrapRow {
                    AfText(tr(UiText.VLC_MISSING), color = AutoFlowColors.Amber, size = 12.sp)
                    AfButton(tr(UiText.VLC_INSTALL), model::openVlcDownload, kind = ButtonKind.Flow)
                }
            }
        }
    }
}

@Composable
private fun FontsCard(fonts: List<String>, builtIn: List<String>, model: SettingsModel, scope: CoroutineScope) {
    SectionCard(tr(UiText.FONTS_OVERLAYS), Modifier.testTag("settings-fonts"), initiallyExpanded = false) {
        if (builtIn.isNotEmpty()) {
            AfText(tr(UiText.FONTS_BUNDLED_HINT), muted = true, size = 12.sp)
        }
        if (fonts.isEmpty()) AfText(tr(UiText.FONTS_EMPTY), muted = true)
        fonts.forEach { path ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText(path, Modifier.weight(1f), mono = true, size = 12.sp)
                AfButton(tr(UiText.REMOVE), { model.removeFont(path) })
            }
        }
        AfButton(tr(UiText.FONTS_ADD), { scope.launch { model.addFonts() } })
    }
}

@Composable
private fun ModelsCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    SectionCard(tr(UiText.MODEL_CONFIG), Modifier.testTag("settings-models"), initiallyExpanded = false) {
        AfText(tr(UiText.MODEL_IN_USE, "source" to (state.models.source)), muted = true)
        state.models.problem?.let { AfText(it, color = AutoFlowColors.Amber, size = 12.sp) }
        SectionCard(tr(UiText.V3_MORE_OPTIONS), initiallyExpanded = false) {
            PathRow(tr(UiText.MODELS_OVERRIDE), state.settings.modelsConfigPath, tr(UiText.BUNDLED_CONFIG), model::setModelsConfigPath) {
                AfButton(tr(UiText.BROWSE), { scope.launch { model.browseModelsConfig() } })
            }
        }
        Box(
            Modifier.fillMaxWidth().heightIn(max = 240.dp).background(AutoFlowColors.Background)
                .verticalScroll(rememberScrollState()).padding(10.dp),
        ) {
            AfText(state.models.text, mono = true, size = 12.sp, muted = true)
        }
    }
}

/**
 * Preview-features toggles (5A-3): one switch per flag in [FeatureFlags.asMap].
 * Shared with Android Self-test so both routes use the same stored overrides.
 */
@Composable
fun PreviewFeaturesCard(model: SettingsModel, initiallyExpanded: Boolean = true) {
    val state by model.state.collectAsState()
    val flags = state.features.asMap()
    if (flags.isEmpty()) return
    SectionCard(tr(UiText.PREVIEW_FEATURES), Modifier.testTag("settings-labs"), initiallyExpanded = initiallyExpanded) {
        AfText(tr(UiText.PREVIEW_FEATURES_HINT), muted = true)
        flags.forEach { (key, value) ->
            AfSwitch(key, value, { model.setFeature(key, it) })
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
    actions: @Composable () -> Unit,
) {
    var text by remember(saved) { mutableStateOf(saved.orEmpty()) }
    AfText(label, muted = true)
    FieldRow(
        field = { AfTextField(text, { text = it }, placeholder, it) },
        actions = {
            if (text != saved.orEmpty()) AfButton(tr(UiText.APPLY), { onApply(text) }, kind = ButtonKind.Primary)
            actions()
        },
    )
}
