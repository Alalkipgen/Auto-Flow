package io.github.alalkipgen.autoflow.desktop

import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.platform.AppDirs
import io.github.alalkipgen.autoflow.platform.JvmRenderPackages
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.pack.PackageRenderResult
import io.github.alalkipgen.autoflow.ui.pack.PackageRenderer
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.runBlocking
import org.koin.dsl.koinApplication

/**
 * F-7 (D-96): `AutoFlow --render <package.zip | project folder> [--out <folder>] [--quality preview|export] [--home <folder>]`
 * renders a render package (Export ▸ Render package, on the phone) on a computer without opening the window — the
 * same export the screens run, with ffmpeg from `PATH`. A zip is unpacked into the output folder first; the finished
 * video is copied there and printed as `OUTPUT <path>` (also written to `autoflow-render.log`, for Windows where the
 * app has no console). The app's own settings and Library are not touched: it runs with its own home
 * (`<out>/.autoflow-render-home`) unless `--home` is given.
 *
 * Exit codes: 0 done, 1 the render failed, 2 bad arguments or a missing input.
 */
object HeadlessRender {
    const val FLAG = "--render"
    const val LOG = "autoflow-render.log"
    const val HOME = ".autoflow-render-home"
    const val OK = 0
    const val FAILED = 1
    const val BAD_ARGS = 2

    val USAGE = """
        Usage: AutoFlow --render <package.zip | project folder> [--out <folder>] [--quality preview|export] [--home <folder>]

          Renders an Auto Flow render package (Export > Render package) without opening the window.
          A .zip is unpacked into the output folder; the finished video is copied there.
          --out      where the project and the video go (default: the folder of the input)
          --quality  Movie Recap / Music Video only: preview (540p) or export (1080p, default)
          --home     the app folder to use (default: <out>/$HOME, so your own settings are not touched)
        Needs ffmpeg on PATH (Linux: sudo apt install ffmpeg).
    """.trimIndent()

    data class Args(val input: File, val out: File?, val quality: Quality?, val home: File?)

    sealed interface Parsed {
        data object Help : Parsed
        data class Bad(val reason: String) : Parsed
        data class Ok(val args: Args) : Parsed
    }

    /** True when [args] ask for the command line, not the window. */
    fun wanted(args: Array<String>): Boolean = args.firstOrNull()?.let { it == FLAG || it == "--help" || it == "-h" } == true

    fun parse(args: List<String>): Parsed {
        if (args.any { it == "--help" || it == "-h" }) return Parsed.Help
        val rest = if (args.firstOrNull() == FLAG) args.drop(1) else args
        var input: String? = null
        var out: String? = null
        var quality: Quality? = null
        var home: String? = null
        var i = 0
        while (i < rest.size) {
            val a = rest[i]
            fun value(): String? = rest.getOrNull(i + 1)?.takeIf { !it.startsWith("--") }.also { i++ }
            when (a) {
                "--out" -> out = value() ?: return Parsed.Bad("--out needs a folder.")
                "--home" -> home = value() ?: return Parsed.Bad("--home needs a folder.")
                "--quality" -> quality = when (value()?.lowercase()) {
                    "preview" -> Quality.PREVIEW
                    "export" -> Quality.EXPORT
                    else -> return Parsed.Bad("--quality is preview or export.")
                }
                else -> if (a.startsWith("--") || input != null) return Parsed.Bad("Unknown argument: $a") else input = a
            }
            i++
        }
        val file = File(input ?: return Parsed.Bad("Give the package to render.")).absoluteFile
        return Parsed.Ok(Args(file, out?.let { File(it).absoluteFile }, quality, home?.let { File(it).absoluteFile }))
    }

    /** Runs the command line; answers the exit code. */
    fun run(args: List<String>, out: PrintStream = System.out, err: PrintStream = System.err): Int {
        val a = when (val p = parse(args)) {
            Parsed.Help -> { out.println(USAGE); return OK }
            is Parsed.Bad -> { err.println(p.reason); err.println(); err.println(USAGE); return BAD_ARGS }
            is Parsed.Ok -> p.args
        }
        if (!a.input.exists()) { err.println("Not found: ${a.input.path}"); return BAD_ARGS }
        val zip = a.input.isFile
        if (!zip && !File(a.input, "project.json").isFile) { err.println("Not a project folder (no project.json): ${a.input.path}"); return BAD_ARGS }
        val outDir = a.out ?: a.input.parentFile ?: File(".").absoluteFile
        if (!outDir.isDirectory && !outDir.mkdirs()) { err.println("Cannot make the folder ${outDir.path}"); return BAD_ARGS }
        val log = Log(File(outDir, LOG), out)
        val home = a.home ?: File(outDir, HOME)
        val previousHome = System.getProperty(AppDirs.HOME_OVERRIDE_PROPERTY)
        System.setProperty(AppDirs.HOME_OVERRIDE_PROPERTY, home.path)
        val app = koinApplication { modules(desktopModule(), uiModule, aiModule) }
        val koin = app.koin
        try {
            val dir = if (!zip) a.input else {
                log.line("UNPACK ${a.input.path} -> ${outDir.path}")
                when (val r = runBlocking { JvmRenderPackages().unpack(a.input.path, outDir.path) }) {
                    is PlatformResult.Ok -> r.value.firstOrNull()?.let { File(outDir, it.folder) }
                        ?: return log.fail("The package has no project.", BAD_ARGS, err)
                    is PlatformResult.Failed -> return log.fail(r.reason, BAD_ARGS, err)
                    is PlatformResult.Unsupported -> return log.fail(r.reason, BAD_ARGS, err)
                }
            }
            log.line("PROJECT ${dir.path}")
            val renderer = PackageRenderer(koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.get(), koin.getOrNull())
            var shown = -1
            val result = runBlocking {
                renderer.render(dir.path.replace('\\', '/'), a.quality) { f ->
                    val pct = (f * 100).toInt().coerceIn(0, 100)
                    if (pct >= shown + 5) { shown = pct; log.line("PROGRESS $pct%") }
                }
            }
            return when (result) {
                is PackageRenderResult.Failed -> log.fail(result.reason, FAILED, err)
                is PackageRenderResult.Done -> {
                    val made = File(result.output)
                    val copy = File(outDir, made.name)
                    if (made.canonicalPath != copy.canonicalPath) Files.copy(made.toPath(), copy.toPath(), StandardCopyOption.REPLACE_EXISTING)
                    log.line("OUTPUT ${copy.path}")
                    OK
                }
            }
        } catch (e: Exception) {
            return log.fail("${e::class.simpleName}: ${e.message}", FAILED, err)
        } finally {
            app.close()
            if (previousHome == null) System.clearProperty(AppDirs.HOME_OVERRIDE_PROPERTY) else System.setProperty(AppDirs.HOME_OVERRIDE_PROPERTY, previousHome)
        }
    }

    /** Lines to stdout and to `autoflow-render.log` in the output folder. */
    private class Log(private val file: File, private val out: PrintStream) {
        fun line(text: String) {
            out.println(text)
            runCatching { file.appendText(text + "\n") }
        }
        fun fail(reason: String, code: Int, err: PrintStream): Int {
            err.println("FAILED $reason")
            runCatching { file.appendText("FAILED $reason\n") }
            return code
        }
    }
}
