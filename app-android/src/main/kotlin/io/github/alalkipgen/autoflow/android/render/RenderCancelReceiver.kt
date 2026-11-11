package io.github.alalkipgen.autoflow.android.render

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** The render notification's Cancel (D-26): stops only that render ([RenderJobs.cancel]); the queue goes on. */
class RenderCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        intent.getStringExtra(EXTRA_ID)?.let { RenderJobs.of(context).cancel(it) }
    }

    companion object {
        private const val EXTRA_ID = "id"

        fun intent(context: Context, id: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            id.hashCode(),
            Intent(context, RenderCancelReceiver::class.java).putExtra(EXTRA_ID, id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
