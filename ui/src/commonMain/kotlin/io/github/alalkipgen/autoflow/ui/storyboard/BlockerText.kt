package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.workflow.Blocker

/** What the user must do for each WorkflowRules blocker. The rule itself lives only in `:core`. */
fun Blocker.text(language: UiLanguage = UiLanguage.ENGLISH): String = textKey().text(language)
fun Blocker.notice(): UiNotice = UiNotice(textKey())
private fun Blocker.textKey(): UiText = when (this) {
    Blocker.EMPTY_LYRICS -> UiText.BLOCKER_EMPTY_LYRICS
    Blocker.NO_DURATION -> UiText.BLOCKER_NO_DURATION
    Blocker.INVALID_CLIP_LENGTH -> UiText.BLOCKER_INVALID_CLIP_LENGTH
    Blocker.NO_SPEC -> UiText.BLOCKER_NO_SPEC
    Blocker.STORYBOARD_NOT_APPROVED -> UiText.BLOCKER_STORYBOARD_NOT_APPROVED
    Blocker.NO_PROMPT -> UiText.BLOCKER_NO_PROMPT
    Blocker.PROMPT_NOT_APPROVED -> UiText.BLOCKER_PROMPT_NOT_APPROVED
    Blocker.PROMPT_STALE -> UiText.BLOCKER_PROMPT_STALE
    Blocker.NO_CHOSEN_REF -> UiText.BLOCKER_NO_CHOSEN_REF
    Blocker.REF_NOT_APPROVED -> UiText.BLOCKER_REF_NOT_APPROVED
    Blocker.NO_VIDEOS -> UiText.BLOCKER_NO_VIDEOS
    Blocker.NO_ACCEPTED_VIDEOS -> UiText.BLOCKER_NO_ACCEPTED_VIDEOS
    Blocker.NO_RIGHTS -> UiText.BLOCKER_NO_RIGHTS
    Blocker.NO_MOVIE -> UiText.BLOCKER_NO_MOVIE
    Blocker.NO_PICKED_SCENES -> UiText.BLOCKER_NO_PICKED_SCENES
    Blocker.NO_SCRIPT_SOURCE -> UiText.BLOCKER_NO_SCRIPT_SOURCE
    Blocker.NO_SCRIPT -> UiText.BLOCKER_NO_SCRIPT
    Blocker.SCRIPT_OUTDATED -> UiText.BLOCKER_SCRIPT_OUTDATED
    Blocker.BLANK_SEGMENT -> UiText.BLOCKER_BLANK_SEGMENT
    Blocker.SCRIPT_NOT_APPROVED -> UiText.BLOCKER_SCRIPT_NOT_APPROVED
    Blocker.NO_VOICE -> UiText.BLOCKER_NO_VOICE
}

fun StoryboardResult.message(language: UiLanguage = UiLanguage.ENGLISH): String? = when (this) {
    StoryboardResult.Done -> null
    is StoryboardResult.Blocked -> blockers.joinToString(" · ") { it.text(language) }
    is StoryboardResult.Failed -> notice?.text(language) ?: UiText.RESULT_DETAIL.text(language, "detail" to message)
}
