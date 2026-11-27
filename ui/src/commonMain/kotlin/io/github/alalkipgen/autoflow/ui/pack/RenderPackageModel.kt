package io.github.alalkipgen.autoflow.ui.pack

import androidx.compose.runtime.staticCompositionLocalOf
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PackageFile
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.platform.RenderPackages
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.BackupModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable

/**
 * `render.json` of a render package (F-7, D-96): the choices of the export screen that are not saved in the project
 * (Dubbing / Subtitles burn look, size, lines, line length, text, `.wav`), the subtitle version it showed, the font
 * file it used (`fonts/…`) and the pronunciation fixes (Library) the voice takes were made with — so the project
 * renders the same elsewhere. Names are the enum names of `:media` / `:core`; unknown values fall back to defaults.
 */
@Serializable
data class RenderPlan(
    val format: String = FORMAT,
    val version: Int = 1,
    val mode: Mode,
    /** The app that wrote it (`0.4.2 (sha)`). */
    val app: String = "",
    /** Movie Recap / Music Video: `PREVIEW` or `EXPORT`. */
    val quality: String? = null,
    val look: String? = null,
    val size: String? = null,
    /** Lines on screen and line length; null = by the video's frame. */
    val lines: Int? = null,
    val length: String? = null,
    /** Subtitles: `ORIGINAL`, `BURMESE` or `BOTH`. */
    val text: String? = null,
    /** Dubbing: keep the voice track as `.wav` too. */
    val wav: Boolean = false,
    /** Dubbing / Subtitles: the subtitle version and its document, as the export showed them. */
    val docVersion: String? = null,
    val doc: SubtitleDoc? = null,
    /** `fonts/<file>` inside the project, or null (no Burmese font). */
    val font: String? = null,
    val lexicon: List<LexiconEntry> = emptyList(),
) {
    companion object {
        const val FORMAT = "autoflow-render"
        /** In the project folder of the package. */
        const val FILE = "render.json"

        /**
         * Adds the package's pronunciation fixes that the Library does not have yet (a `written` it already has keeps its
         * own `spoken`), so the takes made with them are not shown as changed. Answers how many were added.
         */
        fun mergeLexicon(library: LibraryStore, entries: List<LexiconEntry>): Int {
            val lib = library.load()
            val known = lib.lexicon.map { it.written }.toSet()
            val added = entries.filter { it.written.isNotEmpty() && it.written !in known }.distinctBy { it.written }
            if (added.isNotEmpty()) library.save(lib.copy(lexicon = lib.lexicon + added))
            return added.size
        }

        fun read(fs: FileSystem, projectDir: String): RenderPlan? = runCatching {
            val path = joinPath(projectDir, FILE)
            if (!fs.exists(path)) null
            else CoreJson.decodeFromString(serializer(), fs.read(path).decodeToString()).takeIf { it.format == FORMAT }
        }.getOrNull()
    }
}

/** The Render package card's state for the project at [dir]. [progress] 0–1 while writing. */
data class RenderPackageUi(val dir: String? = null, val busy: Boolean = false, val progress: Float = 0f, val zip: String? = null,
    val notice: UiNotice? = null, val ok: Boolean = false)

/**
 * Makes the open project's render package (F-7, D-96): [RenderPlan] + the font as extras, the zip written to the
 * project's `exports/` (never inside a package), then on a phone a copy in `Download/AutoFlow/` (survives uninstall,
 * shows in Files, can be shared); the desktop shows the exports folder.
 */
class RenderPackageModel(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val packages: RenderPackages?,
    private val frames: RecapFrames?,
    private val lexicon: () -> Lexicon,
    private val share: FileShare?,
    private val scope: CoroutineScope,
    private val app: String = "",
) {
    private val _state = MutableStateFlow(RenderPackageUi())
    val state: StateFlow<RenderPackageUi> = _state.asStateFlow()
    private var job: Job? = null

    val available: Boolean get() = packages != null
    val projects: StateFlow<OpenProject?> get() = session.state
    val sharesFiles: Boolean get() = share?.sharesFiles == true

    /** Writes the package of the open project with [plan]'s choices; one at a time. */
    fun pack(plan: RenderPlan) {
        val p = packages ?: return
        val open = session.open ?: return
        if (job?.isActive == true) return
        val font = frames?.font(open.dir)
        val fontPath = font?.let { "${RecapFrames.PACKAGED_FONTS_DIR}/${it.fileName}" }
        val full = plan.copy(format = RenderPlan.FORMAT, app = app, font = fontPath, lexicon = lexicon().entries)
        val extras = listOfNotNull(
            PackageFile(RenderPlan.FILE, CoreJson.encodeToString(RenderPlan.serializer(), full).encodeToByteArray()),
            font?.let { PackageFile(fontPath!!, it.bytes) },
        )
        val folder = open.dir.trimEnd('/').substringAfterLast('/').let { if (it.endsWith(SUFFIX)) it else "$it$SUFFIX" }
        val name = "${safeName(open.project.name)}-render-package.zip"
        val zip = joinPath(joinPath(open.dir, EXPORTS_DIR), name)
        _state.value = RenderPackageUi(open.dir, busy = true)
        job = scope.launch {
            val r = p.pack(open.dir, folder, extras, zip) { pr -> _state.update { if (it.dir == open.dir) it.copy(progress = pr.fraction) else it } }
            _state.value = when (r) {
                is PlatformResult.Ok -> {
                    // A phone keeps a copy where uninstalling does not delete it; the desktop file stays in exports.
                    val copy = if (sharesFiles) share?.saveToDownloads(r.value.zip, ZIP_MIME, name) else null
                    val location = (copy as? PlatformResult.Ok)?.value ?: r.value.zip
                    RenderPackageUi(open.dir, zip = r.value.zip, ok = true, notice = UiNotice(UiText.PACKAGE_SAVED, "location" to location,
                        "size" to BackupModel.fileSize(r.value.zipBytes), "n" to r.value.files))
                }
                is PlatformResult.Failed -> RenderPackageUi(open.dir, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
                is PlatformResult.Unsupported -> RenderPackageUi(open.dir, notice = UiNotice(UiText.RESULT_DETAIL, "detail" to r.reason))
            }
        }
    }

    fun share(title: String) = done()?.let { result(share?.share(it, ZIP_MIME, title)) }
    fun openFolder() = done()?.let { result(share?.openFolder(it.substringBeforeLast('/'))) }

    private fun done(): String? = _state.value.takeIf { it.dir == session.open?.dir && !it.busy }?.zip

    private fun result(r: PlatformResult<Unit>?) {
        val why = when (r) {
            is PlatformResult.Failed -> r.reason
            is PlatformResult.Unsupported -> r.reason
            else -> return
        }
        _state.update { it.copy(notice = UiNotice(UiText.RESULT_DETAIL, "detail" to why), ok = false) }
    }

    companion object {
        const val ZIP_MIME = "application/zip"
        private const val SUFFIX = ".autoflow"
        private const val EXPORTS_DIR = "exports"

        /** A file name part from a project name: letters, digits, `-` and `_` (Burmese letters kept). */
        fun safeName(name: String): String = name.map { if (it.isLetterOrDigit() || it == '-' || it == '_' || it.code in 0x1000..0x109F) it else '-' }
            .joinToString("").trim('-').ifEmpty { "project" }
    }
}

/** The app's render package model; null where packages are not available (tests, or no binding). */
val LocalRenderPackages = staticCompositionLocalOf<RenderPackageModel?> { null }
