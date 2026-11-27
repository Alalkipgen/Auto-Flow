package io.github.alalkipgen.autoflow.ui.voice

import androidx.compose.ui.unit.sp
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import io.github.alalkipgen.autoflow.ai.config.EdgeTtsConfig
import io.github.alalkipgen.autoflow.ai.config.ModelConfig
import io.github.alalkipgen.autoflow.core.flags.FeatureFlags
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.recap.VoiceLock
import io.github.alalkipgen.autoflow.media.audio.SongAudio
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.GlassFilterChip
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.i18n.My
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.i18n.tr
import io.github.alalkipgen.autoflow.ui.media.MediaTools
import io.github.alalkipgen.autoflow.ui.settings.BundledConfig
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Edge "Read aloud" voices in the voice-lock pickers (V-1b, D-86): free, no API key, next to the Gemini voices. */
object EdgeVoices {
    /** [VoiceLock.provider] of an Edge lock; its model is `tts.edge.model`. */
    const val PROVIDER = "edge"

    /** A voice job's pace (F-3): Edge's own `tts.edge.rpm` for an Edge lock, else Gemini's [VoiceSpeakJob.RPM]. */
    fun rpm(lock: VoiceLock?, edgeRpm: Int): Int = if (lock?.provider == PROVIDER) edgeRpm.coerceAtLeast(1) else VoiceSpeakJob.RPM

    /** Whole minutes [n] requests take at [rpm] (at least 1). */
    fun minutes(n: Int, rpm: Int): Int = maxOf(1, (n + rpm - 1) / rpm.coerceAtLeast(1))

    /** "Edge TTS" for an Edge lock, else "Gemini TTS" — the name a Speak error starts with. */
    fun ttsName(lock: VoiceLock): String = if (lock.provider == PROVIDER) "Edge TTS" else "Gemini TTS"

    /** The Gemini model a Gemini voice chip locks to: the current one, unless the lock is an Edge voice. */
    fun geminiModel(lock: VoiceLock?, models: List<String>): String? =
        lock?.takeIf { it.provider != PROVIDER }?.model?.takeIf { it.isNotBlank() } ?: models.firstOrNull()

    /** The Edge settings to offer now: null unless `tts.edge` is set up and flag `labs.edgeVoices` is on (Preview features). */
    fun offered(edge: EdgeTtsConfig?, flags: () -> FeatureFlags): EdgeTtsConfig? = edge?.takeIf { it.available && flags().labs.edgeVoices }

    /** For the Koin modules: flags as Settings → Preview features sets them now. */
    fun offeredWith(config: ModelConfig?, settings: SettingsStore?, bundled: BundledConfig?): () -> EdgeTtsConfig? = {
        offered(config?.tts?.edge) { FeatureFlags.load(bundled?.featuresJson(), settings?.state?.value?.featureOverrides.orEmpty()) }
    }
}

/** Thiha / Nilar chips; a tap locks to that Edge voice ([tag] prefixes the test tags). */
@Composable
fun EdgeVoiceChips(edge: EdgeTtsConfig, lock: VoiceLock?, enabled: Boolean, tag: String, glass: Boolean = false, onPick: (VoiceLock) -> Unit) {
    // [glass] (Dubbing, Group 4 F 1): a small 12 sp muted label and glass filter chips; Voice & Audio keeps its look.
    if (glass) AfText(My.breakSyllables(tr(UiText.EDGE_VOICES)), muted = true, size = 12.sp)
    else AfText(My.breakSyllables(tr(UiText.EDGE_VOICES)), muted = true)
    WrapRow {
        edge.voicesFor("my").forEach { v ->
            val on = lock?.provider == EdgeVoices.PROVIDER && lock.voice == v.id
            val pick = { onPick(VoiceLock(EdgeVoices.PROVIDER, edge.model, v.id, "my")) }
            if (glass) GlassFilterChip(My.breakSyllables("${v.name} · Edge"), on, pick, Modifier.testTag("$tag-edge-${v.name}"), enabled = enabled)
            else AfChip(My.breakSyllables("${v.name} · Edge"), on, pick, Modifier.testTag("$tag-edge-${v.name}"), enabled = enabled)
        }
    }
}

/** "Nilar · Edge" / "Charon · Gemini": a voice lock as people say it (not the raw id and model, §8.4). */
fun voiceLabel(lock: VoiceLock, edge: EdgeTtsConfig?): String =
    if (lock.provider == EdgeVoices.PROVIDER) "${edge?.voicesFor(lock.lang.ifBlank { "my" })?.firstOrNull { it.id == lock.voice }?.name ?: lock.voice} · Edge"
    else "${lock.voice} · Gemini"

/**
 * Edge sends mp3; takes and joins need PCM WAV (D-86). ffmpeg decodes it through two fixed files in [dir] (one decode
 * at a time, overwritten each time, so nothing piles up). Null when ffmpeg or the folder is missing or it fails.
 */
class EdgeMp3Decoder(private val fs: FileSystem, private val dir: String?, private val media: () -> MediaTools?) {
    private val lock = Mutex()

    suspend fun wav(mp3: ByteArray): ByteArray? {
        val d = dir ?: return null
        val tools = media() ?: return null
        val runner = tools.runner ?: return null
        val ffmpeg = tools.ffmpeg() ?: return null
        return lock.withLock {
            runCatching {
                fs.createDirectories(d)
                val input = joinPath(d, "edge.mp3")
                val output = joinPath(d, "edge.wav")
                fs.writeDurably(input, mp3)
                if (SongAudio.decodeMonoWav(runner, ffmpeg, input, output, d, RATE) == null) fs.read(output) else null
            }.getOrNull()
        }
    }

    companion object {
        /** Edge's own rate (`audio-24khz-…`), so nothing is resampled. */
        const val RATE = 24_000
        const val DIR = "edge-tts"
    }
}
