package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.io.joinPath

/**
 * Per-user app files, outside any project folder. On desktop [root] is `~/.autoflow` (see `AppDirs.desktop()`);
 * Android passes its app files directory. Nothing here is project data: every file can be lost without losing work,
 * except the encrypted API key, which the user can enter again.
 *
 * [projectsHome] is where new projects go without asking: Android's external files dir (D-27); null on desktop,
 * where the user picks a folder for each project.
 */
class AppDirs(root: String, projectsHome: String? = null) {
    val root: String = root.toPortablePath().trimEnd('/')
    val projectsHome: String? = projectsHome?.toPortablePath()?.trimEnd('/')

    /** Rebuildable project list (D-20). */
    val projectsIndex: String get() = joinPath(root, "projects.json")

    /** App settings: Fake/Real AI, tool paths. Never holds the API key. */
    val settings: String get() = joinPath(root, "settings.json")

    /** DPAPI-encrypted API key (D-19). */
    val secrets: String get() = joinPath(root, "secrets.bin")

    /** Daily request counter (specs/ai-providers.md). */
    val usage: String get() = joinPath(root, "usage.json")

    override fun equals(other: Any?): Boolean = other is AppDirs && other.root == root && other.projectsHome == projectsHome
    override fun hashCode(): Int = 31 * root.hashCode() + projectsHome.hashCode()
    override fun toString(): String = if (projectsHome == null) "AppDirs($root)" else "AppDirs($root, projects $projectsHome)"

    companion object {
        const val DIR_NAME = ".autoflow"

        /** JVM system property that moves [root], for tests and portable runs: `-Dautoflow.home=D:/af-home`. */
        const val HOME_OVERRIDE_PROPERTY = "autoflow.home"
    }
}
