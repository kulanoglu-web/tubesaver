package de.cleartube.app.player

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.KeyEvent
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import de.cleartube.app.data.Store
import de.cleartube.app.ui.ClearTubeTheme
import de.cleartube.app.ui.formatDuration
import kotlinx.coroutines.delay

/**
 * Eingebauter Player. Zwei Open-Source-Engines stehen zur Wahl (VLC und ExoPlayer);
 * bei einem Fehler wechselt er automatisch zur jeweils anderen. Bedienung per Touch oder Fernbedienung:
 * OK = Pause/Weiter, Links/Rechts = 10 s, Hoch/Runter = Leiste anzeigen, Zurück = beenden.
 */
class PlayerActivity : ComponentActivity() {
    private var engine: VideoEngine? = null
    private lateinit var holder: FrameLayout
    private lateinit var uri: Uri
    private var jobId = 0L
    private var engineName = "vlc"
    private var switched = false

    private var pos by mutableLongStateOf(0L)
    private var dur by mutableLongStateOf(0L)
    private var isPlaying by mutableStateOf(true)
    private var overlayUntil by mutableLongStateOf(0L)
    private var engineLabel by mutableStateOf("")
    private var title = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        uri = Uri.parse(intent.getStringExtra(EXTRA_URI) ?: return finish())
        jobId = intent.getLongExtra(EXTRA_JOB, 0L)
        title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        engineName = intent.getStringExtra(EXTRA_ENGINE) ?: Store.player
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        holder = FrameLayout(this)

        setContent {
            ClearTubeTheme {
                Box(Modifier.fillMaxSize().background(Color.Black).clickable { showOverlay() }) {
                    AndroidView(factory = { holder }, modifier = Modifier.fillMaxSize())
                    val visible = System.currentTimeMillis() < overlayUntil || !isPlaying
                    LaunchedEffect(Unit) {
                        while (true) {
                            engine?.let {
                                pos = it.position
                                dur = it.duration
                                isPlaying = it.playing
                                if (it.playing) saveProgress()
                            }
                            delay(500)
                        }
                    }
                    if (visible) Overlay()
                }
            }
        }
        startEngine(engineName, Store.position(jobId))
    }

    private fun startEngine(name: String, startMs: Long) {
        engine?.release()
        holder.removeAllViews()
        val ev = EngineEvents(
            onEnded = { runOnUiThread { Store.setWatched(jobId, true); Store.setPosition(jobId, 0); finish() } },
            onError = { msg -> runOnUiThread { fallback(msg) } }
        )
        val e = try {
            if (name == "exo") ExoEngine(this, ev) else VlcEngine(this, ev)
        } catch (t: Throwable) {
            Toast.makeText(this, "Player nicht startbar: ${t.message}", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        engine = e
        engineName = name
        engineLabel = e.name
        holder.addView(e.view)
        try {
            e.play(uri, startMs)
        } catch (t: Throwable) {
            fallback(t.message ?: "Fehler")
        }
        showOverlay()
    }

    /** Bei einem Fehler einmal mit dem anderen Player weitermachen. */
    private fun fallback(msg: String) {
        if (switched) {
            Toast.makeText(this, "Wiedergabe nicht möglich: $msg", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        switched = true
        val other = if (engineName == "exo") "vlc" else "exo"
        Toast.makeText(this, "Wechsel zu ${if (other == "exo") "ExoPlayer" else "VLC"} ($msg)", Toast.LENGTH_SHORT).show()
        startEngine(other, engine?.position ?: 0L)
    }

    private fun showOverlay() {
        overlayUntil = System.currentTimeMillis() + 4000
        // Neuzeichnen anstoßen, damit die Leiste auch wieder verschwindet
        pos += 0
    }

    private fun saveProgress() {
        val e = engine ?: return
        val d = e.duration
        if (jobId == 0L || d <= 0) return
        Store.setPosition(jobId, e.position)
        if (e.position > d * 0.92) Store.setWatched(jobId, true)
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xAA000000)).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                LinearProgressIndicator(
                    progress = { if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f },
                    modifier = Modifier.fillMaxWidth()
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        (formatDuration(pos / 1000) ?: "0:00") + " / " + (formatDuration(dur / 1000) ?: "–"),
                        color = Color.White, style = MaterialTheme.typography.labelLarge
                    )
                    Text(
                        (if (isPlaying) "▶" else "❚❚") + "  $engineLabel   ◀◀ 10 s   10 s ▶▶",
                        color = Color.White, style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = engine
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                e?.toggle(); showOverlay(); return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                e?.seekTo(e.position + 10_000); showOverlay(); return true
            }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> {
                e?.seekTo(e.position - 10_000); showOverlay(); return true
            }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                showOverlay(); return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        saveProgress()
        engine?.let { if (it.playing) it.toggle() }
        super.onPause()
    }

    override fun onDestroy() {
        saveProgress()
        engine?.release()
        engine = null
        super.onDestroy()
    }

    companion object {
        private const val EXTRA_URI = "uri"
        private const val EXTRA_JOB = "job"
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_ENGINE = "engine"

        /** engine: "vlc", "exo" oder null für die Einstellung */
        fun start(ctx: Context, uri: Uri, jobId: Long, title: String, engine: String? = null) {
            ctx.startActivity(
                Intent(ctx, PlayerActivity::class.java)
                    .putExtra(EXTRA_URI, uri.toString())
                    .putExtra(EXTRA_JOB, jobId)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_ENGINE, engine)
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            )
        }
    }
}
