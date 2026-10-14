package de.cleartube.app.download

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import de.cleartube.app.App
import de.cleartube.app.MainActivity
import de.cleartube.app.data.JobState
import de.cleartube.app.data.SeriesRepo
import de.cleartube.app.data.Store
import de.cleartube.app.data.YouTubeRepo
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Sucht neue Folgen der abonnierten Serien, lädt sie automatisch und räumt alte Folgen weg. */
object SeriesSync {
    private val mutex = Mutex()
    private const val DAY_MS = 86_400_000L
    private const val MAX_PER_SERIES = 3

    /** Gibt die Zahl der neu eingereihten Folgen zurück. */
    suspend fun check(ctx: Context): Int = mutex.withLock {
        val tree = Store.tree?.let(Uri::parse)
        val updates = HashMap<String, Pair<Set<String>, Long>>()
        var queued = 0
        val titles = mutableListOf<String>()

        for (s in Store.loadSeries()) {
            try {
                val first = SeriesRepo.episodes(s.id)
                val all = first.items + (first.next?.let {
                    runCatching { SeriesRepo.moreEpisodes(s.id, it).items }.getOrDefault(emptyList())
                } ?: emptyList())
                if (all.isEmpty()) continue
                val fresh = all.filter { it.url !in s.known }
                // Erfolgreich eingereihte Folgen (und überzählige) gelten als bekannt; gescheiterte werden wiederholt
                val handled = mutableListOf<String>()
                if (tree != null) {
                    // Vorschauen und Ausschnitte (kürzer als die Mindestlänge) werden nicht automatisch geladen
                    val full = fresh.filter { it.isFull() }
                    handled += fresh.filterNot { it.isFull() }.map { it.url }
                    for (ep in full.take(MAX_PER_SERIES)) {
                        val ok = runCatching {
                            val d = YouTubeRepo.details(ep.url)
                            val opt = SeriesRepo.pickOption(d.options, Store.seriesHeight, s.pref) ?: error("keine Variante")
                            DownloadHub.enqueue(d.title, d.thumbnail ?: ep.thumbnail, opt, tree, ep.url, s.id)
                        }.isSuccess
                        if (ok) {
                            queued++
                            titles += ep.title
                            handled += ep.url
                        }
                    }
                    handled += full.drop(MAX_PER_SERIES).map { it.url }
                }
                updates[s.id] = (s.known + handled) to System.currentTimeMillis()
            } catch (_: Throwable) {
                // Serie beim nächsten Durchgang erneut prüfen
            }
        }

        // Aktuellen Stand lesen, damit zwischenzeitlich hinzugefügte Serien nicht verloren gehen
        Store.saveSeries(Store.loadSeries().map { l ->
            updates[l.id]?.let { (known, t) -> l.copy(known = l.known + known, lastCheck = t) } ?: l
        })

        if (queued > 0) startQueue(ctx, titles)
        queued
    }

    /** Startet das Laden; im Hintergrund kann Android das verbieten, dann erscheint eine Benachrichtigung. */
    private fun startQueue(ctx: Context, titles: List<String>) {
        val started = runCatching {
            ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        }.isSuccess
        val text = if (titles.size == 1) titles[0] else "${titles.size} neue Folgen"
        val i = Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(ctx, 1, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val n = NotificationCompat.Builder(ctx, App.SERIES_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle(if (started) "Neue Folge wird geladen" else "Neue Folge bereit zum Laden")
            .setContentText(text)
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        ctx.getSystemService(NotificationManager::class.java).notify(77, n)
    }

    /** Folgen aus Serien nach X Tagen löschen (einstellbar). */
    fun autoDelete(ctx: Context): Int {
        if (!Store.seriesAutoDelete) return 0
        val limit = Store.seriesKeepDays * DAY_MS
        val now = System.currentTimeMillis()
        var n = 0
        DownloadHub.jobs.value
            .filter {
                it.state == JobState.DONE && it.seriesId.isNotEmpty() && it.finishedAt > 0 &&
                    now - it.finishedAt >= limit
            }
            .forEach { j ->
                val uri = j.resultUri
                if (uri == null || !Cleanup.exists(ctx, uri) || Cleanup.delete(ctx, uri)) {
                    DownloadHub.remove(j.id)
                    n++
                }
            }
        return n
    }
}

class SeriesWorker(ctx: Context, params: WorkerParameters) : CoroutineWorker(ctx, params) {
    override suspend fun doWork(): Result {
        SeriesSync.autoDelete(applicationContext)
        if (Store.seriesAutoDownload) SeriesSync.check(applicationContext)
        return Result.success()
    }
}
