package io.github.alalkipgen.autoflow.media

/** Text overlay backend for exports (specs/render-graph.md): libass `ass=` when available, else Skia PNG + `overlay`. */
enum class OverlayBackend { LIBASS, PNG_OVERLAY }

/**
 * What an ffmpeg binary can do, read from `ffmpeg -version`, `-filters` and `-encoders` (PLAN 1D version check;
 * the Android Self-test reports it too). Parsing never throws: unknown output gives empty sets.
 */
data class FfmpegCapabilities(
    val version: String?,
    val configuration: String?,
    val filters: Set<String>,
    val encoders: Set<String>,
) {
    /** The `ass` filter, or a build configured `--enable-libass` (PLAN 1D: either one picks libass). */
    val hasAss: Boolean get() = "ass" in filters || configuration?.contains("--enable-libass") == true
    val hasXfade: Boolean get() = "xfade" in filters
    val hasLibx264: Boolean get() = "libx264" in encoders
    val hasAac: Boolean get() = "aac" in encoders
    val overlayBackend: OverlayBackend get() = if (hasAss) OverlayBackend.LIBASS else OverlayBackend.PNG_OVERLAY

    companion object {
        private val VersionLine = Regex("""^ffmpeg version (\S+)""", RegexOption.MULTILINE)
        private val ConfigLine = Regex("""^configuration: (.*)$""", RegexOption.MULTILINE)

        // " T.. ass               V->V       Render ASS subtitles…" (flags vary by version; the I/O column has "->").
        private val FilterLine = Regex("""^\s*[A-Z.|]{2,4}\s+(\S+)\s+\S*->\S*\s""")

        // " V....D libx264              libx264 H.264 / AVC …" (legend lines look like " V..... = Video").
        private val EncoderLine = Regex("""^\s*[VAS][A-Z.]{5}\s+(\S+)\s""")

        fun parse(versionOut: String, filtersOut: String, encodersOut: String) = FfmpegCapabilities(
            version = VersionLine.find(versionOut)?.groupValues?.get(1),
            configuration = ConfigLine.find(versionOut)?.groupValues?.get(1)?.trim(),
            filters = names(filtersOut, FilterLine),
            encoders = names(encodersOut, EncoderLine),
        )

        private fun names(out: String, line: Regex): Set<String> =
            out.lineSequence().mapNotNull { line.find(it)?.groupValues?.get(1) }.filter { it != "=" }.toSet()
    }
}

/** `ffmpeg -benchmark` stderr: `bench: maxrss=123456KiB` (older builds print `kB`) — peak RSS of the ffmpeg process. */
object FfmpegBenchmark {
    private val MaxRss = Regex("""maxrss=(\d+)\s*(KiB|kB)""")

    fun maxRssKb(stderr: String): Long? = MaxRss.findAll(stderr).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
}
