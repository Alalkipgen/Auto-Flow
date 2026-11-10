package io.github.alalkipgen.autoflow.platform

import java.awt.Desktop
import java.awt.GraphicsEnvironment
import java.net.URI

/** Something that shows a URI in the user's browser; may throw. */
fun interface Browser {
    fun browse(uri: URI)
}

/** [UrlOpener] via `java.awt.Desktop`, or the OS opener command when AWT cannot browse. [browser] null = none. */
class DesktopUrlOpener(private val browser: Browser? = defaultBrowser()) : UrlOpener {
    override fun open(url: String): PlatformResult<Unit> {
        if (!isWebUrl(url)) return PlatformResult.Failed("Only http and https links can be opened")
        val b = browser ?: return PlatformResult.Unsupported("No web browser available in this session")
        return try {
            b.browse(URI(url.trim()))
            PlatformResult.Ok(Unit)
        } catch (e: Exception) {
            PlatformResult.Failed("Could not open the browser: ${e.message ?: e::class.simpleName}")
        }
    }
}

private fun defaultBrowser(): Browser? {
    if (GraphicsEnvironment.isHeadless()) return null
    if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
        return Browser { Desktop.getDesktop().browse(it) }
    }
    val os = System.getProperty("os.name").orEmpty().lowercase()
    val command = when {
        "win" in os -> listOf("rundll32", "url.dll,FileProtocolHandler")
        "mac" in os -> listOf("open")
        else -> listOf("xdg-open")
    }
    // No shell: the URL is one argument, and isWebUrl already limited it to http(s).
    return Browser { uri ->
        ProcessBuilder(command + uri.toASCIIString())
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start()
    }
}
