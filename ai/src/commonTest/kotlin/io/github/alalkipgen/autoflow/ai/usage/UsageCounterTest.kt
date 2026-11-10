package io.github.alalkipgen.autoflow.ai.usage

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

class UsageCounterTest {
    private val path = "/home/u/.autoflow/usage.json"
    private val hour = 3_600_000L

    @Test
    fun countsPerModelAndPersists() = runTest {
        val fs = InMemoryFileSystem()
        val c = UsageCounter(fs, path, nowMs = { 0 })
        assertEquals(1, c.record("a"))
        assertEquals(2, c.record("a"))
        assertEquals(1, c.record("b"))
        assertTrue(fs.exists(path))
        assertEquals(mapOf("a" to 2, "b" to 1), UsageCounter(fs, path, nowMs = { 0 }).today())
    }

    @Test
    fun dayRollsOverAtMidnightPacific() = runTest {
        var now = 7 * hour + 59 * 60_000 // 1970-01-01T07:59Z = 23:59 on 1969-12-31 Pacific (UTC−8)
        val c = UsageCounter(InMemoryFileSystem(), path, nowMs = { now })
        c.record("a")
        now += 60_000
        assertEquals(emptyMap(), c.today())
        assertEquals(1, c.record("a"))
    }

    @Test
    fun corruptOrUnwritableFileNeverFails() = runTest {
        val fs = InMemoryFileSystem()
        fs.createDirectories("/home/u/.autoflow")
        fs.writeDurably(path, "{not json".encodeToByteArray())
        assertEquals(1, UsageCounter(fs, path, nowMs = { 0 }).record("a"))

        val broken = InMemoryFileSystem(failWhen = { _, _ -> true })
        val c = UsageCounter(broken, path, nowMs = { 0 })
        assertEquals(1, c.record("a"))
        assertEquals(2, c.record("a")) // kept in memory
    }

    @Test
    fun civilDates() {
        assertEquals("1970-01-01", UsageCounter.civilDate(0))
        assertEquals("1969-12-31", UsageCounter.civilDate(-1))
        assertEquals("2000-02-29", UsageCounter.civilDate(11_016))
        assertEquals("2026-11-10", UsageCounter.civilDate(20_767))
        assertEquals("2026-11-09", UsageCounter.quotaDay(20_767 * 24 * hour + 7 * hour))
    }
}
