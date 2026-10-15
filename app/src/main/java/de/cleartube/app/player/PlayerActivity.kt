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
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
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
    private var lastSave = 0L

    private var pos by mutableLongStateOf(0L)
    private var dur by mutableLongStateOf(0L)
    private var isPlaying by mutableStateOf(true)
    private var overlayUntil by mutableLongStateOf(0L)
    private var overlayShown by mutableStateOf(true)
    private var engineLabel by mutableStateOf("")
    private var swipeMs by mutableStateOf<Long?>(null)
    private var dragging by mutableStateOf(false)
    private var dragFrac by mutableFloatStateOf(0f)
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
                var widthPx by remember { mutableStateOf(1f) }
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .onSizeChanged { widthPx = it.width.toFloat().coerceAtLeast(1f) }
                        // Tippen: Leiste zeigen; Doppeltippen links/rechts: 10 s zurück/vor
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { showOverlay() },
                                onDoubleTap = { o -> seekBy(if (o.x < size.width / 2) -10_000 else 10_000) }
                            )
                        }
                        // Wischen: nach rechts vor, nach links zurück (ganze Bildbreite = 2 Minuten)
                        .pointerInput(Unit) {
                            var total = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { total = 0f },
                                onDragEnd = {
                                    swipeMs?.let { seekBy(it) }
                                    swipeMs = null
                                },
                                onDragCancel = { swipeMs = null },
                                onHorizontalDrag = { change, dx ->
                                    change.consume()
                                    total += dx
                                    val e = engine
                                    var delta = (total / widthPx * 120_000f).toLong()
                                    if (e != null) {
                                        val max = if (e.duration > 0) e.duration else Long.MAX_VALUE
                                        delta = delta.coerceIn(-e.position, max - e.position)
                                    }
                                    swipeMs = delta
                                    showOverlay()
                                }
                            )
                        }
                ) {
                    AndroidView(factory = { holder }, modifier = Modifier.fillMaxSize())
                    LaunchedEffect(Unit) {
                        while (true) {
                            engine?.let {
                                if (!dragging) pos = it.position
                                dur = it.duration
                                isPlaying = it.playing
                                saveProgress(false)
                            }
                            delay(400)
                        }
                    }
                    // Leiste nach einigen Sekunden ohne Bedienung ausblenden
                    LaunchedEffect(overlayUntil) {
                        val wait = overlayUntil - System.currentTimeMillis()
                        if (wait > 0) delay(wait)
                        overlayShown = false
                    }
                    val visible = overlayShown || !isPlaying || dragging
                    if (visible) Overlay()
                    swipeMs?.let { d ->
                        val e = engine
                        val target = ((e?.position ?: 0L) + d).coerceAtLeast(0)
                        Text(
                            (if (d >= 0) "▶▶ +" else "◀◀ −") + (formatDuration(kotlin.math.abs(d) / 1000) ?: "0:00") +
                                "\n" + (formatDuration(target / 1000) ?: "0:00"),
                            color = Color.White,
                            style = MaterialTheme.typography.headlineMedium,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            modifier = Modifier.align(Alignment.Center).background(Color(0x99000000)).padding(20.dp)
                        )
                    }
                }
            }
        }
        // Weitermachen, wo man aufgehört hat (nur wenn nicht schon fast am Ende)
        val saved = Store.position(jobId)
        val savedDur = Store.duration(jobId)
        val resume = if (saved > 5_000 && (savedDur <= 0 || saved < savedDur - 15_000)) (saved - 2_000).coerceAtLeast(0) else 0L
        startEngine(engineName, resume)
    }

    private fun startEngine(name: String, startMs: Long) {
        engine?.release()
        holder.removeAllViews()
        val ev = EngineEvents(
            onEnded = {
                runOnUiThread {
                    Store.setWatched(jobId, true)
                    Store.setPosition(jobId, 0)
                    finish()
                }
            },
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

    /** Bei einem Fehler einmal mit dem anderen Player an derselben Stelle weitermachen. */
    private fun fallback(msg: String) {
        if (switched) {
            Toast.makeText(this, "Wiedergabe nicht möglich: $msg", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        switched = true
        val other = if (engineName == "exo") "vlc" else "exo"
        Toast.makeText(this, "Wechsel zu ${if (other == "exo") "ExoPlayer" else "VLC"} ($msg)", Toast.LENGTH_SHORT).show()
        startEngine(other, engine?.position ?: Store.position(jobId))
    }

    private fun showOverlay() {
        overlayUntil = System.currentTimeMillis() + 4000
        overlayShown = true
    }

    /** Position regelmäßig speichern (alle 3 s), mit force sofort. */
    private fun saveProgress(force: Boolean) {
        val e = engine ?: return
        if (jobId == 0L) return
        val now = System.currentTimeMillis()
        if (!force && now - lastSave < 3000) return
        lastSave = now
        val p = e.position
        val d = e.duration
        if (p <= 0 && d <= 0) return
        Store.setPosition(jobId, p)
        if (d > 0) {
            Store.setDuration(jobId, d)
            if (p > d * 0.93) Store.setWatched(jobId, true)
        }
    }

    private fun seekBy(deltaMs: Long) {
        val e = engine ?: return
        val target = (e.position + deltaMs).coerceIn(0, if (e.duration > 0) e.duration else Long.MAX_VALUE)
        e.seekTo(target)
        pos = target
        showOverlay()
    }

    @androidx.compose.runtime.Composable
    private fun Overlay() {
        val frac = if (dur > 0) (pos.toFloat() / dur).coerceIn(0f, 1f) else 0f
        val shownPos = if (dragging) (dragFrac * dur).toLong() else pos
        Box(Modifier.fillMaxSize()) {
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Color(0xCC000000)).padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Slider(
                    value = if (dragging) dragFrac else frac,
                    onValueChange = {
                        dragging = true
                        dragFrac = it
                        showOverlay()
                    },
                    onValueChangeFinished = {
                        engine?.let { e ->
                            val target = (dragFrac * dur).toLong()
                            e.seekTo(target)
                            pos = target
                            saveProgress(true)
                        }
                        dragging = false
                        showOverlay()
                    },
                    enabled = dur > 0
                )
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        (formatDuration(shownPos / 1000) ?: "0:00") + " / " + (formatDuration(dur / 1000) ?: "–"),
                        color = Color.White, style = MaterialTheme.typography.labelLarge,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { seekBy(-10_000) }) {
                        Icon(Icons.Default.Replay10, "10 Sekunden zurück", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    IconButton(onClick = { engine?.toggle(); isPlaying = engine?.playing ?: false; showOverlay() }) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            if (isPlaying) "Pause" else "Abspielen",
                            tint = Color.White, modifier = Modifier.size(40.dp)
                        )
                    }
                    IconButton(onClick = { seekBy(10_000) }) {
                        Icon(Icons.Default.Forward10, "10 Sekunden vor", tint = Color.White, modifier = Modifier.size(30.dp))
                    }
                    Text(
                        engineLabel, color = Color(0xCCFFFFFF), style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.End
                    )
                }
            }
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val e = engine
        // Gedrückt halten springt immer weiter: 10 s, dann 30 s, dann 1 Minute, dann 3 Minuten
        val step = when {
            event.repeatCount < 4 -> 10_000L
            event.repeatCount < 12 -> 30_000L
            event.repeatCount < 30 -> 60_000L
            else -> 180_000L
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_SPACE,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, KeyEvent.KEYCODE_MEDIA_PLAY, KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                if (event.repeatCount == 0) {
                    e?.toggle()
                    isPlaying = e?.playing ?: false
                    showOverlay()
                }
                return true
            }
            KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> { seekBy(step); return true }
            KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_MEDIA_REWIND -> { seekBy(-step); return true }
            KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> { showOverlay(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onPause() {
        saveProgress(true)
        engine?.let { if (it.playing) it.toggle() }
        super.onPause()
    }

    override fun onStop() {
        saveProgress(true)
        super.onStop()
    }

    override fun onDestroy() {
        saveProgress(true)
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
