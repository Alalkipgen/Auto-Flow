package io.github.alalkipgen.autoflow.ui.di

import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.ai.registry.ProviderRegistry
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.AppAiHost
import io.github.alalkipgen.autoflow.ui.settings.LoadedModelConfig
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.settings.loadModelConfig
import io.github.alalkipgen.autoflow.ui.input.InputModel
import io.github.alalkipgen.autoflow.ui.shell.ShellModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardJobs
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardModel
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService
import kotlinx.coroutines.MainScope
import org.koin.dsl.module

/**
 * App bindings shared by desktop and Android. The app module must bind the platform: [AppDirs],
 * `core.io.FileSystem`, `SecureStorage`, `Clipboard`, `UrlOpener`, `FilePicker`, `ToolLocator` and `BundledConfig`; then load
 * `aiModule` too, which takes [AiHost] and `ModelConfig` from here.
 */
val uiModule = module {
    single { SettingsStore(get(), get<AppDirs>().settings) }
    single { UsageCounter(get(), get<AppDirs>().usage) }
    single { loadModelConfig(get(), get<SettingsStore>().state.value.modelsConfigPath, get()) }
    single { get<LoadedModelConfig>().config }
    single<AiHost> { AppAiHost(get(), get(), get(), get()) }
    single { ProviderStatusModel(get(), get()) }
    single { ProjectStore(get()) }
    single { ProjectIndex(get(), get<AppDirs>().projectsIndex) }
    single { CurrentProject() }
    factory { DashboardModel(get(), get(), get(), get(), get(), get()) }
    single { ProjectSession(get(), get(), get(), get()) }
    single { StoryboardService(get(), get()) }
    single { StoryboardJobs(get(), MainScope()) }
    factory { InputModel(get(), get()) }
    factory { StoryboardModel(get(), get(), get()) }
    single { ShellModel(get()) }
    factory {
        val registry = get<ProviderRegistry>()
        SettingsModel(get(), get(), get(), get(), get(), get(), get(), get(), testKeyCall = registry::testGeminiKey)
    }
}
