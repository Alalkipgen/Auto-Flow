package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.ai.registry.AiService
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.AudioPlayButton
import io.github.alalkipgen.autoflow.ui.components.BesideOrUnder
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.GlassButton
import io.github.alalkipgen.autoflow.ui.components.GlassCallout
import io.github.alalkipgen.autoflow.ui.components.GlassCard
import io.github.alalkipgen.autoflow.ui.components.GlassCardHeader
import io.github.alalkipgen.autoflow.ui.components.GlassChoiceRow
import io.github.alalkipgen.autoflow.ui.components.GlassCollapsible
import io.github.alalkipgen.autoflow.ui.components.GlassDialogAction
import io.github.alalkipgen.autoflow.ui.components.GlassEmptyRow
import io.github.alalkipgen.autoflow.ui.components.GlassEqualButtons
import io.github.alalkipgen.autoflow.ui.components.GlassFieldRow
import io.github.alalkipgen.autoflow.ui.components.GlassFilterChip
import io.github.alalkipgen.autoflow.ui.components.GlassHairline
import io.github.alalkipgen.autoflow.ui.components.GlassInsetDivider
import io.github.alalkipgen.autoflow.ui.components.GlassJsonBox
import io.github.alalkipgen.autoflow.ui.components.GlassMonoBox
import io.github.alalkipgen.autoflow.ui.components.GlassPill
import io.github.alalkipgen.autoflow.ui.components.GlassProgress
import io.github.alalkipgen.autoflow.ui.components.GlassRowTile
import io.github.alalkipgen.autoflow.ui.components.GlassSegment
import io.github.alalkipgen.autoflow.ui.components.GlassSegmented
import io.github.alalkipgen.autoflow.ui.components.GlassSettingsList
import io.github.alalkipgen.autoflow.ui.components.GlassSwitchRow
import io.github.alalkipgen.autoflow.ui.components.GlassTextField
import io.github.alalkipgen.autoflow.ui.components.MonoText
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.components.SoftTile
import io.github.alalkipgen.autoflow.ui.components.ToneChip
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcon
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.jobs.JobRow
import io.github.alalkipgen.autoflow.ui.jobs.JobRunCard
import io.github.alalkipgen.autoflow.ui.jobs.JobSource
import io.github.alalkipgen.autoflow.ui.jobs.aiKindLabel
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.GlassColors
import io.github.alalkipgen.autoflow.ui.theme.Tone
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/*
 * The Settings sub-pages (P9-17a, Settings sample README §2): the old cards' controls, strings, enable rules, model
 * calls and test tags in the glass layout of `images/<lang>/1…9-*.png`.
 */

/** Caption-size helper text, Muted, with Burmese syllable breaks (README §1.4). */
@Composable
private fun Hint(text: String, modifier: Modifier = Modifier, colour: Color = AutoFlowColors.Muted) =
    AfText(My.breakSyllables(text), modifier, color = colour, type = AfType.Caption)

// §2.1 Language

@Composable
internal fun LanguagePage(model: SettingsModel) {
    Column(Modifier.fillMaxWidth().testTag("settings-language"), verticalArrangement = Arrangement.spacedBy(16.dp)) { LanguageChoices(model) }
}

/** [LanguagePage] in a titled card; shares SettingsStore.state with App and the separate Self-test activity. */
@Composable
fun LanguageCard(model: SettingsModel) {
    AfCard(tr(UiText.LANGUAGE), Modifier.testTag("settings-language")) { LanguageChoices(model) }
}

/** `LANGUAGE_HINT` over a choice list: one row per [UiLanguage] (its native name and a radio), divider inset 14. */
@Composable
private fun LanguageChoices(model: SettingsModel) {
    val settings by model.appSettings.collectAsState()
    Hint(tr(UiText.LANGUAGE_HINT))
    GlassSettingsList(UiLanguage.entries.map { language ->
        @Composable {
            GlassChoiceRow(language.nativeName, UiLanguage.fromCode(settings.uiLanguage) == language, { model.setUiLanguage(language) },
                Modifier.testTag("language-${language.code}"))
        }
    }, dividerStart = 14.dp)
}

// §2.2 AI & keys

@Composable
internal fun AiPage(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope, chatboxModifier: Modifier) {
    Column(Modifier.fillMaxWidth().testTag("settings-ai"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(padding = PaddingValues(vertical = 4.dp), gap = 0.dp) {
            GlassSwitchRow(tr(UiText.USE_FAKE_AI), state.settings.useFakeProviders, model::setUseFakes)
        }
        GeminiKeyCard(state, model, scope)
        if (model.chatboxOffered) {
            ChatboxKeyCard(state, model, scope, chatboxModifier)
            ServicesCard(state, model)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AfIcon(AfIcons.Regular.Clock, Modifier.padding(top = 2.dp), tint = AutoFlowColors.Muted, size = 17.dp)
            AfText(todayCalls(state, retries = true), Modifier.weight(1f), muted = true)
        }
    }
}

@Composable
private fun GeminiKeyCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    var input by remember { mutableStateOf("") }
    val hasKey = state.keyState == KeyState.SET
    KeyCard(
        title = tr(UiText.GEMINI_KEY), icon = AfIcons.Fill.Key, colour = AutoFlowColors.Purple, key = state.keyState, chipTag = "gemini-key-chip",
        missing = tr(UiText.KEY_NOT_SAVED), masked = state.maskedKey, maskedSentence = tr(UiText.KEY_SAVED_MASKED, "orEmpty" to state.maskedKey.orEmpty()),
        problem = when (state.keyState) {
            KeyState.UNREADABLE -> tr(UiText.KEY_UNREADABLE)
            KeyState.UNSUPPORTED -> tr(UiText.KEY_UNSUPPORTED)
            else -> null
        },
        field = { GlassTextField(input, { input = it }, tr(UiText.KEY_PASTE), Modifier.fillMaxWidth(), secret = true) },
        actions = listOf(
            GlassDialogAction(tr(UiText.SAVE), { model.saveKey(input); input = "" }, ButtonKind.Primary, enabled = input.isNotBlank()),
            GlassDialogAction(tr(UiText.TEST), { scope.launch { model.testKey() } }, enabled = hasKey && !state.testing),
            GlassDialogAction(tr(UiText.REMOVE), model::removeKey, ButtonKind.Danger, enabled = hasKey),
        ),
        diagnostic = { state.geminiTest?.takeIf { it.label == UiText.JOB_TEST_GEMINI_KEY }?.let { GeminiDiagnosticCard(it, model) } },
        hint = tr(UiText.KEY_TEST_HINT),
    ) {
        if (model.canTestTts) {
            var playing by remember { mutableStateOf(false) }
            GlassHairline()
            WrapRow {
                GlassButton(tr(UiText.TEST_TTS), { scope.launch { model.testTts() } }, enabled = hasKey && !state.testing, icon = AfIcons.Fill.Volume)
                state.ttsSample?.let { AudioPlayButton(it, playing, { playing = !playing }) }
            }
            state.geminiTest?.takeIf { it.label == UiText.TEST_TTS }?.let { GeminiDiagnosticCard(it, model) }
            Hint(tr(UiText.TTS_TEST_HINT))
        }
    }
}

/** F-4 (D-93): the Chatbox AI license key, laid out as the Gemini key card. */
@Composable
private fun ChatboxKeyCard(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope, modifier: Modifier) {
    var input by remember { mutableStateOf("") }
    val hasKey = state.chatboxKeyState == KeyState.SET
    KeyCard(
        title = tr(UiText.CHATBOX_AI), icon = AfIcons.Fill.Spark, colour = AutoFlowColors.Cyan, key = state.chatboxKeyState, chipTag = "chatbox-key-chip",
        missing = tr(UiText.CHATBOX_KEY_NOT_SAVED), masked = state.maskedChatboxKey,
        maskedSentence = tr(UiText.CHATBOX_KEY_SAVED_MASKED, "orEmpty" to state.maskedChatboxKey.orEmpty()),
        problem = if (state.chatboxKeyState == KeyState.UNREADABLE || state.chatboxKeyState == KeyState.UNSUPPORTED) tr(UiText.CHATBOX_KEY_PROBLEM) else null,
        field = { GlassTextField(input, { input = it }, tr(UiText.CHATBOX_KEY_PASTE), Modifier.fillMaxWidth().testTag("chatbox-key-field"), secret = true) },
        actions = listOf(
            GlassDialogAction(tr(UiText.SAVE), { model.saveChatboxKey(input); input = "" }, ButtonKind.Primary, enabled = input.isNotBlank(), tag = "chatbox-key-save"),
            GlassDialogAction(tr(UiText.TEST), { scope.launch { model.testChatboxKey() } }, enabled = hasKey && !state.testing, tag = "chatbox-key-test"),
            GlassDialogAction(tr(UiText.REMOVE), model::removeChatboxKey, ButtonKind.Danger, enabled = hasKey, tag = "chatbox-key-remove"),
        ),
        diagnostic = { state.geminiTest?.takeIf { it.label == UiText.JOB_TEST_CHATBOX_KEY }?.let { GeminiDiagnosticCard(it, model) } },
        hint = tr(UiText.CHATBOX_KEY_HINT),
        modifier = modifier.testTag("settings-chatbox"),
    )
}

/**
 * A key card (README §2.2; tint [colour] .14, gap 10): header tile + [title] + the key chip; the masked key in a mono
 * pill (SET; [maskedSentence] is its description), the [problem] line (UNREADABLE in Failed, UNSUPPORTED Muted) or
 * nothing (MISSING); the field, Save / Test / Remove, a running test, the [hint], then [extra].
 */
@Composable
private fun KeyCard(title: String, icon: ImageVector, colour: Color, key: KeyState, chipTag: String, missing: String, masked: String?,
    maskedSentence: String, problem: String?, field: @Composable () -> Unit, actions: List<GlassDialogAction>,
    diagnostic: @Composable () -> Unit, hint: String, modifier: Modifier = Modifier, extra: @Composable ColumnScope.() -> Unit = {}) {
    GlassCard(modifier, tint = colour, tintAlpha = GlassColors.TintSettingsCard, gap = 10.dp) {
        GlassCardHeader(title, { GlassRowTile(icon, colour) }, trailing = keyChip(key, missing, chipTag))
        when (key) {
            KeyState.SET -> GlassPill(masked.orEmpty(), description = maskedSentence)
            KeyState.MISSING -> Unit
            else -> problem?.let { Hint(it, colour = if (key == KeyState.UNREADABLE) AutoFlowColors.Failed else AutoFlowColors.Muted) }
        }
        field()
        GlassEqualButtons(actions)
        diagnostic()
        Hint(hint)
        extra()
    }
}

/** Who answers text and Transcribe calls: two-segment controls; Chatbox AI only once its key is saved (or chosen). */
@Composable
private fun ServicesCard(state: SettingsUiState, model: SettingsModel) {
    val ready = state.chatboxKeyState == KeyState.SET
    GlassCard(gap = 14.dp) {
        ServiceChoice(tr(UiText.AI_SERVICE_TEXT), state.settings.textService, ready, "text-service", model::setTextService)
        ServiceChoice(tr(UiText.AI_SERVICE_TRANSCRIBE), state.settings.transcribeService, ready, "transcribe-service", model::setTranscribeService)
        GlassCallout(AfIcons.Fill.Info, My.breakSyllables(tr(UiText.CHATBOX_TRANSCRIBE_HINT)), AutoFlowColors.Amber, textColour = AutoFlowColors.Text)
    }
}

@Composable
private fun ServiceChoice(label: String, service: String, chatboxReady: Boolean, tag: String, onPick: (String) -> Unit) {
    val chatbox = service == AiService.CHATBOX
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Hint(label)
        GlassSegmented(
            listOf(GlassSegment("Gemini", "$tag-gemini"), GlassSegment("Chatbox AI", "$tag-chatbox", enabled = chatboxReady || chatbox)),
            selected = if (chatbox) 1 else 0,
            onSelect = { onPick(if (it == 0) AiService.GEMINI else AiService.CHATBOX) },
        )
    }
}

@Composable
private fun GeminiDiagnosticCard(run: GeminiDiagnosticRun, model: SettingsModel) {
    JobRunCard(JobRow(JobSource.AI, tr(run.label), canCancel = true, jobId = run.id, timedRun = true, indeterminate = true),
        Modifier.testTag("gemini-diagnostic-run"), headerAction = {
            GlassButton(tr(UiText.CANCEL), { model.cancelGeminiTest(run.id) }, kind = ButtonKind.Danger, small = true)
        })
}

// §2.3 Model config

@Composable
internal fun ModelsPage(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    var more by rememberSaveable { mutableStateOf(false) }
    GlassCard(Modifier.testTag("settings-models"), gap = 12.dp) {
        GlassCardHeader(tr(UiText.MODEL_IN_USE, "source" to state.models.source), { GlassRowTile(AfIcons.Regular.Cpu, null) })
        state.models.problem?.let { Hint(it, colour = AutoFlowColors.Amber) }
        GlassCollapsible(tr(UiText.V3_MORE_OPTIONS), more, { more = !more }, Modifier.testTag("models-more")) {
            PathField(tr(UiText.MODELS_OVERRIDE), state.settings.modelsConfigPath, tr(UiText.BUNDLED_CONFIG), model::setModelsConfigPath,
                beside = false) {
                GlassButton(tr(UiText.BROWSE), { scope.launch { model.browseModelsConfig() } }, icon = AfIcons.Regular.Folder)
            }
        }
        GlassJsonBox(state.models.text)
    }
}

/**
 * Label + editable path (README §2.3 / §2.6): Apply (Primary) appears once the text differs from the saved value, and
 * an empty value clears it. [beside] = the buttons small and next to the field while it fits (Tools), else under it.
 */
@Composable
private fun PathField(label: String, saved: String?, placeholder: String, onApply: (String?) -> Unit, beside: Boolean,
    actions: @Composable () -> Unit) {
    var text by remember(saved) { mutableStateOf(saved.orEmpty()) }
    val field = @Composable { GlassTextField(text, { text = it }, placeholder, Modifier.fillMaxWidth()) }
    val buttons = @Composable {
        if (text != saved.orEmpty()) GlassButton(tr(UiText.APPLY), { onApply(text) }, kind = ButtonKind.Primary, small = beside)
        actions()
    }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (beside) 6.dp else 10.dp)) {
        Hint(label)
        if (beside) GlassFieldRow(field, buttons)
        else {
            field()
            WrapRow { buttons() }
        }
    }
}

// §2.4 Export defaults

@Composable
internal fun DefaultsPage(settings: AppSettings, model: SettingsModel) {
    GlassCard(Modifier.testTag("settings-defaults"), gap = 14.dp) {
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(tr(UiText.RATIO))
            WrapRow {
                Ratio.entries.forEach { r ->
                    GlassFilterChip(r.label, r == settings.defaultRatio, { model.setDefaultRatio(r) }, leading = { ink -> RatioMark(r, ink) })
                }
            }
        }
        GlassHairline()
        Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Hint(tr(UiText.CLIP_LENGTH))
            WrapRow {
                SettingsModel.CLIP_LENGTHS_MS.forEach { ms ->
                    GlassFilterChip("${ms / 1000}s", ms == settings.defaultClipMs, { model.setDefaultClipMs(ms) })
                }
            }
        }
    }
}

/** The ratio's outline shape (look only): the longer side 14 dp, the shorter at least 6, radius 2, 1.5 dp [ink]. */
@Composable
private fun RatioMark(ratio: Ratio, ink: Color) {
    val k = 14f / maxOf(ratio.width, ratio.height)
    Box(Modifier.size(maxOf(6f, ratio.width * k).dp, maxOf(6f, ratio.height * k).dp)
        .border(1.5.dp, ink, RoundedCornerShape(2.dp)))
}

// §2.5 Fonts

@Composable
internal fun FontsPage(fonts: List<String>, builtIn: List<String>, model: SettingsModel, scope: CoroutineScope) {
    Column(Modifier.fillMaxWidth().testTag("settings-fonts"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (builtIn.isNotEmpty()) GlassCallout(AfIcons.Fill.Info, My.breakSyllables(tr(UiText.FONTS_BUNDLED_HINT)), AutoFlowColors.Info,
            textColour = AutoFlowColors.Text)
        if (fonts.isEmpty()) GlassEmptyRow(AfIcons.Fill.TextAa, AutoFlowColors.Amber, tr(UiText.FONTS_EMPTY), Modifier.testTag("fonts-empty"))
        else GlassCard(tint = AutoFlowColors.Amber, tintAlpha = GlassColors.TintSettingsCard, padding = PaddingValues(vertical = 4.dp), gap = 0.dp) {
            fonts.forEachIndexed { i, path ->
                if (i > 0) GlassInsetDivider(58.dp)
                FontRow(path, i) { model.removeFont(path) }
            }
        }
        GlassButton(tr(UiText.FONTS_ADD), { scope.launch { model.addFonts() } }, Modifier.fillMaxWidth(), icon = AfIcons.Regular.Plus)
    }
}

/** A font row: amber text-aa tile 34 · file name without extension (one line, the name as its description) over the full path (mono 11) · Remove. */
@Composable
private fun FontRow(path: String, index: Int, onRemove: () -> Unit) {
    val name = path.fileName().substringBeforeLast('.')
    BesideOrUnder(
        lead = { SoftTile(AfIcons.Fill.TextAa, AutoFlowColors.Amber, size = 34.dp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                AfText(name, Modifier.semantics { contentDescription = name }, type = AfType.RowTitle, maxLines = 1, ellipsis = true)
                MonoText(path, 11.sp, AutoFlowColors.Muted)
            }
        },
        trailing = { GlassButton(tr(UiText.REMOVE), onRemove, Modifier.testTag("font-remove-$index"), small = true) },
        end = null,
        modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 12.dp, vertical = 8.dp).testTag("font-$index"),
        gap = 12.dp,
    )
}

// §2.6 Tools

@Composable
internal fun ToolsPage(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    GlassCard(Modifier.testTag("settings-tools"), gap = 12.dp) {
        if (state.toolsBundled) {
            // Android (D-23): ffmpeg is inside the APK; there is nothing to point at or install.
            GlassCardHeader("ffmpeg", { GlassRowTile(AfIcons.Regular.Wrench, null) })
            if (state.ffmpegFound) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AfIcon(AfIcons.Fill.CheckCircle, Modifier.padding(top = 2.dp), tint = AutoFlowColors.SuccessText, size = 17.dp)
                    AfText(My.breakSyllables(tr(UiText.FFMPEG_BUNDLED)), Modifier.weight(1f))
                }
                FfmpegCheck(state, model, scope)
            } else {
                AfText(My.breakSyllables(tr(UiText.FFMPEG_MISSING)), color = AutoFlowColors.Amber)
            }
        } else {
            PathField("ffmpeg", state.settings.ffmpegPath, tr(UiText.FFMPEG_PATH), model::setFfmpegPath, beside = true) {
                GlassButton(tr(UiText.DETECT), model::detectFfmpeg, small = true)
                GlassButton(tr(UiText.BROWSE), { scope.launch { model.browseFfmpeg() } }, small = true)
            }
            FfmpegCheck(state, model, scope)
            GlassHairline()
            PathField(tr(UiText.VLC_FOLDER), state.settings.vlcPath, tr(UiText.AUTO_DETECT), model::setVlcPath, beside = true) {
                GlassButton(tr(UiText.BROWSE), { scope.launch { model.browseVlc() } }, small = true)
            }
            val vlc = state.vlcDir
            if (vlc != null) Hint(tr(UiText.VLC_USING, "vlcDir" to vlc))
            else BesideOrUnder(
                lead = {},
                text = { Hint(tr(UiText.VLC_MISSING), colour = AutoFlowColors.WarningText) },
                trailing = { GlassButton(tr(UiText.VLC_INSTALL), model::openVlcDownload, kind = ButtonKind.Primary, small = true) },
                end = null, modifier = Modifier.fillMaxWidth(), gap = 10.dp,
            )
        }
    }
}

/** The ffmpeg version check (PLAN 1D): its line in a mono box (Amber when it found a problem) and Check ffmpeg. */
@Composable
private fun FfmpegCheck(state: SettingsUiState, model: SettingsModel, scope: CoroutineScope) {
    LaunchedEffect(model, state.settings.ffmpegPath) { model.checkFfmpeg() }
    state.ffmpegCheck?.let {
        GlassMonoBox(state.ffmpegNotice?.text(LocalUiLanguage.current) ?: it, colour = if (state.ffmpegCheckWarn) AutoFlowColors.Amber else AutoFlowColors.Muted)
    }
    GlassButton(tr(UiText.CHECK_FFMPEG), { scope.launch { model.checkFfmpeg() } }, icon = AfIcons.Regular.Redo)
}

// §2.7 Backup

/** D-27: the projects live in app storage that an uninstall deletes, so the zip goes to Download/AutoFlow. */
@Composable
internal fun BackupPage(model: BackupModel) {
    val state by model.state.collectAsState()
    Column(Modifier.fillMaxWidth().testTag("settings-backup"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCallout(AfIcons.Fill.Warn, My.breakSyllables(tr(UiText.BACKUP_STORAGE_HINT) + tr(UiText.BACKUP_DOWNLOAD_HINT)), AutoFlowColors.Amber,
            textColour = AutoFlowColors.Text)
        GlassCard(gap = 12.dp) {
            GlassCardHeader(if (state.projects == 1) tr(UiText.ONE_PROJECT) else tr(UiText.MANY_PROJECTS, "projects" to state.projects),
                { GlassRowTile(AfIcons.Regular.Archive, null) })
            val progress = state.progress
            if (progress != null) {
                BesideOrUnder(
                    lead = {},
                    text = { AfText(tr(UiText.BACKUP_PROGRESS, "toInt" to (progress.fraction * 100).toInt()), Modifier.testTag("backup-progress"), type = AfType.RowTitle) },
                    trailing = { GlassButton(tr(UiText.CANCEL), model::cancel, kind = ButtonKind.Danger, small = true) },
                    end = null, modifier = Modifier.fillMaxWidth(), gap = 10.dp,
                )
                GlassProgress(progress.fraction.takeIf { it.isFinite() }?.coerceIn(0f, 1f))
            } else {
                GlassButton(tr(UiText.BACKUP_ALL), model::start, Modifier.fillMaxWidth(), ButtonKind.Primary, enabled = state.projects > 0,
                    icon = AfIcons.Regular.Archive)
                if (state.last != null) GlassButton(tr(UiText.SHARE), model::share, Modifier.fillMaxWidth(), icon = AfIcons.Regular.Share)
                GlassButton(tr(UiText.RESTORE_BACKUP), model::restore, Modifier.fillMaxWidth(), icon = AfIcons.Regular.Redo)
            }
            state.message?.let { Hint(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it), colour = AutoFlowColors.Cyan) }
        }
    }
}

// §2.8 Preview features

/**
 * Preview-features toggles (5A-3) in a titled section card: one switch per flag in [FeatureFlags.asMap]. Shared with
 * Android Self-test so both routes use the same stored overrides.
 */
@Composable
fun PreviewFeaturesCard(model: SettingsModel, initiallyExpanded: Boolean = true) {
    val state by model.state.collectAsState()
    if (state.features.asMap().isEmpty()) return
    SectionCard(tr(UiText.PREVIEW_FEATURES), Modifier.testTag("settings-labs"), initiallyExpanded = initiallyExpanded) {
        FlagsContent(state, model)
    }
}

/** `PREVIEW_FEATURES_HINT`, then one card: the "n of m on" chip and a switch row per flag (raw key), dividers inset 12. */
@Composable
internal fun FlagsContent(state: SettingsUiState, model: SettingsModel) {
    val flags = state.features.asMap()
    Hint(tr(UiText.PREVIEW_FEATURES_HINT))
    GlassCard(padding = PaddingValues(vertical = 4.dp), gap = 0.dp) {
        Row(Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 2.dp), horizontalArrangement = Arrangement.End) {
            ToneChip(flagsOn(flags), Tone.Primary, Modifier.testTag("flags-on"))
        }
        flags.entries.forEachIndexed { i, (key, value) ->
            if (i > 0) GlassInsetDivider(12.dp)
            GlassSwitchRow(breakableKey(key), value, { model.setFeature(key, it) })
        }
    }
}

/**
 * A raw flag key with line-break chances (zero-width spaces) after each dot and before each capitalised word, so
 * `labs.autoCaptionPlacement` wraps as "labs. auto Caption Placement" at font 2.0 instead of cutting a word. `studioV2`
 * (a capital before a digit) stays one word.
 */
internal fun breakableKey(key: String): String = buildString {
    key.forEachIndexed { i, c ->
        if (i > 0 && c.isUpperCase() && key[i - 1].isLowerCase() && key.getOrNull(i + 1)?.isLowerCase() == true) append('\u200B')
        append(c)
        if (c == '.') append('\u200B')
    }
}

// §2.9 Diagnostics

/** Visible entry to real build/tool readouts and the same on-device Self-test as version long-press. */
@Composable
internal fun DiagnosticsPage(state: SettingsUiState, model: SettingsModel, info: AppInfo?, runner: AiJobRunner?) {
    LaunchedEffect(model) { model.checkFfmpeg() }
    Column(Modifier.fillMaxWidth().testTag("settings-diagnostics"), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        GlassCard(gap = 10.dp) {
            val version = info?.version
            GlassCardHeader("Auto Flow", { GlassRowTile(AfIcons.Regular.Shield, null) },
                trailing = version?.let { { GlassPill(it, Modifier.testTag("diagnostics-build")) } })
            if (version == null) Hint(tr(UiText.V3_BUILD_UNAVAILABLE), Modifier.testTag("diagnostics-build"))
            GlassMonoBox(state.ffmpegNotice?.text(LocalUiLanguage.current) ?: state.ffmpegCheck
                ?: tr(if (state.ffmpegFound) UiText.V3_TOOL_DETECTED else UiText.FFMPEG_NOT_FOUND))
            if (!state.toolsBundled) Hint(state.vlcDir?.let { tr(UiText.VLC_USING, "vlcDir" to it) } ?: tr(UiText.VLC_MISSING))
        }
        val runs = runner?.jobs?.collectAsState()?.value.orEmpty()
        val recent = runs.filter { it.timings.isNotEmpty() }.maxByOrNull { it.timingsUpdatedAt ?: Long.MIN_VALUE }
        val language = LocalUiLanguage.current
        GlassCard(gap = 10.dp) {
            GlassCardHeader(tr(UiText.LAST_RUN_TIMINGS), { GlassRowTile(AfIcons.Regular.Clock, null) }, Modifier.testTag("diagnostics-run-timings"),
                trailing = recent?.let { { ToneChip(aiKindLabel(it.kind, language), Tone.Neutral) } })
            if (recent == null) Hint(tr(UiText.NO_RUN_TIMINGS))
            else Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for ((stage, samples) in recent.timings.groupBy { it.stage }) {
                    val values = "${samples.sumOf { it.ms }} ms · ×${samples.size}" +
                        (samples.mapNotNull { it.bytes }.takeIf { it.isNotEmpty() }?.let { " · ${it.sum()} B" } ?: "")
                    BesideOrUnder(
                        lead = {},
                        text = { Hint(tr(stage.timingLabel())) },
                        trailing = { AfText(values, type = AfType.Numbers) },
                        end = null, modifier = Modifier.fillMaxWidth().testTag("timing-${stage.name}"), gap = 12.dp,
                    )
                }
            }
        }
        val open = info?.onVersionLongPress
        GlassButton(tr(UiText.SELF_TEST), { open?.invoke() }, Modifier.fillMaxWidth().testTag("settings-self-test"), enabled = open != null,
            icon = AfIcons.Regular.CheckCircle)
        if (open == null) Hint(tr(UiText.V3_SELFTEST_ANDROID))
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
