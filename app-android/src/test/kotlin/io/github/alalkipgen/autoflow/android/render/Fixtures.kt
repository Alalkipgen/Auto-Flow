package io.github.alalkipgen.autoflow.android.render

import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.media.render.Clip
import io.github.alalkipgen.autoflow.media.render.RenderGraph
import io.github.alalkipgen.autoflow.media.render.RenderJob
import io.github.alalkipgen.autoflow.media.render.RenderPresets
import java.io.File
import java.nio.file.Files
import java.util.UUID

fun tempDir(): File = Files.createTempDirectory("af-render").toFile()

fun newId(): String = UUID.randomUUID().toString()

fun testJob(dir: File, title: String = "Test") = RenderJob(
    title = title,
    graph = RenderGraph(clips = listOf(Clip(null, trimOutMs = 1_000, label = "#01")), output = RenderPresets.preview(Ratio.R9_16)),
    workDir = dir.path,
    files = emptyMap(),
    fontsDir = "fonts",
    outputName = "exports/test",
)
