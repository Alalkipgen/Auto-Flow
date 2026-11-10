// S3: Compose Desktop video playback via VLCJ (callback video surface -> Skia image, no heavyweight AWT canvas).
// Manual: run and use the controls. Automated (CI): S3_AUTO=1 runs play/pause/seek checks, writes results/<tag>/.
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.clickable
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.ImageInfo
import uk.co.caprica.vlcj.factory.MediaPlayerFactory
import uk.co.caprica.vlcj.factory.discovery.NativeDiscovery
import uk.co.caprica.vlcj.player.base.MediaPlayer
import uk.co.caprica.vlcj.player.base.MediaPlayerEventAdapter
import uk.co.caprica.vlcj.player.embedded.EmbeddedMediaPlayer
import uk.co.caprica.vlcj.player.embedded.videosurface.CallbackVideoSurface
import uk.co.caprica.vlcj.player.embedded.videosurface.VideoSurfaceAdapters
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormat
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.BufferFormatCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.RenderCallback
import uk.co.caprica.vlcj.player.embedded.videosurface.callback.format.RV32BufferFormat
import java.awt.Rectangle
import java.awt.Robot
import java.io.File
import java.nio.ByteBuffer
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.system.exitProcess

class VlcPlayer {
    val factory: MediaPlayerFactory
    val player: EmbeddedMediaPlayer
    var frame by mutableStateOf<ImageBitmap?>(null)
    var timeMs by mutableStateOf(0L)
    var lengthMs by mutableStateOf(0L)
    var playing by mutableStateOf(false)
    @Volatile var frames = 0L
    @Volatile var lastFrameNs = 0L
    @Volatile var srcW = 0
    @Volatile var srcH = 0
    @Volatile var lastSkia: org.jetbrains.skia.Image? = null
    val discovered: Boolean
    private var bytes = ByteArray(0)

    init {
        System.getenv("S3_VLC_DIR")?.let { System.setProperty("jna.library.path", it) }
        discovered = NativeDiscovery().discover()
        val args = (System.getenv("S3_VLC_ARGS") ?: "--quiet").split(" ").filter { it.isNotBlank() }
        factory = MediaPlayerFactory(*args.toTypedArray())
        player = factory.mediaPlayers().newEmbeddedMediaPlayer()
        val formatCallback = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                srcW = sourceWidth; srcH = sourceHeight
                bytes = ByteArray(sourceWidth * sourceHeight * 4)
                return RV32BufferFormat(sourceWidth, sourceHeight)
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val renderCallback = object : RenderCallback {
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                val buf = nativeBuffers[0]
                buf.rewind(); buf.get(bytes); buf.rewind()
                // RV32 is BGRX in memory; alpha byte is undefined, so treat as opaque.
                val img = org.jetbrains.skia.Image.makeRaster(
                    ImageInfo(bufferFormat.width, bufferFormat.height, ColorType.BGRA_8888, ColorAlphaType.OPAQUE),
                    bytes, bufferFormat.width * 4,
                )
                lastSkia = img
                frame = img.toComposeImageBitmap()
                frames++
                lastFrameNs = System.nanoTime()
            }
        }
        player.videoSurface().set(CallbackVideoSurface(formatCallback, renderCallback, true, VideoSurfaceAdapters.getVideoSurfaceAdapter()))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun timeChanged(mediaPlayer: MediaPlayer, newTime: Long) { timeMs = newTime }
            override fun lengthChanged(mediaPlayer: MediaPlayer, newLength: Long) { lengthMs = newLength }
            override fun playing(mediaPlayer: MediaPlayer) { playing = true }
            override fun paused(mediaPlayer: MediaPlayer) { playing = false }
            override fun stopped(mediaPlayer: MediaPlayer) { playing = false }
        })
    }

    fun release() { player.controls().stop(); player.release(); factory.release() }
}

fun fmt(ms: Long) = "%d:%02d.%01d".format(ms / 60000, (ms / 1000) % 60, (ms % 1000) / 100)

@Composable
fun Button(label: String, onClick: () -> Unit) {
    Box(Modifier.background(Color(0xFF2B2B33)).clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 6.dp)) {
        BasicText(label, style = TextStyle(color = Color.White, fontSize = 13.sp))
    }
}

fun main() {
    System.getenv("SKIKO_RENDER_API")?.let { System.setProperty("skiko.renderApi", it) }
    val video = File(System.getenv("S3_VIDEO") ?: "../fixtures/sample.mp4").absoluteFile
    val auto = System.getenv("S3_AUTO") == "1"
    val tag = System.getenv("S3_TAG") ?: System.getProperty("os.name").lowercase().substringBefore(' ')
    val outDir = File("results/$tag").apply { mkdirs() }
    val report = mutableListOf<String>()
    fun log(s: String) { println(s); report += s }
    fun writeReport() = File(outDir, "report.txt").writeText(report.joinToString("\n") + "\n")

    val t0 = System.nanoTime()
    val vlc = try { VlcPlayer() } catch (e: Throwable) {
        log("FAIL: VLC init: ${e.javaClass.simpleName}: ${e.message}"); writeReport(); exitProcess(2)
    }
    log("os=${System.getProperty("os.name")} ${System.getProperty("os.arch")} java=${System.getProperty("java.version")}")
    log("nativeDiscovery=${vlc.discovered} vlc=${vlc.factory.application().version()} initMs=${(System.nanoTime() - t0) / 1_000_000}")
    log("video=${video.name} exists=${video.exists()}")

    application {
        val state = rememberWindowState(size = DpSize(420.dp, 860.dp))
        Window(onCloseRequest = { vlc.release(); exitApplication() }, state = state, title = "S3 VLCJ playback") {
            Column(Modifier.fillMaxSize().background(Color(0xFF111114)).padding(8.dp)) {
                // 9:16 stage; the frame is letterboxed with ContentScale.Fit, like the real preview panel.
                Box(Modifier.fillMaxWidth().aspectRatio(9f / 16f).background(Color.Black), contentAlignment = Alignment.Center) {
                    vlc.frame?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(if (vlc.playing) "Pause" else "Play") { if (vlc.playing) vlc.player.controls().pause() else vlc.player.controls().play() }
                    Button("-2s") { vlc.player.controls().setTime((vlc.timeMs - 2000).coerceAtLeast(0)) }
                    Button("+2s") { vlc.player.controls().setTime(vlc.timeMs + 2000) }
                    BasicText("${fmt(vlc.timeMs)} / ${fmt(vlc.lengthMs)}", style = TextStyle(color = Color.White, fontSize = 13.sp))
                }
            }

            fun shot(name: String) {
                val p = window.contentPane.locationOnScreen
                val img = Robot().createScreenCapture(Rectangle(p.x, p.y, window.contentPane.width, window.contentPane.height))
                ImageIO.write(img, "png", File(outDir, "$name.png"))
                vlc.lastSkia?.encodeToData(EncodedImageFormat.PNG)?.bytes?.let { File(outDir, "$name-frame.png").writeBytes(it) }
            }

            LaunchedEffect(Unit) {
                if (!auto) { vlc.player.media().play(video.path); return@LaunchedEffect }
                try {
                    val ok = mutableListOf<Boolean>()
                    fun check(name: String, pass: Boolean, detail: String) { ok += pass; log("${if (pass) "PASS" else "FAIL"} $name: $detail") }
                    val playNs = System.nanoTime()
                    vlc.player.media().play(video.path)
                    var waited = 0
                    while (vlc.frames == 0L && waited < 15000) { delay(50); waited += 50 }
                    check("first frame", vlc.frames > 0, "after ${(System.nanoTime() - playNs) / 1_000_000} ms; source ${vlc.srcW}x${vlc.srcH}")
                    if (vlc.frames > 0) {
                        check("9:16 source", vlc.srcW * 16 == vlc.srcH * 9, "${vlc.srcW}x${vlc.srcH}")
                        val f0 = vlc.frames; val tA = System.nanoTime(); delay(2000)
                        val fps = (vlc.frames - f0) * 1e9 / (System.nanoTime() - tA)
                        check("playback", fps > 20, "rendered %.1f fps over 2 s; time=%d ms".format(fps, vlc.timeMs))
                        withContext(Dispatchers.IO) { shot("01-playing") }

                        vlc.player.controls().setPause(true); delay(800)
                        val p1 = vlc.player.status().time(); val fp = vlc.frames; delay(1000)
                        val p2 = vlc.player.status().time()
                        check("pause", abs(p2 - p1) < 50 && vlc.frames - fp <= 1, "time $p1 -> $p2 ms, frames +${vlc.frames - fp} while paused")

                        val target = 5000L; val fs = vlc.frames; val sNs = System.nanoTime()
                        vlc.player.controls().setTime(target)
                        waited = 0
                        while (vlc.frames == fs && waited < 3000) { delay(20); waited += 20 }
                        delay(300)
                        val st = vlc.player.status().time()
                        check("seek while paused", abs(st - target) <= 250 && vlc.frames > fs,
                            "target $target -> $st ms; new frame after ${(System.nanoTime() - sNs) / 1_000_000} ms")
                        withContext(Dispatchers.IO) { shot("02-seek-paused") }

                        vlc.player.controls().setPause(false); delay(1500)
                        val r = vlc.player.status().time()
                        check("resume", r > st + 800, "time $st -> $r ms after 1.5 s")
                        val sNs2 = System.nanoTime(); vlc.player.controls().setTime(1000); delay(700)
                        val r2 = vlc.player.status().time()
                        check("seek while playing (backward)", r2 in 900..2200, "target 1000 -> $r2 ms (${(System.nanoTime() - sNs2) / 1_000_000} ms incl. 700 ms wait)")
                        withContext(Dispatchers.IO) { shot("03-resumed") }
                    }
                    log("RESULT ${if (ok.all { it }) "PASS" else "FAIL"} (${ok.count { it }}/${ok.size})")
                    writeReport()
                } catch (e: Throwable) {
                    log("FAIL: exception ${e.javaClass.simpleName}: ${e.message}"); writeReport()
                }
                vlc.release()
                exitApplication()
            }
        }
    }
    exitProcess(0)
}
