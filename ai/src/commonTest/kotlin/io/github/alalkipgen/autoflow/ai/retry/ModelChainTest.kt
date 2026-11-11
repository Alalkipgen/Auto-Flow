package io.github.alalkipgen.autoflow.ai.retry

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest

@OptIn(ExperimentalCoroutinesApi::class)
class ModelChainTest {
    private val models = listOf("m1", "m2")

    /** Plays scripted outcomes per model; records the call order. */
    private class Script(vararg perModel: Pair<String, List<Attempt<String>>>) {
        private val queues = perModel.associate { (m, l) -> m to ArrayDeque(l) }
        val calls = mutableListOf<String>()
        val attempt: suspend (String) -> Attempt<String> = { m ->
            calls += m
            queues[m]?.removeFirstOrNull() ?: Attempt.Success("ok-$m")
        }
    }

    private val e503 = Attempt.Transient("http_503", "overloaded")

    private suspend fun run(s: Script, chain: ModelChain = ModelChain()) = chain.run(models, s.attempt)

    @Test
    fun transientErrorsBackOffExponentiallyOnTheSameModel() = runTest {
        val s = Script("m1" to listOf(e503, e503))
        val r = assertIs<AiResult.Ok<String>>(run(s))
        assertEquals("ok-m1", r.value)
        assertEquals(3, r.usage!!.attempts)
        assertEquals(1_000L + 2_000L, currentTime)
    }

    @Test
    fun fiveTransientFailuresMoveToTheNextCandidate() = runTest {
        // maxTries=3: three transient failures on m1 → move to m2
        val s = Script("m1" to List(3) { e503 })
        val r = assertIs<AiResult.Ok<String>>(run(s))
        assertEquals("m2", r.usage!!.model)
        assertEquals(listOf("m1", "m1", "m1", "m2"), s.calls)
        assertEquals(1_000L + 2_000, currentTime) // no wait after the last try
    }

    @Test
    fun short429IsWaitedForLong429SkipsToNextModel() = runTest {
        val short = Script("m1" to listOf(Attempt.Limited(30_000, "slow down")))
        assertEquals("ok-m1", assertIs<AiResult.Ok<String>>(run(short)).value)
        assertEquals(30_000, currentTime)

        val long = Script("m1" to listOf(Attempt.Limited(3_600_000, "daily quota")))
        assertEquals("ok-m2", assertIs<AiResult.Ok<String>>(run(long)).value)
        assertEquals(30_000, currentTime) // no extra wait
    }

    @Test
    fun allModelsLimitedReturnsRateLimitedWithTheSoonestRetry() = runTest {
        val s = Script(
            "m1" to listOf(Attempt.Limited(7_200_000, "m1 quota")),
            "m2" to listOf(Attempt.Limited(null, "m2 quota")),
        )
        assertEquals(AiResult.RateLimited(7_200_000, "m1 quota"), run(s))
    }

    @Test
    fun unavailableSkipsAtOnceAndFatalStopsTheChain() = runTest {
        val gone = Script("m1" to listOf(Attempt.Unavailable("http_404", "retired")))
        assertEquals("ok-m2", assertIs<AiResult.Ok<String>>(run(gone)).value)
        assertEquals(0, currentTime)

        val fatal = Script("m1" to listOf(Attempt.Fatal("invalid_api_key", "bad key")))
        assertEquals(AiResult.Failed("invalid_api_key", "bad key"), run(fatal))
        assertEquals(listOf("m1"), fatal.calls)
    }

    @Test
    fun attemptTimeoutMovesToTheNextCandidate() = runTest {
        val chain = ModelChain(RetryPolicy(attemptTimeoutMs = 120_000))
        val r = chain.run(models) { m -> if (m == "m1") delay(300_000).let { Attempt.Success("late") } else Attempt.Success("ok") }
        assertEquals("ok", assertIs<AiResult.Ok<String>>(r).value)
        assertEquals(120_000, currentTime)
        val all = chain.run(models) { delay(300_000).let { Attempt.Success("late") } }
        assertEquals("timeout", assertIs<AiResult.Failed>(all).code)
    }

    @Test
    fun exhaustedChainReportsTheLastError() = runTest {
        val chain = ModelChain(RetryPolicy(maxTries = 2))
        val r = chain.run(models) { Attempt.Transient("http_500", "boom") }
        assertEquals(AiResult.Failed("http_500", "boom"), r)
        assertEquals("no_model", assertIs<AiResult.Failed>(chain.run(emptyList()) { Attempt.Success(1) }).code)
    }

    @Test
    fun cancellationDuringBackoffStopsWithoutFurtherAttempts() = runTest {
        val s = Script("m1" to List(5) { e503 })
        var result: AiResult<String>? = null
        val job = launch { result = ModelChain().run(models, s.attempt) }
        advanceTimeBy(500)
        runCurrent()
        job.cancel()
        advanceTimeBy(60_000)
        assertTrue(job.isCancelled)
        assertEquals(null, result)
        assertEquals(listOf("m1"), s.calls)
    }

    @Test
    fun everyAttemptIsCounted() = runTest {
        val usage = UsageCounter(InMemoryFileSystem(), "/home/.autoflow/usage.json", nowMs = { 0 })
        run(Script("m1" to listOf(e503, Attempt.Unavailable("http_404", "gone"))), ModelChain(usage = usage))
        assertEquals(mapOf("m1" to 2, "m2" to 1), usage.today())
    }
}
