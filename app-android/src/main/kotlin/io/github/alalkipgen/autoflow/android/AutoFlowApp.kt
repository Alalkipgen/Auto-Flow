package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.android.ai.AiWorkerRegistry
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.recap.RecapModel
import org.koin.android.ext.android.getKoin
import org.koin.core.context.startKoin

class AutoFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // A wiring error must not crash every launch: record it, so MainActivity shows it (and the Self-test still opens).
        try {
            startKoin { modules(androidModule(this@AutoFlowApp), uiModule, aiModule) }
            // 5A-5 / D-51: wire the TTS processor into AiWorker so it survives process recreation.
            val recapModel = getKoin().getOrNull<RecapModel>()
            AiWorkerRegistry.processor = io.github.alalkipgen.autoflow.core.recap.AiItemProcessor { job, item ->
                when (job.kind) {
                    AiJobKind.TTS_ALL -> {
                        val idx = item.toIntOrNull() ?: return@AiItemProcessor
                        recapModel?.speakForJob(idx) ?: error("RecapModel not available")
                    }
                    else -> { /* other kinds handled when implemented */ }
                }
            }
        } catch (e: Exception) {
            CrashLog.record(this, Thread.currentThread(), e)
        }
    }
}
