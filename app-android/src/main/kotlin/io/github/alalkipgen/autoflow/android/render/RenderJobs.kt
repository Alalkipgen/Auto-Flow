package io.github.alalkipgen.autoflow.android.render

import android.content.Context
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.media.render.RenderJob
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.UUID

/**
 * The render jobs of this app (PLAN 1F, D-26), shared by [WorkManagerRenderQueue], [RenderWorker] and the
 * notification's Cancel ([RenderCancelReceiver]). In [dir]:
 * - `<id>.json` — the [RenderJob] (WorkManager input data is capped at 10 KB). It stays until WorkManager no longer
 *   runs or retries that work ([prune]), so a render restarted after the process died still finds it.
 * - `<id>.cancel` — the owner cancelled it. Cancelling never cancels the WorkManager work, because WorkManager would
 *   also cancel every render queued behind it in the chain: a marked job that has not started is skipped, and the
 *   one rendering in this process is stopped.
 */
class RenderJobs(private val dir: File) {
    private val running = HashMap<String, () -> Unit>()

    /** Also kept in memory, so a cancel still holds when the marker file cannot be written. */
    private val cancelled = HashSet<String>()

    /** Writes the job durably (temp file → fsync → rename). */
    fun write(id: String, job: RenderJob) {
        val target = file(id, JOB) ?: throw IOException("not a render id: $id")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("cannot create ${dir.path}")
        val tmp = File(dir, "$id$JOB.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(CoreJson.encodeToString(RenderJob.serializer(), job).encodeToByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(target)) {
            tmp.delete()
            throw IOException("cannot save ${target.path}")
        }
    }

    /** Throws when the job file is missing or unreadable. */
    fun read(id: String): RenderJob {
        val f = file(id, JOB) ?: throw IOException("not a render id: $id")
        return CoreJson.decodeFromString(RenderJob.serializer(), f.readText())
    }

    fun exists(id: String): Boolean = file(id, JOB)?.isFile == true

    @Synchronized
    fun remove(id: String) {
        file(id, JOB)?.delete()
        file(id, CANCEL)?.delete()
        cancelled -= id
    }

    @Synchronized
    fun isCancelled(id: String): Boolean = id in cancelled || file(id, CANCEL)?.exists() == true

    @Synchronized
    fun isRunning(id: String): Boolean = id in running

    /**
     * The worker, before it renders: true = go, and [stop] stops this render if the owner cancels it from now on;
     * false = it was cancelled before it started, so it must not render.
     */
    @Synchronized
    fun begin(id: String, stop: () -> Unit): Boolean {
        if (isCancelled(id)) return false
        running[id] = stop
        return true
    }

    @Synchronized
    fun end(id: String) {
        running -= id
    }

    /** Marks [id] cancelled and stops it when it is rendering in this process; true = it was running. */
    @Synchronized
    fun cancel(id: String): Boolean {
        val marker = file(id, CANCEL) ?: return false
        cancelled += id
        runCatching { dir.mkdirs(); marker.createNewFile() }
        val stop = running[id] ?: return false
        stop()
        return true
    }

    /**
     * Deletes the files of jobs WorkManager no longer runs or retries ([active] = the ids of its unfinished render
     * work) once they are older than [graceMs], because an enqueue may still be on its way. Returns how many.
     */
    @Synchronized
    fun prune(active: Set<String>, nowMs: Long = System.currentTimeMillis(), graceMs: Long = GRACE_MS): Int {
        var deleted = 0
        for (f in dir.listFiles().orEmpty()) {
            val id = f.name.substringBefore('.')
            if (id in active || id in running || nowMs - f.lastModified() <= graceMs) continue
            if (f.delete()) {
                deleted++
                cancelled -= id
            }
        }
        return deleted
    }

    /** Only real ids name files, so an id can never point outside [dir]. */
    private fun file(id: String, suffix: String): File? =
        if (runCatching { UUID.fromString(id).toString() == id }.getOrDefault(false)) File(dir, "$id$suffix") else null

    companion object {
        const val GRACE_MS = 60 * 60 * 1000L
        private const val JOB = ".json"
        private const val CANCEL = ".cancel"

        @Volatile private var shared: RenderJobs? = null

        /** One per process: the queue, the worker and the Cancel receiver must agree on what is running. */
        fun of(context: Context): RenderJobs = shared ?: synchronized(this) {
            shared ?: RenderJobs(File(context.applicationContext.filesDir, "render-jobs")).also { shared = it }
        }
    }
}
