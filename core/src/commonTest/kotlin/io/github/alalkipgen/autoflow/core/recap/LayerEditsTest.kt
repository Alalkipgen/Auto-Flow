package io.github.alalkipgen.autoflow.core.recap

import kotlin.test.*

class LayerEditsTest {
    private val blur = Layer("stable", LayerType.BLUR, listOf(0.1f, 0.2f, 0.3f, 0.4f))

    @Test fun finiteOutputFrameGeometryOnly() {
        assertNull(LayerEdits.problem(blur))
        for (r in listOf(listOf(Float.NaN, 0f, 1f, 1f), listOf(0f, Float.POSITIVE_INFINITY, 1f, 1f),
            listOf(0f, 0f, 0f, 1f), listOf(-0.1f, 0f, 1f, 1f), listOf(0.5f, 0f, 0.6f, 1f), listOf(0f))) {
            assertNotNull(LayerEdits.problem(blur.copy(rect = r)), "$r")
        }
        // These types ignore their rect; an edit must preserve, not reinterpret it as a movie mask.
        for (type in listOf(LayerType.BORDER, LayerType.BACKGROUND)) {
            assertFalse(LayerEdits.usesRect(type))
            assertNull(LayerEdits.problem(blur.copy(type = type, rect = emptyList())))
        }
    }

    @Test fun validTimesAndTypeSpecificParameters() {
        assertNull(LayerEdits.problem(blur.copy(fromMs = 4_000, toMs = null)))
        for (l in listOf(blur.copy(fromMs = -1), blur.copy(fromMs = 4, toMs = 4), blur.copy(fromMs = 5, toMs = 4),
            blur.copy(params = LayerParams(sigma = Float.NaN)), blur.copy(params = LayerParams(feather = 0.06f)),
            blur.copy(type = LayerType.IMAGE), blur.copy(type = LayerType.IMAGE, params = LayerParams(image = "im_x", opacity = Float.POSITIVE_INFINITY)),
            blur.copy(type = LayerType.TEXT), blur.copy(type = LayerType.BORDER, params = LayerParams(px = 0)),
            blur.copy(type = LayerType.BORDER, params = LayerParams(colour = "red")),
            blur.copy(type = LayerType.BACKGROUND, params = LayerParams(mode = BackgroundMode.IMAGE)))) {
            assertNotNull(LayerEdits.problem(l), "$l")
        }
    }
}
