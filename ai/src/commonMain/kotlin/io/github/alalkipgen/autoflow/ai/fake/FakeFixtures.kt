package io.github.alalkipgen.autoflow.ai.fake

import io.github.alalkipgen.autoflow.core.hash.Sha256

/** Deterministic seed for fakes: first 8 bytes of SHA-256 over the NUL-joined [parts]. */
internal fun fakeSeed(vararg parts: String): Long =
    Sha256.digest(parts.joinToString("\u0000").encodeToByteArray())
        .take(8).fold(0L) { acc, b -> (acc shl 8) or (b.toLong() and 0xff) }

/** Fixture text shared by the fake providers: Burmese for `*My` fields and `lang = "my"`, English otherwise. */
internal object FakeFixtures {
    val BURMESE = listOf(
        "မိုးရွာနေတဲ့ ညမှာ တစ်ယောက်တည်း",
        "လမ်းမီးတွေအောက်မှာ ဖြည်းဖြည်းလျှောက်နေတယ်",
        "ပြတင်းပေါက်ကနေ ငေးကြည့်နေတယ်",
        "အမှတ်တရ ဓာတ်ပုံဟောင်းကို ကိုင်ထားတယ်",
        "မနက်ခင်း နေရောင်ထဲမှာ ပြုံးနေတယ်",
    )
    val ENGLISH = listOf(
        "young woman walking alone in the rain",
        "neon-lit street at night",
        "slow push-in, shallow depth of field",
        "warm tungsten light against cold blue shadows",
        "melancholic, hopeful",
        "old photograph held in trembling hands",
    )

    fun pool(lang: String): List<String> = if (lang.lowercase().startsWith("my")) BURMESE else ENGLISH
}
