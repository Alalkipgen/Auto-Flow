package io.github.alalkipgen.autoflow.ui.recap

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
import io.github.alalkipgen.autoflow.core.recap.RecapRules
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
 * Movie Recap screen (docs/specs/recap.md §7): numbered cards 1 Movie · 2 Scenes, then the script, voice and fit
 * cards ([extraCards], later slices). Locked cards say what to do first ([RecapRules]).
 */
@Composable
fun RecapScreen(model: RecapModel, modifier: Modifier = Modifier, extraCards: @Composable (Recap) -> Unit = {}) {
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
            AfText("Open a Movie Recap project on the Dashboard (New Movie Recap).", muted = true)
            return@Column
        }
        MovieCard(model, state, recap)
        ScenesCard(model, state, recap)
        ScriptCard(model, state, recap)
        extraCards(recap)
    }
}

@Composable
private fun MovieCard(model: RecapModel, state: RecapUiState, recap: Recap) {
    AfCard("1 · Movie") {
        val movie = recap.movie
        if (movie == null) {
            AfText("Choose the movie file (mp4, mkv, mov, webm). It is copied into the project; the phone needs about twice its size free while copying.", muted = true)
        } else {
            AfText("${state.movieName ?: movie.file} · ${clock(movie.durationMs)} · ${movie.width}×${movie.height}" +
                (if (movie.hasAudio) "" else " · no sound") + " · ${movie.sizeBytes / (1024 * 1024)} MB")
        }
        val gate = RecapRules.canImportMovie(recap)
        if (!gate.allowed) AfText(gate.blockers.first().text(), color = AutoFlowColors.Amber)
        AfButton(if (movie == null) IMPORT_MOVIE else "Replace movie", { model.importMovie() },
            kind = if (movie == null) ButtonKind.Primary else ButtonKind.Secondary, enabled = gate.allowed && !state.busy)
    }
}

@Composable
private fun ScenesCard(model: RecapModel, state: RecapUiState, recap: Recap) {
    AfCard("2 · Scenes") {
        val gate = RecapRules.canFindScenes(recap)
        if (!gate.allowed) {
            AfText(gate.blockers.first().text(), muted = true)
            return@AfCard
        }
        WrapRow {
            AfButton(if (recap.scenes.isEmpty()) FIND_SCENES else "🔍 Find again", { model.findScenes(recap.sceneThreshold) },
                kind = if (recap.scenes.isEmpty()) ButtonKind.Primary else ButtonKind.Secondary, enabled = !state.busy)
            AfChip("More scenes", recap.sceneThreshold == Scenes.THRESHOLD_MORE, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_MORE) })
            AfChip("Normal", recap.sceneThreshold == Scenes.THRESHOLD_NORMAL, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_NORMAL) })
            AfChip("Fewer scenes", recap.sceneThreshold == Scenes.THRESHOLD_FEWER, { if (!state.busy) model.findScenes(Scenes.THRESHOLD_FEWER) })
        }
        if (recap.scenes.isEmpty()) {
            AfText("Finding scenes reads the whole movie once (a 2-hour movie takes a few minutes on the phone).", muted = true)
            return@AfCard
        }
        AfText("Target length", muted = true, size = 12.sp)
        WrapRow {
            Recap.TARGETS_MS.forEach { t -> AfChip("${t / 60_000} min", recap.targetMs == t, { model.setTarget(t) }) }
        }
        val off = Scenes.offTarget(recap)
        AfText("Picked ${recap.picked.size} scenes · ${clock(recap.pickedMs)} of target ${clock(recap.targetMs)}",
            color = if (off) AutoFlowColors.Amber else null, bold = true)
        var pickedOnly by remember { mutableStateOf(false) }
        var page by remember(recap.scenes.size, pickedOnly) { mutableIntStateOf(0) }
        WrapRow {
            AfSwitch("Show picked only", pickedOnly, { pickedOnly = it })
            if (recap.picked.isNotEmpty()) AfButton("Clear picks", { model.clearPicks() }, kind = ButtonKind.Danger)
        }
        val rows = recap.scenes.withIndex().filter { !pickedOnly || it.value.picked }
        val pages = maxOf(1, (rows.size + SCENES_PER_PAGE - 1) / SCENES_PER_PAGE)
        val shown = rows.drop(page * SCENES_PER_PAGE).take(SCENES_PER_PAGE)
        shown.forEach { (i, scene) -> SceneRow(model, i, scene) }
        if (pages > 1) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                AfButton("◀", { page-- }, enabled = page > 0)
                AfText("Page ${page + 1} of $pages · ${rows.size} scenes", muted = true)
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
            image?.let { Image(it, contentDescription = "Scene ${index + 1}", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        }
        Column(Modifier.weight(1f)) {
            AfText("#${index + 1} · ${clock(scene.startMs)}–${clock(scene.endMs)}", bold = scene.picked)
            AfText("${scene.durationMs / 100 / 10.0} s", muted = true, size = 12.sp)
        }
        AfText(if (scene.picked) "☑" else "☐", size = 20.sp, color = if (scene.picked) AutoFlowColors.Purple else AutoFlowColors.Muted)
    }
}
