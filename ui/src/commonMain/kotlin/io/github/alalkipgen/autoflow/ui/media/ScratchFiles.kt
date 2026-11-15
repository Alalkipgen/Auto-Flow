package io.github.alalkipgen.autoflow.ui.media

/**
 * Deletes the scratch file at [path] (a render's own `<name>.part.mp4`, audit F16); false when it was not there or
 * could not be deleted. Never used for artifacts, which are immutable (AGENTS.md §1).
 */
internal expect fun deleteScratchFile(path: String): Boolean
