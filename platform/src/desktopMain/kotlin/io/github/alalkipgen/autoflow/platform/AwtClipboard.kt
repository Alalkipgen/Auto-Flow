package io.github.alalkipgen.autoflow.platform

import java.awt.GraphicsEnvironment
import java.awt.HeadlessException
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.awt.datatransfer.Clipboard as AwtSystemClipboard

/**
 * [Clipboard] on the AWT system clipboard. Windows briefly locks the clipboard while another app reads it
 * (`IllegalStateException`), so a copy is retried a few times. [target] is the clipboard to write; null = none.
 */
class AwtClipboard(
    private val target: () -> AwtSystemClipboard? = ::systemClipboardOrNull,
    private val sleep: (Long) -> Unit = Thread::sleep,
) : Clipboard {
    override fun copyText(text: String): PlatformResult<Unit> {
        val clipboard = target() ?: return PlatformResult.Unsupported("No system clipboard in this session")
        val selection = StringSelection(text)
        for (attempt in 1..ATTEMPTS) {
            try {
                clipboard.setContents(selection, selection)
                return PlatformResult.Ok(Unit)
            } catch (_: IllegalStateException) {
                if (attempt < ATTEMPTS) sleep(RETRY_DELAY_MS * attempt)
            }
        }
        return PlatformResult.Failed("The clipboard is in use by another app. Try again.")
    }

    private companion object {
        const val ATTEMPTS = 3
        const val RETRY_DELAY_MS = 30L
    }
}

private fun systemClipboardOrNull(): AwtSystemClipboard? {
    if (GraphicsEnvironment.isHeadless()) return null
    return try {
        Toolkit.getDefaultToolkit().systemClipboard
    } catch (_: HeadlessException) {
        null
    }
}
