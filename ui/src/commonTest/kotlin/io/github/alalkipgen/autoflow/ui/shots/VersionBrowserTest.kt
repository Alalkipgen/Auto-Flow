package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.HandoffState
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Version browser per shot (PLAN Phase 2): every imported video, Draft / Final labels, preview and pick. */
class VersionBrowserTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"
    private val model = ShotsModel(session)
    private var draft = ArtifactId("")
    private var final = ArtifactId("")

    private fun video(b: Byte, note: String?, provider: String = "user"): ArtifactId =
        (session.artifacts()!!.put(byteArrayOf(b), ArtifactKind.VIDEO, "mp4", 1, provider = provider, note = note) as CoreResult.Ok).value.id

    private fun open() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        draft = video(1, "draft")
        final = video(2, "final", provider = "inbox")
        val shot = Shot(1, 0, 8_000, videos = listOf(draft, final), acceptedVideo = draft)
        session.save(p.copy(shots = listOf(shot.copy(handoff = shot.handoff.copy(pass = Pass.FINAL, state = HandoffState.ACCEPTED)), Shot(2, 8_000, 8_000))))
        model.select(1)
    }

    private fun detail() = assertNotNull(model.current().detail)

    @Test
    fun listsEveryVersionWithItsPassAndShowsTheAcceptedOne() {
        open()
        val d = detail()
        assertEquals(Pass.FINAL, d.pass)
        assertEquals(listOf(1, 2), d.versions.map { it.version })
        assertEquals(listOf(Pass.DRAFT, Pass.FINAL), d.versions.map { it.pass })
        assertEquals(listOf(true, false), d.versions.map { it.accepted })
        assertEquals(draft, d.previewing, "the accepted version plays first")
        assertTrue(d.versions[0].path!!.startsWith("$dir/artifacts/vd_"))
        assertTrue(Regex("""\d{4}-\d\d-\d\d \d\d:\d\d""").matches(d.versions[0].importedAt))
        assertEquals("v1 · Draft (Fast) · ${d.versions[0].importedAt} · ✓ Accepted", versionLine(d.versions[0]))
        assertEquals("Videos (2 versions)", versionsTitle(2))
    }

    @Test
    fun previewChangesOnlyThePlayerAndUseThisVersionAcceptsIt() {
        open()
        model.preview(1, final)
        assertEquals(final, detail().previewing)
        assertEquals(draft, session.open!!.project.shots[0].acceptedVideo, "preview saves nothing")

        assertNull(model.acceptVersion(1, final))
        val shot = session.open!!.project.shots[0]
        assertEquals(final, shot.acceptedVideo)
        assertTrue(shot.approvals.video)
        assertEquals(HandoffState.ACCEPTED, shot.handoff.state)
        assertEquals(listOf(draft, final), shot.videos, "no version is removed")
        assertEquals(listOf(false, true), detail().versions.map { it.accepted })
    }

    @Test
    fun aVideoOfAnotherShotCannotBePicked() {
        open()
        val other = video(3, null)
        assertEquals("That video is not one of Shot 1's versions", model.acceptVersion(1, other))
        assertEquals(draft, session.open!!.project.shots[0].acceptedVideo)
    }

    @Test
    fun withoutAcceptedVideoTheNewestPlaysAndOldImportsHaveNoPassLabel() {
        open()
        val p = session.open!!.project
        val plain = video(4, null)
        session.save(p.copy(shots = p.shots.map { if (it.number == 1) it.copy(videos = it.videos + plain, acceptedVideo = null) else it }))
        val d = detail()
        assertEquals(plain, d.previewing)
        assertNull(d.versions[2].pass)
        assertEquals("v3 · ${d.versions[2].importedAt}", versionLine(d.versions[2]))
    }
}
