package io.github.alalkipgen.autoflow.core.recap

/** Validation for native output-frame layers; never interprets legacy movie-picture masks. */
object LayerEdits {
    fun usesRect(type: LayerType): Boolean = type !in listOf(LayerType.BORDER, LayerType.BACKGROUND)

    fun problem(layer: Layer): String? {
        if (layer.id.isBlank()) return "Layer ID is missing"
        if (layer.fromMs < 0 || layer.toMs?.let { it <= layer.fromMs } == true) return "Use 0 ≤ fromMs < toMs (blank toMs = end)"
        if (usesRect(layer.type)) {
            val r = layer.rect
            if (r.size != 4 || r.any { !it.isFinite() }) return "Rect must contain four finite numbers"
            if (r[0] < 0f || r[1] < 0f || r[2] <= 0f || r[3] <= 0f || r[0] + r[2] > 1.000001f || r[1] + r[3] > 1.000001f)
                return "Rect must have positive dimensions and fit inside the output frame (0–1)"
        }
        val p = layer.params
        return when (layer.type) {
            LayerType.BLUR -> when {
                !p.sigma.isFinite() || p.sigma !in 0.01f..1024f -> "Sigma must be finite and in 0.01–1024"
                !p.feather.isFinite() || p.feather !in 0f..0.05f -> "Feather must be in 0–0.05 of the output height"
                else -> null
            }
            LayerType.IMAGE -> when {
                p.image.isNullOrBlank() -> "Choose an image; a missing image cannot be rendered"
                !p.opacity.isFinite() || p.opacity !in 0f..1f -> "Opacity must be in 0–1"
                else -> null
            }
            LayerType.TEXT -> if (p.textMy.isBlank()) "Enter the layer text" else null
            LayerType.BORDER -> when {
                p.px !in 1..1080 -> "Border width must be in 1–1080 px"
                !colour(p.colour) -> "Use a #RRGGBB colour"
                else -> null
            }
            LayerType.BACKGROUND -> when {
                p.mode == BackgroundMode.IMAGE && p.image.isNullOrBlank() -> "Choose a background image"
                p.mode == BackgroundMode.COLOUR && !colour(p.colour) -> "Use a #RRGGBB colour"
                else -> null
            }
        }
    }

    private fun colour(s: String) = s.matches(Regex("#[0-9a-fA-F]{6}"))
}
