package io.github.alalkipgen.autoflow.ui.library

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.library.*
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.recap.QaRecapFixture
import kotlinx.coroutines.*
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File
import java.nio.file.Files
import java.util.Base64
import kotlin.test.*

/** Real QA assets, distinct real A/B project IDs, serialized saves/reopens, never live providers. */
internal class LibraryTestFixture : AutoCloseable {
    companion object {
        private val base by lazy { QaRecapFixture.create(Files.createTempDirectory("library-qa-base").toFile()) }
        private val fontBytes by lazy {
            val f = generateSequence(File("").absoluteFile) { it.parentFile }
                .map { File(it, "fonts/Pyidaungsu-Regular.ttf.b64") }.first { it.isFile }
            Base64.getMimeDecoder().decode(f.readBytes())
        }
        fun bytes(dir: File) = dir.walkTopDown().filter { it.isFile && !it.relativeTo(dir).invariantSeparatorsPath.startsWith(".cache/") }
            .associate { it.relativeTo(dir).invariantSeparatorsPath to it.readBytes().toList() }
    }
    val root = Files.createTempDirectory("library-isolation").toFile()
    private val realFs = JvmFs()
    var failProjectWrites = false
    var failLibraryWrites = false
    val fs = object : FileSystem by realFs {
        override fun writeDurably(path: String, bytes: ByteArray) {
            if (failProjectWrites && path.contains(".autoflow/") || failLibraryWrites && path.contains("/library/library.json"))
                error("deliberate test write failure")
            realFs.writeDurably(path, bytes)
        }
    }
    val store = ProjectStore(fs)
    fun project(name: String, recap: Boolean = true): OpenProject {
        val dir = File(root, "$name.autoflow")
        val p = (store.create(dir.path, name, if (recap) Mode.MOVIE_RECAP else Mode.MUSIC_VIDEO) as CoreResult.Ok).value
        if (!recap) return OpenProject(dir.path, p)
        listOf("source", "artifacts").forEach { File(base.dir, it).copyRecursively(File(dir, it), overwrite = true) }
        return OpenProject(dir.path, (store.save(dir.path, p.copy(recap = base.project.recap)) as CoreResult.Ok).value)
    }
    val a = project("QA Landscape")
    val b = project("QA Other")
    val current = CurrentProject().apply { set(a) }
    val session = ProjectSession(fs, store, ProjectIndex(fs, File(root, "index.json").path), current)
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val libraryDir = File(root, "library").apply { mkdirs() }
    val libraryStore = LibraryStore(fs, libraryDir.path)
    val style = CaptionStyle(size = CaptionSize.L, colour = "#FFFF00", box = true)
    val voice = SavedVoice("voice", "QA recorded voice lock", VoiceLock("qa", "offline-fixture", "fixture", "my"))
    val kit = BrandKit("kit", "QA Brand Kit", logo = "files/qa-logo.png", watermark = "files/qa-watermark.png",
        font = "files/qa-font.ttf", colours = listOf("#FFFF00"), captionStyle = CoreJson.encodeToString(CaptionStyle.serializer(), style))
    val library = Library(brandKits = listOf(kit), voices = listOf(voice), lexicon = listOf(LexiconEntry("ရေးပုံ", "အသံထွက်")),
        music = listOf(MusicItem("music", "QA tone", "files/qa-tone.wav", 1000)), fonts = listOf(FontItem("font", "Pyidaungsu QA", "files/qa-font.ttf")),
        presets = listOf(Preset("caption", "QA caption", PresetKind.CAPTION, buildJsonObject { put("size", "S"); put("colour", "#00FFFF"); put("box", true) }),
            Preset("style", "QA unsupported style", PresetKind.STYLE), Preset("export", "QA unsupported export", PresetKind.EXPORT)))
    init {
        val files = File(libraryDir, "files").apply { mkdirs() }
        val png = File(base.dir, "artifacts").listFiles()!!.first { it.extension == "png" }
        png.copyTo(File(files, "qa-logo.png")); png.copyTo(File(files, "qa-watermark.png"))
        File(base.dir, "artifacts").listFiles()!!.first { it.extension == "wav" }.copyTo(File(files, "qa-tone.wav"))
        File(files, "qa-font.ttf").writeBytes(fontBytes)
        libraryStore.save(library)
    }
    val model = LibraryModel(libraryStore, session, scope, fs)
    fun saved(open: OpenProject) = (store.open(open.dir) as CoreResult.Ok).value
    override fun close() { scope.cancel() }
}

class LibraryApplyIsolationTest {
    @Test fun realCopyApplySaveReopenAndDeletingLibraryOriginalsCannotChangeEitherProject() = LibraryTestFixture().use { f ->
        val bBefore = LibraryTestFixture.bytes(File(f.b.dir)); val immutable = LibraryTestFixture.bytes(File(f.a.dir, "artifacts"))
        assertNotEquals(f.a.project.id, f.b.project.id)
        assertEquals(f.style, f.model.applyBrandKit("kit")); assertTrue(f.model.state.value.messageOk)
        assertEquals(f.style, f.saved(f.a).recap!!.captionStyle)
        assertEquals("library/qa-watermark.png", f.saved(f.a).recap!!.visual.watermarkFile)
        for (name in listOf("qa-logo.png", "qa-watermark.png", "qa-font.ttf"))
            assertContentEquals(File(f.libraryDir, "files/$name").readBytes(), File(f.a.dir, "library/$name").readBytes())
        f.model.applyVoice("voice"); assertEquals(f.voice.lock, f.saved(f.a).recap!!.voiceLock)
        val beforeCopy = File(f.a.dir, "project.json").readBytes()
        val copied = assertNotNull(f.model.applyMusicAsBgm("music")); f.model.applyFont("font")
        assertContentEquals(beforeCopy, File(f.a.dir, "project.json").readBytes(), "copy-only buttons cannot wire BGM/select a font")
        assertEquals(f.a.project.recap!!.audio.bgm, f.saved(f.a).recap!!.audio.bgm)
        assertContentEquals(File(f.libraryDir, "files/qa-tone.wav").readBytes(), File(copied).readBytes())
        f.model.applyPreset("caption")
        assertEquals(f.style.copy(size = CaptionSize.S, colour = "#00FFFF"), f.saved(f.a).recap!!.captionStyle)
        val aAfter = LibraryTestFixture.bytes(File(f.a.dir))
        f.libraryDir.deleteRecursively() // deliberate external deletion, not a production operation
        f.model.refresh()
        assertEquals(aAfter, LibraryTestFixture.bytes(File(f.a.dir)))
        assertEquals(bBefore, LibraryTestFixture.bytes(File(f.b.dir)))
        assertEquals(immutable, LibraryTestFixture.bytes(File(f.a.dir, "artifacts")), "immutable artifacts never rewritten")
    }

    @Test fun unsupportedInvalidAndFailedOperationsNeverPretendSuccessOrWriteOtherProject() = LibraryTestFixture().use { f ->
        val aBefore = LibraryTestFixture.bytes(File(f.a.dir)); val bBefore = LibraryTestFixture.bytes(File(f.b.dir))
        for (id in listOf("style", "export")) {
            f.model.applyPreset(id); assertFalse(f.model.state.value.messageOk)
            assertEquals(UiText.V3_LIBRARY_PRESET_UNSUPPORTED, f.model.state.value.notice!!.key)
            assertEquals(aBefore, LibraryTestFixture.bytes(File(f.a.dir)))
        }
        f.libraryStore.save(f.library.copy(presets = listOf(Preset("invalid", "Invalid", PresetKind.CAPTION)))); f.model.refresh()
        f.model.applyPreset("invalid"); assertEquals(UiText.V3_LIBRARY_PRESET_INVALID, f.model.state.value.notice!!.key)
        assertEquals(aBefore, LibraryTestFixture.bytes(File(f.a.dir)))
        f.failProjectWrites = true
        f.model.applyVoice("voice"); assertFalse(f.model.state.value.messageOk); assertEquals(UiText.SAVE_FAILED, f.model.state.value.notice!!.key)
        assertEquals(aBefore, LibraryTestFixture.bytes(File(f.a.dir)))
        f.failProjectWrites = false
        File(f.libraryDir, "files/qa-font.ttf").delete()
        f.model.applyBrandKit("kit"); assertFalse(f.model.state.value.messageOk)
        assertEquals(UiText.V3_LIBRARY_PARTIAL, f.model.state.value.notice!!.key)
        assertEquals(2, f.model.state.value.notice!!.arguments["done"]); assertEquals(3, f.model.state.value.notice!!.arguments["total"])
        assertEquals(bBefore, LibraryTestFixture.bytes(File(f.b.dir)))
    }

    @Test fun saveDeleteAndTokenNoticesRetainUserNamesAndNeverClaimSuccessAfterSaveFailure() = LibraryTestFixture().use { f ->
        val aBefore = LibraryTestFixture.bytes(File(f.a.dir)); val bBefore = LibraryTestFixture.bytes(File(f.b.dir))
        f.model.saveCaptionPreset("ပိုင်ရှင် caption"); assertTrue(f.model.state.value.messageOk)
        assertEquals("ပိုင်ရှင် caption", f.libraryStore.load().presets.last().name)
        val notice = f.model.state.value.notice!!
        assertNotEquals(notice.text(UiLanguage.ENGLISH), notice.text(UiLanguage.BURMESE))
        assertTrue(notice.text(UiLanguage.BURMESE).contains("ပိုင်ရှင် caption"))
        f.model.saveBrandKit("ပိုင်ရှင် kit"); assertTrue(f.model.state.value.messageOk)
        assertEquals("ပိုင်ရှင် kit", f.libraryStore.load().brandKits.last().name)
        f.model.deleteBrandKit("kit"); assertFalse(f.libraryStore.load().brandKits.any { it.id == "kit" })
        f.failLibraryWrites = true; f.model.saveCaptionPreset("Cannot save")
        assertFalse(f.model.state.value.messageOk); assertEquals(UiText.SAVE_FAILED, f.model.state.value.notice!!.key)
        assertEquals(aBefore, LibraryTestFixture.bytes(File(f.a.dir))); assertEquals(bBefore, LibraryTestFixture.bytes(File(f.b.dir)))
    }

    @Test fun switchedProjectReceivesOnlyItsOwnExplicitCopiesAndNonRecapNoOpIsNotGreenApplied() = LibraryTestFixture().use { f ->
        val aBefore = LibraryTestFixture.bytes(File(f.a.dir))
        f.current.set(f.b); f.model.applyMusicAsBgm("music")
        assertTrue(File(f.b.dir, "source/qa-tone.wav").isFile); assertFalse(File(f.a.dir, "source/qa-tone.wav").exists())
        assertEquals(aBefore, LibraryTestFixture.bytes(File(f.a.dir)))
        val mv = f.project("QA music video", recap = false); f.current.set(mv)
        f.libraryStore.save(Library(brandKits = listOf(BrandKit("empty", "No supported values")))); f.model.refresh()
        val before = LibraryTestFixture.bytes(File(mv.dir)); f.model.applyBrandKit("empty")
        assertFalse(f.model.state.value.messageOk); assertEquals(UiText.V3_LIBRARY_KIT_COPIED, f.model.state.value.notice!!.key)
        assertEquals(before, LibraryTestFixture.bytes(File(mv.dir)))
    }
}
