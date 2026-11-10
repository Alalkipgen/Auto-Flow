package io.github.alalkipgen.autoflow.ai.storyboard

import io.github.alalkipgen.autoflow.ai.JsonSchema
import io.github.alalkipgen.autoflow.core.model.Section
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.model.StoryboardMode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/** Prompts and structured-output schemas for the two storyboard stages (specs/prompt-compiler.md §1, spike S1). */
object StoryboardPrompts {
    /** S1 notes: ask for colloquial Burmese, and keep character tokens out of prose. */
    const val SYSTEM = "You are a music-video director writing storyboards for AI video tools (Google Flow / Veo). " +
        "Fields ending in My are read by a Burmese user: write natural, colloquial Myanmar Unicode Burmese, the " +
        "everyday spoken register, not literary style. Fields ending in En are sent to image and video models: " +
        "concise, concrete, visual English. Never put Burmese in En fields. Return only JSON that matches the schema."

    val analysisSchema: JsonSchema = JsonSchema(
        obj(
            "themeMy" to str(), "arcMy" to str(),
            "sections" to arr(
                obj(
                    "type" to buildJsonObject {
                        put("type", "string")
                        putJsonArray("enum") { Section.entries.forEach { add(it.name) } }
                    },
                    "startMs" to int(), "endMs" to int(), "lyricsMy" to str(),
                ),
            ),
            "motifsEn" to arr(str()), "paletteEn" to str(), "charactersMy" to arr(str()),
        ),
    )

    /** An object with exactly [count] shots: models follow object roots more reliably than bare arrays. */
    fun storyboardSchema(count: Int): JsonSchema = JsonSchema(
        obj(
            "shots" to arr(
                obj(
                    "shot" to int(), "sceneMy" to str(), "lyricsMy" to str(),
                    "subjectEn" to str(), "actionEn" to str(), "settingEn" to str(), "cameraEn" to str(),
                    "moodEn" to str(), "lightingEn" to str(), "characters" to arr(str()), "motifEn" to str(),
                    "transitionEn" to str(),
                ),
                min = count, max = count,
            ),
        ),
    )

    fun analysisPrompt(brief: StoryboardBrief): String = with(brief.settings) {
        // Lyrics are appended after trimIndent: their lines have no indent and would stop it from trimming.
        """
        Analyse these Burmese song lyrics for a ${ratio.label} AI music video.
        Song length: ${timecode(durationMs)} ($durationMs ms). Shots are about ${clipMs / 1000} s each.
        STYLE
        Split the lyrics into sections (${Section.entries.joinToString { it.name }}) whose startMs/endMs run in order
        from 0 to $durationMs; estimate the timings from the number of lines if needed.
        motifsEn: 3 to 6 recurring visual motifs. paletteEn: one line. charactersMy: short Burmese descriptions of the
        people in the story (empty for a pure performance or abstract video).
        Lyrics:
        """.trimIndent().replace("STYLE", styleLines(brief)) + "\n" + brief.lyricsMy.trim()
    }

    /** [notes]: the user's own `sceneMy` per shot number, to keep when regenerating. */
    fun storyboardPrompt(brief: StoryboardBrief, analysisJson: String, shots: List<Shot>, notes: Map<Int, String>): String {
        val list = shots.joinToString("\n") { s ->
            "shot ${s.number}: ${timecode(s.startMs)}-${timecode(s.startMs + s.durationMs)}" + (s.section?.let { " $it" } ?: "")
        }
        val characters = if (brief.characters.isEmpty()) {
            "There are no named characters: describe people directly in the En fields and leave characters empty."
        } else {
            "Characters (token = who): " + brief.characters.joinToString("; ") { "${it.token} = ${it.nameMy} (${it.descriptionEn})" } +
                ". In En fields write a character only as {{TOKEN}}, e.g. {{${brief.characters.first().token}}}, and list " +
                "the tokens used in characters."
        }
        val g = brief.globalStyle
        val style = listOf(g.visual, g.palette, g.lighting, g.camera).filter { it.isNotBlank() }.joinToString("; ").ifEmpty { "not set" }
        val noteLines = notes.entries.sortedBy { it.key }.joinToString("\n") { "shot ${it.key}: ${it.value}" }
        return buildString {
            appendLine("Write one ShotSpec per shot for a ${brief.settings.ratio.label} music video: ${shots.size} shot(s), in this order:")
            appendLine(list)
            appendLine(styleLines(brief))
            appendLine("Rules:")
            appendLine("- shot is the number above. lyricsMy is the lyric sung during that shot (\"\" if instrumental).")
            appendLine("- sceneMy: one or two colloquial Burmese sentences describing what we see.")
            appendLine("- subjectEn, actionEn, settingEn, cameraEn, moodEn, lightingEn: short English phrases for a video model.")
            appendLine("- motifEn: one motif from the analysis or \"\". transitionEn: cut, dissolve, match cut, whip pan…")
            appendLine("- $characters")
            appendLine("Global style (the app adds it to every prompt; do not repeat it): $style.")
            if (noteLines.isNotEmpty()) {
                appendLine("The director already wrote these scenes. Keep their sceneMy and meaning, and write En fields that match:")
                appendLine(noteLines)
            }
            appendLine("Analysis:")
            append(analysisJson)
        }
    }

    private fun styleLines(brief: StoryboardBrief): String = with(brief.settings) {
        "Song style: ${songStyle.ifBlank { "not given" }}. Video style: ${videoStyle.ifBlank { "not given" }}. " +
            "Mood/colour: ${mood.ifBlank { "not given" }}. Storyboard mode: $storyboardMode (${describe(storyboardMode)})."
    }

    private fun describe(mode: StoryboardMode) = when (mode) {
        StoryboardMode.NARRATIVE -> "a story with characters"
        StoryboardMode.PERFORMANCE -> "the singer performing the song"
        StoryboardMode.ABSTRACT -> "visual metaphors, no plot"
        StoryboardMode.MIXED -> "a story intercut with performance"
    }

    /** `m:ss.t`, e.g. `1:04.5`. */
    fun timecode(ms: Long): String {
        val tenths = ms / 100
        return "${tenths / 600}:${((tenths / 10) % 60).toString().padStart(2, '0')}.${tenths % 10}"
    }

    private fun str() = buildJsonObject { put("type", "string") }
    private fun int() = buildJsonObject { put("type", "integer") }
    private fun arr(items: JsonObject, min: Int? = null, max: Int? = null) = buildJsonObject {
        put("type", "array")
        put("items", items)
        min?.let { put("minItems", it) }
        max?.let { put("maxItems", it) }
    }
    private fun obj(vararg props: Pair<String, JsonObject>) = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") { props.forEach { (k, v) -> put(k, v) } }
        putJsonArray("required") { props.forEach { add(it.first) } }
    }
}
