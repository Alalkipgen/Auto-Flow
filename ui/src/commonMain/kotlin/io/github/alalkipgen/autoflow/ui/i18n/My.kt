package io.github.alalkipgen.autoflow.ui.i18n

/**
 * Burmese UI words shared by the Studio v2 screens (5B-13, D-41, DESIGN.md §6). Labels only: project files, job
 * kinds, screen keys and test tags stay English, so a wording change never touches saved data or routing.
 * The seven DESIGN.md §6 strings are kept exactly as written there.
 */
object My {
    // DESIGN.md §6 — keep these exact.
    const val APPROVE = "အတည်ပြုမယ်"
    const val EDIT = "ပြင်မယ်"
    const val REGENERATE = "ပြန်ထုတ်မယ်"
    const val COPY_PROMPT = "Prompt ကူးမယ်"
    const val NEEDS_FLOW = "Flow မှာ လုပ်ရန်"
    const val STALE = "ပြန်ထုတ်ရန် လိုသည်"
    const val ACCEPT_VIDEO = "ဗွီဒီယို လက်ခံမယ်"

    // Shared actions.
    const val APPROVED = "အတည်ပြုပြီး"
    const val ACCEPTED = "လက်ခံပြီး"
    const val CANCEL = "ပယ်ဖျက်မယ်"
    const val BACK = "← နောက်သို့"
    const val START = "စမယ်"
    const val OPEN = "ဖွင့်မယ်"
    const val SAVE = "သိမ်းမယ်"
    const val DELETE = "ဖျက်မယ်"
    const val APPLY = "ထည့်သုံးမယ်"
    const val RETRY = "ထပ်စမ်းမယ်"
    const val SETTINGS = "⚙ ဆက်တင်"
    const val PROJECTS_BACK = "← ပရောဂျက်များ"
    const val NO_VOICE = "အသံ မရှိသေး"
    const val IMPORTED = "ထည့်ပြီး"
    const val DRAFT = "မူကြမ်း"
    fun minutes(n: Long): String = "$n မိနစ်"

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
    fun screen(key: String): String = SCREENS[key] ?: key

    private val SCREENS = mapOf(
        "Recap" to "Recap",
        "Classic Recap" to "Recap ပုံစံဟောင်း",
        "Export" to "ထုတ်ယူ",
        "Input" to "ထည့်သွင်း",
        "Shot sheet" to "Shot စာရွက်",
        "Inbox" to "ဝင်စာ",
        "Classic steps" to "အဆင့်ဟောင်းများ",
        "Storyboard" to "Storyboard",
        "Shots" to "Shot များ",
        "References" to "Reference ပုံများ",
        "Flow Handoff" to "Flow သို့ ပို့ရန်",
    )
}
