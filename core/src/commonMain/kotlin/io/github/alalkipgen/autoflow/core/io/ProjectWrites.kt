package io.github.alalkipgen.autoflow.core.io

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.first

/** Process-local coordination between persistent job executors and the sole project-folder deletion boundary.
 * Android executes the workers in the app process. A restarted worker also rechecks the project manifest. */
object ProjectWrites {
    private val owners = MutableStateFlow<Map<String, Int>>(emptyMap())

    fun writer(path: String): (() -> Unit)? = acquire(path, false)
    suspend fun awaitWriter(path: String): () -> Unit {
        while (true) {
            writer(path)?.let { return it }
            owners.first { (it[path] ?: 0) >= 0 }
        }
    }
    fun deletion(path: String): (() -> Unit)? = acquire(path, true)

    private fun acquire(path: String, exclusive: Boolean): (() -> Unit)? {
        while (true) {
            val before = owners.value
            val count = before[path] ?: 0
            if (count < 0 || exclusive && count != 0) return null
            val next = if (exclusive) -1 else count + 1
            if (owners.compareAndSet(before, before + (path to next))) return {
                owners.update { map ->
                    val n = map.getValue(path)
                    if (n <= 1) map - path else map + (path to n - 1)
                }
            }
        }
    }
}
