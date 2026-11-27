package io.github.alalkipgen.autoflow.ui.settings

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.GlassGroupLabel
import io.github.alalkipgen.autoflow.ui.components.GlassRowTile
import io.github.alalkipgen.autoflow.ui.components.GlassScrollColumn
import io.github.alalkipgen.autoflow.ui.components.GlassRowValue
import io.github.alalkipgen.autoflow.ui.components.GlassSettingsList
import io.github.alalkipgen.autoflow.ui.components.GlassSettingsRow
import io.github.alalkipgen.autoflow.ui.components.GlassTopBar
import io.github.alalkipgen.autoflow.ui.components.ToneChip
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.icons.AfIcons
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.Tone
import kotlin.math.roundToInt

/** The Settings pages (P9-17a): the main page of grouped rows ([MAIN]) and one pushed page per row, with its title. */
enum class SettingsPage(val title: UiText) {
    MAIN(UiText.SETTINGS_TITLE),
    LANGUAGE(UiText.LANGUAGE),
    AI(UiText.V3_AI_KEYS),
    MODELS(UiText.MODEL_CONFIG),
    DEFAULTS(UiText.V3_EXPORT_DEFAULTS),
    FONTS(UiText.V3_LIBRARY_FONTS),
    TOOLS(UiText.TOOLS),
    BACKUP(UiText.BACKUP),
    FLAGS(UiText.PREVIEW_FEATURES),
    DIAGNOSTICS(UiText.V3_DIAGNOSTICS),
}

/**
 * Settings (DESIGN.md §3, PLAN 1C, P9-17a): a main page of four groups whose rows open one page each — language, AI
 * keys, model config, export defaults, fonts, tools, backup ([backup], Android, D-27), preview features and
 * diagnostics — and an About footer ([info]). The Panel bar's back button calls [onBack] on the main page (none when
 * null) and returns to the main page, at its scroll position, from a page; so does system Back through [backHandler].
 */
@Composable
fun SettingsScreen(model: SettingsModel, modifier: Modifier = Modifier, info: AppInfo? = null, backup: BackupModel? = null,
    onOpenLexicon: (() -> Unit)? = null,
    runner: AiJobRunner? = null,
    onBack: (() -> Unit)? = null,
    backHandler: @Composable (enabled: Boolean, onBack: () -> Unit) -> Unit = { _, _ -> },
) {
    AppLanguage(model.appSettings) { SettingsContent(model, modifier, info, backup, onOpenLexicon, runner, onBack, backHandler) }
}

@Composable
private fun SettingsContent(model: SettingsModel, modifier: Modifier, info: AppInfo?, backup: BackupModel?, onOpenLexicon: (() -> Unit)?,
    runner: AiJobRunner?, onBack: (() -> Unit)?, backHandler: @Composable (Boolean, () -> Unit) -> Unit) {
    val state by model.state.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(model) { model.refresh(); model.refreshUsage() }
    LaunchedEffect(backup) { backup?.refresh() }
    var pageName by rememberSaveable { mutableStateOf(SettingsPage.MAIN.name) }
    val page = SettingsPage.valueOf(pageName)
    // The Chatbox AI row opens the AI page with the Chatbox card at the top (README §2.2).
    var toChatbox by rememberSaveable { mutableStateOf(false) }
    val mainScroll = rememberScrollState()
    val back = { toChatbox = false; pageName = SettingsPage.MAIN.name }
    if (page != SettingsPage.MAIN) backHandler(true, back)
    Column(modifier.fillMaxSize()) {
        GlassTopBar(tr(page.title), if (page == SettingsPage.MAIN) Modifier.testTag("settings-heading") else Modifier.testTag("settings-page-title"),
            onBack = if (page == SettingsPage.MAIN) onBack else back,
            backModifier = Modifier.testTag(if (page == SettingsPage.MAIN) "subscreen-back" else "settings-page-back"))
        var viewportTop by remember { mutableStateOf(0f) }
        var chatboxTop by remember { mutableStateOf<Float?>(null) }
        val padTop = with(LocalDensity.current) { screenPadding.toPx() }
        Box(Modifier.fillMaxWidth().weight(1f).onGloballyPositioned { viewportTop = it.positionInRoot().y }, contentAlignment = Alignment.TopCenter) {
            key(page) {
                val scroll = if (page == SettingsPage.MAIN) mainScroll else rememberScrollState()
                if (page == SettingsPage.AI) LaunchedEffect(toChatbox, chatboxTop != null) {
                    val top = chatboxTop
                    if (toChatbox && top != null) {
                        scroll.scrollTo((scroll.value + top - viewportTop - padTop).roundToInt().coerceAtLeast(0))
                        toChatbox = false
                    }
                }
                // The viewport includes the padding (GlassScrollColumn), so scrolling to a bottom button can finish.
                GlassScrollColumn(Modifier.widthIn(max = 900.dp).fillMaxSize(), PaddingValues(screenPadding),
                    Arrangement.spacedBy(if (page == SettingsPage.MAIN) 18.dp else 16.dp), scroll) {
                    state.message?.let {
                        AfText(state.notice?.text(LocalUiLanguage.current) ?: tr(UiText.RESULT_DETAIL, "detail" to it),
                            Modifier.testTag("settings-notice"), color = AutoFlowColors.Cyan)
                    }
                    when (page) {
                        SettingsPage.MAIN -> MainPage(state, model, info, backup, onOpenLexicon,
                            open = { pageName = it.name }, openChatbox = { toChatbox = true; pageName = SettingsPage.AI.name })
                        SettingsPage.LANGUAGE -> LanguagePage(model)
                        SettingsPage.AI -> AiPage(state, model, scope,
                            Modifier.onGloballyPositioned { chatboxTop = it.positionInRoot().y })
                        SettingsPage.MODELS -> ModelsPage(state, model, scope)
                        SettingsPage.DEFAULTS -> DefaultsPage(state.settings, model)
                        SettingsPage.FONTS -> FontsPage(state.settings.fontPaths, model.builtInFontPaths, model, scope)
                        SettingsPage.TOOLS -> ToolsPage(state, model, scope)
                        SettingsPage.BACKUP -> backup?.let { BackupPage(it) }
                        SettingsPage.FLAGS -> Column(Modifier.fillMaxWidth().testTag("settings-labs"),
                            verticalArrangement = Arrangement.spacedBy(10.dp)) { FlagsContent(state, model) }
                        SettingsPage.DIAGNOSTICS -> DiagnosticsPage(state, model, info, runner)
                    }
                }
            }
        }
    }
}

/** MAIN-PAGE.md §5: General · AI & keys · Export · Storage & checks, then the About footer. */
@Composable
private fun MainPage(state: SettingsUiState, model: SettingsModel, info: AppInfo?, backup: BackupModel?, onOpenLexicon: (() -> Unit)?,
    open: (SettingsPage) -> Unit, openChatbox: () -> Unit) {
    val language = LocalUiLanguage.current
    Group(tr(UiText.SETTINGS_GENERAL), listOf<@Composable () -> Unit> {
        GlassSettingsRow(tr(UiText.LANGUAGE), { open(SettingsPage.LANGUAGE) }, { GlassRowTile(AfIcons.Fill.Globe, AutoFlowColors.Info) },
            Modifier.testTag("settings-row-language"), trailing = { GlassRowValue(language.nativeName) })
    })
    Group(tr(UiText.V3_AI_KEYS), buildList<@Composable () -> Unit> {
        add {
            GlassSettingsRow(tr(UiText.GEMINI_KEY), { open(SettingsPage.AI) }, { GlassRowTile(AfIcons.Fill.Key, AutoFlowColors.Purple) },
                Modifier.testTag("settings-row-gemini"),
                subtitle = if (state.keyState == KeyState.UNSUPPORTED) tr(UiText.KEY_UNSUPPORTED) else todayCalls(state, retries = false),
                trailing = keyChip(state.keyState, tr(UiText.KEY_NOT_SAVED), "gemini-key-chip"))
        }
        if (model.chatboxOffered) add {
            GlassSettingsRow(tr(UiText.CHATBOX_AI), openChatbox, { GlassRowTile(AfIcons.Fill.Spark, AutoFlowColors.Cyan) },
                Modifier.testTag("settings-row-chatbox"),
                trailing = keyChip(state.chatboxKeyState, tr(UiText.CHATBOX_KEY_NOT_SAVED), "chatbox-key-chip"))
        }
        add {
            val source = state.models.source
            GlassSettingsRow(tr(UiText.MODEL_CONFIG), { open(SettingsPage.MODELS) }, { GlassRowTile(AfIcons.Regular.Cpu, null) },
                Modifier.testTag("settings-row-models"), trailing = {
                    GlassRowValue(if (source == LoadedModelConfig.BUNDLED) tr(UiText.BUNDLED_CONFIG) else source.fileName())
                })
        }
        onOpenLexicon?.let { lexicon ->
            add {
                GlassSettingsRow(tr(UiText.V3_PRONUNCIATION), lexicon, { GlassRowTile(AfIcons.Fill.Volume, AutoFlowColors.Amber) },
                    Modifier.testTag("settings-lexicon"), subtitle = tr(UiText.V3_OPEN_LEXICON))
            }
        }
    })
    Group(tr(UiText.SETTINGS_EXPORT), listOf<@Composable () -> Unit>(
        {
            val settings = state.settings
            GlassSettingsRow(tr(UiText.V3_EXPORT_DEFAULTS), { open(SettingsPage.DEFAULTS) }, { GlassRowTile(AfIcons.Regular.Film, null) },
                Modifier.testTag("settings-row-defaults"),
                trailing = { GlassRowValue("${settings.defaultRatio.label} · ${settings.defaultClipMs / 1000}s") })
        },
        {
            val fonts = state.settings.fontPaths
            GlassSettingsRow(tr(UiText.V3_LIBRARY_FONTS), { open(SettingsPage.FONTS) }, { GlassRowTile(AfIcons.Regular.TextAa, null) },
                Modifier.testTag("settings-row-fonts"),
                subtitle = if (fonts.isEmpty()) tr(UiText.FONTS_EMPTY) else fonts.joinToString(", ") { it.fileName().substringBeforeLast('.') })
        },
        {
            val line = when {
                state.toolsBundled -> tr(if (state.ffmpegFound) UiText.FFMPEG_BUNDLED else UiText.FFMPEG_MISSING)
                else -> tr(if (state.ffmpegFound) UiText.V3_TOOL_DETECTED else UiText.FFMPEG_NOT_FOUND)
            }
            GlassSettingsRow(tr(UiText.TOOLS), { open(SettingsPage.TOOLS) }, { GlassRowTile(AfIcons.Regular.Wrench, null) },
                Modifier.testTag("settings-row-tools"), subtitle = line)
        },
    ))
    Group(tr(UiText.SETTINGS_STORAGE), buildList<@Composable () -> Unit> {
        backup?.let { b ->
            add {
                val projects = b.state.collectAsState().value.projects
                GlassSettingsRow(tr(UiText.BACKUP), { open(SettingsPage.BACKUP) }, { GlassRowTile(AfIcons.Regular.Archive, null) },
                    Modifier.testTag("settings-row-backup"),
                    subtitle = if (projects == 1) tr(UiText.ONE_PROJECT) else tr(UiText.MANY_PROJECTS, "projects" to projects))
            }
        }
        val flags = state.features.asMap()
        if (flags.isNotEmpty()) add {
            GlassSettingsRow(tr(UiText.PREVIEW_FEATURES), { open(SettingsPage.FLAGS) }, { GlassRowTile(AfIcons.Regular.Flask, null) },
                Modifier.testTag("settings-row-labs"), subtitle = flagsOn(flags))
        }
        add {
            GlassSettingsRow(tr(UiText.V3_DIAGNOSTICS), { open(SettingsPage.DIAGNOSTICS) }, { GlassRowTile(AfIcons.Regular.Shield, null) },
                Modifier.testTag("settings-row-diagnostics"), subtitle = "${tr(UiText.SELF_TEST)} · ${tr(UiText.LAST_RUN_TIMINGS)}")
        }
    })
    info?.let { AboutFooter(it) }
}

@Composable
private fun Group(label: String, rows: List<@Composable () -> Unit>) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        GlassGroupLabel(label)
        GlassSettingsList(rows)
    }
}

/**
 * The key chip of the main-page row and the key card (MAIN-PAGE.md §5): SET → Saved (success, check); MISSING → Not
 * saved (neutral; [missing] — the old "…: not saved" line — is its description); UNREADABLE → Save again (error,
 * warning); UNSUPPORTED → none (null).
 */
internal fun keyChip(key: KeyState, missing: String, tag: String): (@Composable () -> Unit)? {
    if (key == KeyState.UNSUPPORTED) return null
    return { KeyChip(key, missing, tag) }
}

@Composable
private fun KeyChip(key: KeyState, missing: String, tag: String) {
    val description = if (key == KeyState.MISSING) Modifier.semantics { contentDescription = missing } else Modifier
    val chip = Modifier.testTag(tag).semantics(mergeDescendants = true) {}.then(description)
    when (key) {
        KeyState.SET -> ToneChip(tr(UiText.V3_SAVED), Tone.Success, chip, AfIcons.Regular.Check)
        KeyState.UNREADABLE -> ToneChip(tr(UiText.KEY_SAVE_AGAIN), Tone.Error, chip, AfIcons.Fill.Warn)
        else -> ToneChip(tr(UiText.KEY_NOT_SAVED_SHORT), Tone.Neutral, chip)
    }
}

/** "Today: 37 API calls" (+ " (includes retries)" and the per-model breakdown on the AI page, as before). */
@Composable
internal fun todayCalls(state: SettingsUiState, retries: Boolean): String {
    val total = state.usageToday.values.sum()
    val first = tr(UiText.TODAY_CALLS_FIRST, "total" to total) + (if (LocalUiLanguage.current == UiLanguage.ENGLISH && total != 1) "s" else "")
    if (!retries) return first
    return first + tr(UiText.TODAY_CALLS_RETRIES) +
        state.usageToday.entries.joinToString(prefix = if (total > 0) ": " else "") { "${it.key} ×${it.value}" }
}

@Composable
internal fun flagsOn(flags: Map<String, Boolean>): String = tr(UiText.FLAGS_ON, "on" to flags.values.count { it }, "total" to flags.size)

internal fun String.fileName(): String = trimEnd('/', '\\').substringAfterLast('/').substringAfterLast('\\')

/** About footer (MAIN-PAGE.md §5): "Auto Flow <version>"; on Android a long press opens the Self-test (PLAN S6). */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AboutFooter(info: AppInfo) {
    val open = info.onVersionLongPress
    Column(Modifier.fillMaxWidth().testTag("settings-about").padding(top = 4.dp, bottom = 6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        AfText(
            "Auto Flow ${info.version}",
            Modifier.testTag("about-version").then(if (open != null) Modifier.combinedClickable(onClick = {}, onLongClick = open) else Modifier),
            color = AutoFlowColors.Muted, type = AfType.Caption, textAlign = TextAlign.Center,
        )
        if (open != null) AfText(My.breakSyllables(tr(UiText.SELFTEST_OPEN_HINT)), color = AutoFlowColors.Faint, type = AfType.Caption,
            textAlign = TextAlign.Center)
    }
}
