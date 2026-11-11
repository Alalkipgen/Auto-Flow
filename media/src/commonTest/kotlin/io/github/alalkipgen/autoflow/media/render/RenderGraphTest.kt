package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Ratio
import kotlin.test.Test
import kotlin.test.assertEquals

class RenderGraphTest {
    private val graph = RenderGraph(
        clips = listOf(
            Clip(ArtifactId("vd_0123456789ab"), trimInMs = 500, trimOutMs = 8_500),
            Clip(null, trimOutMs = 8_000, transition = Transition.CROSSFADE, transitionMs = 500, label = "#02 — pending"),
            Clip(ArtifactId("vd_ba9876543210"), trimOutMs = 4_000),
        ),
        audio = AudioTrack("audio/song.mp3", offsetMs = -1_000, fadeInMs = 500, fadeOutMs = 2_000),
        overlays = listOf(Overlay("မိုးရွာနေတဲ့ ည", 1_000, 4_000, "Noto Sans Myanmar", 64, anim = Anim.FADE)),
        output = RenderPresets.export(Ratio.R9_16),
    )

    @Test
    fun durationSubtractsCrossfadeOverlaps() {
        assertEquals(8_000L + 8_000 - 500 + 4_000, graph.durationMs)
    }

    @Test
    fun roundTripsThroughProjectJson() {
        val json = CoreJson.encodeToString(RenderGraph.serializer(), graph)
        assertEquals(graph, CoreJson.decodeFromString(RenderGraph.serializer(), json))
        check("\"transition\": \"CROSSFADE\"" in json && "\"ratio\": \"9:16\"" in json && "\"quality\": \"EXPORT\"" in json) { json }
    }
}
