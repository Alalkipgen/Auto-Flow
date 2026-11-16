package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import io.github.alalkipgen.autoflow.ui.settings.AppSettings
import kotlinx.coroutines.flow.StateFlow

/** UI only. Codes are stable app settings, never a project/content/voice locale. */
enum class UiLanguage(val code: String, val nativeName: String) {
    ENGLISH("en", "English"), BURMESE("my", "မြန်မာ");
    companion object { fun fromCode(code: String?) = entries.firstOrNull { it.code == code } ?: ENGLISH }
}

/** Immutable scoped value, not mutable global language state. Reads invalidate visible composition. */
val LocalUiLanguage = staticCompositionLocalOf { UiLanguage.ENGLISH }

@Composable
fun AppLanguage(settings: StateFlow<AppSettings>, content: @Composable () -> Unit) {
    val state by settings.collectAsState()
    CompositionLocalProvider(LocalUiLanguage provides UiLanguage.fromCode(state.uiLanguage), content = content)
}

/** Single keyed English/Burmese catalog; IDs are never serialized into project data. */
enum class UiText(val en: String, val my: String?) {
    APPROVE("Approve", "အတည်ပြုမယ်"),
    EDIT("Edit", "ပြင်မယ်"),
    REGENERATE("Regenerate", "ပြန်ထုတ်မယ်"),
    COPY_PROMPT("Copy prompt", "Prompt ကူးမယ်"),
    NEEDS_FLOW("Needs Flow", "Flow မှာ လုပ်ရန်"),
    STALE("Needs regeneration", "ပြန်ထုတ်ရန် လိုသည်"),
    ACCEPT_VIDEO("Accept video", "ဗွီဒီယို လက်ခံမယ်"),
    APPROVED("Approved", "အတည်ပြုပြီး"),
    ACCEPTED("Accepted", "လက်ခံပြီး"),
    CANCEL("Cancel", "ပယ်ဖျက်မယ်"),
    BACK("← Back", "← နောက်သို့"),
    START("Start", "စမယ်"),
    OPEN("Open", "ဖွင့်မယ်"),
    SAVE("Save", "သိမ်းမယ်"),
    DELETE("Delete", "ဖျက်မယ်"),
    APPLY("Apply", "ထည့်သုံးမယ်"),
    RETRY("Retry", "ထပ်စမ်းမယ်"),
    SETTINGS("⚙ Settings", "⚙ ဆက်တင်"),
    PROJECTS_BACK("← Projects", "← ပရောဂျက်များ"),
    NO_VOICE("No voice yet", "အသံ မရှိသေး"),
    IMPORTED("Imported", "ထည့်ပြီး"),
    DRAFT("Draft", "မူကြမ်း"),
    MINUTES("{n} min", "{n} မိနစ်"),
    HOME("Home", "ပင်မ"),
    PROJECTS("Projects", "ပရောဂျက်များ"),
    LIBRARY("Library", "စာကြည့်တိုက်"),
    JOBS("Jobs", "အလုပ်များ"),
    SOURCE("Source", "ရင်းမြစ်"),
    STORY("Story", "ဇာတ်လမ်း"),
    VOICE("Voice", "အသံ"),
    STYLE("Style", "ပုံစံ"),
    EXPORT("Export", "ထုတ်ယူ"),
    SETTINGS_TITLE("Settings", "ဆက်တင်"),
    LANGUAGE("Language", "ဘာသာစကား"),
    LANGUAGE_HINT("Choose the app UI language. Project content and voices do not change.", "App UI ဘာသာစကား ရွေးပါ။ ပရောဂျက်စာသားနှင့် အသံများ မပြောင်းပါ။"),
    CLASSIC_RECAP("Classic Recap", "Recap ပုံစံဟောင်း"),
    INPUT("Input", "ထည့်သွင်း"),
    SHOT_SHEET("Shot sheet", "Shot စာရွက်"),
    INBOX("Inbox", "ဝင်စာ"),
    CLASSIC_STEPS("Classic steps", "အဆင့်ဟောင်းများ"),
    STORYBOARD("Storyboard", "Storyboard"),
    SHOTS("Shots", "Shot များ"),
    REFERENCES("References", "Reference ပုံများ"),
    FLOW_HANDOFF("Flow Handoff", "Flow သို့ ပို့ရန်"),
    PREVIOUS_STEP("← Previous step", "← ယခင်အဆင့်"),
    NEXT_STEP("Next: {step} →", "နောက်အဆင့်: {step} →"),
    ;
    fun text(language: UiLanguage, vararg args: Pair<String, Any?>): String = resolveText(en, my, language, args.toMap())
}

private val placeholders = Regex("\\{([A-Za-z][A-Za-z0-9_]*)\\}")
fun textPlaceholders(template: String): Set<String> = placeholders.findAll(template).map { it.groupValues[1] }.toSet()

/** Missing/invalid translation falls back to English. Substitute once, never reinterpret user content. */
internal fun resolveText(en: String, my: String?, language: UiLanguage, args: Map<String, Any?> = emptyMap()): String {
    val template = if (language == UiLanguage.BURMESE && !my.isNullOrBlank() && textPlaceholders(en) == textPlaceholders(my)) my else en
    return placeholders.replace(template) { match -> args[match.groupValues[1]]?.toString() ?: match.value }
}

@Composable
fun tr(key: UiText, vararg args: Pair<String, Any?>): String = key.text(LocalUiLanguage.current, *args)
