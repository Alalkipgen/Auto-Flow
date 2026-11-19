package io.github.alalkipgen.autoflow.ui.home

import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.recap.RecapStep
import io.github.alalkipgen.autoflow.ui.shell.MV_CLASSIC
import io.github.alalkipgen.autoflow.ui.shell.SHOT_SHEET
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.V2Nav

/** Allowlisted destinations only: no sub-screen drafts, block IDs, approval state or translated labels. */
internal enum class HomeStep(val mode: Mode, val screen: String, val recap: RecapStep? = null) {
    SOURCE(Mode.MOVIE_RECAP, "Recap", RecapStep.SOURCE),
    STORY(Mode.MOVIE_RECAP, "Recap", RecapStep.STORY),
    VOICE(Mode.MOVIE_RECAP, "Recap", RecapStep.VOICE),
    STYLE(Mode.MOVIE_RECAP, "Recap", RecapStep.STYLE),
    RECAP_EXPORT(Mode.MOVIE_RECAP, "Export", RecapStep.EXPORT),
    RECAP_CLASSIC(Mode.MOVIE_RECAP, "Classic Recap"),
    INPUT(Mode.MUSIC_VIDEO, "Input"),
    SHOT_LIST(Mode.MUSIC_VIDEO, SHOT_SHEET),
    INBOX(Mode.MUSIC_VIDEO, "Inbox"),
    MV_EXPORT(Mode.MUSIC_VIDEO, "Export"),
    CLASSIC(Mode.MUSIC_VIDEO, MV_CLASSIC),
    STORYBOARD(Mode.MUSIC_VIDEO, "Storyboard"),
    SHOTS(Mode.MUSIC_VIDEO, "Shots"),
    REFERENCES(Mode.MUSIC_VIDEO, "References"),
    FLOW_HANDOFF(Mode.MUSIC_VIDEO, "Flow Handoff");

    fun nav(): V2Nav = V2Nav(StudioV2Dest.PROJECTS, screen,
        recapStep = if (recap == RecapStep.EXPORT) RecapStep.STYLE else recap ?: RecapStep.SOURCE)

    companion object {
        fun from(nav: V2Nav, mode: Mode): HomeStep? = entries.firstOrNull {
            it.mode == mode && it.screen == nav.projectScreen && (it.screen != "Recap" || it.recap == nav.recapStep)
        }
    }
}

internal data class HomeResume(val path: String, val projectStamp: String, val step: HomeStep) {
    fun belongsTo(project: OpenProject): Boolean = path == project.dir && step.mode == project.project.mode &&
        projectStamp == stamp(project.project.id)

    companion object {
        fun from(project: OpenProject, nav: V2Nav): HomeResume? = HomeStep.from(nav, project.project.mode)?.let {
            HomeResume(project.dir, stamp(project.project.id), it)
        }
        private fun stamp(id: String) = Sha256.hex(id.encodeToByteArray())
    }
}

/** Disposable UI cache, deliberately outside the project and settings. Absence/corruption is a normal cache miss. */
internal class HomeContinueCache(private val fs: FileSystem, appRoot: String) {
    private val root = joinPath(appRoot, "cache/home-continue-v1")
    private fun key(path: String) = joinPath(root, Sha256.hex(path.encodeToByteArray()))

    fun read(path: String, mode: Mode): HomeResume? = runCatching {
        val file = key(path)
        if (!fs.exists(file) || fs.size(file) !in 66L..96L) return null
        val (stamp, token) = fs.read(file).decodeToString(throwOnInvalidSequence = true).split(' ', limit = 2)
        if (stamp.length != 64 || stamp.any { it !in '0'..'9' && it !in 'a'..'f' }) return null
        val step = HomeStep.entries.firstOrNull { it.name == token && it.mode == mode } ?: return null
        HomeResume(path, stamp, step)
    }.getOrNull()

    fun write(hint: HomeResume) {
        runCatching {
            fs.createDirectories(root)
            fs.withFolderLock(root) {
                val file = key(hint.path)
                fs.writeDurably("$file.tmp", "${hint.projectStamp} ${hint.step.name}".encodeToByteArray())
                fs.atomicMove("$file.tmp", file)
            }
        }
    }
}
