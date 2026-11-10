package io.github.alalkipgen.autoflow.core.model

import kotlinx.serialization.json.Json

/**
 * JSON format for `project.json` and artifact sidecars. Strict on purpose: an unknown key fails decoding instead of
 * being silently dropped on the next save (never lose user data). Defaults and nulls are written, so every file shows
 * its full shape.
 */
val CoreJson: Json = Json {
    prettyPrint = true
    encodeDefaults = true
    explicitNulls = true
    ignoreUnknownKeys = false
}
