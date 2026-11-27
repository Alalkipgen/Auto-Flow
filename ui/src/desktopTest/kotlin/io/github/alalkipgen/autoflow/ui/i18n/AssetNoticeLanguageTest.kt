package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.FilePicker
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.inbox.InboxModel
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.references.*
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import kotlin.test.*

/** Already-visible results retranslate; a language switch never imports or rewrites project content. */
@OptIn(ExperimentalTestApi::class)
class AssetNoticeLanguageTest {
    @Test fun portrait100() = matrix(412, 1f)
    @Test fun portrait130() = matrix(412, 1.3f)
    @Test fun portrait200() = matrix(412, 2f)
    @Test fun wide100() = matrix(840, 1f)
    @Test fun wide130() = matrix(840, 1.3f)
    @Test fun wide200() = matrix(840, 2f)

    private fun matrix(width: Int, scale: Float) {
        for (bundled in listOf(true, false)) case(width, scale, bundled)
    }

    private fun case(width: Int, scale: Float, bundled: Boolean) = runDesktopComposeUiTest(width = width, height = 760) {
        val fs = InMemoryFileSystem()
        val store = ProjectStore(fs)
        val current = CurrentProject()
        val session = ProjectSession(fs, store, ProjectIndex(fs, "/app/projects.json"), current)
        val project = (store.create("/p", "မိုးရွာသောနေ့", Mode.MUSIC_VIDEO) as CoreResult.Ok).value
        current.set(OpenProject("/p", project))
        assertIs<CoreResult.Ok<*>>(session.save(project.copy(shots = listOf(Shot(1, 0, 8000, lyricsMy = "မြန်မာစာသား မပြောင်းရ")))))
        val original = fs.read("/p/project.json")
        val settings = SettingsStore(fs, "/app/settings.json")
        val titles = mutableListOf<String>()
        val picker = object : FilePicker by FakePicker(unsupported = true) {
            override suspend fun pickFiles(title: String, extensions: Set<String>, multiple: Boolean, startDir: String?): PlatformResult<List<String>> {
                titles += title
                return PlatformResult.Unsupported("no dialogs")
            }
        }
        val model = ReferencesModel(session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, picker,
            uiLanguage = { UiLanguage.fromCode(settings.state.value.uiLanguage) })
        setContent {
            CompositionLocalProvider(LocalDensity provides Density(1f, scale),
                LocalUiFontFamily provides if (bundled) BurmeseTestFont.family else FontFamily.Default) {
                AppLanguage(settings.state) { ReferencesScreen(model, readBytes = { error("cancelled picker must not read content") }) }
            }
        }
        onNodeWithText(UiText.IMPORT_IMAGE.en).performScrollTo().assertIsDisplayed().performMouseInput { click() }
        for (language in UiLanguage.entries + UiLanguage.ENGLISH) {
            settings.update { it.copy(uiLanguage = language.code) }
            waitForIdle()
            onNodeWithText(UiText.INPUT_NO_PICKER.text(language)).performScrollTo().assertIsDisplayed()
            assertEquals(emptyList<String>(), textFitProblems(width, scale), "$width/$scale/${language.code}/$bundled")
            onNodeWithText(UiText.IMPORT_IMAGE.text(language)).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            waitForIdle()
            assertEquals(UiText.PICK_REFERENCE_SHOT.text(language, "n" to 1), titles.last())
            assertEquals(language.code, SettingsStore(fs, "/app/settings.json").state.value.uiLanguage)
        }
        assertContentEquals(original, fs.read("/p/project.json"))
        assertEquals("မြန်မာစာသား မပြောင်းရ", session.open!!.project.shots.single().lyricsMy)
    }

    @Test fun inboxReturnsTheCurrentTypedResultEvenWithoutAnOpenProject() {
        val fs = InMemoryFileSystem()
        val session = ProjectSession(fs, ProjectStore(fs), ProjectIndex(fs, "/app/projects.json"), CurrentProject())
        val state = InboxModel(session, fs).refresh()
        assertEquals(UiText.NO_PROJECT_OPEN.en, state.message)
        assertEquals(UiNotice(UiText.NO_PROJECT_OPEN), state.notice)
    }
}