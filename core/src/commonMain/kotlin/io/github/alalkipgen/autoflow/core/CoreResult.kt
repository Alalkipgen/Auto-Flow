package io.github.alalkipgen.autoflow.core

import io.github.alalkipgen.autoflow.core.model.ArtifactId

/** Result type at the :core boundary. No exceptions cross module boundaries (AGENTS.md §3). */
sealed interface CoreResult<out T> {
    data class Ok<out T>(val value: T) : CoreResult<T>
    data class Err(val error: CoreError) : CoreResult<Nothing>
}

fun <T> CoreResult<T>.getOrNull(): T? = (this as? CoreResult.Ok)?.value

inline fun <T, R> CoreResult<T>.map(transform: (T) -> R): CoreResult<R> = when (this) {
    is CoreResult.Ok -> CoreResult.Ok(transform(value))
    is CoreResult.Err -> this
}

sealed interface CoreError {
    val message: String

    data class NotFound(val path: String) : CoreError {
        override val message: String get() = "Not found: $path"
    }

    data class AlreadyExists(val path: String) : CoreError {
        override val message: String get() = "Already exists and is not empty: $path"
    }

    /** The file exists but cannot be decoded. Nothing was changed; [backupAvailable] means `.bak` can be opened. */
    data class Corrupt(val path: String, val reason: String, val backupAvailable: Boolean = false) : CoreError {
        override val message: String get() = "Cannot read $path: $reason"
    }

    data class UnsupportedSchema(val path: String, val found: Int, val supported: Int) : CoreError {
        override val message: String get() = "$path has schemaVersion $found; this app supports up to $supported"
    }

    data class Io(val path: String, val reason: String) : CoreError {
        override val message: String get() = "I/O error on $path: $reason"
    }

    data class InvalidInput(val reason: String) : CoreError {
        override val message: String get() = reason
    }

    /**
     * The prompt cannot be compiled (specs/prompt-compiler.md §4). [emptyFields] are `*En` field names;
     * [unknownTokens] are character tokens with no character or an empty `descriptionEn`. Shown on the shot card.
     */
    data class CannotCompile(val shot: Int, val emptyFields: List<String>, val unknownTokens: List<String>) : CoreError {
        override val message: String get() = buildList {
            if (emptyFields.isNotEmpty()) add("empty English fields: ${emptyFields.joinToString()}")
            if (unknownTokens.isNotEmpty()) add("unknown characters: ${unknownTokens.joinToString()}")
        }.joinToString("; ", prefix = "Shot $shot: ")
    }

    /** Different bytes map to an existing id (first 12 hex of SHA-256). The existing artifact is left untouched. */
    data class HashCollision(val id: ArtifactId) : CoreError {
        override val message: String get() = "Different content already stored as $id"
    }
}
