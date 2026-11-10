package io.github.alalkipgen.autoflow.ui.di

import io.github.alalkipgen.autoflow.ai.registry.AiHost
import io.github.alalkipgen.autoflow.ai.registry.ProviderRegistry
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.settings.AppAiHost
import io.github.alalkipgen.autoflow.ui.settings.LoadedModelConfig
import io.github.alalkipgen.autoflow.ui.settings.ProviderStatusModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsModel
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.settings.loadModelConfig
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
    factory {
        val registry = get<ProviderRegistry>()
        SettingsModel(get(), get(), get(), get(), get(), get(), get(), get(), testKeyCall = registry::testGeminiKey)
    }
}
