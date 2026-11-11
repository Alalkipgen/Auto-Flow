package io.github.alalkipgen.autoflow.ui.handoff

import io.github.alalkipgen.autoflow.core.CoreResult
import io.github.alalkipgen.autoflow.core.io.InMemoryFileSystem
import io.github.alalkipgen.autoflow.core.model.Approvals
import io.github.alalkipgen.autoflow.core.model.ArtifactId
import io.github.alalkipgen.autoflow.core.model.ArtifactKind
import io.github.alalkipgen.autoflow.core.model.CoreJson
import io.github.alalkipgen.autoflow.core.model.Mode
import io.github.alalkipgen.autoflow.core.model.Pass
import io.github.alalkipgen.autoflow.core.model.ProjectSettings
import io.github.alalkipgen.autoflow.core.model.Shot
import io.github.alalkipgen.autoflow.core.prompt.PromptIR
import io.github.alalkipgen.autoflow.core.store.ProjectStore
import io.github.alalkipgen.autoflow.platform.FileShare
import io.github.alalkipgen.autoflow.platform.PlatformResult
import io.github.alalkipgen.autoflow.ui.projects.CurrentProject
import io.github.alalkipgen.autoflow.ui.projects.OpenProject
import io.github.alalkipgen.autoflow.ui.projects.ProjectIndex
import io.github.alalkipgen.autoflow.ui.projects.ProjectSession
import io.github.alalkipgen.autoflow.ui.settings.FakePicker
import kotlin.io.encoding.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Batch handoff sheet (specs/flow-handoff.md): pending shots, ticks, one offline HTML file in `packages/`. */
class BatchSheetTest {
    private val fs = InMemoryFileSystem()
    private val store = ProjectStore(fs)
    private val current = CurrentProject()
    private val session = ProjectSession(fs, store, ProjectIndex(fs, "/h/projects.json"), current)
    private val dir = "/p/Rain.autoflow"
    private val shared = mutableListOf<String>()
    private val share = object : FileShare {
        override fun open(path: String, mime: String): PlatformResult<Unit> = PlatformResult.Ok(Unit).also { shared += "open $path $mime" }
        override fun share(path: String, mime: String, title: String): PlatformResult<Unit> =
            PlatformResult.Unsupported("No app can receive this file").also { shared += "share $path" }
    }
    private val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)

    private val model = FlowHandoffModel(
        session, { PlatformResult.Ok(Unit) }, { PlatformResult.Ok(Unit) }, FakePicker(),
        nowMs = { 1_794_477_600_000L }, fs = fs, fileShare = share,
    )

    private fun ir(n: Int) = PromptIR(
        shot = n, subject = "A girl <in> the \"rain\" & wind", action = "walks", setting = "street", camera = "close-up",
        lighting = "neon", mood = "sad", style = "anime", characters = emptyList(), negative = "", ratio = "9:16", durationS = 8,
    )

    private fun put(bytes: ByteArray, kind: ArtifactKind, ext: String, shot: Int): ArtifactId =
        (session.artifacts()!!.put(bytes, kind, ext, shot) as CoreResult.Ok).value.id

    /** Shot 1 ready (prompt + approved ref), 2 ready but Final, 3 ref not approved, 4 accepted, 5 no prompt. */
    private fun open() {
        val p = (store.create(dir, "Rain", Mode.MUSIC_VIDEO, ProjectSettings()) as CoreResult.Ok).value
        current.set(OpenProject(dir, p))
        fun prompt(n: Int) = put(CoreJson.encodeToString(PromptIR.serializer(), ir(n)).encodeToByteArray(), ArtifactKind.PROMPT_IR, "json", n)
        val ref1 = put(png, ArtifactKind.IMAGE, "png", 1)
        val ref2 = put(byteArrayOf(9, 9), ArtifactKind.IMAGE, "jpg", 2)
        val ok = Approvals(ref = true)
        val shots = listOf(
            Shot(1, 0, 8_000, lyricsMy = "မိုးတွေရွာ", promptIr = prompt(1), chosenRef = ref1, approvals = ok),
            Shot(2, 8_000, 7_500, promptIr = prompt(2), chosenRef = ref2, approvals = ok).let { it.copy(handoff = it.handoff.copy(pass = Pass.FINAL)) },
            Shot(3, 15_500, 8_000, promptIr = prompt(3), chosenRef = ref1),
            Shot(4, 23_500, 8_000, promptIr = prompt(4), chosenRef = ref1, approvals = ok, acceptedVideo = ref1),
            Shot(5, 31_500, 8_000),
        )
        session.save(p.copy(shots = shots))
    }

    @Test
    fun onlyPendingReadyShotsCanGoOnTheSheetAndStartTicked() {
        open()
        val s = model.current()
        assertEquals(listOf(1, 2), s.rows.filter { it.batchable }.map { it.number })
        assertEquals(listOf(1, 2), s.rows.filter { it.inBatch }.map { it.number })
        assertEquals(2, s.batchCount)
        assertEquals("Batch sheet — 2 of 2 pending shots ticked", batchSummary(2, 2))

        model.toggleBatch(2)
        assertEquals(1, model.current().batchCount)
        model.toggleBatch(2)
        assertEquals(2, model.current().batchCount)
        model.toggleBatch(3)
        assertFalse(model.current().rows.first { it.number == 3 }.inBatch, "a blocked shot stays off")
    }

    @Test
    fun exportWritesOneOfflineHtmlWithPromptsImagesAndCopyButtons() {
        open()
        model.toggleBatch(2)
        model.exportBatch()
        val path = "$dir/packages/batch-2026-11-12.html"
        assertTrue(fs.exists(path))
        val html = fs.read(path).decodeToString()
        assertTrue("Shot 01" in html)
        assertFalse("Shot 02" in html, "unticked")
        assertTrue("data:image/png;base64,${Base64.encode(png)}" in html, "image inline")
        assertTrue("&lt;in&gt; the &quot;rain&quot; &amp; wind" in html, "prompt text escaped")
        assertTrue("<button onclick=\"cp(1, this)\">Copy prompt</button>" in html)
        assertTrue("မိုးတွေရွာ" in html)
        assertFalse("http" in html, "no network")
        assertEquals("packages/batch-2026-11-12.html", model.current().lastBatch)
        assertEquals("Batch sheet saved: packages/batch-2026-11-12.html (1 shot). Open it in a browser next to Flow.", model.current().message)

        model.toggleBatch(2)
        model.exportBatch()
        assertEquals("packages/batch-2026-11-12-2.html", model.current().lastBatch, "the first sheet is kept")
        assertTrue("Final (Quality)" in fs.read("$dir/packages/batch-2026-11-12-2.html").decodeToString())
        assertTrue("data:image/jpeg;base64," in fs.read("$dir/packages/batch-2026-11-12-2.html").decodeToString())
    }

    @Test
    fun openAndShareUseTheLastSheet() {
        open()
        model.openBatch()
        assertTrue(shared.isEmpty(), "nothing exported yet")
        model.exportBatch()
        model.openBatch()
        model.shareBatch()
        assertEquals(listOf("open $dir/packages/batch-2026-11-12.html text/html", "share $dir/packages/batch-2026-11-12.html"), shared)
        assertEquals("No app can receive this file", model.current().message)
    }

    @Test
    fun nothingTickedWritesNothing() {
        open()
        model.toggleBatch(1)
        model.toggleBatch(2)
        model.exportBatch()
        assertNull(model.current().lastBatch)
        assertFalse(fs.exists("$dir/packages/batch-2026-11-12.html"))
        assertEquals("Tick at least one pending shot for the batch sheet", model.current().message)
    }

    @Test
    fun fileNamesNeverOverwrite() {
        assertEquals("batch-2026-11-12.html", BatchSheet.fileName("2026-11-12") { false })
        val taken = setOf("batch-2026-11-12.html", "batch-2026-11-12-2.html")
        assertEquals("batch-2026-11-12-3.html", BatchSheet.fileName("2026-11-12") { it in taken })
        assertEquals("a &lt;b&gt; &#39;c&#39;", BatchSheet.esc("a <b> 'c'"))
    }
}
