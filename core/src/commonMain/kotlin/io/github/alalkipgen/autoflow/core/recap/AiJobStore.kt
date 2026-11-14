package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.CoreJson

/**
 * Loads and saves [AiJob] records to `<jobsDir>/<id>.json` (5A-5).
 * [jobsDir] = `filesDir/ai-jobs`.
 */
class AiJobStore(
    private val fs: FileSystem,
    private val jobsDir: String,
) {
    fun save(job: AiJob) {
        fs.createDirectories(jobsDir)
        val path = joinPath(jobsDir, "${job.id}.json")
        fs.writeDurably(path, CoreJson.encodeToString(AiJob.serializer(), job).encodeToByteArray())
    }

    fun load(id: String): AiJob? {
        val path = joinPath(jobsDir, "$id.json")
        if (!fs.exists(path)) return null
        return runCatching {
            CoreJson.decodeFromString(AiJob.serializer(), fs.read(path).decodeToString())
        }.getOrNull()
    }

    fun loadAll(): List<AiJob> {
        if (!fs.exists(jobsDir)) return emptyList()
        return fs.list(jobsDir)
            .filter { it.endsWith(".json") }
            .mapNotNull { name ->
                runCatching {
                    CoreJson.decodeFromString(AiJob.serializer(), fs.read(joinPath(jobsDir, name)).decodeToString())
                }.getOrNull()
            }
            .sortedBy { it.id }
    }

    fun delete(id: String) {
        // FileSystem has no delete; we mark the job CANCELLED instead.
        val path = joinPath(jobsDir, "$id.json")
        if (fs.exists(path)) {
            val job = load(id) ?: return
            save(job.copy(state = AiJobState.CANCELLED))
        }
    }
}
