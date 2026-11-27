package io.github.alalkipgen.autoflow.ui.recap

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.recap.QuickRecap
import io.github.alalkipgen.autoflow.core.recap.RecapStyle
import io.github.alalkipgen.autoflow.core.recap.RecapV2
import io.github.alalkipgen.autoflow.ui.export.ExportHarness
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import kotlin.test.*

/**
 * Group 6 🆕 Q-3: the Quick Recap wizard's style is used. A recap made by the wizard with Commentary (`recap.quick`) and no
 * story yet opens Story's Writing options on Commentary; without the wizard (or once a story exists) it is as before.
 */
@OptIn(ExperimentalTestApi::class)
class QuickRecapStyleTest {
    private fun quick(style: RecapStyle) = CoreJson.encodeToJsonElement(QuickRecap.serializer(), QuickRecap(style = style))

    @Test fun startStyleReadsTheWizardChoice() {
        assertEquals(RecapStyle.COMMENTARY, RecapModel.startStyle(RecapV2(quick = quick(RecapStyle.COMMENTARY))))
        assertEquals(RecapStyle.PLAIN, RecapModel.startStyle(RecapV2(quick = quick(RecapStyle.PLAIN))))
        assertEquals(RecapStyle.PLAIN, RecapModel.startStyle(RecapV2()), "not a Quick Recap: Plain, as before")
        assertEquals(RecapStyle.PLAIN, RecapModel.startStyle(null))
    }

    @Test fun commentaryWizardOpensWritingOptionsOnCommentary() = runDesktopComposeUiTest(width = 412, height = 1400) {
        ExportHarness().use { h ->
            val open = h.session.open!!
            val saved = (h.store.save(open.dir, open.project.copy(recap = open.project.recap!!.copy(quick = quick(RecapStyle.COMMENTARY), story = null)))
                as CoreResult.Ok).value
            h.current.set(OpenProject(open.dir, saved))
            h.recap.refresh()
            val state = h.recap.state.value
            assertNull(state.story)
            val recap = state.recap!!.let { r -> if (r.picked.isNotEmpty()) r else r.copy(scenes = StoryStates.SCENES) }
            setContent { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) { ScriptCard(h.recap, state, recap, studioOptions = true) } }
            waitForIdle()
            val title = UiText.V3_STORY_OPTIONS.en
            val folded = hasContentDescription(title) and SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, UiText.V3_COLLAPSED.en)
            while (onAllNodes(folded).fetchSemanticsNodes().isNotEmpty()) { onAllNodes(folded)[0].performSemanticsAction(SemanticsActions.OnClick); waitForIdle() }
            onNode(hasText(UiText.STYLE_COMMENTARY.en)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "selected"))
            onNode(hasText(UiText.STYLE_PLAIN.en)).assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "not selected"))
        }
    }
}
