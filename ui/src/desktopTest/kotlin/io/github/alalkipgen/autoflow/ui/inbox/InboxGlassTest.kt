package io.github.alalkipgen.autoflow.ui.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import java.nio.file.Files
import kotlin.test.*

/**
 * Music Video plan F (sample 11 / 11b): the Inbox's glass parts — the shared card with and without a project, the folder
 * box and the round Refresh, the empty dashed box, the file rows (Attach to shot / Cannot attach), the shot chips with
 * their start time, Cancel, the green attached callout and the no-shots line. Same model calls as before.
 */
@OptIn(ExperimentalTestApi::class)
class InboxGlassTest {
    @Test fun englishStandard() = glass(1f, UiLanguage.ENGLISH)
    @Test fun burmeseLarge() = glass(2f, UiLanguage.BURMESE)

    private fun glass(font: Float, language: UiLanguage) {
        val root = Files.createTempDirectory("inbox-glass")
        try {
            val fs = JvmFs()
            val dir = root.resolve("Music.autoflow").toString()
            val hold = Files.createDirectory(root.resolve(".shared-inbox"))
            val store = ProjectStore(fs)
            val current = CurrentProject()
            val session = ProjectSession(fs, store, ProjectIndex(fs, "$root/recents.json"), current)
            val model = InboxModel(session, fs)
            val shared = SharedInbox(fs, session, hold.toString())
            Files.write(hold.resolve("IMG_1.jpg"), byteArrayOf(1))
            shared.refresh()
            runDesktopComposeUiTest(width = 360, height = 1400) {
                fun shows(tag: String, text: String) = onNode(hasAnyAncestor(hasTestTag(tag)) and
                    (hasText(text, substring = true) or hasText(My.breakSyllables(text), substring = true)), useUnmergedTree = true).assertExists()
                fun inRow(row: String, tag: String) = onNode(hasTestTag(tag) and hasAnyAncestor(hasTestTag(row)))
                fun click(node: SemanticsNodeInteraction) { node.performSemanticsAction(SemanticsActions.OnClick); waitForIdle() }
                fun tap(node: SemanticsNodeInteraction) = click(node.performScrollTo())
                setContent {
                    CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language) { InboxScreen(model, shared = shared) }
                }
                // No project: the shared card asks for one (no Move here), no folder box, the page note stays.
                onNodeWithTag("inbox-shared").assertIsDisplayed()
                onNodeWithTag("inbox-shared-no-project").assertIsDisplayed()
                shows("inbox-shared", UiText.SHARED_TO_AUTOFLOW.text(language))
                onNodeWithTag("shared-file-IMG_1.jpg").assertIsDisplayed()
                onNodeWithTag("inbox-move-here").assertDoesNotExist()
                onNodeWithTag("inbox-folder").assertDoesNotExist()
                onNodeWithTag("inbox-refresh").assertContentDescriptionEquals(UiText.REFRESH.text(language)).assertHasClickAction()

                runOnIdle {
                    val project = assertNotNull(store.create(dir, "Music", Mode.MUSIC_VIDEO).getOrNull())
                    current.set(OpenProject(dir, project))
                    assertNotNull(session.save(project.copy(shots = listOf(Shot(1, 0, 8_000), Shot(2, 8_000, 16_000)))).getOrNull())
                }
                waitForIdle()
                onNodeWithTag("inbox-folder").assertIsDisplayed()
                onNodeWithTag("inbox-empty").assertIsDisplayed()
                shows("inbox-empty", UiText.INBOX_EMPTY.text(language))
                onNodeWithTag("inbox-shared-no-project").assertDoesNotExist()
                tap(onNodeWithTag("inbox-move-here"))
                onNodeWithTag("inbox-move-here").assertDoesNotExist()
                onNodeWithTag("inbox-file-IMG_1.jpg").assertIsDisplayed()
                onNodeWithTag("inbox-empty").assertDoesNotExist()

                Files.write(root.resolve("Music.autoflow/inbox/notes.pdf"), byteArrayOf(2))
                Files.write(root.resolve("Music.autoflow/inbox/clip.mp4"), byteArrayOf(3, 4))
                // At the top of the page; its 48 dp touch area reaches past the scrolled content (performScrollTo would never settle).
                click(onNodeWithTag("inbox-refresh"))
                inRow("inbox-file-notes.pdf", "inbox-cannot-attach").assertIsDisplayed()
                inRow("inbox-file-notes.pdf", "inbox-attach").assertDoesNotExist()
                tap(inRow("inbox-file-clip.mp4", "inbox-attach"))
                shows("inbox-file-clip.mp4", UiText.CHOOSE_SHOT.text(language))
                shows("inbox-shot-2", "Shot 2")
                shows("inbox-shot-2", "0:08")
                tap(onNodeWithTag("inbox-attach-cancel"))
                onNodeWithTag("inbox-shot-2").assertDoesNotExist()
                tap(inRow("inbox-file-clip.mp4", "inbox-attach"))
                tap(onNodeWithTag("inbox-shot-2"))
                shows("inbox-message", UiText.VIDEO_ATTACHED.text(language, "n" to 2))
                onNodeWithTag("inbox-file-clip.mp4").assertDoesNotExist()
                assertEquals(1, session.open!!.project.shots.single { it.number == 2 }.videos.size)

                runOnIdle { assertNotNull(session.save(session.open!!.project.copy(shots = emptyList())).getOrNull()) }
                waitForIdle()
                tap(inRow("inbox-file-IMG_1.jpg", "inbox-attach"))
                shows("inbox-file-IMG_1.jpg", UiText.NO_SHOTS_STORYBOARD.text(language))
                onNodeWithTag("inbox-shot-1").assertDoesNotExist()
            }
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
