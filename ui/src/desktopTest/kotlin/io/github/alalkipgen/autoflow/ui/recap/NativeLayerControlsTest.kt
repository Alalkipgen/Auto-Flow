package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlinx.coroutines.*
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class NativeLayerControlsTest {
    @Test fun addDragPinchWheelSaveReorderTextAndRemovePersistByStableId() = runDesktopComposeUiTest(width = 600, height = 900) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val dir = "/p/native.autoflow"
            val p = (store.create(dir, "Native", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val legacy = RecapVisual(masks = listOf(RecapMask(MaskPreset.BOTTOM, MaskPreset.BOTTOM.rect)), outputRatio = Ratio.R16_9)
            val saved = (store.save(dir, p.copy(recap = RecapV2(storyApproved = true, visual = legacy,
                layers = listOf(Layer("unchanged-border", LayerType.BORDER))))) as CoreResult.Ok).value
            val current = CurrentProject().apply { set(OpenProject(dir, saved)) }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs, null, null, scope)
            fun recap() = (store.open(dir) as CoreResult.Ok).value.recap!!
            setContent {
                val open by current.state.collectAsState()
                CompositionLocalProvider(LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RecapLayerControls(open!!.project.recap!!, true, model) }
                }
            }
            onNodeWithTag("layer-add-BLUR").assertIsDisplayed().performClick()
            val id = model.layerEditor.state.value.draft!!.layer.id
            val rect = onNodeWithTag("native-layer-rect").performScrollTo().assertIsDisplayed()
            rect.performTouchInput {
                val start = Offset(width * 0.5f, height * 0.8f)
                down(start); moveTo(start + Offset(-width * 0.05f, -height * 0.1f)); moveTo(start + Offset(-width * 0.1f, -height * 0.2f)); up()
            }
            waitForIdle()
            rect.performTouchInput {
                val c = Offset(width * 0.4f, height * 0.6f)
                down(0, c - Offset(width * 0.03f, 0f)); down(1, c + Offset(width * 0.03f, 0f))
                moveTo(0, c - Offset(width * 0.04f, 0f)); moveTo(1, c + Offset(width * 0.04f, 0f)); up(0); up(1)
            }
            waitForIdle()
            rect.performMouseInput { moveTo(center); scroll(1f) }
            onNodeWithTag("layer-earlier").performScrollTo().performClick()
            onNodeWithTag("layer-save").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitForIdle()
            val blur = recap().layers.first()
            assertEquals(id, blur.id); assertEquals(LayerType.BLUR, blur.type)
            assertTrue(blur.rect[1] < 0.65f && blur.rect[3] > 0.2f, "real gesture changed persisted output rect: ${blur.rect}")
            assertEquals(legacy, recap().visual, "legacy movie-picture masks untouched")
            assertTrue(recap().storyApproved)

            onNodeWithTag("layer-add-TEXT").performScrollTo().performClick()
            val textId = model.layerEditor.state.value.draft!!.layer.id
            onNodeWithContentDescription("Layer စာသား").performScrollTo().performTextReplacement("မင်္ဂလာပါ")
            onNodeWithContentDescription("စတင်ချိန် (output ms)").performScrollTo().performTextReplacement("500")
            onNodeWithContentDescription("ဆုံးချိန် (output ms၊ အဆုံးအထိဆို အလွတ်ထားပါ)").performScrollTo().performTextReplacement("2000")
            onNodeWithTag("layer-save").performScrollTo().assertIsEnabled().performClick()
            waitForIdle()
            assertEquals("မင်္ဂလာပါ", recap().layers.last().params.textMy)
            assertEquals(500L, recap().layers.last().fromMs); assertEquals(2000L, recap().layers.last().toMs)
            onNodeWithTag("layer-edit-$textId").performScrollTo().performClick()
            onNodeWithTag("layer-remove").performScrollTo().performClick()
            waitForIdle()
            assertEquals(listOf(id, "unchanged-border"), recap().layers.map { it.id })
        } finally { scope.cancel() }
    }

    @Test fun invalidTypedValuesDisableSaveAndCancelLeavesSerializedProjectUnchanged() = runDesktopComposeUiTest(width = 412, height = 760) {
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            val fs = InMemoryFileSystem(); val store = ProjectStore(fs); val dir = "/p/cancel.autoflow"
            val p = (store.create(dir, "Cancel", Mode.MOVIE_RECAP) as CoreResult.Ok).value
            val saved = (store.save(dir, p.copy(recap = RecapV2(layers = listOf(Layer("original", LayerType.BLUR)))) ) as CoreResult.Ok).value
            val current = CurrentProject().apply { set(OpenProject(dir, saved)) }
            val model = RecapModel(ProjectSession(fs, store, ProjectIndex(fs, "/index.json"), current), fs, null, null, scope)
            val before = fs.read("$dir/project.json").toList()
            setContent {
                val open by current.state.collectAsState()
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { RecapLayerControls(open!!.project.recap!!, true, model) }
            }
            onNodeWithTag("layer-edit-original").performScrollTo().performClick()
            onNodeWithContentDescription("ဘယ်ဘက် x").performScrollTo().performTextReplacement("NaN")
            onNodeWithTag("layer-save").performScrollTo().assertIsNotEnabled()
            onNodeWithContentDescription("ဘယ်ဘက် x").performScrollTo().performTextReplacement("not a number")
            onNodeWithTag("layer-save").performScrollTo().assertIsNotEnabled()
            onNodeWithTag("layer-cancel").performScrollTo().assertIsDisplayed().performClick()
            assertEquals(before, fs.read("$dir/project.json").toList())
        } finally { scope.cancel() }
    }
}
