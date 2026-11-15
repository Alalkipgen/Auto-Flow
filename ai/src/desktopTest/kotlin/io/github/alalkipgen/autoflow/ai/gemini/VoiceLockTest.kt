package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.VoiceRef
import io.github.alalkipgen.autoflow.ai.config.TtsCandidate
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest

/**
 * 5A-1 voice lock (D-35, studio-v2 §3): `speak()` uses exactly one model. A 503 / 429 / 404 on the locked model
 * never falls through to another candidate; 429 / 5xx are "busy" (the job pauses), 404 is not (the job fails).
 */
class VoiceLockTest {
    private val locked = "gemini-3.8-flash-tts"
    private val candidates = listOf(
        TtsCandidate(locked, langs = listOf("my", "en")),
        TtsCandidate("gemini-3.8-flash-lite-tts", langs = listOf("en")),
        TtsCandidate("gemini-2.5-flash-preview-tts", langs = listOf("en")),
    )
    private val models = mutableListOf<String>()

    private fun TestScope.provider(status: HttpStatusCode, body: String, all: List<TtsCandidate> = candidates) = GeminiTtsProvider(
        http = HttpClient(MockEngine) {
            engine {
                dispatcher = StandardTestDispatcher(testScheduler)
                addHandler { req ->
                    models += req.url.encodedPath.substringAfterLast("/models/").substringBefore(":")
                    respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
                }
            }
        },
        apiKey = { "k" },
        allCandidates = all,
    )

    private fun err(code: Int, msg: String) = """{"error":{"code":$code,"message":"$msg"}}"""
    private val retryLater =
        """{"error":{"code":429,"message":"quota","details":[{"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120s"}]}}"""

    @Test fun locked503_retriesOnlyTheLockedModel_andIsBusy() = runTest {
        val r = provider(HttpStatusCode.ServiceUnavailable, err(503, "overloaded")).speak("က", VoiceRef("Charon"), "my", null, locked)
        assertIs<AiResult.Failed>(r)
        assertTrue(r.isBusy(), "503 after retries → the job pauses")
        assertEquals(List(3) { locked }, models, "D-15: 3 tries, all on the locked model")
    }

    @Test fun locked429_neverFallsThrough_andIsBusy() = runTest {
        val r = provider(HttpStatusCode.TooManyRequests, retryLater).speak("က", VoiceRef("Charon"), "en", null, locked)
        assertIs<AiResult.RateLimited>(r)
        assertTrue(r.isBusy())
        assertEquals(listOf(locked), models, "even for English, where two other models could answer")
    }

    @Test fun locked404_failsWithoutAnotherModel_andIsNotBusy() = runTest {
        val r = provider(HttpStatusCode.NotFound, err(404, "model not found")).speak("က", VoiceRef("Charon"), "en", null, locked)
        assertEquals("model_unavailable", assertIs<AiResult.Failed>(r).code)
        assertFalse(r.isBusy(), "404 → the job fails: Model unavailable · Change voice…")
        assertEquals(listOf(locked), models)
    }

    @Test fun lockFromTheProviderFieldWorksTheSame() = runTest {
        val p = GeminiTtsProvider(
            http = HttpClient(MockEngine) {
                engine {
                    dispatcher = StandardTestDispatcher(testScheduler)
                    addHandler { req ->
                        models += req.url.encodedPath.substringAfterLast("/models/").substringBefore(":")
                        respond(err(503, "busy"), HttpStatusCode.ServiceUnavailable, headersOf(HttpHeaders.ContentType, "application/json"))
                    }
                }
            },
            apiKey = { "k" }, allCandidates = candidates, lockedModel = "gemini-2.5-flash-preview-tts",
        )
        p.speak("hi", VoiceRef("Kore"), "en", null, null)
        assertEquals(setOf("gemini-2.5-flash-preview-tts"), models.toSet())
    }

    /** A voice chip used before any take stores a lock with model "" — it must not be sent as a model name. */
    @Test fun blankLock_usesTheDefaultModel_notAnEmptyName() = runTest {
        provider(HttpStatusCode.ServiceUnavailable, err(503, "busy")).speak("က", VoiceRef("Kore"), "my", null, "")
        assertTrue(models.isNotEmpty())
        assertEquals(setOf(locked), models.toSet())
    }

    /** No lock yet: the default lock = the first model that speaks the language, and only that one (spec §3). */
    @Test fun noLock_usesOnlyTheFirstModelForTheLanguage() = runTest {
        val all = listOf(
            TtsCandidate("a-tts", langs = listOf("en", "my")),
            TtsCandidate("b-tts", langs = listOf("en", "my")),
        )
        provider(HttpStatusCode.NotFound, err(404, "gone"), all).speak("က", VoiceRef("Kore"), "my", null, null)
        assertEquals(listOf("a-tts"), models, "no automatic model change, ever — not even before the first take")
    }

    @Test fun noModelForTheLanguage_failsWithoutARequest() = runTest {
        val r = provider(HttpStatusCode.OK, "{}").speak("x", VoiceRef("Kore"), "xx", null, null)
        assertEquals("no_tts_model_for_lang", assertIs<AiResult.Failed>(r).code)
        assertTrue(models.isEmpty())
    }
}
