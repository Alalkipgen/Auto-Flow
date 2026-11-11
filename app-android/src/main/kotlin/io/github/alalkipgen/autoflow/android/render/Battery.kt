package io.github.alalkipgen.autoflow.android.render

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import io.github.alalkipgen.autoflow.media.render.BatteryGuard

/** Battery level for the PLAN 1F render guard ([BatteryGuard]: warn below 20 % when not charging). */
data class Battery(val percent: Int?, val charging: Boolean) {
    val lowWarning: Boolean get() = BatteryGuard.shouldWarn(percent, charging)

    override fun toString() = (percent?.let { "$it%" } ?: "unknown") + if (charging) ", charging" else ", on battery"

    companion object {
        /** Reads the sticky battery broadcast (no receiver is registered); unknown when it is unavailable. */
        fun read(context: Context): Battery {
            val i = runCatching { context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) }.getOrNull()
                ?: return Battery(null, false)
            val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            return Battery(if (level < 0) null else level * 100 / scale, i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0)
        }
    }
}
