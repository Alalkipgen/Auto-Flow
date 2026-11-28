package io.github.alalkipgen.autoflow.ui.theme

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/** DESIGN.md §9.1 rule 6: app colours live in the theme; `Color(0x…)` elsewhere only on the allow-list below. */
class ColourLiteralScanTest {
    /** These turn a user's `#RRGGBB` caption/layer/Brand Kit colour into a Color; they hold no app colour. */
    private val allowed = setOf(
        "ui/src/commonMain/kotlin/io/github/alalkipgen/autoflow/ui/library/LibraryScreen.kt",
        "ui/src/commonMain/kotlin/io/github/alalkipgen/autoflow/ui/recap/NativeFramePainter.kt",
        "ui/src/commonMain/kotlin/io/github/alalkipgen/autoflow/ui/recap/RecapPictureControls.kt",
        "ui/src/commonMain/kotlin/io/github/alalkipgen/autoflow/ui/subtitles/BurnedCaption.kt",
    )

    @Test fun colourLiteralsLiveOnlyInTheTheme() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }.first { File(it, "settings.gradle.kts").isFile }
        val hits = listOf("ui", "app-android", "app-desktop").flatMap { module ->
            val src = File(root, "$module/src")
            src.listFiles().orEmpty().filter { it.name == "main" || it.name.endsWith("Main") }.flatMap { set ->
                set.walkTopDown().filter { it.isFile && it.extension == "kt" && "Color(0x" in it.readText() }
                    .map { it.relativeTo(root).invariantSeparatorsPath }.toList()
            }
        }.filterNot { "/theme/" in it || it in allowed }
        assertEquals(emptyList(), hits.sorted())
    }
}
