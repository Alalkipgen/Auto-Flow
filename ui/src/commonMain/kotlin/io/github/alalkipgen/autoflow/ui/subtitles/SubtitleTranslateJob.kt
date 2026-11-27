package io.github.alalkipgen.autoflow.ui.subtitles

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.retry.isBusy
import io.github.alalkipgen.autoflow.ai.subtitles.SubtitleTranslator
import io.github.alalkipgen.autoflow.ai.subtitles.TranslateBrief
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.config.ToolsConfig
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.AiItemPaused
import io.github.alalkipgen.autoflow.core.recap.AiJob
import io.github.alalkipgen.autoflow.core.recap.AiJobKind
import io.github.alalkipgen.autoflow.core.recap.AiJobRunner
import io.github.alalkipgen.autoflow.core.recap.AiTimingStage
import io.github.alalkipgen.autoflow.core.recap.timedAiStage
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleTranslate
import io.github.alalkipgen.autoflow.core.subtitles.TranslateName
import io.github.alalkipgen.autoflow.core.subtitles.TranslateRequest
import io.github.alalkipgen.autoflow.core.subtitles.TranslatedBatch
import io.github.alalkipgen.autoflow.core.subtitles.TranslatedLine
import io.github.alalkipgen.autoflow.core.subtitles.myHash
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlinx.serialization.Serializable
import kotlin.time.Clock

/**
 * Translate to Burmese of a Subtitles project (S-6, D-81, spec §7–§8): a resumable SUB_TRANSLATE job of the project
 * folder. Each item is one batch of `translate.batchLines` lines of the version it started from, sent with the lines
 * before it as context; the answer is saved to `.cache/jobs/<job>/b<id>.json` and never sent again. A batch whose
 * answer stays unusable after one retry is saved as failed and the others go on. When every batch is saved, the
 * Burmese joins the current version as one new `st` (only lines whose original did not change since).
 */
class SubtitleTranslateJob(
    private val session: ProjectSession,
    private val fs: FileSystem,
    private val translator: SubtitleTranslator?,
    private val config: ToolsConfig = ToolsConfig(),
    private val clock: Clock = Clock.System,
) {
    /** What Translate would send from version [base]: [lines] in [batches] (one request each). */
    data class Plan(val base: ArtifactId, val lines: Int, val batches: List<List<String>>) {
        val requests: Int get() = batches.size
    }

    /** How a finished job ended: lines that got Burmese, lines whose batch failed. */
    @Serializable
    data class Result(val translated: Int, val failed: Int)

    val available: Boolean get() = translator != null

    /** The lines of the current version that Translate would send; null without a saved version. */
    fun plan(dir: String, replaceEdited: Boolean): Plan? {
        val base = session.projectAt(dir)?.subtitles?.current ?: return null
        val doc = read(dir, base) ?: return null
        val todo = SubtitleTranslate.todo(doc, replaceEdited)
        return Plan(base, todo.size, SubtitleTranslate.batches(todo, config.translate.batchLines))
    }

    suspend fun enqueue(runner: AiJobRunner, dir: String, plan: Plan, style: String, names: List<TranslateName>, replaceEdited: Boolean): String {
        val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
        val id = "st-${project.id}-${clock.now().toEpochMilliseconds()}-${kotlin.random.Random.nextInt(0x1000000).toString(16)}"
        val request = TranslateRequest(plan.base.value, style, names, replaceEdited, config.translate.contextLines, plan.batches)
        fs.createDirectories(VideoTranscriber.jobDir(dir, id))
        fs.writeDurably(joinPath(VideoTranscriber.jobDir(dir, id), VideoTranscriber.REQUEST),
            CoreJson.encodeToString(TranslateRequest.serializer(), request).encodeToByteArray())
        val items = plan.batches.map(SubtitleTranslate::item)
        val key = Sha256.hex((listOf(project.id, plan.base.value, style, replaceEdited.toString()) + names.map { "${it.from}=${it.my}" } + items)
            .joinToString("|").encodeToByteArray())
        return runner.enqueue(AiJob(id, AiJobKind.SUB_TRANSLATE, dir, items, rpm = RPM, inputKey = key))
    }

    fun isItemDone(dir: String, jobId: String, item: String): Boolean = fs.exists(joinPath(VideoTranscriber.jobDir(dir, jobId), "$item.json"))

    /** One batch of job [jobId]: [AiItemPaused] when Gemini is busy, an exception when the request failed (Resume retries it). */
    suspend fun runItem(dir: String, jobId: String, item: String) {
        val folder = VideoTranscriber.jobDir(dir, jobId)
        val request = request(dir, jobId) ?: error("This Translate job's request is missing in $dir")
        if (!isItemDone(dir, jobId, item)) {
            val t = translator ?: error(NO_GEMINI)
            val ids = request.batches.firstOrNull { SubtitleTranslate.item(it) == item } ?: error("This Translate job has an unknown item: $item")
            val base = read(dir, ArtifactId(request.base)) ?: error("The subtitles this job started from cannot be read")
            val byId = base.cues.associateBy { it.id }
            val lines = ids.mapNotNull { byId[it] }.filter { it.text.isNotBlank() }
            val context = SubtitleTranslate.context(base, ids.first(), request.contextLines).map { it.text to it.textMy }
            val brief = TranslateBrief(base.lang, request.style, request.names.map { it.from to it.my }, context, lines.map { it.id to it.text })
            val batch = if (lines.isEmpty()) TranslatedBatch() else when (val r = t.translate(brief)) {
                is AiResult.Ok -> TranslatedBatch(r.value.map { (id, my) -> TranslatedLine(id, my, myHash(byId.getValue(id).text)) })
                is AiResult.RateLimited -> throw AiItemPaused("Gemini is busy (${r.message.ifBlank { "rate limited" }}). Resume to go on.")
                is AiResult.Failed -> when {
                    r.isBusy() -> throw AiItemPaused("Gemini is busy (${r.msg}). Resume to go on.")
                    r.code == SubtitleTranslator.INVALID_OUTPUT -> TranslatedBatch(failed = r.msg)
                    else -> error("Gemini: ${r.msg}")
                }
                is AiResult.Deferred -> error(r.instruction)
            }
            timedAiStage(AiTimingStage.SAVE) {
                val path = joinPath(folder, "$item.json")
                fs.writeDurably("$path.tmp", CoreJson.encodeToString(TranslatedBatch.serializer(), batch).encodeToByteArray())
                fs.atomicMove("$path.tmp", path)
            }
        }
        finish(dir, jobId, request)
    }

    /** The end of finished job [jobId], null while it runs. */
    fun result(dir: String, jobId: String): Result? = runCatching {
        CoreJson.decodeFromString(Result.serializer(), fs.read(joinPath(VideoTranscriber.jobDir(dir, jobId), VideoTranscriber.RESULT)).decodeToString())
    }.getOrNull()

    private fun finish(dir: String, jobId: String, request: TranslateRequest) {
        val folder = VideoTranscriber.jobDir(dir, jobId)
        if (fs.exists(joinPath(folder, VideoTranscriber.RESULT))) return
        val saved = request.batches.map { ids ->
            val text = runCatching { fs.read(joinPath(folder, "${SubtitleTranslate.item(ids)}.json")).decodeToString() }.getOrNull() ?: return
            ids to CoreJson.decodeFromString(TranslatedBatch.serializer(), text)
        }
        val lines = saved.flatMap { it.second.lines }
        val failed = saved.filter { it.second.failed != null }.sumOf { it.first.size }
        var translated = 0
        if (lines.isNotEmpty()) {
            val project = session.projectAt(dir) ?: error("The project in $dir cannot be read")
            val store = ArtifactStore(fs, dir, clock)
            var problem: String? = null
            val updated = session.update(dir, project.id) { p ->
                val tool = p.subtitles ?: SubtitlesTool()
                val current = tool.current?.let { read(dir, it) } ?: run { problem = "The current subtitles cannot be read"; return@update p }
                val (doc, n) = SubtitleTranslate.apply(current, lines)
                translated = n
                if (n == 0) return@update p
                val next = doc.copy(source = SubtitleDoc.SOURCE_EDIT, derivedFrom = tool.current)
                when (val put = store.put(CoreJson.encodeToString(SubtitleDoc.serializer(), next).encodeToByteArray(), ArtifactKind.SUBTITLES, "json")) {
                    is CoreResult.Ok -> p.copy(subtitles = tool.copy(current = put.value.id))
                    is CoreResult.Err -> { problem = put.error.message; p }
                }
            }
            problem?.let { error(it) }
            (updated as? CoreResult.Err)?.let { error(it.error.message) }
        }
        fs.writeDurably(joinPath(folder, VideoTranscriber.RESULT), CoreJson.encodeToString(Result.serializer(), Result(translated, failed)).encodeToByteArray())
    }

    private fun request(dir: String, jobId: String): TranslateRequest? = runCatching {
        CoreJson.decodeFromString(TranslateRequest.serializer(), fs.read(joinPath(VideoTranscriber.jobDir(dir, jobId), VideoTranscriber.REQUEST)).decodeToString())
    }.getOrNull()

    private fun read(dir: String, id: ArtifactId): SubtitleDoc? = (ArtifactStore(fs, dir, clock).readBytes(id) as? CoreResult.Ok)?.value?.let {
        runCatching { CoreJson.decodeFromString(SubtitleDoc.serializer(), it.decodeToString()) }.getOrNull()
    }

    companion object {
        const val RPM = 10
        const val NO_GEMINI = "Gemini is not set up, so the lines cannot be translated. Add a key in Settings."
    }
}
