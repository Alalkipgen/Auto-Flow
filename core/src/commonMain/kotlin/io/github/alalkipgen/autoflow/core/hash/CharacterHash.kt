package io.github.alalkipgen.autoflow.core.hash

import io.github.alalkipgen.autoflow.core.model.Character
import kotlinx.serialization.json.Json

/**
 * The `characters` input of a prompt (Q-10): `"CHAR1,CHAR2|ch_<12 hex>"` — the tokens the shot uses, sorted, and a
 * hash of their current English descriptions (a missing character hashes as ""). The tokens travel with the value so
 * `Stale.prompt` can recompute it from `project.characters` alone. Null when the shot uses no character.
 */
object CharacterHash {
    fun input(characters: List<Character>, tokens: Collection<String>): String? {
        val used = tokens.filter { it.isNotBlank() }.distinct().sorted()
        if (used.isEmpty()) return null
        val byToken = characters.associateBy { it.token }
        val canonical = Json.encodeToString(used.map { listOf(it, byToken[it]?.descriptionEn?.trim() ?: "") })
        return used.joinToString(",") + "|ch_" + Sha256.hex(canonical.encodeToByteArray()).take(12)
    }

    /** The tokens recorded in an [input] value. */
    fun tokens(input: String): List<String> = input.substringBefore('|').split(',').filter { it.isNotBlank() }
}
