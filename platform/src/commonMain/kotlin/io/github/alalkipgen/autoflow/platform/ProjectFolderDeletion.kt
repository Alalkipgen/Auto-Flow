package io.github.alalkipgen.autoflow.platform

/** Available only with an app-owned filesystem projects root. No implementation for SAF or arbitrary folders. */
interface ProjectFolderDeletion {
    data class Target(val folder: String, val projectId: String, val name: String, val bytes: Long)
    enum class Refusal { UNSUPPORTED, PROTECTED_FILES, CHANGED, BUSY, IO }
    sealed interface Result {
        data class Ready(val target: Target) : Result
        data object Deleted : Result
        data class Refused(val reason: Refusal) : Result
    }
    fun inspect(root: String, folder: String): Result
    fun delete(root: String, target: Target, pending: () -> Boolean): Result
}
