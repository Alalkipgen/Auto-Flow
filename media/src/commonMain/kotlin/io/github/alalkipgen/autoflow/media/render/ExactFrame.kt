package io.github.alalkipgen.autoflow.media.render

/**
 * "Exact frame" (D-42, studio-v2.md §4): one still of the output at [atMs] made by the export compiler itself —
 * the same [RenderCompiler] graph (blur boxes, background, looks, layers, burned-in captions through libass with the
 * same font), then `-ss <t> … -frames:v 1` into a PNG / JPEG instead of the mp4. Used by the live preview, the export
 * pre-flight thumbnails (5B-9) and the Publish Kit thumbnail (5B-10).
 *
 * To stay quick on a phone the graph is cut down to the one clip under [atMs] ([window]): only that movie range is
 * opened and decoded, overlays and layers are moved into the clip's own time, and the sound is dropped. Crossfades
 * mix two clips, so a graph with any crossfade is kept whole (seeking in the full output).
 */
object ExactFrame {
    /** The graph to compile and where in its output the frame is. */
    data class Window(val graph: RenderGraph, val seekMs: Long, val clipIndex: Int?)

    fun window(graph: RenderGraph, atMs: Long): Window {
        val total = graph.durationMs
        val t = atMs.coerceIn(0, maxOf(0, total - 1))
        val silent = VoiceTrack(emptyList(), clipsVolume = 0.0)
        if (graph.clips.isEmpty() || graph.clips.any { it.crossfadeMs > 0 }) {
            return Window(graph.copy(audio = null, voice = silent), t, null)
        }
        var start = 0L
        graph.clips.forEachIndexed { k, clip ->
            val end = start + clip.durationMs
            if (t < end || k == graph.clips.lastIndex) {
                val shift = start
                val length = clip.durationMs
                val overlays = graph.overlays.filter { it.endMs > shift && it.startMs < shift + length }
                    .map { it.copy(startMs = maxOf(0, it.startMs - shift), endMs = it.endMs - shift) }
                val layers = graph.layers.filter { l -> (l.toMs ?: Long.MAX_VALUE) > shift && l.fromMs < shift + length }
                    .map { l -> l.copy(fromMs = maxOf(0, l.fromMs - shift), toMs = l.toMs?.let { it - shift }) }
                val one = graph.copy(clips = listOf(clip), overlays = overlays, layers = layers, audio = null, voice = silent)
                return Window(one, t - shift, k)
            }
            start = end
        }
        error("unreachable")
    }

    /**
     * Compiled export args → still-image args: the same inputs and filtergraph, only the video out, `-ss` [seekMs]
     * (output side: frame-exact after the filters) and one frame into [image] (`.png`, or `.jpg` at high quality).
     */
    fun stillArgs(compiledArgs: List<String>, seekMs: Long, image: String): List<String> {
        val fc = compiledArgs.indexOf("-filter_complex")
        require(fc > 0 && fc + 1 < compiledArgs.size) { "not a compiled render" }
        // The sound chains still end in [aout]; a sink keeps the graph valid without an audio output.
        val graph = compiledArgs[fc + 1].let { if ("[aout]" in it) "$it;[aout]anullsink" else it }
        val jpeg = image.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") }
        return compiledArgs.subList(0, fc) + listOf("-filter_complex", graph, "-map", "[vout]", "-an",
            "-ss", RenderCompiler.sec(seekMs), "-frames:v", "1", "-update", "1") +
            (if (jpeg) listOf("-q:v", "2") else emptyList()) + image
    }

    /** `.cache/frames/<t>.png` (spec §4): one file per output time in ms, so each time is rendered once per change. */
    fun cacheName(atMs: Long, ext: String = "png"): String = "$atMs.$ext"
}
