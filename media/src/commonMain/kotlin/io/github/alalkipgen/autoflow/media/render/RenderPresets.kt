package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.model.Ratio

/**
 * Preview (540p proxy) and Export (1080p) outputs per ratio — one table for desktop and Android (PLAN 1D, 1F).
 * The short side is 540 / 1080 px; the long side follows the ratio, rounded down to an even number for yuv420p.
 */
object RenderPresets {
    const val FPS = 24
    const val PREVIEW_SHORT_SIDE = 540
    const val EXPORT_SHORT_SIDE = 1080

    fun preview(ratio: Ratio): Output = output(ratio, PREVIEW_SHORT_SIDE, Quality.PREVIEW)

    fun export(ratio: Ratio): Output = output(ratio, EXPORT_SHORT_SIDE, Quality.EXPORT)

    fun of(ratio: Ratio, quality: Quality): Output = if (quality == Quality.PREVIEW) preview(ratio) else export(ratio)

    private fun output(ratio: Ratio, short: Int, quality: Quality): Output {
        val landscape = ratio.width >= ratio.height
        val long = even(short.toLong() * maxOf(ratio.width, ratio.height) / minOf(ratio.width, ratio.height))
        val (w, h) = if (landscape) long to short else short to long
        return Output(ratio, w, h, FPS, quality, presetName(ratio, short))
    }

    /** `yt1080`, `tiktok540`, `ig1x1-1080`… a label for file names and the export list. */
    private fun presetName(ratio: Ratio, short: Int): String = when (ratio) {
        Ratio.R16_9 -> "yt$short"
        Ratio.R9_16 -> "tiktok$short"
        Ratio.R1_1 -> "ig1x1-$short"
        else -> "${ratio.width}x${ratio.height}-$short"
    }

    private fun even(v: Long): Int = (v - v % 2).toInt()
}
