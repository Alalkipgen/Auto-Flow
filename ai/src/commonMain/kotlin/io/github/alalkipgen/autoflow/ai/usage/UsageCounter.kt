package io.github.alalkipgen.autoflow.ai.usage

import io.github.alalkipgen.autoflow.core.io.FileSystem
import kotlin.time.Clock
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * App-side requests-per-day counter per model (D-15: the API returns no rate-limit headers), persisted at [path]
 * (`~/.autoflow/usage.json`). Advisory only: I/O or parse errors never fail an AI call; a broken file starts over.
 * The day rolls over at midnight Pacific, when Gemini daily quotas reset (fixed UTC−8, DST ignored).
 */
class UsageCounter(
    private val fs: FileSystem,
    private val path: String,
    private val nowMs: () -> Long = { Clock.System.now().toEpochMilliseconds() },
) {
    private val mutex = Mutex()
    private var cache: UsageFile? = null

    /** Counts one request to [model]; returns today's count for it. */
    suspend fun record(model: String): Int = mutex.withLock {
        val day = quotaDay(nowMs())
        val current = load().takeIf { it.day == day } ?: UsageFile(day)
        val count = (current.requests[model] ?: 0) + 1
        val next = current.copy(requests = current.requests + (model to count))
        cache = next
        save(next)
        count
    }

    /** Today's requests per model. */
    suspend fun today(): Map<String, Int> = mutex.withLock {
        load().takeIf { it.day == quotaDay(nowMs()) }?.requests.orEmpty()
    }

    private fun load(): UsageFile = cache ?: try {
        if (fs.exists(path)) JSON.decodeFromString<UsageFile>(fs.read(path).decodeToString()) else UsageFile("")
    } catch (e: Exception) {
        UsageFile("")
    }.also { cache = it }

    private fun save(file: UsageFile) {
        try {
            path.substringBeforeLast('/', "").takeIf { it.isNotEmpty() }?.let(fs::createDirectories)
            val tmp = "$path.tmp"
            fs.writeDurably(tmp, JSON.encodeToString(UsageFile.serializer(), file).encodeToByteArray())
            fs.atomicMove(tmp, path)
        } catch (e: Exception) {
            // Advisory counter: keep counting in memory.
        }
    }

    companion object {
        private val JSON = Json { ignoreUnknownKeys = true; prettyPrint = true }
        private const val DAY_MS = 86_400_000L
        private const val PACIFIC_OFFSET_MS = -8 * 3_600_000L

        /** ISO date (`yyyy-MM-dd`) of the quota day containing [epochMs]. */
        fun quotaDay(epochMs: Long): String = civilDate((epochMs + PACIFIC_OFFSET_MS).floorDiv(DAY_MS))

        /** Proleptic Gregorian date for days since 1970-01-01 (H. Hinnant's `civil_from_days`). */
        internal fun civilDate(epochDay: Long): String {
            val z = epochDay + 719_468
            val era = z.floorDiv(146_097L)
            val doe = z - era * 146_097
            val yoe = (doe - doe / 1_460 + doe / 36_524 - doe / 146_096) / 365
            val doy = doe - (365 * yoe + yoe / 4 - yoe / 100)
            val mp = (5 * doy + 2) / 153
            val d = doy - (153 * mp + 2) / 5 + 1
            val m = if (mp < 10) mp + 3 else mp - 9
            val y = yoe + era * 400 + if (m <= 2) 1 else 0
            return "${y.toString().padStart(4, '0')}-${m.toString().padStart(2, '0')}-${d.toString().padStart(2, '0')}"
        }
    }
}

@Serializable
internal data class UsageFile(val day: String, val requests: Map<String, Int> = emptyMap())
