package io.github.alalkipgen.autoflow.ui.recap

import io.github.alalkipgen.autoflow.ui.i18n.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.alalkipgen.autoflow.core.recap.Recap
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.core.recap.RecapRulesV2
import io.github.alalkipgen.autoflow.core.recap.toV1
import io.github.alalkipgen.autoflow.core.recap.RecapScene
import io.github.alalkipgen.autoflow.core.recap.Scenes
import io.github.alalkipgen.autoflow.ui.components.AfButton
import io.github.alalkipgen.autoflow.ui.components.AfCard
import io.github.alalkipgen.autoflow.ui.components.AfChip
import io.github.alalkipgen.autoflow.ui.components.AfSwitch
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.ButtonKind
import io.github.alalkipgen.autoflow.ui.components.WrapRow
import io.github.alalkipgen.autoflow.ui.components.decodeImage
import io.github.alalkipgen.autoflow.ui.components.screenPadding
import io.github.alalkipgen.autoflow.ui.projects.RIGHTS_NOTICE
import io.github.alalkipgen.autoflow.ui.recap.RecapModel.Companion.clock
import io.github.alalkipgen.autoflow.ui.storyboard.text
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowShapes

const val IMPORT_MOVIE = "🎬 Import movie"
const val FIND_SCENES = "🔍 Find scenes"
const val SCENES_PER_PAGE = 30

/**
 * Movie Recap screen (docs/specs/recap.md §7): numbered cards 1 Movie · 2 Scenes · 3 Script · 4 Voice · 5 Fit check,
 * whose button calls [onGoToExport]. Locked cards say what to do first ([RecapRulesV2]).
 */
@Composable
fun RecapScreen(model: RecapModel, modifier: Modifier = Modifier, onGoToExport: () -> Unit = {}) {
    val state by model.state.collectAsState()
    LaunchedEffect(model) { model.refresh() }
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(screenPadding).widthIn(max = 900.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        AfText("Movie Recap", size = 18.sp, bold = true)
        AfText(RIGHTS_NOTICE, color = AutoFlowColors.Amber, size = 12.sp)
        state.job?.let { job ->
            val pct = state.progress?.let { " ${(it * 100).toInt()} %" }.orEmpty()
            AfText("⏳ $job$pct", color = AutoFlowColors.Cyan)
        }
        state.message?.let { msg ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfText(msg, Modifier.weight(1f), color = if (state.messageOk) AutoFlowColors.Green else AutoFlowColors.Amber)
                AfButton("✕", { model.dismissMessage() })
            }
        }
        val recap = state.recap
        if (recap == null) {
            AfText(tr(UiText.RECAP_OPEN_HINT), muted = true)
            return@Column
        }
        MovieCard(model, state, recap)
        ScenesCard(model, state, recap)
        ScriptCard(model, state, recap)
        VoiceCard(model, state, recap)
        FitCard(model, state, recap, onGoToExport)
    }
}

@Composable
internal fun MovieCard(model: RecapModel, state: RecapUiState, recap: RecapV2) {
    AfCard(tr(UiText.RECAP_MOVIE_CARD)) {
        val movie = recap.movie
        if (movie == null) {
            AfText(tr(UiText.RECAP_MOVIE_IMPORT_HINT), muted = true)
        } else {
            AfText("${state.movieName ?: movie.file} · ${clock(movie.durationMs)} · ${movie.width}×${movie.height}" +
                (if (movie.hasAudio) "" else tr(UiText.NO_SOUND_SUFFIX)) + " · ${movie.sizeBytes / (1024 * 1024)} MB")
        }
        val gate = RecapRulesV2.canImportMovie(recap)
        if (!gate.allowed) AfText(gate.blockers.first().text(LocalUiLanguage.current), color = AutoFlowColors.Amber)
        AfButton(if (movie == null) tr(UiText.IMPORT_MOVIE) else tr(UiText.REPLACE_MOVIE), { model.importMovie() },
            kind = if (movie == null) ButtonKind.Primary else ButtonKind.Secondary, enabled = gate.allowed && !state.busy)
    }
}

@Composable
internal fun ScenesCard(model: RecapModel, state: RecapUiState, recap: RecapV2, selectedScene: Int? = null) {
    AfCard(tr(UiText.RECAP_SCENES_CARD)) {
        val gate = RecapRulesV2.canFindScenes(recap)
        if (!gate.allowed) {
            AfText(gate.blockers.first().text(LocalUiLanguage.current), muted = true)
            return@AfCard
        }
        WrapRow {
            AfButton(if (recap.scenes.isEmpty()) tr(UiText.FIND_SCENES) else tr(UiText.FIND_SCENES_AGAIN), { model.findScenes(recap.sceneThreshold) },
                kind = if (recap.scenes.isEmpty()) ButtonKind.Primary else ButtonKind.Secondary, enabled = !state.busy)
            AfChip(tr(UiText.MORE_SCENES), recap.sceneThreshold == Scenes.THRESHOLD_MORE, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_MORE) })
            AfChip(tr(UiText.NORMAL), recap.sceneThreshold == Scenes.THRESHOLD_NORMAL, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_NORMAL) })
            AfChip(tr(UiText.FEWER_SCENES), recap.sceneThreshold == Scenes.THRESHOLD_FEWER, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_FEWER) })
        }
        if (recap.scenes.isEmpty()) {
            AfText(tr(UiText.FIND_SCENES_HINT), muted = true)
            return@AfCard
        }
        AfText(tr(UiText.TARGET_LENGTH), muted = true, size = 12.sp)
        WrapRow {
            Recap.TARGETS_MS.forEach { t -> AfChip(tr(UiText.TARGET_MINUTES, "t60000" to (t / 60_000)), recap.targetMs == t, { model.setTarget(t) }) }
        }
        val off = Scenes.offTarget(recap.toV1())
        AfText(tr(UiText.SCENES_PICKED, "size" to (recap.picked.size), "pickedMs" to (clock(recap.pickedMs)), "targetMs" to (clock(recap.targetMs))),
            color = if (off) AutoFlowColors.Amber else null, bold = true)
        var pickedOnly by remember { mutableStateOf(false) }
        var page by remember(recap.scenes.size, pickedOnly) { mutableIntStateOf(0) }
        WrapRow {
            AfSwitch(tr(UiText.SHOW_PICKED), pickedOnly, { pickedOnly = it })
            if (recap.picked.isNotEmpty()) AfButton(tr(UiText.CLEAR_PICKS), { model.clearPicks() }, kind = ButtonKind.Danger)
        }
        val rows = recap.scenes.withIndex().filter { (selectedScene == null || it.index == selectedScene) && (!pickedOnly || it.value.picked) }
        val pages = maxOf(1, (rows.size + SCENES_PER_PAGE - 1) / SCENES_PER_PAGE)
        val shown = rows.drop(page * SCENES_PER_PAGE).take(SCENES_PER_PAGE)
        shown.forEach { (i, scene) -> SceneRow(model, i, scene) }
        if (pages > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("◀", { page-- }, enabled = page > 0)
                AfText(tr(UiText.SCENES_PAGE, "page1" to (page + 1), "pages" to (pages), "size" to (rows.size)), muted = true)
                AfButton("▶", { page++ }, enabled = page < pages - 1)
            }
        }
    }
}

@Composable
private fun SceneRow(model: RecapModel, index: Int, scene: RecapScene) {
    val image by produceState<ImageBitmap?>(null, scene.startMs, scene.endMs) { value = model.thumbnail(scene)?.let(::decodeImage) }
    Row(
        Modifier.fillMaxWidth().clip(AutoFlowShapes.Card)
            .background(if (scene.picked) AutoFlowColors.Purple.copy(alpha = 0.18f) else AutoFlowColors.Card)
            .clickable { model.togglePick(index) }.padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Box(Modifier.size(width = 96.dp, height = 54.dp).clip(AutoFlowShapes.Card).background(AutoFlowColors.Background)) {
            image?.let { Image(it, contentDescription = tr(UiText.SCENE_DESCRIPTION, "index1" to (index + 1)), Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            AfText("#${index + 1} · ${clock(scene.startMs)}–${clock(scene.endMs)}", bold = scene.picked)
            AfText("${scene.durationMs / 100 / 10.0} s", muted = true, size = 12.sp)
        }
        AfText(if (scene.picked) "☑" else "☐", size = 20.sp, color = if (scene.picked) AutoFlowColors.Purple else AutoFlowColors.Muted)
    }
}
