package io.github.alalkipgen.autoflow.media.playback

import android.app.Activity
import android.app.Application
import android.os.Bundle
import android.net.Uri
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackException
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.flow.MutableStateFlow
import java.io.File

/** Existing Media3 engine, now source-clock controlled and lifecycle bound; no looping first-clip player. */
class AndroidMoviePlayer(private val activity: Activity, path: String, private val muted: Boolean = true) : MoviePlayer {
    private val player = ExoPlayer.Builder(activity).build()
    override val frames = MutableStateFlow<MovieFrame?>(null) // TextureView stays BELOW Compose overlays.
    private var closed = false
    private var desiredPlaying = false
    private var foreground = true
    private var problem: String? = null
    private val lifecycle = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(a: Activity) { if (a === activity) { foreground = true; applyPlaying() } }
        override fun onActivityStopped(a: Activity) { if (a === activity) { foreground = false; applyPlaying() } }
        override fun onActivityDestroyed(a: Activity) { if (a === activity) close() }
        override fun onActivityCreated(a: Activity, b: Bundle?) {}
        override fun onActivityResumed(a: Activity) {}
        override fun onActivityPaused(a: Activity) {}
        override fun onActivitySaveInstanceState(a: Activity, b: Bundle) {}
    }
    init {
        player.volume = if (muted) 0f else 1f
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) { problem = "Android playback unavailable (${error.errorCodeName}). Still / Exact frame remain available." }
        })
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        activity.application.registerActivityLifecycleCallbacks(lifecycle)
    }
    fun attach(view: TextureView) { if (!closed) player.setVideoTextureView(view) }
    override fun setPlaying(playing: Boolean) { desiredPlaying = playing; applyPlaying() }
    private fun applyPlaying() { if (!closed) player.playWhenReady = desiredPlaying && foreground }
    override fun seek(positionMs: Long) { if (!closed) player.seekTo(positionMs.coerceAtLeast(0)) }
    override fun setRate(rate: Float) { if (!closed && rate.isFinite() && rate in 0.1f..4f) player.setPlaybackSpeed(rate) }
    override fun setVolume(volume: Float) { if (!closed && !muted && volume.isFinite()) player.volume = volume.coerceIn(0f, 1f) }
    override fun snapshot() = if (closed) MovieSnapshot(phase = MoviePhase.CLOSED) else MovieSnapshot(
        player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), when {
            problem != null -> MoviePhase.FAILED
            !foreground -> MoviePhase.PAUSED
            player.playbackState == Player.STATE_ENDED -> MoviePhase.ENDED
            player.isPlaying -> MoviePhase.PLAYING
            player.playbackState == Player.STATE_BUFFERING || player.playbackState == Player.STATE_IDLE -> MoviePhase.OPENING
            else -> MoviePhase.PAUSED
        }, problem,
    )
    override fun close() {
        if (!closed) { closed = true; activity.application.unregisterActivityLifecycleCallbacks(lifecycle); player.clearVideoSurface(); player.release() }
    }
}
