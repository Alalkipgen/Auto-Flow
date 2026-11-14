package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.recap.BackgroundMode
import io.github.alalkipgen.autoflow.core.recap.Layer
import io.github.alalkipgen.autoflow.core.recap.LayerType

/**
 * Compiles a list of [Layer]s into extra ffmpeg filter chains and ASS events that sit on top of the base video
 * strip (spec §4, D-37).  Called by [RenderCompiler.compile] after the base strip is assembled.
 *
 * The compiled output is designed to be appended to [RenderCompiler]'s `chains` list.  The final output label is
 * always `[vlayer]`, which the caller replaces `[vout]` with when any layers are present.
 *
 * Layer order = drawing order (spec: "List order = drawing order; captions are drawn last").
 * TEXT layers contribute ASS events (returned separately); they are merged with the existing ASS file.
 */
object LayerCompiler {

    data class LayerResult(
        /** Additional `-filter_complex` chains to append. The last chain maps `[vlayer]`. */
        val chains: List<String>,
        /** Additional input file paths that must be prepended to [RenderCompiler]'s input list (image files). */
        val inputPaths: List<String>,
        /** ASS events for TEXT layers; merged into the existing ASS file. */
        val assEvents: List<Overlay>,
    )

    /**
     * @param layers  Layers in drawing order.
     * @param output  The output dimensions / fps.
     * @param totalMs Total render duration.
     * @param resolveImage Maps a Layer.params.image id to an absolute file path (or null if missing).
     * @param baseInputCount Number of input streams already added by [RenderCompiler] (images get index baseInputCount + k).
     */
    fun compile(
        layers: List<Layer>,
        output: Output,
        totalMs: Long,
        resolveImage: (String) -> String?,
        baseInputCount: Int,
    ): LayerResult {
        if (layers.isEmpty()) return LayerResult(emptyList(), emptyList(), emptyList<Overlay>())

        val chains = mutableListOf<String>()
        val inputPaths = mutableListOf<String>()
        val assEvents = mutableListOf<Overlay>()
        var inputIdx = baseInputCount
        var prev = "vseq"   // label coming out of the base strip

        for ((k, layer) in layers.withIndex()) {
            val next = if (k == layers.lastIndex) "vlayer" else "vl$k"
            val from = layer.fromMs
            val to = layer.toMs ?: totalMs
            val p = layer.params
            val w = output.width
            val h = output.height

            when (layer.type) {
                LayerType.BLUR -> {
                    // rect crop → gblur → optional feather → overlay
                    val (rx, ry, rw, rh) = rectPx(layer.rect, w, h)
                    val sigma = p.sigma.coerceAtLeast(1f)
                    val enableExpr = enableExpr(from, to)
                    val featherChain = if (p.feather > 0f) {
                        val fade = (p.feather * h).toInt().coerceAtLeast(1)
                        ",alphaextract,vignette=angle=PI/2:x0=$rx:y0=$ry:x1=${rx + rw}:y1=${ry + rh}:eval=frame,alphamerge"
                    } else ""
                    chains += "[$prev]split[bg$k][fg$k];" +
                        "[fg$k]crop=$rw:$rh:$rx:$ry,gblur=sigma=$sigma$featherChain[blur$k];" +
                        "[bg$k][blur$k]overlay=$rx:$ry:enable='$enableExpr'[$next]"
                }

                LayerType.IMAGE -> {
                    val path = p.image?.let(resolveImage)
                    if (path == null) {
                        // Skip missing image: just relay the stream
                        chains += "[$prev]null[$next]"
                    } else {
                        val (rx, ry, rw, _) = rectPx(layer.rect, w, h)
                        val alpha = (p.opacity.coerceIn(0f, 1f) * 255).toInt()
                        val idx = inputIdx++
                        inputPaths += path
                        val enableExpr = enableExpr(from, to)
                        chains += "[$prev][${idx}:v]scale=$rw:-1,format=yuva420p," +
                            "colorchannelmixer=aa=${p.opacity}," +
                            "overlay=$rx:$ry:enable='$enableExpr'[$next]"
                    }
                }

                LayerType.TEXT -> {
                    // TEXT layers generate ASS events; no filtergraph change needed
                    val fontSize = (h / 18).coerceAtLeast(12)
                    assEvents += Overlay(
                        text = p.textMy,
                        startMs = from,
                        endMs = to,
                        font = p.style.ifBlank { "" },
                        sizePx = fontSize,
                        color = "#FFFFFF",
                        position = Pos.CENTER,
                    )
                    chains += "[$prev]null[$next]"
                }

                LayerType.BORDER -> {
                    // drawbox × 4: top, bottom, left, right
                    val px = p.px.coerceAtLeast(1)
                    val c = p.colour.removePrefix("#").padEnd(6, '0').let { "0x$it@1.0" }
                    val enableExpr = enableExpr(from, to)
                    val boxes = listOf(
                        "drawbox=0:0:iw:$px:c=$c:t=fill",
                        "drawbox=0:ih-$px:iw:$px:c=$c:t=fill",
                        "drawbox=0:0:$px:ih:c=$c:t=fill",
                        "drawbox=iw-$px:0:$px:ih:c=$c:t=fill",
                    ).joinToString(",") { "$it:enable='$enableExpr'" }
                    chains += "[$prev]$boxes[$next]"
                }

                LayerType.BACKGROUND -> {
                    // A fill layer behind a movie that doesn't cover the full output frame.
                    // Compose a full-frame background from blurred/image/colour, then overlay the base strip.
                    val enableExpr = enableExpr(from, to)
                    when (p.mode) {
                        BackgroundMode.BLUR -> {
                            chains += "[$prev]split[bgfg$k][bgblur$k];" +
                                "[bgblur$k]scale=${w}:${h}:force_original_aspect_ratio=increase," +
                                "crop=${w}:${h},gblur=sigma=30[bgfull$k];" +
                                "[bgfull$k][bgfg$k]overlay=0:0:enable='$enableExpr'[$next]"
                        }
                        BackgroundMode.COLOUR -> {
                            val c = p.colour.removePrefix("#").padEnd(6, '0').let { "0x$it" }
                            chains += "[$prev]split[bgov$k][bgfg$k];" +
                                "color=c=$c:s=${w}x${h}:r=${output.fps}[bgcol$k];" +
                                "[bgcol$k][bgfg$k]overlay=0:0:enable='$enableExpr'[$next]"
                        }
                        BackgroundMode.IMAGE -> {
                            val path = p.image?.let(resolveImage)
                            if (path == null) {
                                chains += "[$prev]null[$next]"
                            } else {
                                val idx = inputIdx++
                                inputPaths += path
                                chains += "[$prev][${idx}:v]scale=${w}:${h}:force_original_aspect_ratio=increase," +
                                    "crop=${w}:${h}[bgimg$k];" +
                                    "[bgimg$k][$prev]overlay=0:0:enable='$enableExpr'[$next]"
                            }
                        }
                    }
                }
            }
        }

        return LayerResult(chains, inputPaths, assEvents)
    }

    // ── helpers ────────────────────────────────────────────────────────────────

    /** Enable expression for `enable='between(t,from,to)'`. */
    private fun enableExpr(fromMs: Long, toMs: Long): String =
        "between(t,${fromMs / 1000.0},${toMs / 1000.0})"

    /** [rect] = [x,y,w,h] in 0–1; return pixel coordinates clamped to [frameW] × [frameH]. */
    private fun rectPx(rect: List<Float>, frameW: Int, frameH: Int): IntArray {
        val x = rect.getOrElse(0) { 0f }
        val y = rect.getOrElse(1) { 0f }
        val rw = rect.getOrElse(2) { 1f }
        val rh = rect.getOrElse(3) { 1f }
        val px = (x * frameW).toInt().coerceIn(0, frameW - 1)
        val py = (y * frameH).toInt().coerceIn(0, frameH - 1)
        val pw = (rw * frameW).toInt().coerceIn(1, frameW - px)
        val ph = (rh * frameH).toInt().coerceIn(1, frameH - py)
        return intArrayOf(px, py, pw, ph)
    }
}
