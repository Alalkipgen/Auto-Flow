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
    HOME_CREATE("Create", "အသစ်လုပ်မယ်"),
    HOME_NEW_RECAP("🎬 New Movie Recap", "🎬 Movie Recap အသစ်"),
    HOME_NEW_MV("🎵 New Music Video", "🎵 Music Video အသစ်"),
    HOME_RECENT("Recent projects", "မကြာသေးမီက ပရောဂျက်များ"),
    HOME_EMPTY("No projects yet. Create one to see it here, or view all in Projects.", "ပရောဂျက် မရှိသေးပါ။ အသစ်လုပ်ရင် ဒီမှာ ပေါ်မယ်၊ အားလုံးကို ပရောဂျက်များ မှာ ကြည့်ပါ။"),
    HOME_QUICK("⚡ Quick Recap", "⚡ အမြန် Recap"),
    HOME_QUICK_HINT("Create a movie recap in one flow, pausing for your approval.", "ရုပ်ရှင်တစ်ကားကို တစ်ချက်နှိပ်ရုံနဲ့ recap လုပ်မယ် — သင် အတည်ပြုဖို့ပဲ ရပ်မယ်။"),
    HOME_QUICK_SOURCE("The movie needs subtitles or a transcript.", "ရုပ်ရှင်ထဲမှာ စာတန်းထိုး (subtitle) သို့မဟုတ် transcript ပါရမယ်။"),
    HOME_QUICK_START("Start Quick Recap…", "အမြန် Recap စမယ်…"),
    LENGTH("Length", "ကြာချိန်"),
    HOME_NEXT_STYLE("Next: Style →", "နောက်အဆင့်: ပုံစံ →"),
    HOME_CONFIRM("Quick Recap · {minLabel} · {styleLabelstyle}", "အမြန် Recap · {minLabel} · {styleLabelstyle}"),
    HOME_CONFIRM_HINT("Find scenes, let AI pick scenes and Gemini draft the script, then stop at Story for you to {APPROVE} or {EDIT}.", "ပြကွက်တွေ ရှာမယ်၊ AI က ပြကွက် ရွေးမယ်၊ Gemini က script ရေးမယ်။ ဇာတ်လမ်း စစ်ချိန်မှာ ရပ်မယ် — သင် {APPROVE} သို့မဟုတ် {EDIT}။"),
    HOME_BACKGROUND("TTS and rendering then continue as background jobs.", "ပြီးရင် TTS နဲ့ render က နောက်ကွယ်မှာ အလုပ်များ အဖြစ် ဆက်လုပ်မယ်။"),
    HOME_ACTIVE("⚡ Active jobs", "⚡ လုပ်နေသော အလုပ်များ"),
    JOB_RUNNING("Running", "လုပ်နေသည်"),
    JOB_QUEUED("Queued", "စောင့်နေသည်"),
    JOB_PAUSED("Paused", "ခဏရပ်ထားသည်"),
    JOB_FAILED("Failed", "မအောင်မြင်ပါ"),
    HOME_JOB_ROW("[{stateLabel}] {name} · {value3} · {itemsLeft} left{etaText}", "[{stateLabel}] {name} · {value3} · {itemsLeft} ခု ကျန်{etaText}"),
    STYLE_PLAIN("Plain", "ရိုးရိုး"),
    STYLE_COMMENTARY("Commentary", "မှတ်ချက်ပေး"),
    LIBRARY_COPY_HINT("{APPLY} copies into the current project. Projects never depend on the original Library file.", "{APPLY} = လက်ရှိ project ထဲကို ကူးထည့်တယ်။ Project တွေက စာကြည့်တိုက်ထဲက ဖိုင်ကို တိုက်ရိုက် မသုံးပါ။"),
    LIBRARY_KITS("Brand Kits ({size})", "Brand Kit များ ({size})"),
    LIBRARY_SAVE_KIT("💼 Save current project as Brand Kit…", "💼 လက်ရှိ project ကို Brand Kit အဖြစ် သိမ်းမယ်…"),
    LIBRARY_KIT_NAME("Brand Kit name", "Brand Kit အမည်"),
    LIBRARY_KITS_EMPTY("No Brand Kits saved. Use the button above to save the current project settings.", "Brand Kit မသိမ်းရသေးပါ။ အပေါ်က ခလုတ်နဲ့ လက်ရှိ project ရဲ့ ဆက်တင်တွေကို သိမ်းပါ။"),
    LIBRARY_DELETE_KIT("Delete \"{name}\"?", "\"{name}\" ကို ဖျက်မလား?"),
    LIBRARY_DELETE_HINT("This removes the Brand Kit from the Library.", "ဒီ Brand Kit ကို စာကြည့်တိုက်ထဲက ဖယ်ရှားမယ်။"),
    LIBRARY_COLOURS("Colours: {joinToString}", "အရောင်များ: {joinToString}"),
    LIBRARY_CAPTION_SAVED("Caption style: saved", "စာတန်းပုံစံ: သိမ်းထား"),
    LIBRARY_APPLY_CURRENT("{APPLY} to current project", "လက်ရှိ project မှာ {APPLY}"),
    LIBRARY_DELETE_HOLD("Long-press to delete", "ဖျက်ရန် ကြာကြာ ဖိထားပါ"),
    LIBRARY_VOICES("Voices ({size})", "အသံများ ({size})"),
    LIBRARY_VOICES_EMPTY("No saved voices. Lock a voice in Movie Recap → Voice to save it here.", "သိမ်းထားသော အသံ မရှိသေးပါ။ Movie Recap → အသံ မှာ အသံတစ်ခု သော့ခတ်ရင် ဒီမှာ သိမ်းမယ်။"),
    LIBRARY_LEXICON("Lexicon ({size} entries)", "အသံထွက် အဘိဓာန် ({size} ခု)"),
    LIBRARY_LEXICON_HINT("Word substitutions apply before TTS; the longest written form takes priority.", "TTS မပြောခင် စကားလုံးလိုက် အစားထိုးတယ်။ အရှည်ဆုံး ရေးပုံက ဦးစားပေးရတယ်။"),
    LIBRARY_LEXICON_EMPTY("No lexicon entries. Add them in Movie Recap → Script → Lexicon.", "အဘိဓာန်ထဲ ဘာမှ မရှိသေးပါ။ Movie Recap → Script → Lexicon မှာ ထည့်ပါ။"),
    LIBRARY_MORE("… {size10} more", "… နောက်ထပ် {size10} ခု"),
    LIBRARY_MUSIC("Music ({size})", "တေးဂီတ ({size})"),
    LIBRARY_MUSIC_HINT("{APPLY} copies into the project. Use as BGM also connects it to Recap audio.", "{APPLY} = project ထဲကို ကူးထည့်တယ်။ နောက်ခံတေး အဖြစ်သုံး = Recap အသံထဲမှာပါ ချိတ်ပေးတယ်။"),
    LIBRARY_MUSIC_EMPTY("No music yet.", "တေးဂီတ မရှိသေးပါ။"),
    LIBRARY_USE_BGM("Use as BGM", "နောက်ခံတေး အဖြစ်သုံးမယ်"),
    LIBRARY_FONTS("Fonts ({size})", "ဖောင့်များ ({size})"),
    LIBRARY_FONTS_EMPTY("No custom fonts. Add them in Settings → Fonts.", "ကိုယ်ပိုင် ဖောင့် မရှိသေးပါ။ ဆက်တင် → Fonts မှာ ထည့်ပါ။"),
    LIBRARY_PRESETS("Presets ({size})", "Preset များ ({size})"),
    LIBRARY_SAVE_PRESET("Save caption style as preset…", "စာတန်းပုံစံကို preset အဖြစ် သိမ်းမယ်…"),
    LIBRARY_PRESET_NAME("Preset name", "Preset အမည်"),
    LIBRARY_PRESETS_EMPTY("No saved presets.", "Preset မသိမ်းရသေးပါ။"),
    JOB_PREVIEW("Preview 540p", "နမူနာ 540p"),
    JOB_RENDER_QUEUED("Render — {kind} (queued)", "Render — {kind} (စောင့်နေသည်)"),
    JOB_RENDER_DONE("Render — {kind} done", "Render — {kind} ပြီးပြီ"),
    JOB_RENDER_FAILED("Render — {kind} failed", "Render — {kind} မအောင်မြင်ပါ"),
    JOB_RENDER_CANCELLED("Render — {kind} cancelled", "Render — {kind} ပယ်ဖျက်ပြီး"),
    JOB_ITEMS_LEFT("{size} left", "{size} ခု ကျန်"),
    JOB_TTS_ALL("Speak all", "အားလုံး အသံထုတ်"),
    JOB_TRANSCRIBE("Transcribe", "စာသားပြောင်း"),
    JOB_QUICK_RECAP("Quick Recap", "အမြန် Recap"),
    JOB_DONE("Done", "ပြီးပြီ"),
    JOB_CANCELLED("Cancelled", "ပယ်ဖျက်ပြီး"),
    JOBS_EMPTY_FIRST("Nothing running. Scene detection, scripts, voices, storyboards and renders appear here; ", "ဘာမှ လုပ်မနေပါ။ ပြကွက်ရှာခြင်း၊ script ရေးခြင်း၊ အသံ၊ storyboard နဲ့ render တွေ လုပ်နေချိန် ဒီမှာ ပေါ်မယ်၊ "),
    JOBS_EMPTY_LAST("they continue when you switch screens.", "တခြားစာမျက်နှာ ပြောင်းလည်း ဆက်လုပ်နေမယ်။"),
    SHELL_EXPORT_HINT("Pre-flight → render → save / share", "မထုတ်ခင် စစ်ဆေး → render → သိမ်း / မျှဝေ"),
    SHELL_CLASSIC_HINT("The Shot sheet includes these steps for each shot. Classic screens remain available for one release.", "Shot စာရွက်က shot တစ်ခုချင်းစီအတွက် ဒီအဆင့်တွေ အားလုံးကို လုပ်ပေးတယ်။ စာမျက်နှာဟောင်းတွေကို နောက်ထုတ်ဝေမှု တစ်ကြိမ်အထိ ထားမယ်။"),
    EXIT_TITLE("Exit Auto Flow?", "Auto Flow ကို ပိတ်မလား?"),
    EXIT_HINT("Renders continue in the background.", "Render တွေ နောက်ကွယ်မှာ ဆက်လုပ်နေမယ်။"),
    EXIT("Exit", "ပိတ်မယ်"),
    STAY("Stay", "ဆက်နေမယ်"),
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
