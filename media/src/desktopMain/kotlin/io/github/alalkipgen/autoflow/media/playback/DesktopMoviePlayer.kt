package io.github.alalkipgen.autoflow.media.playback

import com.sun.jna.NativeLibrary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.io.File
import java.nio.ByteBuffer

/** D-07 / D-16: lazy installed-VLC discovery. Merely starting Auto Flow does not load libVLC. */
object DesktopMoviePlayer {
    private val discoveryLock = Any()
    private var loadedFrom: String? = null
    private var loaded = false

    fun open(path: String, configuredVlcDir: String? = null, muted: Boolean = true): MovieOpen = synchronized(discoveryLock) {
        if (!File(path).isFile) return MovieOpen.Unavailable("Movie file is unavailable. Still / Exact frame remain available.")
        val dir = configuredVlcDir?.let { File(it).absoluteFile.normalize() }
        if (dir != null && (!dir.isDirectory || listOf("libvlc.dll", "libvlc.so.5", "libvlc.so", "libvlc.dylib").none { File(dir, it).isFile })) {
            return MovieOpen.Unavailable("VLC path does not contain libVLC. Set a compatible 64-bit VLC 3.0.x folder in Settings, or clear it for discovery.")
        }
        if (loaded && dir?.path != loadedFrom) {
            return MovieOpen.Unavailable("VLC path changed after loading libVLC. Restart Auto Flow to use the new Settings path.")
        }
        var factory: MediaPlayerFactory? = null
        var player: EmbeddedMediaPlayer? = null
        try {
            if (!loaded) {
                if (dir != null) {
                    NativeLibrary.addSearchPath("vlc", dir.path)
                    NativeLibrary.addSearchPath("libvlc", dir.path)
                } else NativeDiscovery().discover()
            }
            factory = MediaPlayerFactory(*if (muted) arrayOf("--quiet", "--no-audio", "--no-video-title-show")
                else arrayOf("--quiet", "--no-video-title-show"))
            loaded = true; loadedFrom = dir?.path
            val version = factory.application().version()
            if (!version.startsWith("3.0.")) {
                factory.release()
                return MovieOpen.Unavailable("Incompatible VLC $version. Install 64-bit VLC 3.0.x separately; no native VLC ships with Auto Flow.")
            }
            player = factory.mediaPlayers().newEmbeddedMediaPlayer()
            val result = VlcMoviePlayer(factory, player)
            result.prepare(path)
            MovieOpen.Ready(result)
        } catch (e: Throwable) {
            // LinkageError / UnsatisfiedLinkError are expected for absent or wrong-architecture VLC.
            runCatching { player?.release() }; runCatching { factory?.release() }
            MovieOpen.Unavailable("VLC playback unavailable (${e.javaClass.simpleName}). Install 64-bit VLC 3.0.x separately or set its folder in Settings. Still / Exact frame remain available.")
        }
    }
}

private class VlcMoviePlayer(private val factory: MediaPlayerFactory, private val player: EmbeddedMediaPlayer) : MoviePlayer {
    private val lock = Any()
    private val mutableFrames = MutableStateFlow<MovieFrame?>(null)
    override val frames = mutableFrames.asStateFlow()
    @Volatile private var closed = false
    @Volatile private var phase = MoviePhase.OPENING
    @Volatile private var problem: String? = null
    @Volatile private var displayWidth = 0
    @Volatile private var displayHeight = 0
    @Volatile private var sourceWidth = 0
    @Volatile private var sourceHeight = 0
    private var serial = 0L
    private var pendingSeek: Long? = null
    private var pendingRate = 1f
    private var started = false

    init {
        val format = object : BufferFormatCallback {
            override fun getBufferFormat(sourceWidth: Int, sourceHeight: Int): BufferFormat {
                this@VlcMoviePlayer.sourceWidth = sourceWidth; this@VlcMoviePlayer.sourceHeight = sourceHeight
                // A preview decoder must not copy a 4K/8K native buffer 25-60 times per second into Compose.
                val scale = minOf(1.0, 960.0 / maxOf(sourceWidth, sourceHeight).coerceAtLeast(1))
                return RV32BufferFormat((sourceWidth * scale).toInt().coerceAtLeast(1), (sourceHeight * scale).toInt().coerceAtLeast(1))
            }
            override fun allocatedBuffers(buffers: Array<ByteBuffer>) {}
        }
        val render = object : RenderCallback {
            override fun display(mediaPlayer: MediaPlayer, nativeBuffers: Array<ByteBuffer>, bufferFormat: BufferFormat) {
                if (closed) return
                val width = if (displayWidth > 0 && sourceWidth > 0) (displayWidth.toLong() * bufferFormat.width / sourceWidth).toInt().coerceIn(1, bufferFormat.width) else bufferFormat.width
                val height = if (displayHeight > 0 && sourceHeight > 0) (displayHeight.toLong() * bufferFormat.height / sourceHeight).toInt().coerceIn(1, bufferFormat.height) else bufferFormat.height
                val size = bufferFormat.width.toLong() * bufferFormat.height * 4
                if (size !in 1..(8192L * 8192 * 4)) return
                // Never expose/reuse libVLC's mutable native buffer on the UI thread.
                val buffer = nativeBuffers[0].duplicate().apply { clear() }
                val bytes = ByteArray(size.toInt()); buffer.get(bytes)
                // RV32 is BGRX, NOT premultiplied BGRA. Its unused X byte may be zero. Normalize only our
                // copy, or nested offscreen Compose compositing can add the background through transparent RGB.
                for (alpha in 3 until bytes.size step 4) bytes[alpha] = 0xFF.toByte()
                if (!closed) mutableFrames.value = MovieFrame(width, height, bufferFormat.width * 4, bytes, ++serial)
            }
        }
        player.videoSurface().set(CallbackVideoSurface(format, render, true, VideoSurfaceAdapters.getVideoSurfaceAdapter()))
        player.events().addMediaPlayerEventListener(object : MediaPlayerEventAdapter() {
            override fun playing(mediaPlayer: MediaPlayer) { if (!closed) phase = MoviePhase.PLAYING }
            override fun paused(mediaPlayer: MediaPlayer) { if (!closed) phase = MoviePhase.PAUSED }
            override fun finished(mediaPlayer: MediaPlayer) { if (!closed) phase = MoviePhase.ENDED }
            override fun error(mediaPlayer: MediaPlayer) { if (!closed) { problem = "VLC could not decode this movie. Still / Exact frame remain available."; phase = MoviePhase.FAILED } }
            override fun videoOutput(mediaPlayer: MediaPlayer, newCount: Int) {
                mediaPlayer.video().videoDimension()?.let { displayWidth = it.width; displayHeight = it.height }
            }
        })
    }

    fun prepare(path: String) = control { check(player.media().prepare(path)) { "VLC refused the movie" } }
    override fun setPlaying(playing: Boolean) = control {
        if (playing) player.controls().play() else player.controls().setPause(true)
    }
    override fun seek(positionMs: Long) = control {
        if (started) player.controls().setTime(positionMs.coerceAtLeast(0))
        else pendingSeek = positionMs.coerceAtLeast(0)
    }
    override fun setRate(rate: Float) = control {
        check(rate.isFinite() && rate in 0.1f..4f) { "Invalid playback speed" }
        pendingRate = rate
        if (started) check(player.controls().setRate(rate)) { "VLC could not apply playback speed" }
    }
    override fun snapshot(): MovieSnapshot = synchronized(lock) {
        if (closed) MovieSnapshot(phase = MoviePhase.CLOSED)
        else try {
            if (phase == MoviePhase.PLAYING && !started) {
                started = true
                check(player.controls().setRate(pendingRate)) { "VLC could not apply playback speed" }
                pendingSeek?.let { player.controls().setTime(it) }
                pendingSeek = null
            }
            MovieSnapshot(player.status().time().coerceAtLeast(0), player.status().length().coerceAtLeast(0), phase, problem)
        } catch (e: Throwable) { fail(e); MovieSnapshot(phase = phase, problem = problem) }
    }
    private inline fun control(block: () -> Unit) = synchronized(lock) {
        if (!closed && phase != MoviePhase.FAILED) try { block() } catch (e: Throwable) { fail(e) }
        Unit
    }
    private fun fail(e: Throwable) { problem = "VLC playback failed (${e.javaClass.simpleName}). Still / Exact frame remain available."; phase = MoviePhase.FAILED }
    override fun close() = synchronized(lock) {
        if (!closed) {
            closed = true; phase = MoviePhase.CLOSED
            runCatching { player.controls().stop() }
            runCatching { player.release() }
            runCatching { factory.release() }
            mutableFrames.value = null
        }
    }
}
