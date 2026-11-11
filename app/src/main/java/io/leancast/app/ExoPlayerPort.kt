package io.leancast.app

import android.content.Context
import android.graphics.SurfaceTexture
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import io.leancast.domain.cast.CastSession
import io.leancast.domain.cast.MediaHttpHeaders
import io.leancast.domain.cast.PlayerPort
import io.leancast.domain.upnp.MimeInference

/**
 * [PlayerPort] backed by ExoPlayer. All player access is marshalled to the main
 * thread; position/duration are cached by a main-thread poller so HTTP threads
 * can read them synchronously.
 */
class ExoPlayerPort(private val context: Context) : PlayerPort {

    var session: CastSession? = null

    @Volatile private var cachedPositionMs = 0L
    @Volatile private var cachedDurationMs = 0L
    @Volatile private var cachedIsLive = false
    @Volatile private var lastUri: String? = null

    private val main = Handler(Looper.getMainLooper())
    private var player: ExoPlayer? = null
    /** Offscreen surface so playback never depends on PlayerActivity existing. */
    private var headlessTexture: SurfaceTexture? = null
    private var headlessSurface: Surface? = null
    private val poller = object : Runnable {
        override fun run() {
            player?.let { p ->
                cachedPositionMs = p.currentPosition.coerceAtLeast(0)
                cachedDurationMs =
                    if (p.duration > 0) p.duration else 0L
                // Live items report a sliding-window duration; the session turns
                // that into "unknown" instead of passing the window size on.
                cachedIsLive = p.isCurrentMediaItemLive
            }
            main.postDelayed(this, POLL_MS)
        }
    }

    val currentPlayer: Player? get() = player

    override fun prepareAndPlay(uri: String) {
        lastUri = uri
        main.post {
            val p = obtain()
            val mime = MimeInference.guessMimeType(uri)
            val item = MediaItem.Builder().setUri(uri).setMimeType(mime).build()
            // Route explicitly: DefaultMediaSourceFactory's inference misses
            // CN CDNs whose manifests hide "m3u8" mid-path (youku et al.).
            val source = when (mime) {
                MimeInference.HLS ->
                    androidx.media3.exoplayer.hls.HlsMediaSource
                        .Factory(dataSources()).createMediaSource(item)
                else ->
                    androidx.media3.exoplayer.source.ProgressiveMediaSource
                        .Factory(dataSources()).createMediaSource(item)
            }
            p.setMediaSource(source)
            p.setPlaybackSpeed(session?.playbackSpeed ?: 1.0f)
            p.prepare()
            p.playWhenReady = true
        }
    }

    override fun setPlaybackSpeed(speed: Float) {
        main.post { player?.setPlaybackSpeed(speed) }
    }

    override fun play() {
        main.post { player?.play() }
    }

    override fun pause() {
        main.post { player?.pause() }
    }

    override fun seekTo(ms: Long) {
        main.post { player?.seekTo(ms) }
    }

    override fun stop() {
        main.post { player?.stop() }
    }

    override fun currentPositionMs(): Long = cachedPositionMs
    override fun currentDurationMs(): Long = cachedDurationMs
    override fun isLive(): Boolean = cachedIsLive

    fun release() {
        main.post {
            player?.release()
            player = null
            headlessSurface?.release()
            headlessSurface = null
            headlessTexture?.release()
            headlessTexture = null
        }
        main.removeCallbacks(poller)
        main.removeCallbacks(headlessFallback)
    }

    /** True while a PlayerView is displaying this player: the offscreen surface must not steal it back. */
    @Volatile private var viewAttached = false

    /** The offscreen surface, attached only after [HEADLESS_FALLBACK_DELAY_MS] when no PlayerView has taken the player. */
    private val headlessFallback = Runnable { attachHeadlessSurface() }

    /** Marks whether a PlayerView currently owns the player's video surface. */
    fun setViewAttached(attached: Boolean) {
        viewAttached = attached
    }

    /**
     * Gives the player an offscreen surface so decoding (and audio) never
     * depend on PlayerActivity existing: right after boot a cast can start
     * before any activity has run, and without a surface the video has
     * nowhere to go. Skipped while a PlayerView owns the surface: handing
     * the decoder back to the offscreen surface mid-playback leaves the
     * screen black on decoders that cannot switch outputs while running.
     * Runs at once on the main thread (all callers are already there), so a
     * queued attach can never land after a PlayerView took over.
     */
    fun attachHeadlessSurface() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { attachHeadlessSurface() }
            return
        }
        if (viewAttached) return
        val p = player ?: return
        runCatching {
            if (headlessSurface == null) {
                val tex = SurfaceTexture(0).apply { setDefaultBufferSize(1280, 720) }
                headlessTexture = tex
                headlessSurface = Surface(tex)
            }
            headlessSurface?.let { p.setVideoSurface(it) }
        }
    }

    /**
     * Drops the player's current video surface (the offscreen one) so the
     * next surface set — PlayerView's — is a fresh bind, not a switch while
     * the decoder is running. PlayerActivity calls this before attaching.
     */
    fun clearVideoSurface() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            main.post { clearVideoSurface() }
            return
        }
        runCatching { player?.setVideoSurface(null) }
    }

    private fun obtain(): ExoPlayer =
        player ?: buildPlayer().also { created ->
            player = created
            cachedPositionMs = 0
            cachedDurationMs = 0
            cachedIsLive = false
            // Do NOT hand the decoder the offscreen surface yet: PlayerActivity
            // is launched on TRANSITIONING and its surface should be the first
            // one the decoder sees — switching outputs mid-stream leaves the
            // screen black on this TV's decoder. The offscreen surface is only
            // a fallback if no view takes the player in time.
            main.postDelayed(headlessFallback, HEADLESS_FALLBACK_DELAY_MS)
            main.post(poller)
        }

    /**
     * CN video CDNs frequently filter non-browser user agents and redirect
     * across protocols; wire both into the HTTP stack (see MediaHttpHeaders).
     */
    private fun dataSources(): DefaultDataSource.Factory {
        val http = DefaultHttpDataSource.Factory()
            .setUserAgent(MediaHttpHeaders.USER_AGENT)
            .setDefaultRequestProperties(mapOf("Accept" to "*/*"))
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(CONNECT_TIMEOUT_MS)
            .setReadTimeoutMs(READ_TIMEOUT_MS)
        return DefaultDataSource.Factory(context, http)
    }

    private fun buildPlayer(): ExoPlayer {
        return ExoPlayer.Builder(context)
            .setMediaSourceFactory(DefaultMediaSourceFactory(dataSources()))
            .build()
            .also { p ->
                p.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) session?.onPlayerPrepared()
                        if (playbackState == Player.STATE_ENDED) session?.stop()
                    }

                    override fun onPlayerError(error: PlaybackException) {
                        Log.e(
                            TAG,
                            "playback error ${error.errorCodeName} uri=$lastUri",
                            error,
                        )
                        session?.onPlayerError()
                    }

                    // Field diagnostics for "audio but no video" reports:
                    // tells apart codec-unsupported (no selected video track)
                    // from surface/renderer issues (track selected, no picture).
                    override fun onTracksChanged(tracks: androidx.media3.common.Tracks) {
                        tracks.groups
                            .filter { it.type == androidx.media3.common.C.TRACK_TYPE_VIDEO }
                            .forEach { group ->
                                val selected = (0 until group.length)
                                    .map { group.getTrackFormat(it) to group.isTrackSelected(it) }
                                    .filter { it.second }
                                    .joinToString { "${it.first.sampleMimeType ?: "?"} ${it.first.width}x${it.first.height}" }
                                Log.i(
                                    TAG,
                                    "video track selected=[$selected] supported=${group.isSupported} size=${group.length}",
                                )
                            }
                    }
                })
            }
    }

    companion object {
        private const val TAG = "LeanCastPlayer"
        private const val POLL_MS = 500L
        private const val CONNECT_TIMEOUT_MS = 8_000
        private const val READ_TIMEOUT_MS = 8_000
        /** How long PlayerActivity gets to take the player before the offscreen surface is used as a fallback. */
        private const val HEADLESS_FALLBACK_DELAY_MS = 2_000L
    }
}
