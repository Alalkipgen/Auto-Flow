package io.github.alalkipgen.autoflow.ui.pack

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.library.LibraryStore
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.SourceVideo
import io.github.alalkipgen.autoflow.core.model.SubtitlesTool
import io.github.alalkipgen.autoflow.core.recap.Lexicon
import io.github.alalkipgen.autoflow.core.recap.LexiconEntry
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.JvmRenderPackages
import io.github.alalkipgen.autoflow.platform.PackageFile
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.export.RecapFrames
import io.github.alalkipgen.autoflow.ui.i18n.UiLanguage
import io.github.alalkipgen.autoflow.ui.i18n.UiText
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import io.github.alalkipgen.autoflow.ui.settings.SettingsStore
import java.io.File
import java.nio.file.Files
import java.util.Base64
import java.util.zip.ZipFile
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/** F-7 (D-96): Render package — the zip, its `render.json` and font, Open package, and the packaged font. */
@OptIn(ExperimentalTestApi::class)
class RenderPackageModelTest {
    private val tmp: File = Files.createTempDirectory("af-render-package").toFile()
    private val root = tmp.path.replace('\\', '/')
    private val fs = JvmFs()
    private val store = ProjectStore(fs)
    private val index = ProjectIndex(fs, "$root/app/projects.json")
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, index, current)
    private val settings = SettingsStore(fs, "$root/app/settings.json")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = "$root/projects/Film.autoflow"
    private val font: ByteArray = Base64.getMimeDecoder().decode(javaClass.getResourceAsStream("/fonts/NotoSansMyanmar-subset.ttf.b64")!!.readBytes())

    @AfterTest
    fun cleanup() {
        scope.cancel()
        tmp.deleteRecursively()
    }

    private fun open(): OpenProject {
        val p = (store.create(dir, "ရုပ်ရှင် Film", Mode.SUBTITLES) as CoreResult.Ok).value
        fs.createDirectories("$dir/source")
        fs.writeDurably("$dir/source/movie.mp4", ByteArray(100) { 1 })
        fs.createDirectories("$dir/exports")
        fs.writeDurably("$dir/exports/old.mp4", ByteArray(10))
        val saved = (store.save(dir, p.copy(subtitles = SubtitlesTool(video = SourceVideo("source/movie.mp4", 100, 2_000, 320, 240, 25.0)))) as CoreResult.Ok).value
        return OpenProject(dir, saved).also(current::set)
    }

    private fun settingsFont() {
        fs.createDirectories("$root/fonts")
        fs.writeDurably("$root/fonts/noto.ttf", font)
        settings.update { it.copy(fontPaths = listOf("$root/fonts/noto.ttf")) }
    }

    private fun model() = RenderPackageModel(session, fs, JvmRenderPackages(), RecapFrames(session, settings, fs),
        lexicon = { Lexicon(listOf(LexiconEntry("AI", "အေအိုင်"))) }, share = null, scope = scope, app = "0.4.2 (test)")

    @Test
    fun packWritesTheChoicesTheFontAndTheFixesIntoExports() = runBlocking {
        open()
        settingsFont()
        val model = model()
        model.pack(RenderPlan(mode = Mode.SUBTITLES, look = "BOXED", text = "BURMESE", lines = 2, length = "LONG"))
        val s = withTimeout(60_000) { model.state.first { it.dir == dir && !it.busy } }
        assertTrue(s.ok, s.notice?.text(UiLanguage.ENGLISH))
        assertEquals(UiText.PACKAGE_SAVED, s.notice?.key)
        val zip = File(assertNotNull(s.zip))
        assertEquals("ရုပ်ရှင်-Film-render-package.zip", zip.name)
        assertEquals("$dir/exports", zip.parentFile.path.replace('\\', '/'))
        ZipFile(zip).use { z ->
            val names = z.entries().toList().map { it.name }
            assertTrue("Film.autoflow/project.json" in names, names.toString())
            assertTrue("Film.autoflow/source/movie.mp4" in names)
            assertFalse(names.any { "/exports/" in it }, "a package never holds itself or other exports")
            val fontEntry = names.single { it.startsWith("Film.autoflow/fonts/") }
            assertContentEquals(font, z.getInputStream(z.getEntry(fontEntry)).readBytes())
            val plan = CoreJson.decodeFromString(RenderPlan.serializer(), z.getInputStream(z.getEntry("Film.autoflow/render.json")).readBytes().decodeToString())
            assertEquals(Mode.SUBTITLES, plan.mode)
            assertEquals("BOXED", plan.look)
            assertEquals(2, plan.lines)
            assertEquals("0.4.2 (test)", plan.app)
            assertEquals(fontEntry.removePrefix("Film.autoflow/"), plan.font)
            assertEquals(listOf(LexiconEntry("AI", "အေအိုင်")), plan.lexicon)
        }
    }

    @Test
    fun openPackageUnpacksIntoTheChosenFolderAddsOnlyNewFixesAndOpensIt() = runBlocking {
        open()
        val zip = "$root/Film-render-package.zip"
        val plan = RenderPlan(mode = Mode.SUBTITLES, lexicon = listOf(LexiconEntry("AI", "အေအိုင်"), LexiconEntry("TV", "တီဗွီ")))
        val extras = listOf(PackageFile(RenderPlan.FILE, CoreJson.encodeToString(RenderPlan.serializer(), plan).encodeToByteArray()))
        assertIs<PlatformResult.Ok<*>>(JvmRenderPackages().pack(dir, "Film.autoflow", extras, zip))
        val library = LibraryStore(fs, "$root/app/library")
        library.save(library.load().copy(lexicon = listOf(LexiconEntry("TV", "တီဗီ"))))
        val other = "$root/other"
        fs.createDirectories(other)
        val dash = DashboardModel(fs, store, index, settings, FakePicker(folder = other, files = listOf(zip)), current,
            packages = JvmRenderPackages(), library = library)
        assertTrue(dash.openPackage(), dash.state.value.message)
        assertEquals("$other/Film.autoflow", current.state.value?.dir)
        assertEquals("ရုပ်ရှင် Film", current.state.value?.project?.name)
        assertEquals(UiText.PACKAGE_OPENED, dash.state.value.notice?.key)
        assertEquals(listOf(LexiconEntry("TV", "တီဗီ"), LexiconEntry("AI", "အေအိုင်")), library.load().lexicon.toList(),
            "the Library's own TV stays; only AI is added")
    }

    @Test
    fun aPackagedFontIsUsedBeforeTheSettingsFonts() {
        open()
        val frames = RecapFrames(session, settings, fs)
        assertNull(frames.font(), "no Settings font, no built-ins")
        fs.createDirectories("$dir/${RecapFrames.PACKAGED_FONTS_DIR}")
        fs.writeDurably("$dir/${RecapFrames.PACKAGED_FONTS_DIR}/Packaged.ttf", font)
        val packaged = assertNotNull(frames.font())
        assertContentEquals(font, packaged.bytes)
        assertNull(frames.font(projectDir = null), "another project does not see it")
    }

    @Test
    fun theCardMakesThePackageAndShowsWhereItWent() = runComposeUiTest {
        open()
        settingsFont()
        val model = model()
        setContent { CompositionLocalProvider(LocalRenderPackages provides model) { RenderPackageCard({ RenderPlan(mode = Mode.SUBTITLES) }) } }
        onNodeWithTag("render-package").assertExists()
        onNodeWithTag("render-package-make").assertDoesNotExist()
        onNodeWithText("+").performClick()
        onNodeWithTag("render-package-make").performClick()
        waitUntil(timeoutMillis = 60_000) { onAllNodesWithTag("render-package-folder").fetchSemanticsNodes().isNotEmpty() }
        onNodeWithTag("render-package-notice").assertExists()
        assertTrue(File("$dir/exports/ရုပ်ရှင်-Film-render-package.zip").isFile)
    }

    @Test
    fun withoutPackagesTheCardIsNotShown() = runComposeUiTest {
        setContent { RenderPackageCard({ RenderPlan(mode = Mode.SUBTITLES) }) }
        onNodeWithTag("render-package").assertDoesNotExist()
    }

    @Test
    fun safeNamesKeepBurmeseLettersAndDigits() {
        assertEquals("ရုပ်ရှင်-Film-2", RenderPackageModel.safeName("ရုပ်ရှင် Film 2"))
        assertEquals("project", RenderPackageModel.safeName("///"))
    }
}
