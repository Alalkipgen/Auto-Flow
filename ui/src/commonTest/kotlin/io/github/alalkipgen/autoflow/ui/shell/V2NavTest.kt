package io.github.alalkipgen.autoflow.ui.shell

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    fun musicVideoProjectsReachEveryClassicScreen() {
        assertEquals(listOf("Recap", "Export"), v2ProjectScreens(recap = true))
        assertTrue(v2ProjectScreens(recap = false).containsAll(listOf("Input", "Storyboard", "Shots", "Flow Handoff", "Export")))
    }
}
