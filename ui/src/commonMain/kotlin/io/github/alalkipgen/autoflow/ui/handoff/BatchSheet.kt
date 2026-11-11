package io.github.alalkipgen.autoflow.ui.handoff

import kotlin.io.encoding.Base64

/** One shot on the batch sheet; [image] is the chosen reference (png/jpg bytes), shown inline. */
data class BatchShot(
    val number: Int,
    val timecode: String,
    val durationMs: Long,
    val pass: String,
    val lyric: String,
    val prompt: String,
    val image: ByteArray? = null,
    val imageExt: String = "png",
)

/**
 * Batch handoff sheet (specs/flow-handoff.md): one offline HTML file with every selected shot — prompt, reference
 * image inline (data URI) and a Copy button (plain JS, no network). Opened in a phone or desktop browser next to Flow.
 */
object BatchSheet {
    fun html(projectName: String, ratio: String, date: String, shots: List<BatchShot>): String = buildString {
        append("<!DOCTYPE html>\n<html lang=\"my\"><head><meta charset=\"utf-8\">")
        append("<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">")
        append("<title>").append(esc("$projectName — batch $date")).append("</title>")
        append("<style>").append(CSS).append("</style></head><body>\n")
        append("<h1>").append(esc(projectName)).append("</h1>")
        append("<p class=\"muted\">").append(esc("Batch sheet · $date · ${shots.size} shot(s) · $ratio")).append("</p>\n")
        shots.forEach { s ->
            append("<section class=\"shot\">")
            append("<h2>").append("Shot ").append(s.number.toString().padStart(2, '0')).append("</h2>")
            append("<p class=\"meta\">").append(esc("${s.timecode} · ${seconds(s.durationMs)} · $ratio · ${s.pass}")).append("</p>")
            if (s.lyric.isNotBlank()) append("<p class=\"lyric\">").append(esc(s.lyric)).append("</p>")
            s.image?.let { bytes ->
                val mime = if (s.imageExt.lowercase() in setOf("jpg", "jpeg")) "image/jpeg" else "image/png"
                append("<img alt=\"").append(esc("Shot ${s.number} reference")).append("\" src=\"data:").append(mime)
                    .append(";base64,").append(Base64.encode(bytes)).append("\">")
            }
            append("<pre id=\"p").append(s.number).append("\">").append(esc(s.prompt)).append("</pre>")
            append("<button onclick=\"cp(").append(s.number).append(", this)\">Copy prompt</button>")
            append("</section>\n")
        }
        append("<script>").append(JS).append("</script>\n</body></html>\n")
    }

    /** `packages/batch-<date>.html`, or `-2`, `-3`… when that name is taken. */
    fun fileName(date: String, taken: (String) -> Boolean): String {
        val base = "batch-$date"
        if (!taken("$base.html")) return "$base.html"
        var n = 2
        while (taken("$base-$n.html")) n++
        return "$base-$n.html"
    }

    internal fun esc(text: String): String = buildString(text.length) {
        text.forEach { c ->
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&#39;")
                else -> append(c)
            }
        }
    }

    private fun seconds(ms: Long): String {
        val tenths = (ms + 50) / 100
        return if (tenths % 10 == 0L) "${tenths / 10} s" else "${tenths / 10}.${tenths % 10} s"
    }

    private const val CSS =
        "body{background:#0f1117;color:#e6e8ee;font-family:'Noto Sans Myanmar','Padauk',system-ui,sans-serif;" +
            "max-width:860px;margin:0 auto;padding:16px}" +
            "h1{font-size:20px;margin:0 0 4px}.muted,.meta{color:#8b93a7;font-size:13px;margin:4px 0}" +
            ".shot{background:#171a23;border:1px solid #2a2f3d;border-radius:10px;padding:12px;margin:12px 0}" +
            "h2{font-size:16px;margin:0}.lyric{font-size:14px;margin:6px 0}" +
            "img{max-width:100%;max-height:420px;border-radius:8px;display:block;margin:8px 0}" +
            "pre{white-space:pre-wrap;word-break:break-word;background:#0f1117;border:1px solid #2a2f3d;" +
            "border-radius:8px;padding:8px;font-size:13px}" +
            "button{background:#7c5cff;color:#fff;border:0;border-radius:8px;padding:10px 16px;font-size:14px}"

    /** Clipboard API when allowed, else select + execCommand (file:// pages on some browsers). */
    private const val JS =
        "function cp(n,b){var t=document.getElementById('p'+n).textContent;" +
            "function done(){b.textContent='Copied ✓';setTimeout(function(){b.textContent='Copy prompt'},1500)}" +
            "function old(){var a=document.createElement('textarea');a.value=t;document.body.appendChild(a);a.select();" +
            "try{document.execCommand('copy');done()}catch(e){}document.body.removeChild(a)}" +
            "if(navigator.clipboard&&window.isSecureContext){navigator.clipboard.writeText(t).then(done,old)}else{old()}}"
}
