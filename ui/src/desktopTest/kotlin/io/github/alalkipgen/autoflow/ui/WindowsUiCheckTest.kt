package io.github.alalkipgen.autoflow.ui

import androidx.compose.ui.graphics.toAwtImage
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelectable
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.alalkipgen.autoflow.ai.registry.aiModule
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.ui.di.uiModule
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.DashboardModel
import io.github.alalkipgen.autoflow.ui.shell.App
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertNotNull
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin

/**
 * Checks that need a real desktop OS. The Windows CI job sets `AUTOFLOW_EXPECT_MYANMAR_FONT=1` (the font check must
 * pass) and `AUTOFLOW_SCREENSHOTS_DIR` (PNGs uploaded as a workflow artifact), so the UI can be reviewed without a PC.
 */
@OptIn(ExperimentalTestApi::class)
class WindowsUiCheckTest {
    @AfterTest
    fun tearDown() = stopKoin()

    @Test
    fun systemFontFallbackCoversMyanmar() {
        val face = FontMgr.default.matchFamilyStyleCharacter(null, FontStyle.NORMAL, arrayOf("my"), 'က'.code)
        println("Myanmar fallback font: ${face?.familyName ?: "none"}")
        if (System.getenv("AUTOFLOW_EXPECT_MYANMAR_FONT") == "1") assertNotNull(face, "No system font covers Myanmar (U+1000)")
    }

    @Test
    fun screenshotsOfTheMainScreens() {
        val dir = System.getenv("AUTOFLOW_SCREENSHOTS_DIR")?.let(::File) ?: return
        dir.mkdirs()
        runComposeUiTest {
            val fs = InMemoryFileSystem()
            val koin = startKoin { modules(fakePlatformModule(fs), uiModule, aiModule) }.koin
            val store = koin.get<ProjectStore>()
            store.create("D:/Videos/မိုးရေစက်.autoflow", "မိုးရေစက် (Rain drops)", Mode.MUSIC_VIDEO)
            store.create("D:/Videos/ကျွန်တော့်ညများ.autoflow", "ကျွန်တော် မိုးရွာနေတဲ့ ညမှာ", Mode.MUSIC_VIDEO)
            store.create("E:/USB/Gone.autoflow", "Gone", Mode.MUSIC_VIDEO)
            val dashboard = koin.get<DashboardModel>()
            listOf("E:/USB/Gone.autoflow", "D:/Videos/ကျွန်တော့်ညများ.autoflow", "D:/Videos/မိုးရေစက်.autoflow").forEach(dashboard::open)
            fs.atomicMove("E:/USB/Gone.autoflow/project.json", "E:/moved.json")
            koin.get<CurrentProject>().set(null)
            setContent { App() }
            save(dir, "dashboard")
            onNode(hasText("Settings") and isSelectable()).performClick()
            save(dir, "settings")
        }
    }

    private fun ComposeUiTest.save(dir: File, name: String) {
        waitForIdle()
        ImageIO.write(onRoot().captureToImage().toAwtImage(), "png", File(dir, "$name.png"))
    }
}
