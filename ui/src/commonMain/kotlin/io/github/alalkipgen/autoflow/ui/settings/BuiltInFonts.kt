package io.github.alalkipgen.autoflow.ui.settings

import io.github.alalkipgen.autoflow.core.io.FileSystem
import io.github.alalkipgen.autoflow.core.io.joinPath

/**
 * Burmese fonts shipped inside the app (D-49): Pyidaungsu and Noto Sans Myanmar, regular + bold, from `fonts/`.
 * They are copied once into `<app dir>/fonts/` (rewritten only when missing or a different size), because Android and
 * libass both load fonts from files. UI text and lyric / caption overlays use them without picking anything in
 * Settings; fonts added in Settings are still used too.
 */
class BuiltInFonts(private val fs: FileSystem, private val dir: String?, private val source: BundledConfig) {
    /** Paths of the installed built-in fonts; empty when the app has none or the folder cannot be written. */
    val paths: List<String> by lazy { install() }

    private fun install(): List<String> {
        val target = dir ?: return emptyList()
        return FILES.mapNotNull { name ->
            runCatching {
                val bytes = source.bundledFont(name) ?: return@mapNotNull null
                val path = joinPath(target, name)
                if (!fs.exists(path) || fs.size(path) != bytes.size.toLong()) {
                    fs.createDirectories(target)
                    fs.writeDurably(path, bytes)
                }
                path
            }.getOrNull()
        }
    }

    companion object {
        const val DIR = "fonts"
        val FILES = listOf(
            "Pyidaungsu-Regular.ttf",
            "Pyidaungsu-Bold.ttf",
            "NotoSansMyanmar-Regular.ttf",
            "NotoSansMyanmar-Bold.ttf",
        )

        /** Settings fonts first, then the built-in ones, without duplicates. */
        fun withBuiltIns(settingsFonts: List<String>, builtIn: BuiltInFonts?): List<String> =
            (settingsFonts + builtIn?.paths.orEmpty()).distinct()
    }
}
