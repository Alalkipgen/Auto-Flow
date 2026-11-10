package io.github.alalkipgen.autoflow.ui.shots

import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.ImageProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeImageProvider
import io.github.alalkipgen.autoflow.ai.fake.FakeTextProvider
import io.github.alalkipgen.autoflow.ai.storyboard.StoryboardGenerator
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Ratio
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.storyboard.StoryboardService

/** Shared set-up for shot tests: a generated, approved 4-shot storyboard (30 s, 8 s clips). */
class ShotFixture(images: ImageProvider = FakeImageProvider()) {
    val fs = InMemoryFileSystem()
    val store = ProjectStore(fs)
    val current = CurrentProject()
    val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    val storyboard = StoryboardService(session, StoryboardGenerator(FakeTextProvider()))
    val copied = mutableListOf<String>()
    val opened = mutableListOf<String>()
    val service = ShotService(session, images)

    val project get() = current.state.value!!.project
    fun shot(n: Int) = project.shots.first { it.number == n }

    suspend fun open(approve: Boolean = true) {
        val dir = "/p/Rain.autoflow"
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings(durationMs = 30_000, clipMs = 8_000)) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        session.writeLyrics("မိုးတွေရွာနေတယ်")
        storyboard.generateAll()
        if (approve) storyboard.approveAll()
    }

    /** Shot [n] with an approved prompt and an approved Ref A: ready for Flow. */
    suspend fun prepareFlow(n: Int = 1) {
        service.compilePrompt(n)
        service.approvePrompt(n)
        service.requestRef(n, RefSlot.A)
        service.approveRef(n)
    }

    suspend fun png(ratio: Ratio) = (FakeImageProvider().image("x", ratio, emptyList()) as AiResult.Ok).value
}
