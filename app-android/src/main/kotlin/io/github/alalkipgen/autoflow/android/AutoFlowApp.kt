package io.github.alalkipgen.autoflow.android

import android.app.Application
import io.github.alalkipgen.autoflow.ui.di.uiModule
import org.koin.core.context.startKoin

class AutoFlowApp : Application() {
    override fun onCreate() {
        super.onCreate()
        startKoin { modules(uiModule) }
    }
}
