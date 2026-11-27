package io.github.alalkipgen.autoflow.ui.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.getOrNull
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

@OptIn(ExperimentalTestApi::class)
class InboxDeletionTest {
    @Test fun englishStandard() = mediaDeletion(412, 1f, UiLanguage.ENGLISH)
    @Test fun englishLarge() = mediaDeletion(412, 2f, UiLanguage.ENGLISH)
    @Test fun burmeseStandard() = mediaDeletion(412, 1f, UiLanguage.BURMESE)
    @Test fun burmeseLarge() = mediaDeletion(412, 2f, UiLanguage.BURMESE)
    @Test fun narrowPhone() = mediaDeletion(360, 2f, UiLanguage.BURMESE)

    private fun mediaDeletion(width: Int, font: Float, language: UiLanguage) = withTemp { root ->
        val fs = JvmFs()
        val dir = root.resolve("Music.autoflow").toString()
        val hold = Files.createDirectory(root.resolve(".shared-inbox"))
        val store = ProjectStore(fs)
        val current = CurrentProject()
        val session = ProjectSession(fs, store, ProjectIndex(fs, "$root/recents.json"), current)
        val model = InboxModel(session, fs)
        val shared = SharedInbox(fs, session, hold.toString())
        val project = assertNotNull(store.create(dir, "Music", Mode.MUSIC_VIDEO).getOrNull())
        current.set(OpenProject(dir, project))
        assertNotNull(session.save(project.copy(shots = listOf(Shot(1, 0, 1_000)))).getOrNull())
        val clip = "စမ်းသပ်ထားသောဗီဒီယိုဖိုင်အမည်ရှည် မူရင်း.mp4"
        val original = Files.write(root.resolve(clip), byteArrayOf(1, 2, 3))
        Files.copy(original, hold.resolve(clip))
        shared.received()
        model.attach(model.refresh().items.single(), 1)
        val attached = session.open!!.project.shots.single().videos.single()
        val manifest = fs.read("$dir/project.json")

        repeat(2) {
            Files.copy(original, hold.resolve(clip))
            shared.received()
        }
        Files.write(hold.resolve("photo.jpg"), byteArrayOf(4, 5))
        shared.received()
        Files.write(hold.resolve("waiting.jpg"), byteArrayOf(6))
        Files.write(hold.resolve(".still-copying.part"), byteArrayOf(7))
        shared.refresh()
        val survivor = SharedInbox.freeName(clip, setOf(clip))

        runDesktopComposeUiTest(width = width, height = 680) {
            val show = mutableStateOf(true)
            fun tap(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed().performMouseInput { click() }
            fun row(tag: String) = onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
            fun hold(tag: String) = row(tag).performTouchInput { longClick(topCenter + Offset(0f, 20f)) }
            fun noDelete() = onNodeWithText(My.breakSyllables(UiText.CARD_DELETE.text(language))).assertDoesNotExist()
            fun selectDelete(tag: String) {
                onNodeWithTag("$tag-remove-confirm").assertDoesNotExist()
                tap("$tag-remove-menu")
            }
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language) {
                    if (show.value) InboxScreen(model, shared = shared)
                }
            }
            val clipTag = "inbox-file-$clip"
            val photoTag = "inbox-file-photo.jpg"
            val waitingTag = "shared-file-waiting.jpg"
            noDelete()
            row(clipTag).performTouchInput { click(topCenter + Offset(0f, 20f)) }
            noDelete()
            fun clipAction(action: UiText) = onNode(hasAnyAncestor(hasTestTag(clipTag)) and hasText(action.text(language)))
                .performScrollTo().assertIsDisplayed().performMouseInput { click() }
            clipAction(UiText.ATTACH_SHOT)
            onNodeWithText("Shot 1").performScrollTo().assertIsDisplayed()
            noDelete()
            clipAction(UiText.CANCEL)

            // Step 1 = the file (its kind tile, name and "Inbox"); Cancel and a tap outside delete nothing.
            hold(clipTag)
            onNodeWithTag("glass-dialog-title").assertTextEquals(My.breakSyllables(clip))
            onNodeWithTag("glass-dialog-subtitle").assertTextEquals(My.breakSyllables(UiText.INBOX.text(language)))
            onNodeWithTag("glass-dialog-lead").assertIsDisplayed()
            tap("$clipTag-remove-cancel")
            noDelete()
            hold(clipTag)
            onAllNodes(isRoot())[1].performMouseInput { click(Offset(5f, 5f)) }
            noDelete()
            assertTrue(fs.exists("$dir/inbox/$clip"))
            hold(clipTag)
            selectDelete(clipTag)
            val title = My.breakSyllables(UiText.INBOX_DELETE_TITLE.text(language, "name" to clip))
            onNodeWithText(title).assertIsDisplayed()
            onNodeWithTag("$clipTag-remove-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            tap("$clipTag-remove-cancel")
            assertTrue(fs.exists("$dir/inbox/$clip"))
            row(clipTag).performMouseInput { rightClick(topCenter + Offset(0f, 20f)) }
            selectDelete(clipTag)
            tap("$clipTag-remove-confirm")
            onNodeWithTag(clipTag).assertDoesNotExist()
            row("inbox-file-$survivor")

            val accessibleDelete = row(photoTag).fetchSemanticsNode().config[SemanticsActions.CustomActions].single()
            assertEquals(UiText.INBOX_DELETE_TITLE.text(language, "name" to "photo.jpg"), accessibleDelete.label)
            runOnIdle { assertTrue(accessibleDelete.action()) }
            val replacement = byteArrayOf(8, 9, 10)
            fs.writeDurably("$dir/inbox/photo.jpg", replacement)
            selectDelete(photoTag)
            tap("$photoTag-remove-confirm")
            val failed = UiText.INBOX_DELETE_FAILED.text(language, "name" to "photo.jpg")
            onNodeWithText(My.breakSyllables(failed)).performScrollTo().assertIsDisplayed()
            assertContentEquals(replacement, fs.read("$dir/inbox/photo.jpg"), "an open menu cannot delete a changed file")
            hold(photoTag)
            selectDelete(photoTag)
            tap("$photoTag-remove-confirm")

            row(waitingTag).assertHeightIsAtLeast(48.dp)
            hold(waitingTag)
            onNodeWithTag("glass-dialog-subtitle").assertTextEquals(My.breakSyllables(UiText.SHARED_TO_AUTOFLOW.text(language)))
            onNodeWithTag("glass-dialog-lead").assertDoesNotExist()
            selectDelete(waitingTag)
            tap("$waitingTag-remove-confirm")
            onNodeWithTag(waitingTag).assertDoesNotExist()
            noDelete()
            runOnIdle { show.value = false }
            onNodeWithTag("inbox-file-$survivor").assertDoesNotExist()
            runOnIdle { show.value = true }
            row("inbox-file-$survivor")
            onNodeWithTag(clipTag).assertDoesNotExist()
            noDelete()
        }
        val freshStore = ProjectStore(JvmFs())
        val reopened = CurrentProject().apply { set(OpenProject(dir, assertNotNull(freshStore.open(dir).getOrNull()))) }
        val freshSession = ProjectSession(JvmFs(), freshStore, ProjectIndex(fs, "$root/recents.json"), reopened)
        assertEquals(listOf(survivor), InboxModel(freshSession, JvmFs()).refresh().items.map { it.name })
        assertTrue(SharedInbox(JvmFs(), freshSession, hold.toString()).state.value.waiting.isEmpty())
        assertTrue(Files.exists(hold.resolve(".still-copying.part")))
        assertContentEquals(byteArrayOf(1, 2, 3), Files.readAllBytes(original))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read("$dir/inbox/$survivor"))
        assertContentEquals(byteArrayOf(1, 2, 3), fs.read("$dir/${InboxModel.DONE_DIR}/$clip"))
        assertContentEquals(byteArrayOf(1, 2, 3), freshSession.artifacts()!!.readBytes(attached).getOrNull())
        assertContentEquals(manifest, fs.read("$dir/project.json"))
    }

    @Test fun directoriesPartialImportsTraversalAndReplacementsAreRefused() = withTemp { root ->
        val fs = JvmFs()
        val folder = Files.createDirectory(root.resolve("inbox"))
        val outside = Files.write(root.resolve("original.mp4"), byteArrayOf(1))
        Files.createDirectory(folder.resolve("directory.mp4"))
        Files.write(folder.resolve(".photo.part"), byteArrayOf(1))
        Files.write(folder.resolve("photo.part"), byteArrayOf(1))
        for (name in listOf("../original.mp4", "..\\original.mp4", outside.toString(), "directory.mp4", ".photo.part", "photo.part")) {
            assertNull(fs.prepareInboxDeletion(folder.toString(), name), name)
        }
        assertNull(fs.prepareInboxDeletion(root.toString(), "original.mp4"))
        val file = Files.write(folder.resolve("clip.mp4"), byteArrayOf(2))
        val delete = assertNotNull(fs.prepareInboxDeletion(folder.toString(), "clip.mp4"))
        Files.write(file, byteArrayOf(3, 4))
        assertFalse(delete())
        assertContentEquals(byteArrayOf(3, 4), Files.readAllBytes(file))
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(outside))
    }

    @Test fun linkedEntryOrInboxCannotDeleteOutsideMedia() = withTemp { root ->
        val fs = JvmFs()
        val folder = Files.createDirectory(root.resolve("inbox"))
        val elsewhere = Files.createDirectory(root.resolve("external"))
        val original = Files.write(elsewhere.resolve("clip.mp4"), byteArrayOf(1))
        val file = Files.write(folder.resolve("clip.mp4"), byteArrayOf(2))
        val delete = assertNotNull(fs.prepareInboxDeletion(folder.toString(), "clip.mp4"))
        Files.delete(file)
        Files.createSymbolicLink(file, original)
        assertFalse(delete())
        assertNull(fs.prepareInboxDeletion(folder.toString(), "clip.mp4"))
        Files.delete(file)
        Files.delete(folder)
        Files.createSymbolicLink(folder, elsewhere)
        assertNull(fs.prepareInboxDeletion(folder.toString(), "clip.mp4"))
        assertContentEquals(byteArrayOf(1), Files.readAllBytes(original))
        Files.delete(folder)
    }

    private fun withTemp(test: (Path) -> Unit) {
        val root = Files.createTempDirectory("autoflow-inbox-").toRealPath()
        try { test(root) } finally { root.toFile().deleteRecursively() }
    }
}