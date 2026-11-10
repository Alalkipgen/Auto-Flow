package io.github.alalkipgen.autoflow.platform

/** Result type at the :platform boundary. No exceptions cross module boundaries (AGENTS.md §3). */
sealed interface PlatformResult<out T> {
    data class Ok<out T>(val value: T) : PlatformResult<T>

    /** This OS or session cannot do it at all (headless, no browser, DPAPI off Windows). Retrying will not help. */
    data class Unsupported(val reason: String) : PlatformResult<Nothing>

    /** It was attempted and failed; [reason] is safe to show to the user. */
    data class Failed(val reason: String) : PlatformResult<Nothing>
}

fun <T> PlatformResult<T>.getOrNull(): T? = (this as? PlatformResult.Ok)?.value

/** Paths handed to the :core stores use `/`; java.nio accepts it on Windows too. */
fun String.toPortablePath(): String = replace('\\', '/')
