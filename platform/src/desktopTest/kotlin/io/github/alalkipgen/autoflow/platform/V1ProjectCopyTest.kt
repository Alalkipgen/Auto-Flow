package io.github.alalkipgen.autoflow.platform

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.hash.Sha256
import io.github.alalkipgen.autoflow.core.model.*
import io.github.alalkipgen.autoflow.core.recap.*
import io.github.alalkipgen.autoflow.core.store.ArtifactStore
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import java.io.File
import java.nio.file.Files
import kotlinx.serialization.json.*
import kotlin.test.*

/** Synthetic projects on a real disk; the current v2 edits and ALL original artifacts must remain byte-identical. */
class V1ProjectCopyTest {
    private val home = Files.createTempDirectory("v1-recovery").toFile()
    private val source = File(home, "Current.autoflow")
    private val dest = File(home, "Recovered.autoflow")
    private val fs = JvmFs()
    private fun <T> CoreResult<T>.ok(): T = assertIs<CoreResult.Ok<T>>(this).value

    @AfterTest fun cleanup() { home.deleteRecursively() }

    private data class Fixture(val raw: JsonObject, val v1: Recap, val script: RecapScript)
    private fun fixture(): Fixture {
        val store = ProjectStore(fs)
        val project = store.create(source.path, "Original", Mode.MOVIE_RECAP).ok()
        val arts = ArtifactStore(fs, source.path)
        fun put(text: String, kind: ArtifactKind, ext: String = "json") =
            arts.put(text.encodeToByteArray(), kind, ext).ok().id
        val script = RecapScript(source = "manual", style = RecapStyle.COMMENTARY, segments = listOf(
            RecapSegment(0, 3000, "written first"), RecapSegment(4000, 7000, "written second"),
        ))
        val rs = put(CoreJson.encodeToString(RecapScript.serializer(), script), ArtifactKind.RECAP_SCRIPT)
        val first = put("immutable first audio", ArtifactKind.AUDIO, "wav")
        val second = put("immutable second audio", ArtifactKind.AUDIO, "wav")
        val bgm = put("immutable music", ArtifactKind.AUDIO, "wav")
        val tx = put("""{"source":"manual","text":"transcript"}""", ArtifactKind.TRANSCRIPT)
        val image = arts.put(byteArrayOf(4, 5, 6), ArtifactKind.IMAGE, "png").ok()
        File(source, "source/movie.mp4").writeBytes(byteArrayOf(1, 2, 3))
        File(source, "source/subtitles.srt").writeText("original subtitles")
        val v1 = Recap(
            rightsConfirmed = true, movie = RecapMovie("source/movie.mp4", 3, 9000, 320, 180),
            script = rs, scriptApproved = true, style = RecapStyle.COMMENTARY, register = RecapRegister.WRITTEN,
            subtitles = "source/subtitles.srt", subtitleTrack = 3, transcript = tx,
            takes = listOf(
                VoiceTake(0, first, VoiceSource.TTS, 2100, RecapRules.textHash("written first"), "known-model", "known-voice", "gemini"),
                VoiceTake(1, second, VoiceSource.RECORDED, 2400, RecapRules.textHash("written second")),
            ),
            fit = mapOf(0 to FitChoice.SPEED), movieVolume = .35, burnCaptions = false, looks = listOf(Look.MIRROR),
            audio = RecapAudio(bgm = bgm), captionStyle = CaptionStyle(colour = "#FF00FF", strokeWidth = 3),
            visual = RecapVisual(background = FrameBackground.IMAGE, backgroundImageFile = "artifacts/${image.file}",
                watermarkFile = "artifacts/${image.file}"),
            publishKit = PublishKit(title = "saved title"), quality = ExportQuality.P1080,
        )
        val raw = JsonObject(CoreJson.encodeToJsonElement(Project.serializer(), project).jsonObject.toMutableMap().apply {
            put("schemaVersion", JsonPrimitive(1)); put("recap", CoreJson.encodeToJsonElement(Recap.serializer(), v1))
        })
        File(source, RecapMigration.BACKUP_FILE).writeText(raw.toString())
        // Deliberately lossy old result with valuable later edits, plus a newer immutable artifact.
        put("valuable post-migration audio", ArtifactKind.AUDIO, "wav")
        store.save(source.path, project.copy(name = "Valuable later edits", recap = RecapV2(targetMs = 60_000))).ok()
        return Fixture(raw, v1, script)
    }

    private fun snapshot(dir: File): Map<String, String> = dir.walkTopDown().filter { it.isFile }
        .associate { it.relativeTo(dir).invariantSeparatorsPath to Sha256.hex(it.readBytes()) }

    private fun refused(before: Map<String, String>): String {
        val result = assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, dest))
        assertFalse(dest.exists(), result.reason)
        assertEquals(before, snapshot(source), "refusal must not change current project, backup or artifacts")
        assertTrue(home.list()!!.none { it.startsWith(".recover-v1-") }, "unpublished staging must be cleaned")
        println("RECOVERY_REFUSED ${result.reason}; sourceUnchanged=true; destinationAbsent=true")
        return result.reason
    }

    @Test fun recoversSeparateVerifiedCopyWithoutTouchingCurrentBackupOrArtifacts() {
        val f = fixture()
        val before = snapshot(source)
        val attempted = V1ProjectCopy.recover(source, dest)
        val result = assertIs<V1ProjectCopy.Result.Recovered>(attempted, attempted.toString())
        assertEquals(before, snapshot(source))
        val recovered = ProjectStore(fs).open(dest.path).ok()
        val recap = recovered.recap!!
        val story = CoreJson.decodeFromString(RecapStory.serializer(),
            ArtifactStore(fs, dest.path).readBytes(recap.story!!).ok().decodeToString())
        assertEquals(f.script.segments.map { it.textMy }, story.blocks.map { it.textMy })
        assertEquals(listOf(0L to 3000L, 4000L to 7000L), story.blocks.map { it.clips.single().let { c -> c.inMs to c.outMs } })
        assertEquals(RecapRegister.WRITTEN, story.register)
        assertTrue(recap.storyApproved); assertEquals(2, recap.takes.size)
        f.v1.takes.zip(recap.takes).forEachIndexed { i, (old, new) ->
            assertEquals(story.blocks[i].id, new.block); assertEquals(old.audio, new.audio)
            assertEquals(old.durationMs, new.durationMs); assertEquals(old.textHash, new.spokenHash)
            assertEquals(old.provider, new.provider); assertEquals(old.model, new.model); assertEquals(old.voice, new.voice)
        }
        assertNull(recap.voiceLock, "missing model settings must not be invented")
        assertEquals(3, recap.sources.subtitleTrack); assertEquals(f.v1.transcript, recap.sources.transcript)
        assertEquals(mapOf(story.blocks.first().id to FitChoice.SPEED.name), recap.fit)
        assertEquals(f.v1.captionStyle, recap.captionStyle); assertEquals(f.v1.visual, recap.visual)
        assertEquals(f.v1.publishKit, recap.publishKit); assertEquals(f.v1.audio.copy(movieVolume = .35), recap.audio)
        assertEquals(f.v1.quality, recap.quality); assertEquals(f.v1.looks, recap.looks); assertFalse(recap.burnCaptions)
        val recoveredRaw = CoreJson.parseToJsonElement(File(dest, "project.json").readText()).jsonObject
        f.raw.filterKeys { it != "schemaVersion" && it != "recap" }.forEach { (key, value) -> assertEquals(value, recoveredRaw[key], "preserve root field $key") }
        before.filterKeys { !it.startsWith(".cache/") }.forEach { (path, hash) ->
            val copied = if (path == "project.json") V1ProjectCopy.CURRENT_COPY else path
            assertEquals(hash, Sha256.hex(File(dest, copied).readBytes()), "preserve $path")
        }
        assertEquals("Valuable later edits", ProjectStore(fs).open(source.path).ok().name)
        println("RECOVERY_VERIFIED blocks=${result.blocks} takes=${result.takes} approved=${result.approved} artifacts=${result.artifacts}; copiedFiles=${result.copiedFiles}; allSourceHashesUnchanged=true; backupIdentical=true; copiedArtifactsIdentical=true")
    }

    @Test fun unknownBackupFieldsAreRefusedRatherThanSilentlyLost() {
        val f = fixture()
        File(source, RecapMigration.BACKUP_FILE).writeText(JsonObject(f.raw.toMutableMap().apply {
            put("ownerExtension", JsonPrimitive("not understood by this schema"))
        }).toString())
        assertContains(refused(snapshot(source)), "not valid")
    }

    @Test fun missingBackupIsRefused() {
        fixture(); File(source, RecapMigration.BACKUP_FILE).delete()
        assertContains(refused(snapshot(source)), "No original")
    }

    @Test fun corruptOrNonV1BackupIsRefused() {
        fixture()
        for (bad in listOf("{broken", """{"schemaVersion":2,"mode":"MOVIE_RECAP"}""")) {
            File(source, RecapMigration.BACKUP_FILE).writeText(bad)
            refused(snapshot(source))
        }
    }

    @Test fun missingAndCorruptReferencedArtifactsAreRefused() {
        val f = fixture()
        val meta = ArtifactStore(fs, source.path).get(f.v1.takes.first().audio).ok()
        val audio = File(source, "artifacts/${meta.file}")
        val original = audio.readBytes()
        audio.delete(); refused(snapshot(source))
        audio.writeText("corrupt audio"); assertContains(refused(snapshot(source)), "Corrupt artifact")
        audio.writeBytes(original)
        val script = ArtifactStore(fs, source.path).get(f.v1.script!!).ok()
        File(source, "artifacts/${script.file}").writeText("{corrupt script")
        refused(snapshot(source))
    }

    @Test fun missingMovieAndOrphanTakeCannotBeInvented() {
        val f = fixture()
        File(source, "source/movie.mp4").delete(); assertContains(refused(snapshot(source)), "project reference")
        File(source, "source/movie.mp4").writeBytes(byteArrayOf(1, 2, 3))
        val orphan = f.v1.copy(takes = f.v1.takes + f.v1.takes.first().copy(segment = 99))
        File(source, RecapMigration.BACKUP_FILE).writeText(JsonObject(f.raw.toMutableMap().apply {
            put("recap", CoreJson.encodeToJsonElement(Recap.serializer(), orphan))
        }).toString())
        assertContains(refused(snapshot(source)), "orphan")
    }

    @Test fun malformedAndWrongKindRecapReferencesAreRefused() {
        val f = fixture()
        val bad = ArtifactId("not-an-artifact")
        val cases = listOf(
            f.v1.copy(transcript = bad),
            f.v1.copy(takes = f.v1.takes.map { it.copy(audio = bad) }),
            f.v1.copy(audio = f.v1.audio.copy(bgm = bad)),
            f.v1.copy(script = bad),
            f.v1.copy(transcript = f.v1.takes.first().audio),
            f.v1.copy(takes = f.v1.takes.map { it.copy(audio = f.v1.script!!) }),
            f.v1.copy(audio = f.v1.audio.copy(bgm = f.v1.script)),
        )
        for (recap in cases) {
            File(source, RecapMigration.BACKUP_FILE).writeText(JsonObject(f.raw.toMutableMap().apply {
                put("recap", CoreJson.encodeToJsonElement(Recap.serializer(), recap))
            }).toString())
            assertContains(refused(snapshot(source)), "artifact reference")
        }
    }

    @Test fun malformedAndWrongKindRootReferencesAreRefused() {
        val f = fixture()
        for (id in listOf(ArtifactId("invalid-analysis"), f.v1.takes.first().audio)) {
            File(source, RecapMigration.BACKUP_FILE).writeText(JsonObject(f.raw.toMutableMap().apply {
                put("analysis", JsonPrimitive(id.value))
            }).toString())
            assertContains(refused(snapshot(source)), "artifact reference")
        }
    }

    @Test fun existingFileDirectoryAndSymlinkDestinationsAreNeverReplaced() {
        fixture(); val before = snapshot(source)
        dest.writeText("existing file")
        assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, dest)); assertEquals("existing file", dest.readText())
        dest.delete(); dest.mkdir()
        assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, dest)); assertTrue(dest.isDirectory); assertTrue(dest.list()!!.isEmpty())
        File(dest, "valuable").writeText("keep")
        assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, dest)); assertEquals("keep", File(dest, "valuable").readText())
        dest.deleteRecursively()
        // Symlink creation may require elevated privileges on Windows; Linux always exercises this case.
        if (!System.getProperty("os.name").startsWith("Windows")) {
            Files.createSymbolicLink(dest.toPath(), source.toPath())
            assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, dest)); assertTrue(Files.isSymbolicLink(dest.toPath()))
            Files.delete(dest.toPath())
        }
        assertEquals(before, snapshot(source))
        println("RECOVERY_COLLISIONS noOverwrite=file,emptyDirectory,nonemptyDirectory; sourceUnchanged=true")
    }

    @Test fun nestedDestinationAndEscapingReferencesAreRefused() {
        val f = fixture(); val before = snapshot(source)
        assertIs<V1ProjectCopy.Result.Refused>(V1ProjectCopy.recover(source, File(source, "Nested.autoflow")))
        assertEquals(before, snapshot(source))
        val bad = f.v1.copy(subtitles = "../outside.srt")
        File(home, "outside.srt").writeText("do not use")
        File(source, RecapMigration.BACKUP_FILE).writeText(JsonObject(f.raw.toMutableMap().apply {
            put("recap", CoreJson.encodeToJsonElement(Recap.serializer(), bad))
        }).toString())
        assertContains(refused(snapshot(source)), "unsafe project reference")
        assertEquals("do not use", File(home, "outside.srt").readText())
    }
}
