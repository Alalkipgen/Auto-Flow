package io.github.alalkipgen.autoflow.ui.i18n

import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.platform.PlatformResult
import kotlin.test.*

class UiLanguageTest {
    @Test fun oldSettingsDefaultToEnglishWithoutLosingOtherPreferences() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("/app")
        fs.writeDurably("/app/settings.json", """{"schemaVersion":1,"useFakeProviders":false,"featureOverrides":{"studioV2":true}}""".encodeToByteArray())
        val store = SettingsStore(fs, "/app/settings.json")
        assertEquals("en", store.state.value.uiLanguage)
        assertEquals(UiLanguage.ENGLISH, UiLanguage.fromCode(store.state.value.uiLanguage))
        assertFalse(store.state.value.useFakeProviders)
        assertEquals(mapOf("studioV2" to true), store.state.value.featureOverrides)
        assertEquals(UiLanguage.ENGLISH, UiLanguage.fromCode("future-language"))
    }

    @Test fun switchingBothWaysPersistsOnlyAppSettingsAndKeepsSharedState() {
        val fs = InMemoryFileSystem()
        fs.createDirectories("/project")
        val project = """{"id":"same-project","name":"မြန်မာ","promptEn":"English {n}","spokenHash":"unchanged"}""".encodeToByteArray()
        fs.writeDurably("/project/project.json", project)
        val store = SettingsStore(fs, "/app/settings.json")
        val firstObserver = store.state
        val secondObserver = store.state
        for (code in listOf("my", "en", "my")) {
            assertIs<PlatformResult.Ok<Unit>>(store.update { it.copy(uiLanguage = code) })
            assertEquals(code, firstObserver.value.uiLanguage)
            assertEquals(code, secondObserver.value.uiLanguage)
            assertEquals(code, SettingsStore(fs, "/app/settings.json").state.value.uiLanguage)
            assertContentEquals(project, fs.read("/project/project.json"))
        }
        assertTrue(fs.log.filter { it.startsWith("write ") || it.startsWith("move ") }.drop(1).all { "/app/settings.json" in it })
    }

    @Test fun catalogHasMatchingNonemptyTranslationsAndPlaceholders() {
        for (key in UiText.entries) {
            assertTrue(key.en.isNotBlank(), key.name)
            assertFalse(key.en.contains('\uFFFD'), key.name)
            assertFalse(key.my?.contains('\uFFFD') == true, key.name)
            assertFalse(key.my.isNullOrBlank(), key.name)
            assertEquals(textPlaceholders(key.en), textPlaceholders(key.my!!), key.name)
        }
    }

    @Test fun missingOrDamagedTranslationFallsBackAndArgumentsAreNotReinterpreted() {
        assertEquals("Hello မြန်မာ", resolveText("Hello {name}", null, UiLanguage.BURMESE, mapOf("name" to "မြန်မာ")))
        assertEquals("Hello X", resolveText("Hello {name}", "မင်္ဂလာ {wrong}", UiLanguage.BURMESE, mapOf("name" to "X")))
        assertEquals("{n} 3", resolveText("{name} {n}", null, UiLanguage.ENGLISH, mapOf("name" to "{n}", "n" to 3)))
        assertEquals("အတည်ပြုမယ်", UiText.APPROVE.text(UiLanguage.BURMESE))
        assertEquals("Approve", UiText.APPROVE.text(UiLanguage.ENGLISH))
    }
    @Test fun computedPreflightLocalizesByReasonWithoutInterpretingOrChangingRawDetails() {
        val raw = "owner supplied diagnostic မြန်မာ {n}"
        val item = io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Item(raw,
            io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Kind.WARNING, "Voice",
            io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Reason.UNVOICED, 3)
        assertEquals("3 scenes without a voice take — they will play with movie sound only.", preflightMessage(item, UiLanguage.ENGLISH))
        assertTrue(preflightMessage(item, UiLanguage.BURMESE).contains("ပြကွက် 3"))
        assertEquals(raw, item.message)
        assertEquals("Voice", item.fix)
        val unknown = item.copy(reason = io.github.alalkipgen.autoflow.core.recap.PreflightCheck.Reason.UNKNOWN)
        assertTrue(preflightMessage(unknown, UiLanguage.BURMESE).endsWith(raw))
        assertEquals("အသံ", My.screen(item.fix!!, UiLanguage.BURMESE))
    }

}
