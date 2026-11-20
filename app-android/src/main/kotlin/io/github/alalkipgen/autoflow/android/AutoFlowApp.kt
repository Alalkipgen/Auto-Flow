package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.android.ai.AiWorkerRegistry
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.jobs.RecapJobProcessor
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
            // 5A-5 / D-51: wire the processor into AiWorker so it survives process recreation. Audit F11: it speaks the
            // job's own project and block ids (not the open project / scene indexes); other kinds fail, never Done.
            val koin = getKoin()
            AiWorkerRegistry.processor = RecapJobProcessor({ koin.getOrNull<RecapModel>() }, { koin.getOrNull<io.github.alalkipgen.autoflow.ui.subtitles.VideoTranscriber>() })
        } catch (e: Exception) {
            CrashLog.record(this, Thread.currentThread(), e)
        }
    }
}
