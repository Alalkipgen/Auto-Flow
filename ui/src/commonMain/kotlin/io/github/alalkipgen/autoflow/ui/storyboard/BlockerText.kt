package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.core.workflow.Blocker

/** What the user must do for each WorkflowRules blocker. The rule itself lives only in `:core`. */
fun Blocker.text(): String = when (this) {
    Blocker.EMPTY_LYRICS -> "Add the lyrics"
    Blocker.NO_DURATION -> "Set the song duration (m:ss)"
    Blocker.INVALID_CLIP_LENGTH -> "Choose a clip length"
    Blocker.NO_SPEC -> "This shot has no storyboard yet"
    Blocker.STORYBOARD_NOT_APPROVED -> "Approve the storyboard first"
    Blocker.NO_PROMPT -> "Compile the prompt first"
    Blocker.PROMPT_NOT_APPROVED -> "Approve the prompt first"
    Blocker.PROMPT_STALE -> "The prompt is out of date: compile it again"
    Blocker.NO_CHOSEN_REF -> "Choose a reference image"
    Blocker.REF_NOT_APPROVED -> "Approve the reference image first"
    Blocker.NO_VIDEOS -> "Import a video from Flow first"
    Blocker.NO_ACCEPTED_VIDEOS -> "Accept at least one video"
    Blocker.NO_RIGHTS -> "Tick the rights box (New Movie Recap) first"
    Blocker.NO_MOVIE -> "Import the movie first"
    Blocker.NO_PICKED_SCENES -> "Pick at least one scene"
    Blocker.NO_SCRIPT_SOURCE -> "Type a summary or import subtitles (.srt) first"
    Blocker.NO_SCRIPT -> "Write the script first"
    Blocker.SCRIPT_OUTDATED -> "The picked scenes changed: write the script again"
    Blocker.BLANK_SEGMENT -> "Every picked scene needs narration text"
    Blocker.SCRIPT_NOT_APPROVED -> "Approve the script first"
    Blocker.NO_VOICE -> "Record or speak at least one scene's narration"
}

fun StoryboardResult.message(): String? = when (this) {
    StoryboardResult.Done -> null
    is StoryboardResult.Blocked -> blockers.joinToString(" · ") { it.text() }
    is StoryboardResult.Failed -> message
}
