package io.github.alalkipgen.autoflow.android

import android.content.Context
import android.os.Build
import io.github.alalkipgen.autoflow.BuildConfig
import java.io.File
import java.time.ZonedDateTime

/**
 * The owner tests on a phone without adb, so a crash must be readable in the app: the uncaught-exception handler
 * writes the stack trace to a file, and the next launch shows it (MainActivity) with Copy / Share.
 */
object CrashLog {
    fun file(context: Context) = File(context.filesDir, "crash/last-crash.txt")

    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record(app, thread, error)
            previous?.uncaughtException(thread, error)
        }
    }

    /** Also used for errors the app survives (startup wiring), so they reach the owner the same way. */
    fun record(context: Context, thread: Thread, error: Throwable) {
        runCatching { file(context).apply { parentFile?.mkdirs() }.writeText(describe(thread, error)) }
    }

    fun pending(context: Context): String? = file(context).takeIf { it.isFile }?.let { runCatching { it.readText() }.getOrNull() }

    fun clear(context: Context) {
        file(context).delete()
    }

    private fun describe(thread: Thread, error: Throwable) = buildString {
        appendLine("Auto Flow ${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_SHA}, build ${BuildConfig.VERSION_CODE}) crashed")
        appendLine("Time: ${ZonedDateTime.now()}")
        appendLine("Device: ${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT})")
        appendLine("Thread: ${thread.name}")
        appendLine()
        append(error.stackTraceToString().lineSequence().take(MAX_LINES).joinToString("\n"))
    }

    private const val MAX_LINES = 120
}
