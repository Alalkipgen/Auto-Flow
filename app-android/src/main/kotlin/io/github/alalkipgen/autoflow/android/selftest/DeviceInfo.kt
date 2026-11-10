package io.github.alalkipgen.autoflow.android.selftest

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import io.github.alalkipgen.autoflow.BuildConfig
import io.github.alalkipgen.autoflow.media.selftest.SelfTestReport.Section
import io.github.alalkipgen.autoflow.media.selftest.SelfTestReport.Companion.mb
import java.io.File

/** Report sections about the phone, the build and the bundled binary (PLAN S6: "device info"). */
object DeviceInfo {
    fun device(context: Context): Section {
        val am = context.getSystemService(ActivityManager::class.java)
        val mem = ActivityManager.MemoryInfo().also { am?.getMemoryInfo(it) }
        val rows = buildList {
            add("Model" to "${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            if (Build.VERSION.SDK_INT >= 31) add("SoC" to "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}")
            add("Android" to "${Build.VERSION.RELEASE} (SDK ${Build.VERSION.SDK_INT}), security patch ${Build.VERSION.SECURITY_PATCH}")
            add("ABIs" to Build.SUPPORTED_ABIS.joinToString(", "))
            add("CPU cores" to "${Runtime.getRuntime().availableProcessors()}")
            add("RAM" to "${mb(mem.totalMem)} total, ${mb(mem.availMem)} available" + if (mem.lowMemory) " (LOW)" else "")
            add("App memory class" to "${am?.memoryClass ?: "?"} MB (large ${am?.largeMemoryClass ?: "?"} MB)")
            add("Free storage (app)" to mb(context.cacheDir.usableSpace))
            add("Battery" to battery(context))
            add("Thermal" to thermal(context))
        }
        return Section("Device", rows)
    }

    fun app(context: Context): Section {
        val info = context.applicationInfo
        return Section(
            "App",
            listOf(
                "Version" to "${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE}, ${BuildConfig.GIT_SHA})",
                "Signing" to BuildConfig.SIGNING,
                "targetSdk" to "${info.targetSdkVersion}",
                "64-bit process" to "${Process.is64Bit()}",
            ),
        )
    }

    /** What the APK extracted to nativeLibraryDir (D-23) and whether Android lets us execute it. */
    fun binary(context: Context, buildInfo: String?): Section {
        val dir = File(context.applicationInfo.nativeLibraryDir)
        val rows = buildList {
            add("nativeLibraryDir" to dir.path)
            val libs = dir.listFiles()?.sortedBy { it.name }.orEmpty()
            if (libs.isEmpty()) add("Files" to "none (APK built without the bundled ffmpeg?)")
            libs.forEach { add(it.name to "${mb(it.length())}, canExecute=${it.canExecute()}") }
            add("SELinux context" to (runCatching { File("/proc/self/attr/current").readText().trim('\u0000', ' ', '\n') }.getOrNull() ?: "unreadable"))
            buildInfo?.lineSequence()?.firstOrNull { it.startsWith("FFmpeg") || it.startsWith("ffmpeg") }?.let { add("BUILDINFO" to it.substringBefore(" http").replace(Regex("""\s+"""), " ").trim()) }
        }
        return Section("ffmpeg binary", rows)
    }

    fun battery(context: Context): String {
        val i = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return "unknown"
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        val temp = i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
        val t = if (temp != Int.MIN_VALUE) ", ${temp / 10}.${temp % 10} °C" else ""
        return "${level * 100 / scale}%" + (if (plugged) ", charging" else ", on battery") + t
    }

    fun thermal(context: Context): String {
        if (Build.VERSION.SDK_INT < 29) return "n/a (Android < 10)"
        val s = context.getSystemService(PowerManager::class.java)?.currentThermalStatus ?: return "unknown"
        return listOf("none", "light", "moderate", "severe", "critical", "emergency", "shutdown").getOrElse(s) { "status $s" }
    }

    fun interactive(context: Context): Boolean = context.getSystemService(PowerManager::class.java)?.isInteractive ?: true
}
