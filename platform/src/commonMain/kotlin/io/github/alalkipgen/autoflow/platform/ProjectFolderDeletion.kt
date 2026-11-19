package io.github.alalkipgen.autoflow.platform

import kotlin.time.Instant

/** Available only with an app-owned filesystem projects root. No implementation for SAF or arbitrary folders. */
interface ProjectFolderDeletion {
    data class Target(val folder: String, val projectId: String, val name: String, val bytes: Long,
        val revision: Instant, val backups: List<String>)
    enum class Refusal { UNSUPPORTED, KEEP_BACKUPS_FAILED, CHANGED, BUSY, IO }
    sealed interface Result {
        data class Ready(val target: Target) : Result
        data class Deleted(val backupsAt: String?) : Result
        data class Refused(val reason: Refusal) : Result
    }
    fun inspect(root: String, folder: String): Result
    fun delete(root: String, target: Target, pending: () -> Boolean): Result
}
