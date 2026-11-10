package io.github.alalkipgen.autoflow.ai.gemini

import io.github.alalkipgen.autoflow.ai.retry.Attempt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.serialization.json.JsonArray

class GeminiWireTest {
    private fun ok(text: String, extra: String = "") =
        """{"candidates":[{"content":{"parts":[$extra{"text":${kotlinx.serialization.json.JsonPrimitive(text)}}]},"finishReason":"STOP"}]}"""

    @Test
    fun thoughtPartsAreSkippedAndArraysAccepted() {
        val r = GeminiWire.classify(200, ok("""[{"shot":1}]""", extra = """{"text":"thinking…","thought":true},"""))
        assertIs<JsonArray>(assertIs<Attempt.Success<*>>(r).value)
    }

    @Test
    fun brokenOrBlockedAnswers() {
        assertEquals("invalid_json", assertIs<Attempt.Transient>(GeminiWire.classify(200, ok("{\"a\":"))).code)
        assertEquals("invalid_response", assertIs<Attempt.Transient>(GeminiWire.classify(200, "<html>")).code)
        assertEquals("blocked", assertIs<Attempt.Fatal>(GeminiWire.classify(200, """{"promptFeedback":{"blockReason":"SAFETY"}}""")).code)
        val safety = """{"candidates":[{"content":{"parts":[]},"finishReason":"SAFETY"}]}"""
        assertEquals("blocked", assertIs<Attempt.Fatal>(GeminiWire.classify(200, safety)).code)
    }

    @Test
    fun errorStatusesWithoutBody() {
        assertEquals("http_500", assertIs<Attempt.Transient>(GeminiWire.classify(500, "")).code)
        assertEquals(null, assertIs<Attempt.Limited>(GeminiWire.classify(429, "")).retryAfterMs)
        assertEquals("permission_denied", assertIs<Attempt.Fatal>(GeminiWire.classify(403, "{}")).code)
        assertEquals("http_418", assertIs<Attempt.Fatal>(GeminiWire.classify(418, "{}")).code)
    }
}
