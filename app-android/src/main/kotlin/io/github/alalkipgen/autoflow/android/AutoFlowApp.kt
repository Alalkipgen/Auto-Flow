package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.ui.di.uiModule
import org.koin.core.context.startKoin

class AutoFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        // A wiring error must not crash every launch: record it, so MainActivity shows it (and the Self-test still opens).
        try {
            startKoin { modules(androidModule(this@AutoFlowApp), uiModule, aiModule) }
        } catch (e: Exception) {
            CrashLog.record(this, Thread.currentThread(), e)
        }
    }
}
