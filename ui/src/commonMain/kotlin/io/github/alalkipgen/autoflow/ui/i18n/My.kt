package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.runtime.Composable

/**
 * Burmese UI words shared by the Studio v2 screens (5B-13, D-41, DESIGN.md §6). Labels only: project files, job
 * kinds, screen keys and test tags stay English, so a wording change never touches saved data or routing.
 * The seven DESIGN.md §6 strings are kept exactly as written there.
 */
object My {
    // DESIGN.md §6 — keep these exact.
    val APPROVE: String get() = UiText.APPROVE.my!!
    val EDIT: String get() = UiText.EDIT.my!!
    val REGENERATE: String get() = UiText.REGENERATE.my!!
    val COPY_PROMPT: String get() = UiText.COPY_PROMPT.my!!
    val NEEDS_FLOW: String get() = UiText.NEEDS_FLOW.my!!
    val STALE: String get() = UiText.STALE.my!!
    val ACCEPT_VIDEO: String get() = UiText.ACCEPT_VIDEO.my!!

    // Shared actions.
    val APPROVED: String get() = UiText.APPROVED.my!!
    val ACCEPTED: String get() = UiText.ACCEPTED.my!!
    val CANCEL: String get() = UiText.CANCEL.my!!
    val BACK: String get() = UiText.BACK.my!!
    val START: String get() = UiText.START.my!!
    val OPEN: String get() = UiText.OPEN.my!!
    val SAVE: String get() = UiText.SAVE.my!!
    val DELETE: String get() = UiText.DELETE.my!!
    val APPLY: String get() = UiText.APPLY.my!!
    val RETRY: String get() = UiText.RETRY.my!!
    val SETTINGS: String get() = UiText.SETTINGS.my!!
    val PROJECTS_BACK: String get() = UiText.PROJECTS_BACK.my!!
    val NO_VOICE: String get() = UiText.NO_VOICE.my!!
    val IMPORTED: String get() = UiText.IMPORTED.my!!
    val DRAFT: String get() = UiText.DRAFT.my!!
    @Composable
    fun minutes(n: Long): String = tr(UiText.MINUTES, "n" to n)

    /**
     * [text] with a zero-width space between Burmese syllables, so a narrow label (nav tab) wraps between syllables
     * instead of inside one when a word is wider than its slot at a large font (DESIGN.md §7 Accessibility).
     */
    fun breakSyllables(text: String): String = buildString(text.length + 8) {
        text.forEachIndexed { i, c ->
            if (i > 0 && syllableStart(text, i) && isBurmese(text[i - 1])) append('\u200B')
            append(c)
        }
    }

    private fun isBurmese(c: Char) = c in '\u1000'..'\u109F'

    /** A consonant or independent vowel that starts a syllable: not stacked under one, not a final with asat. */
    private fun syllableStart(text: String, i: Int): Boolean {
        val c = text[i]
        if (c !in '\u1000'..'\u102A' && c != '\u103F') return false
        if (text[i - 1] == '\u1039') return false
        var j = i + 1
        while (j < text.length && isMark(text[j])) {
            if (text[j] == '\u103A' || text[j] == '\u1039') return false
            if (text[j] in '\u102B'..'\u1035') return true // a vowel: a later asat belongs to it (ပေါ်, ကော်)
            j++
        }
        return true
    }

    private fun isMark(c: Char) =
        isBurmese(c) && (c.category == CharCategory.NON_SPACING_MARK || c.category == CharCategory.COMBINING_SPACING_MARK)

    /** A project screen chip: the key (routing, saved nothing) stays English; the chip shows Burmese. */
    @Composable
    fun screen(key: String): String = SCREENS[key]?.let { tr(it) } ?: key

    fun screen(key: String, language: UiLanguage): String = SCREENS[key]?.text(language) ?: key

    private val SCREENS = mapOf(
        "Dashboard" to UiText.DASHBOARD,
        "Project" to UiText.PROJECT_SECTION,
        "Mode" to UiText.MODE_SECTION,
        "System" to UiText.SYSTEM_SECTION,
        "Timeline" to UiText.TIMELINE,
        "Music Video" to UiText.MUSIC_VIDEO,
        "Movie Recap" to UiText.MOVIE_RECAP,
        "Subtitles" to UiText.V3_SUBTITLES,
        "Settings" to UiText.SETTINGS_TITLE,
        "Projects" to UiText.PROJECTS,
        "Home" to UiText.HOME,
        "Library" to UiText.LIBRARY,
        "Jobs" to UiText.JOBS,
        "More" to UiText.MORE,
        "Prompts" to UiText.PROMPTS,
        "Flow Video" to UiText.FLOW_VIDEO,
        "Assembly" to UiText.ASSEMBLY,

        "Voice" to UiText.VOICE,
        "Script" to UiText.STORY,
        "Classic Recap" to UiText.CLASSIC_RECAP,
        "Export" to UiText.EXPORT,
        "Input" to UiText.INPUT,
        "Shot sheet" to UiText.SHOT_SHEET,
        "Inbox" to UiText.INBOX,
        "Classic steps" to UiText.CLASSIC_STEPS,
        "Storyboard" to UiText.STORYBOARD,
        "Shots" to UiText.SHOTS,
        "References" to UiText.REFERENCES,
        "Flow Handoff" to UiText.FLOW_HANDOFF,
    )
}
