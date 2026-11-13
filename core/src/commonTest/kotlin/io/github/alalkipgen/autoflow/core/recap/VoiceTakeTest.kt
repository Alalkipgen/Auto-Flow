package io.github.alalkipgen.autoflow.core.recap

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Voice lock consistency checks (voice lock, D-35). */
class VoiceTakeTest {
    private val lock = VoiceLock(provider = "gemini", model = "gemini-3.8-flash-tts", voice = "Charon", lang = "my")

    private fun take(provider: String?, model: String?, voice: String?) = VoiceTake(
        segment = 0,
        audio = ArtifactId("au_0123456789ab"),
        source = VoiceSource.TTS,
        durationMs = 3_000,
        textHash = "aabbccdd",
        provider = provider,
        model = model,
        voice = voice,
    )

    @Test
    fun matchesLockWhenAllFieldsAgree() {
        assertTrue(take("gemini", "gemini-3.8-flash-tts", "Charon").matchesLock(lock))
    }

    @Test
    fun doesNotMatchWhenModelDiffers() {
        assertFalse(take("gemini", "gemini-2.5-flash-preview-tts", "Charon").matchesLock(lock))
    }

    @Test
    fun doesNotMatchWhenVoiceDiffers() {
        assertFalse(take("gemini", "gemini-3.8-flash-tts", "Kore").matchesLock(lock))
    }

    @Test
    fun doesNotMatchWhenProviderDiffers() {
        assertFalse(take("azure", "gemini-3.8-flash-tts", "Charon").matchesLock(lock))
    }

    @Test
    fun preLockTakeWithNullFieldsDoesNotMatch() {
        assertFalse(take(null, null, null).matchesLock(lock))
    }

    @Test
    fun recordedTakeDoesNotMatchTtsLock() {
        // Recorded takes have provider="recorded", model=null, voice=null — never match a TTS lock.
        assertFalse(take("recorded", null, null).matchesLock(lock))
    }
}
