package io.github.alalkipgen.autoflow.core.prompt

/** Renders a [PromptIR] into the text one target expects (specs/prompt-compiler.md §3). Plain text, no markdown. */
interface PromptDialect {
    fun render(ir: PromptIR): String

    companion object {
        const val MAX_CHARS = 900
        internal const val MAX_AVOID_CHARS = 300
    }
}

/**
 * Google Flow / Veo: subject + action, setting. Camera, lighting, mood, style. Avoid: negative.
 * Copied into Flow by hand (D-04).
 */
object VeoFlowDialect : PromptDialect {
    override fun render(ir: PromptIR): String =
        Dialect.fit(listOf(Dialect.sceneSentence(ir), Text.sentence(Text.join(ir.camera, ir.lighting, Dialect.mood(ir), ir.style))), ir.negative)
}

/** Reference image: same as Veo, but camera motion becomes static framing and it asks for a single still frame. */
object ImageDialect : PromptDialect {
    const val STILL = "single frame, cinematic still"

    override fun render(ir: PromptIR): String = Dialect.fit(
        listOf(
            Dialect.sceneSentence(ir),
            Text.sentence(Text.join(Dialect.staticFraming(ir.camera), ir.lighting, Dialect.mood(ir), ir.style, STILL)),
        ),
        ir.negative,
    )
}

internal object Dialect {
    fun sceneSentence(ir: PromptIR): String {
        val subject = if (ir.characters.isEmpty()) ir.subject else "${ir.subject} (${ir.characters.joinToString("; ")})"
        return Text.sentence(Text.join("$subject ${ir.action}", ir.setting))
    }

    fun mood(ir: PromptIR): String =
        if (Regex("""\b(mood|atmosphere|feel|feeling)\b""", RegexOption.IGNORE_CASE).containsMatchIn(ir.mood)) ir.mood
        else Text.join(ir.mood).let { if (it.isEmpty()) it else "$it mood" }

    /** Motion phrase → static framing for a still image (`null` = drop it). First match wins. */
    private val MOTION: List<Pair<Regex, String?>> = listOf(
        """push[- ]?in|dolly[- ]?in|zoom(?:ing)?[- ]?in|creep(?:ing)?[- ]?in""" to "medium close-up",
        """pull(?:ing)?[- ]?(?:out|back)|dolly[- ]?out|zoom(?:ing)?[- ]?out""" to "wide shot",
        """tracking(?: shot)?|tracks?|follow(?:s|ing)?|truck(?:s|ing)?|dolly(?: shot)?""" to "medium shot",
        """crane(?: shot)?|jib""" to "high angle",
        """whip[- ]?pan|pan(?:s|ning|ned)?|tilt(?:s|ing|ed)?|orbit(?:s|ing)?|arc(?:s|ing)?|hand-?held|steadicam|rack[- ]focus""" to null,
    ).map { (pattern, framing) ->
        Regex(
            """\b(?:(?:slow(?:ly)?|gentle|gently|quick|fast|smooth(?:ly)?|steady)\s+)*(?:$pattern)\b""" +
                """(?:\s+(?:shot|move|movement))?""" +
                """(?:\s+(?:to|toward|towards|across|around|from|over|along|past|through|behind|alongside|with|following|follows|left|right|up|down|in|out|on|onto|into)\b[^,;]*)?""",
            RegexOption.IGNORE_CASE,
        ) to framing
    }

    fun staticFraming(camera: String): String {
        val out = mutableListOf<String>()
        for (segment in camera.split(',', ';')) {
            var rest = segment
            val framings = mutableListOf<String>()
            for ((regex, framing) in MOTION) {
                if (regex.containsMatchIn(rest)) {
                    framing?.let { framings += it }
                    rest = regex.replace(rest, " ")
                }
            }
            out += framings
            Text.clean(rest).takeIf { it.isNotEmpty() }?.let { out += it }
        }
        return out.distinct().joinToString(", ")
    }

    fun fit(sentences: List<String>, negative: String, max: Int = PromptDialect.MAX_CHARS): String {
        val avoid = if (negative.isEmpty()) "" else "Avoid: ${truncateWords(negative, PromptDialect.MAX_AVOID_CHARS)}."
        val budget = max - if (avoid.isEmpty()) 0 else avoid.length + 1
        var main = sentences.filter { it.isNotEmpty() }.joinToString(" ")
        if (main.length > budget) main = truncateWords(main, budget - 1) + "."
        return listOf(main, avoid).filter { it.isNotEmpty() }.joinToString(" ")
    }

    private fun truncateWords(text: String, max: Int): String {
        if (text.length <= max) return text
        val cut = text.take(max + 1).substringBeforeLast(' ').ifEmpty { text.take(max) }
        return cut.trimEnd('.', ',', ';', ':', ' ')
    }
}
