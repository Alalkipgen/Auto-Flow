package io.github.alalkipgen.autoflow.ui.i18n

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import io.github.alalkipgen.autoflow.ai.AiResult
import io.github.alalkipgen.autoflow.ai.usage.UsageCounter
import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.JvmFs
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.projects.*
import io.github.alalkipgen.autoflow.ui.settings.*
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Dest
import io.github.alalkipgen.autoflow.ui.shell.StudioV2Shell
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.nio.file.Files
import kotlin.test.*

/** Real disk, production serialization/artifact stores and displayed pointer clicks, not a dummy JSON sentinel. */
@OptIn(ExperimentalTestApi::class)
class UiLanguageContentTest {
    private fun <T> CoreResult<T>.ok(): T = when (this) {
        is CoreResult.Ok -> value
        is CoreResult.Err -> fail("Production-store fixture failed: ${error.message}")
    }

    @Test fun liveSwitchAndRestartLeaveBothProjectTreesAndSpeechIdentityByteIdentical() = runDesktopComposeUiTest(width = 412, height = 760) {
        val root = Files.createTempDirectory("autoflow-language-content").toFile()
        try {
            val fs = JvmFs()
            val projects = ProjectStore(fs)
            val mvDir = root.resolve("မိုး.autoflow").path.replace('\\', '/')
            val recapDir = root.resolve("ရုပ်ရှင်.autoflow").path.replace('\\', '/')
            val settingsPath = root.resolve("app/settings.json").path.replace('\\', '/')
            val mv = projects.create(mvDir, "မိုးရွာသောနေ့", Mode.MUSIC_VIDEO).ok()
            val mvArtifacts = ArtifactStore(fs, mvDir)
            val ir = PromptIR(shot = 1, subject = "A girl with a red umbrella", action = "Walk towards {n}", setting = "Rainy street",
                camera = "Wide shot", lighting = "Soft daylight", mood = "Hopeful", style = "Cinematic", characters = listOf("MAY"), negative = "No subtitles", ratio = "9:16", durationS = 8)
            val prompt = mvArtifacts.put(CoreJson.encodeToString(PromptIR.serializer(), ir).encodeToByteArray(), ArtifactKind.PROMPT_IR, "json").ok().id
            val ref = mvArtifacts.put(byteArrayOf(1, 2), ArtifactKind.IMAGE, "png").ok().id
            val video = mvArtifacts.put(byteArrayOf(3, 4), ArtifactKind.VIDEO, "mp4").ok().id
            val savedMv = projects.save(mvDir, mv.copy(shots = listOf(Shot(1, 0, 8000, lyricsMy = "မိုးတွေရွာနေတယ်", promptIr = prompt,
                refImages = listOf(ref), chosenRef = ref, videos = listOf(video), acceptedVideo = video,
                approvals = Approvals(true, true, true, true), handoff = Handoff(HandoffState.ACCEPTED))))).ok()
            fs.writeDurably("$mvDir/source/lyrics.txt", "မိုးတွေရွာနေတယ်\nသူငယ်ချင်းများ".encodeToByteArray())
            val created = projects.create(recapDir, "မူလဇာတ်လမ်း", Mode.MOVIE_RECAP).ok()
            val artifacts = ArtifactStore(fs, recapDir)
            val block = RecapBlock("stable-owner-block", listOf(RecapClip(0, 2000), RecapClip(4000, 6000)), "အောင်မြင်တဲ့နေ့", speakMy = "အောင်မြင်သည့်နေ့")
            val story = RecapStory(source = RecapStory.SOURCE_MANUAL, blocks = listOf(block))
            val storyId = artifacts.put(CoreJson.encodeToString(RecapStory.serializer(), story).encodeToByteArray(), ArtifactKind.RECAP_SCRIPT, "json").ok().id
            val oldAudio = artifacts.put("RIFF-owner-take".encodeToByteArray(), ArtifactKind.AUDIO, "wav").ok().id
            val newAudio = artifacts.put("RIFF-stale-take".encodeToByteArray(), ArtifactKind.AUDIO, "wav").ok().id
            val take = BlockTake(block.id, oldAudio, TakeSource.TTS, "gemini", "owner-locked-model", "Zephyr", 3000, SpokenText.hash(block))
            val recap = RecapV2(rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 7, 8000, 1920, 1080), story = storyId, storyApproved = true,
                voiceLock = VoiceLock("gemini", "owner-locked-model", "Zephyr", "my"), takes = listOf(take, take.copy(audio = newAudio, spokenHash = "older-speech-hash")),
                chosenTakes = mapOf(block.id to oldAudio), fit = mapOf(block.id to FitChoice.FREEZE.name),
                captions = RecapCaptions(chunkEdits = mapOf(block.id to listOf(100L, 2500L))),
                layers = listOf(Layer("owner-text", LayerType.TEXT, params = LayerParams(textMy = "မူလရေစာ"))))
            val savedRecap = projects.save(recapDir, created.copy(recap = recap)).ok()
            fs.writeDurably("$recapDir/source/movie.mp4", "fixture".encodeToByteArray())
            fs.writeDurably("$recapDir/source/subtitles.srt", "1\n00:00:00,000 --> 00:00:02,000\nမူလစာတန်း\n".encodeToByteArray())
            val current = CurrentProject().also { it.set(OpenProject(recapDir, savedRecap)) }
            fun snapshot() = listOf(root.resolve("မိုး.autoflow"), root.resolve("ရုပ်ရှင်.autoflow")).flatMap { dir ->
                dir.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path to it.readBytes() }.toList()
            }.toMap()
            val original = snapshot()
            val staleBefore = recap.takes.map { SpokenText.isStale(it, block) }
            assertEquals(listOf(false, true), staleBefore)
            val settings = SettingsStore(fs, settingsPath)
            val secrets = MemorySecureStorage()
            val model = SettingsModel(settings, secrets, ProviderStatusModel(settings, secrets), UsageCounter(fs, root.resolve("app/usage.json").path),
                loadModelConfig(fs, null) { TEST_MODELS_JSON }, FakeTools(), FakePicker(), { PlatformResult.Ok(Unit) }, { AiResult.Ok(Unit) })
            var destination = StudioV2Dest.HOME
            var navigations = 0
            setContent { CompositionLocalProvider(LocalCompact provides true, LocalUiFontFamily provides BurmeseTestFont.family) {
                AppLanguage(settings.state) { StudioV2Shell(destination, { destination = it; navigations++ }, 0, 412f) { SettingsScreen(model) } }
            } }
            // P9-17a: the language choices are on Settings → Language.
            onNodeWithTag("settings-row-language").performScrollTo().assertIsDisplayed().performMouseInput { click() }
            for (language in listOf(UiLanguage.BURMESE, UiLanguage.ENGLISH, UiLanguage.BURMESE)) {
                onNodeWithTag("language-${language.code}").performScrollTo().assertIsDisplayed().performMouseInput { click() }
                waitForIdle()
                onNodeWithContentDescription(UiText.HOME.text(language)).assertIsDisplayed().assertIsSelected()
                assertEquals(language.code, SettingsStore(fs, settingsPath).state.value.uiLanguage)
                assertEquals(StudioV2Dest.HOME, destination)
                assertEquals(0, navigations, "a language click cannot dispatch navigation")
                assertEquals(savedRecap, current.state.value!!.project)
                assertEquals(savedMv, projects.open(mvDir).ok())
                val loaded = projects.open(recapDir).ok()
                assertEquals(savedRecap, loaded)
                assertEquals(staleBefore, loaded.recap!!.takes.map { SpokenText.isStale(it, block) })
                assertEquals(SpokenText.hash(block), loaded.recap!!.takes.first().spokenHash)
                val after = snapshot()
                assertEquals(original.keys, after.keys, "no renamed, added or removed project files")
                original.forEach { (path, bytes) -> assertContentEquals(bytes, after.getValue(path), path) }
            }
        } finally { root.deleteRecursively() }
    }
}
