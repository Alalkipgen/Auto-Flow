package io.github.alalkipgen.autoflow.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class V2NavTest {
    @Test
    fun backClosesSettingsFirst() {
        assertEquals(V2Nav(StudioV2Dest.JOBS), v2Back(V2Nav(StudioV2Dest.JOBS, settings = true), recap = false))
    }

    @Test
    fun backStepsFromRecapExportToRecapThenToTheList() {
        val export = V2Nav(StudioV2Dest.PROJECTS, "Export")
        assertEquals(V2Nav(StudioV2Dest.PROJECTS, "Recap"), v2Back(export, recap = true))
        assertEquals(V2Nav(StudioV2Dest.PROJECTS, null), v2Back(V2Nav(StudioV2Dest.PROJECTS, "Recap"), recap = true))
        assertEquals(V2Nav(StudioV2Dest.PROJECTS, null), v2Back(export, recap = false))
    }

    @Test
    fun backFromATabGoesHomeThenLeaves() {
        assertEquals(V2Nav(StudioV2Dest.HOME), v2Back(V2Nav(StudioV2Dest.LIBRARY), recap = false))
        assertEquals(V2Nav(StudioV2Dest.HOME), v2Back(V2Nav(StudioV2Dest.PROJECTS, null), recap = false))
        assertNull(v2Back(V2Nav(StudioV2Dest.HOME), recap = false))
    }

    @Test
    fun recapStepBackAndClassicKeepTheProjectRoute() {
        val style = V2Nav(StudioV2Dest.PROJECTS, "Recap", recapStep = io.github.alalkipgen.autoflow.ui.recap.RecapStep.STYLE)
        val voice = style.copy(recapStep = io.github.alalkipgen.autoflow.ui.recap.RecapStep.VOICE)
        assertEquals(voice, v2Back(style, recap = true))
        assertEquals(style, v2Back(style.copy(projectScreen = "Export"), recap = true))
        assertEquals(style, v2Back(style.copy(projectScreen = "Classic Recap"), recap = true))
    }

    @Test
    fun musicVideoUsesTheShotSheetAndKeepsTheClassicStepsBehindOneEntry() {
        assertEquals(listOf("Recap", "Export"), v2ProjectScreens(recap = true))
        assertEquals(listOf("Input", SHOT_SHEET, "Inbox", "Export", MV_CLASSIC), v2ProjectScreens(recap = false))
        assertEquals(listOf("Storyboard", "Shots", "References", "Flow Handoff"), MV_CLASSIC_SCREENS)
        // Back: a classic screen → the classic list → the shot sheet → the project list.
        for (s in MV_CLASSIC_SCREENS) assertEquals(V2Nav(StudioV2Dest.PROJECTS, MV_CLASSIC), v2Back(V2Nav(StudioV2Dest.PROJECTS, s), recap = false))
        assertEquals(V2Nav(StudioV2Dest.PROJECTS, SHOT_SHEET), v2Back(V2Nav(StudioV2Dest.PROJECTS, MV_CLASSIC), recap = false))
        assertEquals(V2Nav(StudioV2Dest.PROJECTS, null), v2Back(V2Nav(StudioV2Dest.PROJECTS, SHOT_SHEET), recap = false))
    }
}
