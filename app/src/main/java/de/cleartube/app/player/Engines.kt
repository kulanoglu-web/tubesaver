package de.cleartube.app.player

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import org.videolan.libvlc.LibVLC
import org.videolan.libvlc.Media
import org.videolan.libvlc.MediaPlayer
import org.videolan.libvlc.util.VLCVideoLayout

/** Gemeinsame Schnittstelle, damit die Oberfläche beide Player gleich bedient. */
interface VideoEngine {
    val name: String
    val view: View
    val position: Long
    val duration: Long
    val playing: Boolean
    fun play(uri: Uri, startMs: Long)
    fun toggle()
    fun seekTo(ms: Long)
    fun release()
}

/** Callback-Bündel der Engines */
class EngineEvents(val onEnded: () -> Unit, val onError: (String) -> Unit)

class ExoEngine(ctx: Context, private val ev: EngineEvents) : VideoEngine {
    override val name = "ExoPlayer"
    private val player = ExoPlayer.Builder(ctx).build()
    private val pv = PlayerView(ctx).apply {
        useController = false
        layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        this.player = this@ExoEngine.player
    }
    override val view: View get() = pv
    override val position: Long get() = player.currentPosition
    override val duration: Long get() = player.duration.takeIf { it > 0 } ?: 0L
    override val playing: Boolean get() = player.isPlaying

    init {
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) ev.onEnded()
            }

            override fun onPlayerError(error: PlaybackException) {
                ev.onError(error.errorCodeName)
            }
        })
    }

    override fun play(uri: Uri, startMs: Long) {
        player.setMediaItem(MediaItem.fromUri(uri), startMs)
        player.prepare()
        player.playWhenReady = true
    }

    override fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(ms: Long) = player.seekTo(ms.coerceAtLeast(0))

    override fun release() {
        pv.player = null
        player.release()
    }
}

class VlcEngine(private val ctx: Context, private val ev: EngineEvents) : VideoEngine {
    override val name = "VLC"
    private val lib = LibVLC(ctx, arrayListOf("--no-drop-late-frames", "--no-skip-frames"))
    private val player = MediaPlayer(lib)
    private val layout = VLCVideoLayout(ctx)
    private var pfd: ParcelFileDescriptor? = null
    private var pendingStart = 0L
    private var media: Media? = null
    override val view: View get() = layout
    override val position: Long get() = player.time.coerceAtLeast(0)
    override val duration: Long get() = player.length.coerceAtLeast(0)
    override val playing: Boolean get() = player.isPlaying

    init {
        player.attachViews(layout, null, false, false)
        player.setEventListener { e ->
            when (e.type) {
                MediaPlayer.Event.Playing -> {
                    // Startposition erst setzen, wenn die Wiedergabe wirklich läuft (vorher wird sie ignoriert)
                    if (pendingStart > 0) {
                        val t = pendingStart
                        pendingStart = 0
                        player.time = t
                    }
                }
                MediaPlayer.Event.EndReached -> ev.onEnded()
                MediaPlayer.Event.EncounteredError -> ev.onError("VLC-Fehler")
                else -> Unit
            }
        }
    }

    override fun play(uri: Uri, startMs: Long) {
        val m = if (uri.scheme == "content") {
            val d = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("Datei nicht lesbar")
            pfd = d
            Media(lib, d.fileDescriptor)
        } else {
            Media(lib, uri)
        }
        m.setHWDecoderEnabled(true, false)
        if (startMs > 0) {
            m.addOption(":start-time=" + "%.3f".format(java.util.Locale.US, startMs / 1000.0))
            pendingStart = startMs
        }
        media = m
        player.media = m
        player.play()
    }

    override fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
    }

    override fun seekTo(ms: Long) {
        player.time = ms.coerceAtLeast(0)
    }

    override fun release() {
        runCatching { player.stop() }
        runCatching { player.detachViews() }
        runCatching { media?.release() }
        runCatching { player.release() }
        runCatching { lib.release() }
        runCatching { pfd?.close() }
    }
}
