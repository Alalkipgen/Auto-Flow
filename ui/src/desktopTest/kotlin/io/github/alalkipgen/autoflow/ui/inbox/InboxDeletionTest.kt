package io.github.alalkipgen.autoflow.ui.inbox

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.platform.LocalDensity
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
            setContent {
                CompositionLocalProvider(LocalDensity provides Density(1f, font), LocalUiLanguage provides language) {
                    if (show.value) InboxScreen(model, shared = shared)
                }
            }
            tap("inbox-delete-$clip")
            val title = My.breakSyllables(UiText.INBOX_DELETE_TITLE.text(language, "name" to clip))
            onNodeWithText(title).assertIsDisplayed()
            onNodeWithTag("inbox-delete-confirm").performScrollTo().assertIsDisplayed().assertHeightIsAtLeast(48.dp)
            tap("inbox-delete-cancel")
            assertTrue(fs.exists("$dir/inbox/$clip"))
            tap("inbox-delete-$clip")
            tap("inbox-delete-confirm")
            onNodeWithTag("inbox-delete-$clip").assertDoesNotExist()
            onNodeWithTag("inbox-delete-$survivor").performScrollTo().assertIsDisplayed()
            tap("inbox-delete-photo.jpg")
            tap("inbox-delete-confirm")
            tap("shared-delete-waiting.jpg")
            tap("inbox-delete-confirm")
            onNodeWithTag("shared-delete-waiting.jpg").assertDoesNotExist()
            runOnIdle { show.value = false }
            onNodeWithTag("inbox-delete-$survivor").assertDoesNotExist()
            runOnIdle { show.value = true }
            onNodeWithTag("inbox-delete-$survivor").performScrollTo().assertIsDisplayed()
            onNodeWithTag("inbox-delete-$clip").assertDoesNotExist()
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