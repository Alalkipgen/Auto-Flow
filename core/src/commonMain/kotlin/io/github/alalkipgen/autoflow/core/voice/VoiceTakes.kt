package io.github.alalkipgen.autoflow.core.voice

import io.github.alalkipgen.autoflow.core.model.VoicePart
import io.github.alalkipgen.autoflow.core.model.VoiceTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.RecapRules
import io.github.alalkipgen.autoflow.core.recap.VoiceLock

/** Text → voice take keys (specs/quick-tools.md §6); pure. */
object VoiceTakes {
    /** The tones the chips offer; their TTS `style` text is `tts.tones.<id>` in config/tools.json. */
    val TONES = listOf("calm", "lively", "news", "story")

    /** What TTS speaks for one part: the part after the Lexicon, trimmed. */
    fun spoken(part: String, lexicon: Lexicon): String = lexicon.apply(part).trim()

    /** The take key: [spoken] text, voice lock and tone — a take is current only while all three are unchanged. */
    fun key(spoken: String, lock: VoiceLock?, tone: String?): String =
        RecapRules.textHash(listOf(spoken, lock?.provider, lock?.model, lock?.voice, lock?.lang, tone).joinToString("\u0000") { it.orEmpty() })

    /** The part texts of [voice], in order; [VoiceTool.parts] follow the same order after [plan]. */
    fun texts(voice: VoiceTool, maxChars: Int): List<String> = VoiceParts.split(voice.text, maxChars)

    /**
     * [voice] with its parts re-split from the text: a part whose key is unchanged keeps its id and take, every other part
     * is new (fresh id `p<n>`, no take). The joined voice stays only while the parts are exactly the same.
     */
    fun plan(voice: VoiceTool, maxChars: Int, lexicon: Lexicon): VoiceTool {
        val free = voice.parts.toMutableList()
        var next = (voice.parts.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1
        val parts = texts(voice, maxChars).map { text ->
            val k = key(spoken(text, lexicon), voice.lock, voice.tone)
            free.firstOrNull { it.hash == k }?.also { free.remove(it) } ?: VoicePart("p${next++}", k)
        }
        return voice.copy(parts = parts, joined = voice.joined.takeIf { parts == voice.parts })
    }

    /** Parts that Speak would send: no take, or a take whose key no longer matches (for example after a Lexicon edit). */
    fun unvoiced(voice: VoiceTool, maxChars: Int, lexicon: Lexicon): Int = plan(voice, maxChars, lexicon).parts.count { it.take == null }
}
