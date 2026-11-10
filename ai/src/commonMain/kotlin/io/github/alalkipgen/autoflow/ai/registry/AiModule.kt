package io.github.alalkipgen.autoflow.ai.registry

import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.TextProvider
import io.github.alalkipgen.autoflow.ai.TranscribeProvider
import io.github.alalkipgen.autoflow.ai.TtsProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import org.koin.core.module.dsl.onClose
import org.koin.core.module.dsl.withOptions
import org.koin.dsl.module

/**
 * Koin bindings for :ai (D-11). The host modules must provide [AiHost] and the parsed
 * [io.github.alalkipgen.autoflow.ai.config.ModelConfig]; a [UsageCounter] is optional.
 */
val aiModule = module {
    single { ProviderRegistry(host = get(), config = get(), usage = getOrNull<UsageCounter>()) } withOptions {
        onClose { it?.close() }
    }
    single<TextProvider> { get<ProviderRegistry>().text }
    single<ImageProvider> { get<ProviderRegistry>().image }
    single<TtsProvider> { get<ProviderRegistry>().tts }
    single<TranscribeProvider> { get<ProviderRegistry>().transcribe }
    single { StoryboardGenerator(get()) }
}
