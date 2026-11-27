package io.github.alalkipgen.autoflow.ui.export

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.*
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import io.github.alalkipgen.autoflow.core.recap.PublishChapter
import io.github.alalkipgen.autoflow.core.recap.PublishKit
import io.github.alalkipgen.autoflow.core.recap.PublishKitText
import io.github.alalkipgen.autoflow.media.audio.LoudnessMeasurement
import io.github.alalkipgen.autoflow.media.render.Quality
import io.github.alalkipgen.autoflow.media.render.RenderResult
import io.github.alalkipgen.autoflow.media.render.RenderState
import io.github.alalkipgen.autoflow.ui.BurmeseTestFont
import io.github.alalkipgen.autoflow.ui.components.AfText
import io.github.alalkipgen.autoflow.ui.components.LocalCompact
import io.github.alalkipgen.autoflow.ui.components.SectionCard
import io.github.alalkipgen.autoflow.ui.i18n.*
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageContent
import io.github.alalkipgen.autoflow.ui.pack.RenderPackageUi
import io.github.alalkipgen.autoflow.ui.textFitProblems
import io.github.alalkipgen.autoflow.ui.theme.AfType
import io.github.alalkipgen.autoflow.ui.theme.AutoFlowColors
import io.github.alalkipgen.autoflow.ui.theme.LocalUiFontFamily
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO
import kotlin.test.*

/**
 * The Export step's inner cards in their states (Group 2 D 23 · 1–8 and 24 · 1–5), each under its label in an
 * `export-state-n` box: the Publish Kit open (saved kit · chapters · thumbnail made, then the title changed) and new, the
 * thumbnail making / not made (the card's thumbnail part, as in the sample), the Render package open / packing 45 % /
 * saved on a phone (Share) and on a computer (Open folder); the Render card queued · failed + log · cancelled · last renders
 * · rendering not available here. Shared by [ExportInnerGlassTest] and the screenshots.
 */
internal object ExportInnerStates {
    val KIT = PublishKit(title = "Interstellar ကို ၄ မိနစ်နဲ့ — အချိန်နဲ့ ပြိုင်တဲ့ ခရီး",
        description = "ကမ္ဘာမြေ အစာငတ်ဘေးကနေ လူသားတွေကို ကယ်ဖို့ Cooper ရဲ့ အာကာသ ခရီး။ Christopher Nolan ရဲ့ Interstellar (2014) ကို ၄ မိနစ်နဲ့ အကျဉ်းချုပ် ပြန်ပြောပြထားပါတယ်။",
        hashtags = listOf("#Interstellar", "#MovieRecap", "#မြန်မာ", "#ရုပ်ရှင်"))
    /** One of RecapFrames' own thumbnail failures. */
    const val THUMB_FAILED = "The frame was made but cannot be read."
    const val REASON = "ffmpeg exited with code 1"
    val TAIL = listOf("frame= 5876 fps= 61 q=28.0 size=48128kB time=00:03:15.86", "[Parsed_overlay_4 @ 0x7a1c40] Error while filtering",
        "Error while filtering: Cannot allocate memory", "[out#0/mp4 @ 0x7a20c0] Error writing trailer", "Error closing file: Cannot allocate memory",
        "Conversion failed!")
    const val PHONE = "Download/Interstellar-render-package.zip"
    const val DESKTOP = "D:\\AutoFlow\\Interstellar.autoflow\\exports\\Interstellar-render-package.zip"
    fun saved(location: String) = UiNotice(UiText.PACKAGE_SAVED, "location" to location, "size" to "412 MB", "n" to 37)
    val LOUDNESS = RenderState.Loudness(-14.0, LoudnessMeasurement(-19.3, -1.5, 7.0, -29.6, .2), null)

    @Composable
    private fun state(n: Int, content: @Composable ColumnScope.() -> Unit) =
        Column(Modifier.fillMaxWidth().testTag("export-state-$n"), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)

    /** 23 · 1–8 ([thumb] = a made thumbnail of [kit]); [onPack] / [onShare] / [onOpenFolder] = the Render package buttons. */
    @Composable
    fun PublishSheet(model: ExportModel, kit: PublishKit, chapters: List<PublishChapter>, thumb: PublishThumb, dir: String,
        onPack: () -> Unit = {}, onShare: (String) -> Unit = {}, onOpenFolder: () -> Unit = {}, label: @Composable (String) -> Unit) {
        label("1 · Publish Kit · open (saved kit · chapters from the story · thumbnail made, then the title changed)")
        state(1) { PublishKitCard(kit, chapters, thumb.copy(stale = true), model, studio = true, initiallyExpanded = true) }
        label("2 · Publish Kit · new (no title yet · story not approved)")
        state(2) { PublishKitCard(PublishKit(), emptyList(), null, model, studio = true, initiallyExpanded = true) }
        label("3 · Thumbnail · making (the card's thumbnail part)")
        state(3) { SectionCard("Publish Kit", tile = ExportTiles.PublishKit) { GlassPublishThumb(kit, thumb.copy(working = true), model) } }
        label("4 · Thumbnail · could not be made (the card's thumbnail part)")
        state(4) { SectionCard("Publish Kit", tile = ExportTiles.PublishKit) {
            GlassPublishThumb(kit, thumb.copy(frame = null, failed = THUMB_FAILED), model)
        } }
        val zip = "$dir/exports/Interstellar-render-package.zip"
        @Composable fun pack(s: RenderPackageUi, phone: Boolean) =
            RenderPackageContent(s, phone, onPack, onShare, onOpenFolder, tile = ExportTiles.Package, initiallyExpanded = true)
        label("5 · Render package · open")
        state(5) { pack(RenderPackageUi(dir), phone = true) }
        label("6 · Render package · packing 45 %")
        state(6) { pack(RenderPackageUi(dir, busy = true, progress = .45f), phone = true) }
        label("7 · Render package · saved (phone: Share)")
        state(7) { pack(RenderPackageUi(dir, zip = zip, notice = saved(PHONE), ok = true), phone = true) }
        label("8 · Render package · saved (computer: Open folder)")
        state(8) { pack(RenderPackageUi(dir, zip = zip, notice = saved(DESKTOP), ok = true), phone = false) }
    }

    /** 24 · 1–5: the Render card of [dir]'s export. */
    @Composable
    fun RenderSheet(model: ExportModel, dir: String, label: @Composable (String) -> Unit) {
        val output = "$dir/exports/export.mp4"
        fun run(state: RenderState) = ExportUiState(projectName = "Interstellar", projectDir = dir, run = ExportRun(dir, Quality.EXPORT, "render-1", output, state))
        label("1 · Queued (another render is running)")
        state(1) { RenderCard(run(RenderState.Queued), model, studio = true) }
        label("2 · Failed · reason + the last 6 lines of the ffmpeg log")
        state(2) { RenderCard(run(RenderState.Failed(REASON, listOf("ffmpeg version 7.1") + TAIL)), model, studio = true) }
        label("3 · Cancelled")
        state(3) { RenderCard(run(RenderState.Cancelled), model, studio = true) }
        label("4 · Project reopened · last renders (Export + Preview)")
        state(4) { RenderCard(ExportUiState(projectName = "Interstellar", projectDir = dir, lastRenders = listOf(
            LastRender(dir, Quality.EXPORT, "$dir/exports/Interstellar_16x9.mp4", RenderResult("Interstellar_16x9.mp4", 252_000, 96_000, 1, LOUDNESS)),
            LastRender(dir, Quality.PREVIEW, "$dir/exports/Interstellar_preview.mp4", RenderResult("Interstellar_preview.mp4", 252_000, 31_000, 1)),
        )), model, studio = true) }
        label("5 · Computer · rendering not available yet")
        state(5) { RenderCard(ExportUiState(projectName = "Interstellar", projectDir = dir, unavailable = ExportModel.NO_QUEUE), model, studio = true) }
    }
}

/**
 * Group 2 D 23–24 (PR 12): the Export step's inner cards in the glass look — Publish Kit glass fields with small Copy buttons
 * (`copy`), the chapters one line each in the dark box (cut with … but readable as its description), Save / Discard / Share
 * file and the thumbnail buttons with icons, stale / failed as amber rows without "⚠"; the Render package's info row, glass
 * job card at the same fraction, the saved callout and Share (phone) / Open folder (computer); the Render card's failed
 * row with the log's last 6 lines, the last renders and "not available" as an amber callout. Every text fits a 360 dp
 * phone, the buttons still do their actions, looking writes nothing.
 */
@OptIn(ExperimentalTestApi::class)
class ExportInnerGlassTest {
    private val symbols = listOf("✓", "⚠", "⛔", "ℹ", "📦", "🎬", "⏳")

    private fun ComposeUiTest.texts(): List<String> = onAllNodes(SemanticsMatcher("any") { true }, useUnmergedTree = true)
        .fetchSemanticsNodes().flatMap { node -> node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } }

    private fun png(): ByteArray = ByteArrayOutputStream().use { out ->
        ImageIO.write(BufferedImage(160, 90, BufferedImage.TYPE_INT_RGB), "png", out); out.toByteArray()
    }

    @Test fun exportInnerCardsShowTheGlassLookAndKeepTheirActions() {
        for ((language, font) in listOf(UiLanguage.ENGLISH to 1f, UiLanguage.BURMESE to 2f)) runDesktopComposeUiTest(width = 360, height = 760) {
            ExportHarness().use { h ->
                h.model.refresh()
                val before = h.bytes()
                val dir = h.fixture.dir.path
                val recap = h.model.state.value.recap!!
                val kit = recap.publishKit
                val file = "$dir/exports/export.thumb.jpg"
                val thumb = PublishThumb(dir, file, 57_800, kit.title, frame = RecapFrames.Frame(57_800, file, png()))
                val packed = mutableListOf<String>()
                setContent { CompositionLocalProvider(LocalCompact provides true, LocalDensity provides Density(1f, font),
                    LocalUiLanguage provides language, LocalUiFontFamily provides BurmeseTestFont.family) {
                    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(12.dp).testTag("states"),
                        verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        val label: @Composable (String) -> Unit = { AfText(it, color = AutoFlowColors.PrimaryText, type = AfType.Caption) }
                        ExportInnerStates.PublishSheet(h.model, kit, recap.chapters, thumb, dir, { packed += "pack" }, { packed += "share:$it" },
                            { packed += "folder" }, label)
                        Column(Modifier.testTag("render-states"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            ExportInnerStates.RenderSheet(h.model, dir, label)
                        }
                    }
                } }
                waitForIdle()
                fun text(key: UiText, vararg args: Pair<String, Any?>) = key.text(language, *args)
                fun inState(n: Int, render: Boolean = false) = hasAnyAncestor(hasTestTag("export-state-$n")) and
                    (if (render) hasAnyAncestor(hasTestTag("render-states")) else !hasAnyAncestor(hasTestTag("render-states")))
                fun icons(tag: String, n: Int) = onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasTestTag(tag) and inState(n)), useUnmergedTree = true)
                fun shown(tag: String, n: Int, render: Boolean = false) = onNode(hasTestTag(tag) and inState(n, render), useUnmergedTree = true)
                    .fetchSemanticsNode().config[SemanticsProperties.Text].joinToString("") { it.text }.replace("\u200B", "")

                // 23 · 1: every Publish Kit button has its icon (copy ×4, check, arrow-counter-clockwise, arrow-clockwise, share).
                for (tag in listOf("publish-copy-title", "publish-copy-chapters", "publish-copy-description", "publish-copy-hashtags",
                    "publish-save", "publish-discard", "publish-thumb-make", "publish-thumb-share")) icons(tag, 1).assertCountEquals(1)
                onAllNodes(hasTestTag("button-icon") and hasAnyAncestor(hasClickAction() and hasAnyDescendant(hasText(text(UiText.SHARE_FILE))) and inState(1)),
                    useUnmergedTree = true).assertCountEquals(1)
                // The chapters: one line each, cut with … only on screen (the whole line is its description).
                val lines = PublishKitText.chapterLines(PublishKitText.withChapters(kit, recap.chapters).chapters)
                assertTrue(lines.isNotEmpty())
                for (line in lines) onNode(hasContentDescription(line) and hasAnyAncestor(hasTestTag("publish-chapters") and inState(1))).assertExists()
                onNode(hasText(text(UiText.CHAPTERS_FROM_SCRIPT).trim()) and inState(1)).assertExists()
                onNode(hasTestTag("publish-thumb-image") and inState(1)).assertExists()
                onNode(hasTestTag("publish-thumb-make") and inState(1)).assertIsEnabled().assert(hasText(text(UiText.REMAKE_THUMBNAIL)))
                onNode(hasTestTag("publish-thumb-share") and inState(1)).assertIsEnabled()
                assertEquals(text(UiText.THUMBNAIL_STALE, "STALE" to text(UiText.STALE)), shown("publish-thumb-stale", 1))
                onNode(hasTestTag("publish-copy-title") and inState(1)).performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(kit.title, h.copied.last())
                // 2: no title yet, no chapters: the empty line, Make disabled with its hint row.
                onNode(hasText(text(UiText.CHAPTERS_EMPTY)) and inState(2)).assertExists()
                onNode(hasTestTag("publish-thumb-make") and inState(2)).assertIsNotEnabled()
                onNode(hasTestTag("publish-thumb-share") and inState(2)).assertIsNotEnabled()
                onNode(hasText(text(UiText.THUMBNAIL_TITLE_HINT, "SAVE" to text(UiText.SAVE))) and inState(2)).assertExists()
                // 3: making — both disabled, no stale row while working; 4: the failure as an amber row, Make enabled again.
                onNode(hasTestTag("publish-thumb-make") and inState(3)).assertIsNotEnabled().assert(hasText(text(UiText.THUMBNAIL_WORKING)))
                onNode(hasTestTag("publish-thumb-share") and inState(3)).assertIsNotEnabled()
                onAllNodes(hasTestTag("publish-thumb-stale") and inState(3)).assertCountEquals(0)
                assertEquals(ExportInnerStates.THUMB_FAILED, shown("publish-thumb-failed", 4))
                onNode(hasTestTag("publish-thumb-make") and inState(4)).assertIsEnabled().assert(hasText(text(UiText.MAKE_THUMBNAIL)))
                onNode(hasTestTag("publish-thumb-share") and inState(4)).assertIsNotEnabled()

                // 23 · 5–8: Render package.
                onNode(hasText(My.breakSyllables(text(UiText.PACKAGE_HINT))) and inState(5)).assertExists()
                icons("render-package-make", 5).assertCountEquals(1)
                onNode(hasTestTag("render-package-make") and inState(5)).performSemanticsAction(SemanticsActions.OnClick)
                onAllNodes(hasTestTag("render-package-make") and inState(6)).assertCountEquals(0)
                onAllNodes(hasTestTag("card-tile") and hasAnyAncestor(hasTestTag("render-package-busy") and inState(6)), useUnmergedTree = true).assertCountEquals(1)
                onNode(SemanticsMatcher.expectValue(SemanticsProperties.ProgressBarRangeInfo, ProgressBarRangeInfo(.45f, 0f..1f)) and inState(6)).assertExists()
                for ((n, location) in listOf(7 to ExportInnerStates.PHONE, 8 to ExportInnerStates.DESKTOP))
                    assertEquals(ExportInnerStates.saved(location).text(language), shown("render-package-notice", n))
                icons("render-package-share", 7).assertCountEquals(1); onAllNodes(hasTestTag("render-package-folder") and inState(7)).assertCountEquals(0)
                icons("render-package-folder", 8).assertCountEquals(1); onAllNodes(hasTestTag("render-package-share") and inState(8)).assertCountEquals(0)
                onNode(hasTestTag("render-package-share") and inState(7)).performSemanticsAction(SemanticsActions.OnClick)
                onNode(hasTestTag("render-package-folder") and inState(8)).performSemanticsAction(SemanticsActions.OnClick)
                assertEquals(listOf("pack", "share:${text(UiText.PACKAGE_TITLE)}", "folder"), packed)

                // 24 · 1–5: the Render card.
                val what = text(UiText.EXPORT)
                assertEquals(text(UiText.RENDER_QUEUED, "what" to what), shown("export-status", 1, render = true))
                onNode(hasClickAction() and hasText(text(UiText.CANCEL)) and inState(1, render = true)).assertExists()
                assertEquals(text(UiText.RENDER_FAILED, "what" to what, "reason" to ExportInnerStates.REASON), shown("export-status", 2, render = true))
                // The last 6 log lines only (the first line of the tail is not shown).
                for (line in ExportInnerStates.TAIL) assertEquals(1, onAllNodes(inState(2, render = true), useUnmergedTree = true).fetchSemanticsNodes()
                    .count { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text.replace("\u200B", "") == line } }, line)
                onAllNodes(hasText("ffmpeg version", substring = true) and inState(2, render = true)).assertCountEquals(0)
                assertEquals(text(UiText.RENDER_CANCELLED, "what" to what), shown("export-status", 3, render = true))
                onAllNodes(hasTestTag("export-last") and inState(4, render = true), useUnmergedTree = true).assertCountEquals(2)
                onAllNodes(hasTestTag("export-last-loudness") and inState(4, render = true), useUnmergedTree = true).assertCountEquals(1)
                onAllNodes(hasTestTag("button-icon") and inState(4, render = true), useUnmergedTree = true).assertCountEquals(4)
                onAllNodes(hasClickAction() and hasText(text(UiText.OPEN)) and inState(4, render = true))[0].performSemanticsAction(SemanticsActions.OnClick)
                assertEquals("open:$dir/exports/Interstellar_16x9.mp4", h.shared.last())
                onNode(hasText(text(UiText.EXPORT_NO_QUEUE)) and inState(5, render = true)).assertExists()

                val found = texts().filter { t -> symbols.any { it in t } }
                assertEquals(emptyList(), found, "${language.code} labels with symbols")
                assertEquals(emptyList(), textFitProblems(360, font), "${language.code} $font")
                assertEquals(before, h.bytes(), "looking must not write the project")
            }
        }
    }
}
