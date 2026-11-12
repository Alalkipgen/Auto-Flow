package io.github.alalkipgen.autoflow.media.render

import io.github.alalkipgen.autoflow.core.lyrics.LyricLine
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.Project

/**
 * Project → [RenderGraph] / [RenderJob] for the Export screen (PLAN Q-09 b): one clip per shot in number order (the
 * accepted video, cut or held to the shot length; else a "#NN — pending" card), the song from 0:00 replacing the
 * clips' sound, each shot's `lyricsMy` at the bottom for that shot's time, and the ratio preset. Shots are joined
 * with cuts only, so the lyrics stay on their shots. No font = no lyric overlays (libass must not fall back to a
 * system font for Burmese, specs/render-graph.md). With synced line timings (PLAN Q-11, D-30) each line shows at its
 * own time instead.
 */
object ProjectRender {
    /**
     * [videoFile] gives the file of an accepted video (absolute, or relative to the project folder) or null when it
     * is missing on disk; [songFile] likewise for the song; [font] is the font family for lyrics and cards.
     * [timings] (song time) replace the per-shot lyrics: one overlay per line, cut at the video's end.
     */
    fun graph(
        project: Project,
        quality: Quality,
        songFile: String?,
        videoFile: (ArtifactId) -> String?,
        font: String?,
        timings: List<LyricLine>? = null,
    ): RenderGraph {
        val output = RenderPresets.of(project.settings.ratio, quality)
        val shots = project.shots.filter { it.durationMs > 0 }.sortedBy { it.number }
        val clips = shots.map { shot ->
            val video = shot.acceptedVideo?.takeIf { videoFile(it) != null }
            Clip(video, trimOutMs = shot.durationMs, label = if (video == null) pendingLabel(shot.number) else "")
        }
        val size = lyricSizePx(output)
        val perLine = lineChars(output)
        val total = shots.sumOf { it.durationMs }
        val overlays = when {
            font == null -> emptyList()
            !timings.isNullOrEmpty() -> timings.mapNotNull { line ->
                val text = wrap(line.text, perLine)
                val end = minOf(line.endMs, total)
                if (text.isEmpty() || line.startMs < 0 || end <= line.startMs) null else Overlay(text, line.startMs, end, font, size, anim = Anim.FADE)
            }
            else -> {
                var start = 0L
                shots.mapNotNull { shot ->
                    val from = start
                    start += shot.durationMs
                    val text = wrap(shot.lyricsMy, perLine)
                    if (text.isEmpty()) null else Overlay(text, from, from + shot.durationMs, font, size, anim = Anim.FADE)
                }
            }
        }
        return RenderGraph(clips = clips, audio = songFile?.let { AudioTrack(it) }, overlays = overlays, output = output)
    }

    /** The queued render: [graph] plus the accepted-video files it uses (keyed by artifact id). */
    fun job(
        project: Project,
        projectDir: String,
        quality: Quality,
        songFile: String?,
        videoFile: (ArtifactId) -> String?,
        font: String?,
        fontsDir: String,
        outputName: String,
        title: String,
        timings: List<LyricLine>? = null,
    ): RenderJob {
        val found = mutableMapOf<ArtifactId, String>()
        val graph = graph(project, quality, songFile, { id -> videoFile(id)?.also { found[id] = it } }, font, timings)
        val files = graph.clips.mapNotNull { it.artifact }.distinct().mapNotNull { id -> found[id]?.let { id.value to it } }.toMap()
        return RenderJob(
            title = title,
            graph = graph,
            workDir = projectDir,
            files = files,
            songPath = songFile,
            fontsDir = fontsDir,
            outputName = outputName,
            placeholderFont = font,
        )
    }

    fun pendingLabel(number: Int) = "#${number.toString().padStart(2, '0')} — pending"

    /** Lyric em size: 1/15 of the short side (72 px at 1080, 36 px at 540). */
    fun lyricSizePx(output: Output): Int = minOf(output.width, output.height) / 15

    /** Rough characters per lyric line before breaking at a space: narrow (9:16, 4:5) 22, square 28, wide 40. */
    fun lineChars(output: Output): Int = when {
        output.width * 10 < output.height * 9 -> 22
        output.width * 10 <= output.height * 11 -> 28
        else -> 40
    }

    /**
     * Keeps the lyric's own line breaks, trims blank lines, and breaks a line longer than [maxChars] at spaces
     * (greedy). A line without spaces stays whole: Burmese often has none, and breaking inside a syllable cluster
     * would corrupt it.
     */
    fun wrap(text: String, maxChars: Int): String = text.lines().map { it.trim() }.filter { it.isNotEmpty() }
        .flatMap { line ->
            if (line.length <= maxChars) return@flatMap listOf(line)
            val out = mutableListOf<String>()
            var cur = ""
            for (word in line.split(Regex("\\s+"))) {
                cur = when {
                    cur.isEmpty() -> word
                    cur.length + 1 + word.length <= maxChars -> "$cur $word"
                    else -> { out += cur; word }
                }
            }
            if (cur.isNotEmpty()) out += cur
            out
        }.joinToString("\n")
}
