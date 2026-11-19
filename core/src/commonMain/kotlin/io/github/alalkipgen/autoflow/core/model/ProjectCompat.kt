package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.descriptors.elementNames
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull

/**
 * Forward compatibility (D-78): tells a `project.json` written by a newer Auto Flow apart from a damaged one, so it
 * is shown as "needs a newer Auto Flow" and never "recovered" from an older backup.
 */
object ProjectCompat {
    @OptIn(ExperimentalSerializationApi::class)
    private val knownKeys: Set<String> = Project.serializer().descriptor.elementNames.toSet()

    @OptIn(ExperimentalSerializationApi::class)
    private val knownModes: Set<String> = Mode.serializer().descriptor.elementNames.toSet()

    /** True only for well-formed JSON with a newer schema version, an unknown mode or an unknown top-level key. */
    fun madeByNewerApp(bytes: ByteArray): Boolean {
        val raw = runCatching { Json.parseToJsonElement(bytes.decodeToString()) as? JsonObject }.getOrNull() ?: return false
        val version = (raw["schemaVersion"] as? JsonPrimitive)?.intOrNull
        if (version != null && version > Project.SCHEMA_VERSION) return true
        val mode = (raw["mode"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (mode != null && mode !in knownModes) return true
        return raw.keys.any { it !in knownKeys }
    }
}
