package io.github.alalkipgen.autoflow.core.hash

import io.github.alalkipgen.autoflow.core.model.GlobalStyle
import kotlinx.serialization.json.Json

/** `gs_<12 hex>` over the model-bound fields; [GlobalStyle.hash] itself is excluded. */
fun GlobalStyle.computeHash(): String {
    val canonical = Json.encodeToString(listOf(visual, palette, lighting, camera, negative))
    return "gs_" + Sha256.hex(canonical.encodeToByteArray()).take(12)
}

fun GlobalStyle.withComputedHash(): GlobalStyle = copy(hash = computeHash())
