package io.github.alalkipgen.autoflow.core.prompt

import io.github.alalkipgen.autoflow.core.model.Character

/**
 * Character Bible rules (PLAN Phase 2; specs/prompt-compiler.md): each character is written once — a `{{TOKEN}}`,
 * a Burmese name for the owner and an English description for the models — and [PromptCompiler] puts the
 * description into every shot that uses the token, so the person looks the same in every shot.
 */
object CharacterBible {
    const val MAX_TOKEN = 24

    /** What the owner types → a token the compiler matches (`{{[A-Z0-9_]+}}`): "hero girl" → `HERO_GIRL`. */
    fun normalizeToken(text: String): String =
        text.trim().uppercase().map { c -> if (c in 'A'..'Z' || c in '0'..'9') c else '_' }.joinToString("")
            .replace(Regex("_+"), "_").trim('_').take(MAX_TOKEN)

    /** A token for a new character: CHAR1, CHAR2… not used yet. */
    fun nextToken(existing: List<Character>): String {
        val taken = existing.map { it.token }.toSet()
        return generateSequence(1) { it + 1 }.map { "CHAR$it" }.first { it !in taken }
    }

    /** Why the bible cannot be used as is, one line per problem (empty = fine). Blank rows are ignored. */
    fun problems(characters: List<Character>): List<String> = buildList {
        val rows = characters.filterNot(::blank)
        rows.filter { it.token.isBlank() }.forEach { add("A character has no token (e.g. HERO).") }
        rows.filter { it.token.isNotBlank() && it.descriptionEn.isBlank() }
            .forEach { add("{{${it.token}}} has no English description: shots that use it will not compile.") }
        rows.map { it.token }.filter { it.isNotBlank() }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
            .forEach { add("{{$it}} is used by more than one character.") }
    }

    /** The bible as saved: blank rows dropped, tokens normalized, texts trimmed. */
    fun clean(characters: List<Character>): List<Character> = characters.filterNot(::blank).map {
        it.copy(token = normalizeToken(it.token), nameMy = it.nameMy.trim(), descriptionEn = it.descriptionEn.trim())
    }

    private fun blank(c: Character) = c.token.isBlank() && c.nameMy.isBlank() && c.descriptionEn.isBlank() && c.refImages.isEmpty()
}
