package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson

/**
 * Loads and saves [AiJob] records to `<jobsDir>/<id>.json` (5A-5).
 * [jobsDir] = `filesDir/ai-jobs`.
 *
 * Audit F11: a save is atomic — `<id>.json.tmp` forced to storage, then renamed over `<id>.json` — so a crash or a
 * power cut mid-write leaves the previous state, never a truncated file; [update] is a read-modify-write under the
 * folder lock, so a Cancel and a runner's checkpoint never overwrite each other. A file that cannot be read is
 * [Read.Damaged] (kept as it is and listed by [damaged]), not the same as a missing job.
 */
class AiJobStore(
    private val fs: FileSystem,
    private val jobsDir: String,
) {
    sealed interface Read {
        data object Missing : Read
        data class Damaged(val reason: String) : Read
        data class Ok(val job: AiJob) : Read
    }

    fun save(job: AiJob) {
        fs.createDirectories(jobsDir)
        fs.withFolderLock(jobsDir) { write(job) }
    }

    /** The job file [id] as it is on disk now. */
    fun read(id: String): Read {
        val path = pathOf(id)
        if (!fs.exists(path)) return Read.Missing
        val text = try {
            fs.read(path).decodeToString()
        } catch (e: Exception) {
            return Read.Damaged("cannot read $id.json: ${e.message}")
        }
        return try {
            Read.Ok(CoreJson.decodeFromString(AiJob.serializer(), text))
        } catch (e: Exception) {
            Read.Damaged("$id.json is not a job file: ${e.message?.take(160)}")
        }
    }

    /** The job, or null when it is missing or damaged (see [read] to tell them apart). */
    fun load(id: String): AiJob? = (read(id) as? Read.Ok)?.job

    /**
     * [change] applied to job [id] as it is on disk now, saved, under the folder lock; null when there is no readable
     * job [id] (nothing is written then).
     */
    fun update(id: String, change: (AiJob) -> AiJob): AiJob? {
        if (!fs.exists(jobsDir)) return null
        return fs.withFolderLock(jobsDir) {
            val now = load(id) ?: return@withFolderLock null
            change(now).also { if (it != now) write(it) }
        }
    }

    /** Claim equivalent active inputs atomically, without resubmitting the same request sequence. */
    fun prepareEnqueue(job: AiJob): Enqueue {
        val request = if (job.projectIdentity == null) job.copy(projectIdentity = projectIdentity(job.project)) else job
        fs.createDirectories(jobsDir)
        return fs.withFolderLock(jobsDir) {
            val existing = request.inputKey?.let { key ->
                loadAll().firstOrNull {
                    it.id != request.id && it.inputKey == key && it.project == request.project && it.kind == request.kind &&
                        it.state in setOf(AiJobState.QUEUED, AiJobState.RUNNING)
                }
            }
            if (existing != null) return@withFolderLock Enqueue(existing.id, false)
            val queued = when (val found = read(request.id)) {
                Read.Missing -> request.copy(state = AiJobState.QUEUED, error = null, initialItemCount = request.initialItemCount ?: request.items.size,
                    savedClips = if (request.kind == AiJobKind.TTS_ALL) 0 else null)
                is Read.Damaged -> request.copy(id = "${request.id}-r", state = AiJobState.QUEUED, error = null, initialItemCount = request.items.size,
                    savedClips = if (request.kind == AiJobKind.TTS_ALL) 0 else null)
                is Read.Ok -> when (found.job.state) {
                    AiJobState.DONE, AiJobState.CANCELLED -> return@withFolderLock Enqueue(request.id, false)
                    AiJobState.QUEUED, AiJobState.RUNNING -> return@withFolderLock Enqueue(request.id, true)
                    AiJobState.PAUSED, AiJobState.FAILED -> found.job.copy(state = AiJobState.QUEUED, error = null)
                }
            }
            AiRunTiming.queued(queued.id)
            write(queued)
            Enqueue(queued.id, true)
        }
    }

    internal fun projectKey(dir: String) = fs.canonicalPath(dir)
    internal fun projectStillMatches(job: AiJob): Boolean = job.projectIdentity == null || projectIdentity(job.project) == job.projectIdentity
    private fun projectIdentity(dir: String): String? = io.github.alalkipgen.autoflow.core.store.ProjectStore(fs).open(dir).getOrNull()?.id

    data class Enqueue(val id: String, val shouldLaunch: Boolean)

    fun loadAll(): List<AiJob> = ids().mapNotNull { load(it) }.sortedBy { it.id }

    /** Ids of job files that cannot be read: left untouched, for the Jobs screen and a bug report. */
    fun damaged(): List<String> = ids().filter { read(it) is Read.Damaged }.sorted()

    fun delete(id: String) {
        // FileSystem has no delete; we mark the job CANCELLED instead.
        update(id) { it.copy(state = AiJobState.CANCELLED) }
    }

    private fun ids(): List<String> {
        if (!fs.exists(jobsDir)) return emptyList()
        return fs.list(jobsDir).filter { it.endsWith(".json") }.map { it.removeSuffix(".json") }
    }

    private fun write(job: AiJob) {
        val path = pathOf(job.id)
        val tmp = "$path.tmp"
        fs.writeDurably(tmp, CoreJson.encodeToString(AiJob.serializer(), job).encodeToByteArray())
        fs.atomicMove(tmp, path)
    }

    private fun pathOf(id: String) = joinPath(jobsDir, "$id.json")
}
