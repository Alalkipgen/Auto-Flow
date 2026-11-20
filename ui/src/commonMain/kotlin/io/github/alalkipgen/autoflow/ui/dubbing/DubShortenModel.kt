package io.github.alalkipgen.autoflow.ui.dubbing

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.dubbing.DubShortener
import io.github.alalkipgen.autoflow.ai.dubbing.ShortenLine
import io.github.alalkipgen.autoflow.core.dubbing.DubFitKind
import io.github.alalkipgen.autoflow.core.dubbing.DubRules
import io.github.alalkipgen.autoflow.core.subtitles.SubtitleDoc
import io.github.alalkipgen.autoflow.ui.i18n.UiNotice
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** One Shorten suggestion: line [id] (#[n]) now says [old]; Gemini offers [new] for at most [maxChars]; [use] = chosen. */
data class DubShortenRow(val id: String, val n: Int, val old: String, val new: String, val maxChars: Int, val use: Boolean = false)

data class DubShortenUi(val dir: String? = null, val rows: List<DubShortenRow> = emptyList(), val busy: Boolean = false, val notice: UiNotice? = null)

/**
 * Shorten (specs/dubbing.md §5, D-2c): one Gemini request for the lines whose take is TOO_LONG, each with
 * `maxChars = floor(chars × slot / take × 0.95)`. The answer is only shown, old next to new; the owner chooses per line
 * and **Use n lines** saves them as one new `st` version through [DubScriptModel] — a spoken-line change, so the
 * approval is cleared and only those lines need a new voice.
 */
class DubShortenModel(private val shortener: DubShortener?, private val scope: CoroutineScope) {
    private val _state = MutableStateFlow(DubShortenUi())
    val state: StateFlow<DubShortenUi> = _state.asStateFlow()
    val available: Boolean get() = shortener != null

    /** The lines Shorten would send: every TOO_LONG row with its limit. */
    fun lines(rows: List<DubVoiceRow>, doc: SubtitleDoc): List<ShortenLine> = rows.mapNotNull { r ->
        val take = r.take?.takeIf { r.fit?.kind == DubFitKind.TOO_LONG } ?: return@mapNotNull null
        val cue = doc.cues.firstOrNull { it.id == r.id } ?: return@mapNotNull null
        val spoken = DubRules.spoken(cue)
        ShortenLine(r.id, spoken, DubRules.maxChars(spoken.length, r.slotMs, take.ms))
    }

    fun ask(dir: String, rows: List<DubVoiceRow>, doc: SubtitleDoc): Job? {
        val s = shortener ?: return say(dir, UiNotice(UiText.GEMINI_UNAVAILABLE))
        val lines = lines(rows, doc).takeIf { it.isNotEmpty() } ?: return null
        if (_state.value.busy) return null
        _state.value = DubShortenUi(dir, busy = true)
        val numbers = rows.associate { it.id to it.n }
        return scope.launch {
            val r = try { s.shorten(lines) } catch (e: CancellationException) { throw e } catch (e: Exception) { AiResult.Failed("error", e.message ?: "") }
            _state.value = when (r) {
                is AiResult.Ok -> DubShortenUi(dir, lines.zip(r.value) { l, (_, new) -> DubShortenRow(l.id, numbers[l.id] ?: 0, l.spokenMy, new, l.maxChars) })
                is AiResult.RateLimited -> DubShortenUi(dir, notice = UiNotice(UiText.GEMINI_DETAIL, "detail" to r.message))
                is AiResult.Failed -> DubShortenUi(dir, notice = UiNotice(UiText.GEMINI_DETAIL, "detail" to r.msg))
                is AiResult.Deferred -> DubShortenUi(dir, notice = UiNotice(UiText.GEMINI_DETAIL, "detail" to r.instruction))
            }
        }
    }

    fun toggle(id: String) { _state.value = _state.value.copy(rows = _state.value.rows.map { if (it.id == id) it.copy(use = !it.use) else it }) }

    fun dismiss() { _state.value = DubShortenUi(_state.value.dir) }

    /** Saves the chosen lines whose spoken text is still what Shorten saw; true when a new version was saved. */
    fun use(script: DubScriptModel): Boolean {
        val doc = script.state.value.doc ?: return false
        val chosen = _state.value.rows.filter { r -> r.use && doc.cues.any { it.id == r.id && DubRules.spoken(it) == r.old } }
        if (chosen.isEmpty()) return false
        val ok = script.setSpoken(chosen.associate { it.id to it.new }, UiNotice(UiText.DUB_SHORTEN_USED, "n" to chosen.size))
        if (ok) dismiss()
        return ok
    }

    private fun say(dir: String, notice: UiNotice): Job? { _state.value = DubShortenUi(dir, notice = notice); return null }
}
