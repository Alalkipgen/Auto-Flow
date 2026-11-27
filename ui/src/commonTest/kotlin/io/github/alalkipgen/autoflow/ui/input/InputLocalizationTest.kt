package io.github.alalkipgen.autoflow.ui.input

import io.github.alalkipgen.autoflow.core.lyrics.LyricTimings
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import kotlin.test.*

class InputLocalizationTest {
    @Test fun confirmAndTimingSummariesHaveExplicitLocales() {
        val state = InputUiState("မိုး", settings = ProjectSettings(durationMs = 30_000, clipMs = 8_000), hasStoryboard = true)
        assertTrue(confirmText(state).contains("about 4 shots of 8s"))
        assertTrue(confirmText(state).contains("older version"))
        assertTrue(confirmText(state, UiLanguage.BURMESE).contains("shot 4"))
        assertTrue(confirmText(state, UiLanguage.BURMESE).contains("ဗားရှင်းဟောင်း"))
        val synced = LyricSyncUiState(status = TimingStatus.SYNCED, savedLines = 2, savedSource = LyricTimings.SOURCE_GEMINI)
        assertEquals("2 lines synced (suggested by Gemini): Export shows each line when it is sung.", statusText(synced))
        assertTrue(statusText(synced, UiLanguage.BURMESE).contains("စာကြောင်း 2 ကြောင်း"))
        assertEquals("Owner {n} prompt", videoStyleLabel("Owner {n} prompt", UiLanguage.BURMESE))
        assertEquals("Cinematic", videoStyleLabel("Cinematic", UiLanguage.ENGLISH))
        assertEquals("ရုပ်ရှင်ဆန်သော", videoStyleLabel("Cinematic", UiLanguage.BURMESE))
    }
}
