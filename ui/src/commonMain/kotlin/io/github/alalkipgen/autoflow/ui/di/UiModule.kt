package io.github.alalkipgen.autoflow.ui.di

import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.ai.registry.ProviderRegistry
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.AppAiHost
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import io.github.alalkipgen.autoflow.ui.settings.BackupModel
import io.github.alalkipgen.autoflow.ui.settings.LoadedModelConfig
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.settings.loadModelConfig
import io.github.alalkipgen.autoflow.ui.export.ExportModel
import io.github.alalkipgen.autoflow.ui.handoff.FlowHandoffModel
import io.github.alalkipgen.autoflow.ui.inbox.InboxModel
import io.github.alalkipgen.autoflow.ui.inbox.SharedInbox
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.references.ReferencesModel
import io.github.alalkipgen.autoflow.ui.shell.ShellModel
import io.github.alalkipgen.autoflow.ui.shots.ShotsModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlinx.coroutines.MainScope
import org.koin.dsl.module

/**
 * App bindings shared by desktop and Android. The app module must bind the platform: [AppDirs],
 * `core.io.FileSystem`, `SecureStorage`, `Clipboard`, `UrlOpener`, `FilePicker`, `ToolLocator` and `BundledConfig`; then load
 * `aiModule` too, which takes [AiHost] and `ModelConfig` from here. Optional: `ProjectBackup` (Settings → Backup, D-27)
 * and `AppInfo` (Settings → About).
 */
val uiModule = module {
    single { SettingsStore(get(), get<AppDirs>().settings) }
    single { io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts(get(), getOrNull<AppDirs>()?.let { joinPath(it.root, io.github.alalkipgen.autoflow.ui.settings.BuiltInFonts.DIR) }, get()) }
    single { UsageCounter(get(), get<AppDirs>().usage) }
    single { loadModelConfig(get(), get<SettingsStore>().state.value.modelsConfigPath, get()) }
    single { get<LoadedModelConfig>().config }
    single<AiHost> { AppAiHost(get(), get(), get(), get()) }
    single { ProviderStatusModel(get(), get()) }
    single { ProjectStore(get()) }
    single { ProjectIndex(get(), get<AppDirs>().projectsIndex) }
    single { CurrentProject() }
    factory {
        val index = get<ProjectIndex>()
        val recap = { getOrNull<io.github.alalkipgen.autoflow.ui.recap.RecapModel>() }
        val current = get<CurrentProject>()
        val storyboard = { getOrNull<StoryboardJobs>()?.state?.value }
        DashboardModel(get(), get(), index, get(), get(), current, get<AppDirs>().projectsHome, modelConfig = getOrNull(),
            deletion = getOrNull(), ai = getOrNull(), renders = getOrNull(), foregroundBusy = { key ->
                recap()?.foregroundVoiceRuns?.value?.any { index.canonical(it.dir) == key } == true ||
                    recap()?.state?.value?.let { it.projectDir != null && index.canonical(it.projectDir) == key && (it.busy || it.recording != null) } == true ||
                    (storyboard()?.takeIf { it.running != null }?.let { job ->
                        (job.gemini?.dir ?: current.state.value?.dir)?.let(index::canonical) == key
                    } == true)
            })
    }
    single { BackupModel(getOrNull(), get(), get(), MainScope(), filePicker = getOrNull(), appDirs = getOrNull(),
        language = { io.github.alalkipgen.autoflow.ui.i18n.UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) }) }
    single { ProjectSession(get(), get(), get(), get()) }
    single { StoryboardService(get(), get()) }
    single { StoryboardJobs(get(), MainScope()) }
    single { MediaTools(get(), get(), getOrNull()) }
    single {
        io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesModel(get(), get(), get(), picker = getOrNull(), scope = MainScope(),
            probe = io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesModel.probeWith(get()),
            extract = io.github.alalkipgen.autoflow.ui.subtitles.SubtitlesModel.extractWith(get()),
            uiLanguage = { UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) })
    }
    // config/tools.json (D-84): Phase 7 tunables; the defaults when the app does not ship the file.
    single { io.github.alalkipgen.autoflow.core.config.ToolsConfig.parse(get<BundledConfig>().toolsJson()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleEditorModel(get(), get(), MainScope(), config = get()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleExportModel(get(), get(), getOrNull(), MainScope()) }
    single { io.github.alalkipgen.autoflow.ui.media.MediaTasks(getOrNull(), MainScope()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber(get(), get(), getOrNull(), getOrNull(), config = get()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleTranslateJob(get(), get(), getOrNull(), config = get()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleTranscribeModel(get(), getOrNull(), getOrNull(), getOrNull(), get(), MainScope()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleTranslateModel(get(), get(), getOrNull(), get(), MainScope()) }
    single { io.github.alalkipgen.autoflow.ui.voice.VoiceAudioModel(get(), get(), get(), MainScope()) }
    single { io.github.alalkipgen.autoflow.ui.subtitles.SubtitleBurnModel(get(), get(), get(), frames = getOrNull(), media = getOrNull(), config = get(), share = getOrNull()) }
    factory { InputModel(get(), get(), picker = getOrNull(), media = get(), transcribe = getOrNull(), uiLanguage = { UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) }) }
    factory { StoryboardModel(get(), get(), get(), media = get()) }
    factory { ShotsModel(get()) }
    factory { ReferencesModel(session = get(), clipboard = get(), urlOpener = get(), filePicker = get(), uiLanguage = { UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) }) }
    factory { InboxModel(session = get(), fs = get()) }
    single { SharedInbox(get(), get(), joinPath(get<AppDirs>().projectsHome ?: get<AppDirs>().root, SharedInbox.DIR_NAME)) }
    factory {
        val cfg = get<io.github.alalkipgen.autoflow.ai.config.ModelConfig>()
        FlowHandoffModel(
            session = get(),
            clipboard = get(),
            urlOpener = get(),
            filePicker = get(),
            flowUrl = cfg.flow.url,
            uiLanguage = { UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) },
            creditHintFast = cfg.flow.creditsHint["fast"] ?: 20,
            creditHintQuality = cfg.flow.creditsHint["quality"] ?: 100,
            fs = get(),
            fileShare = getOrNull(),
            media = get(),
        )
    }
    single { ShellModel(get()) }
    // Movie Recap (D-33): a single, so copying / finding scenes keeps going off-screen.
    // Exact frames (D-42): one recap job + still renderer shared by the Recap Style preview and Export.
    single { io.github.alalkipgen.autoflow.ui.export.RecapFrames(get(), get(), get(), media = getOrNull(), builtInFonts = getOrNull(), lexicon = get<io.github.alalkipgen.autoflow.core.library.LibraryStore>().lexicon, previewCacheDir = joinPath(get<AppDirs>().root, "cache/export-preview")) }
    single { io.github.alalkipgen.autoflow.ui.recap.RecapModel(get(), get(), get(), picker = getOrNull(), scope = MainScope(), generator = getOrNull(), tts = getOrNull(), recorder = getOrNull(), library = getOrNull(), frames = getOrNull(), jobs = getOrNull(), lexicons = get<io.github.alalkipgen.autoflow.core.library.LibraryStore>().lexicon, modelConfig = get(), transcriber = getOrNull(), usage = getOrNull(), uiLanguage = { UiLanguage.fromCode(get<SettingsStore>().state.value.uiLanguage) }, thumbnailCacheDir = joinPath(get<AppDirs>().root, "cache/scene-thumbnails-v1")) }
    single { ExportModel(get(), get(), get(), queue = getOrNull(), share = getOrNull(), battery = getOrNull(), scope = MainScope(), clipboard = getOrNull(), builtInFonts = getOrNull(), frames = getOrNull()) }
    single { io.github.alalkipgen.autoflow.ui.jobs.RenderHistory(get(), get<AppDirs>().root) }
    // Library (5A-7)
    single { io.github.alalkipgen.autoflow.core.library.LibraryStore(get(), get<AppDirs>().library) }
    single { io.github.alalkipgen.autoflow.ui.library.LibraryModel(get(), get(), MainScope(), fs = get()) }
    factory {
        val registry = get<ProviderRegistry>()
        val dirs = getOrNull<AppDirs>()
        val fs = get<FileSystem>()
        SettingsModel(
            get(), get(), get(), get(), get(), get(), get(), get(), testKeyCall = registry::testGeminiKey, ffmpegRunner = getOrNull(),
            testTtsCall = { registry.testGeminiTts() },
            saveTtsSample = { bytes ->
                dirs?.let { d ->
                    runCatching { joinPath(d.root, "tts-test.wav").also { fs.createDirectories(d.root); fs.writeDurably(it, bytes) } }.getOrNull()
                }
            },
            bundledFeatures = get<BundledConfig>().featuresJson(),
            builtInFonts = getOrNull(),
        )
    }
}
