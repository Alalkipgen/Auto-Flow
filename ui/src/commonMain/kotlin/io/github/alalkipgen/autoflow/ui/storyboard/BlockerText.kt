package io.github.alalkipgen.autoflow.ui.storyboard

import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.core.workflow.Blocker

/** What the user must do for each WorkflowRules blocker. The rule itself lives only in `:core`. */
fun Blocker.text(language: UiLanguage = UiLanguage.ENGLISH): String = when (this) {
    Blocker.EMPTY_LYRICS -> UiText.BLOCKER_EMPTY_LYRICS.text(language)
    Blocker.NO_DURATION -> UiText.BLOCKER_NO_DURATION.text(language)
    Blocker.INVALID_CLIP_LENGTH -> UiText.BLOCKER_INVALID_CLIP_LENGTH.text(language)
    Blocker.NO_SPEC -> UiText.BLOCKER_NO_SPEC.text(language)
    Blocker.STORYBOARD_NOT_APPROVED -> UiText.BLOCKER_STORYBOARD_NOT_APPROVED.text(language)
    Blocker.NO_PROMPT -> UiText.BLOCKER_NO_PROMPT.text(language)
    Blocker.PROMPT_NOT_APPROVED -> UiText.BLOCKER_PROMPT_NOT_APPROVED.text(language)
    Blocker.PROMPT_STALE -> UiText.BLOCKER_PROMPT_STALE.text(language)
    Blocker.NO_CHOSEN_REF -> UiText.BLOCKER_NO_CHOSEN_REF.text(language)
    Blocker.REF_NOT_APPROVED -> UiText.BLOCKER_REF_NOT_APPROVED.text(language)
    Blocker.NO_VIDEOS -> UiText.BLOCKER_NO_VIDEOS.text(language)
    Blocker.NO_ACCEPTED_VIDEOS -> UiText.BLOCKER_NO_ACCEPTED_VIDEOS.text(language)
    Blocker.NO_RIGHTS -> UiText.BLOCKER_NO_RIGHTS.text(language)
    Blocker.NO_MOVIE -> UiText.BLOCKER_NO_MOVIE.text(language)
    Blocker.NO_PICKED_SCENES -> UiText.BLOCKER_NO_PICKED_SCENES.text(language)
    Blocker.NO_SCRIPT_SOURCE -> UiText.BLOCKER_NO_SCRIPT_SOURCE.text(language)
    Blocker.NO_SCRIPT -> UiText.BLOCKER_NO_SCRIPT.text(language)
    Blocker.SCRIPT_OUTDATED -> UiText.BLOCKER_SCRIPT_OUTDATED.text(language)
    Blocker.BLANK_SEGMENT -> UiText.BLOCKER_BLANK_SEGMENT.text(language)
    Blocker.SCRIPT_NOT_APPROVED -> UiText.BLOCKER_SCRIPT_NOT_APPROVED.text(language)
    Blocker.NO_VOICE -> UiText.BLOCKER_NO_VOICE.text(language)
}

fun StoryboardResult.message(language: UiLanguage = UiLanguage.ENGLISH): String? = when (this) {
    StoryboardResult.Done -> null
    is StoryboardResult.Blocked -> blockers.joinToString(" · ") { it.text(language) }
    is StoryboardResult.Failed -> message
}
