package de.cleartube.app.download

import android.app.Notification
import android.app.PendingIntent
import de.cleartube.app.MainActivity
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import de.cleartube.app.App
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Kind
import de.cleartube.app.data.Option
import de.cleartube.app.data.Store
import de.cleartube.app.data.YouTubeRepo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + NetIO)
    private var worker: Job? = null
    private var lastNotify = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ServiceCompat.startForeground(
            this, NOTIF_ID, notification("Download wird vorbereitet…", 0, true),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        )
        if (worker?.isActive != true) {
            worker = scope.launch {
                runQueue()
                ServiceCompat.stopForeground(this@DownloadService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private suspend fun runQueue() {
        val active = java.util.concurrent.ConcurrentHashMap<Long, Job>()
        while (true) {
            // Freie Plätze mit wartenden Downloads füllen (Zustand sofort auf RUNNING, damit nichts doppelt startet)
            // Nur ladende Downloads belegen einen Platz; Zusammenfügen/Speichern läuft nebenher weiter
            while (active.keys.count { id -> DownloadHub.jobs.value.any { it.id == id && it.state == JobState.RUNNING } } < Store.parallel) {
                val job = DownloadHub.nextQueued() ?: break
                DownloadHub.update(job.id) { it.copy(state = JobState.RUNNING, progress = 0f, message = null) }
                val task = scope.launch { process(job) }
                DownloadHub.running[job.id] = task
                active[job.id] = task
                task.invokeOnCompletion {
                    DownloadHub.running.remove(job.id)
                    active.remove(job.id)
                }
            }
            if (active.isEmpty()) break
            kotlinx.coroutines.delay(400)
        }
    }

    private suspend fun process(job: DownloadJob) {
        val dir = File(cacheDir, "dl/${job.id}").apply { mkdirs() }
        try {
            DownloadHub.update(job.id) { it.copy(state = JobState.RUNNING, progress = 0f, message = null) }
            var o = job.option

            // Gespeicherte Downloads haben keine Adressen mehr: frisch von YouTube holen
            if (o.videoUrls.isEmpty() && o.audioUrls.isEmpty()) o = refreshed(job, o)

            val result = try {
                fetchAndBuild(job, o, dir)
            } catch (e: CancellationException) {
                throw e
            } catch (first: Throwable) {
                // Adressen können abgelaufen oder gesperrt sein: einmal neu holen und wiederholen
                val fresh = runCatching { refreshed(job, o) }.getOrNull() ?: throw first
                o = fresh
                DownloadHub.update(job.id) { it.copy(progress = 0f, message = "Neue Adressen") }
                fetchAndBuild(job, fresh, dir)
            }

            val size = result.size
            var timing = result.timing
            val outUri = result.uri ?: run {
                val t0 = System.currentTimeMillis()
                val u = copyToTree(job, result.file!!, o)
                timing += (if (timing.isEmpty()) "" else " · ") + "Speichern ${(System.currentTimeMillis() - t0) / 1000} s"
                u
            }
            DownloadHub.update(job.id) {
                it.copy(
                    state = JobState.DONE,
                    progress = 1f,
                    message = timing.ifEmpty { null },
                    resultUri = outUri,
                    openMime = openMime(o),
                    finishedAt = System.currentTimeMillis(),
                    fileSize = size
                )
            }
        } catch (e: CancellationException) {
            DownloadHub.update(job.id) { it.copy(state = JobState.CANCELLED) }
            throw e
        } catch (e: Throwable) {
            DownloadHub.update(job.id) {
                it.copy(state = JobState.FAILED, message = e.message ?: e.javaClass.simpleName)
            }
        } finally {
            dir.deleteRecursively()
        }
    }

    /** Holt die Video-Daten neu und sucht die gleiche Variante wie beim ersten Mal. */
    private suspend fun refreshed(job: DownloadJob, old: Option): Option {
        if (job.videoUrl.isEmpty()) error("Video-Adresse unbekannt – bitte neu über die Suche starten")
        val d = YouTubeRepo.details(job.videoUrl)
        fun core(l: String) = l.replace(Regex(" · ~[\\d.,]+ MB"), "")
        return d.options.firstOrNull { it.kind == old.kind && it.ext == old.ext && core(it.label) == core(old.label) }
            ?: d.options.firstOrNull { it.kind == old.kind && it.ext == old.ext }
            ?: error("Diese Variante gibt es nicht mehr – bitte neu über die Suche starten")
    }

    /** Ergebnis: entweder schon im Zielordner (uri) oder eine Datei im Cache, die noch kopiert wird. */
    private class Built(val file: File?, val uri: Uri?, val size: Long, val timing: String = "")

    private fun muxProgress(job: DownloadJob): (Float) -> Unit = { p ->
        DownloadHub.update(job.id) {
            it.copy(
                state = JobState.MUXING, progress = 0.95f + 0.04f * p,
                message = "Wird zusammengefügt… ${(p * 100).toInt()} %"
            )
        }
    }

    /** Lädt die gewählte Variante und liefert die fertige Datei. */
    private suspend fun fetchAndBuild(job: DownloadJob, o: Option, dir: File): Built {
        dir.listFiles()?.forEach { it.delete() }
        val hasMux = o.kind == Kind.VIDEO && o.audioUrls.isNotEmpty()
        var result: File

        val t0 = System.currentTimeMillis()
        if (hasMux) {
            val vFile = File(dir, "video.part")
            val aFile = File(dir, "audio.part")
            // Bild und Ton gleichzeitig laden (Ton ist klein und läuft nebenher mit)
            val pv = java.util.concurrent.atomic.AtomicInteger(0)
            val pa = java.util.concurrent.atomic.AtomicInteger(0)
            fun total() = report(job, 0.95f * (0.9f * pv.get() + 0.1f * pa.get()) / 1000f)
            coroutineScope {
                val v = async {
                    val meter = SpeedMeter(job)
                    DownloadEngine.downloadWithFallback(o.videoUrls, vFile, meter::base) { d, t ->
                        pv.set((1000L * d / t).toInt()); total(); meter.tick(d)
                    }
                }
                val au = async {
                    DownloadEngine.downloadWithFallback(o.audioUrls, aFile, {}) { d, t ->
                        pa.set((1000L * d / t).toInt()); total()
                    }
                }
                v.await()
                au.await()
            }
            val tLoaded = System.currentTimeMillis()
            DownloadHub.update(job.id) { it.copy(state = JobState.MUXING, progress = 0.95f, message = "Wird zusammengefügt… 0 %") }
            val tm = System.currentTimeMillis()
            result = File(dir, "out." + o.ext)
            Muxer.mux(vFile, aFile, result, o.webm, muxProgress(job))
            return Built(
                result, null, result.length(),
                "Laden ${(tLoaded - t0) / 1000} s · Zusammenfügen ${(System.currentTimeMillis() - tm) / 1000} s"
            ).also { vFile.delete(); aFile.delete() }
        } else {
            val urls = if (o.kind == Kind.VIDEO) o.videoUrls else o.audioUrls
            result = File(dir, "out." + o.ext)
            val meter = SpeedMeter(job)
            DownloadEngine.downloadWithFallback(urls, result, meter::base) { d, t ->
                report(job, 0.95f * d / t); meter.tick(d)
            }
            // Reine Audiodatei: zu sauberer M4A umpacken, bei Problemen bleibt das Original
            if (o.kind == Kind.AUDIO && o.ext == "m4a") {
                DownloadHub.update(job.id) { it.copy(state = JobState.MUXING, progress = 0.95f, message = null) }
                val clean = File(dir, "clean.m4a")
                val ok = runCatching { Muxer.remuxAudio(result, clean) }.isSuccess && clean.length() > 0
                if (ok) result = clean
            }
        }
        return Built(result, null, result.length())
    }

    private fun mimeFor(ext: String) = when (ext) {
        "m4a" -> "audio/mp4"
        "webm" -> "video/webm"
        else -> "video/mp4"
    }

    /** Fügt Bild und Ton direkt am Zielort zusammen; bei jedem Problem null (dann Weg über den Cache). */
    private fun muxDirect(job: DownloadJob, o: Option, v: File, a: File): Uri? {
        val name = sanitize(job.title)
        val prog = muxProgress(job)
        try {
            if (job.tree.scheme == "file") {
                val dir = File(job.tree.path!!).apply { mkdirs() }
                var target = File(dir, "$name.${o.ext}")
                var n = 1
                while (target.exists()) target = File(dir, "$name (${n++}).${o.ext}")
                try {
                    Muxer.mux(v, a, target, o.webm, prog)
                    if (target.length() > 0) return Uri.fromFile(target)
                } catch (t: Throwable) {
                    // fällt unten auf den Cache-Weg zurück
                }
                target.delete()
                return null
            }
            val root = DocumentFile.fromTreeUri(this, job.tree) ?: return null
            val doc = root.createFile(mimeFor(o.ext), "$name.${o.ext}") ?: return null
            var ok = false
            try {
                val pfd = contentResolver.openFileDescriptor(doc.uri, "rw") ?: return null
                pfd.use { Muxer.mux(v, a, it.fileDescriptor, o.webm, prog) }
                ok = doc.length() > 0
            } catch (t: Throwable) {
                ok = false
            } finally {
                if (!ok) runCatching { doc.delete() }
            }
            return if (ok) doc.uri else null
        } catch (t: Throwable) {
            return null
        }
    }

    /** Dateityp, mit dem die fertige Datei in einem Player geöffnet wird. */
    private fun openMime(o: Option): String = when {
        o.kind == Kind.AUDIO && o.ext == "webm" -> "audio/webm"
        o.kind == Kind.AUDIO -> "audio/mp4"
        o.ext == "webm" -> "video/webm"
        else -> "video/mp4"
    }

    private fun copyToTree(job: DownloadJob, file: File, o: Option): Uri {
        if (job.tree.scheme == "file") {
            // App-Ordner (z. B. auf dem Fernseher ohne Ordner-Auswahl)
            val dir = File(job.tree.path!!).apply { mkdirs() }
            var target = File(dir, sanitize(job.title) + "." + o.ext)
            var n = 1
            while (target.exists()) target = File(dir, sanitize(job.title) + " (${n++})." + o.ext)
            val total = file.length().coerceAtLeast(1)
            var copied = 0L
            var last = 0L
            file.inputStream().use { input ->
                target.outputStream().use { out ->
                    val buf = ByteArray(4 * 1024 * 1024)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        copied += n
                        val now = System.currentTimeMillis()
                        if (now - last > 700) {
                            last = now
                            val p = copied.toFloat() / total
                            DownloadHub.update(job.id) {
                                it.copy(state = JobState.MUXING, progress = 0.97f + 0.03f * p,
                                    message = "Wird gespeichert… ${(p * 100).toInt()} %")
                            }
                        }
                    }
                }
            }
            return Uri.fromFile(target)
        }
        val root = DocumentFile.fromTreeUri(this, job.tree)
            ?: error("Speicherort nicht mehr verfügbar – bitte neu wählen")
        val ext = o.ext
        val name = sanitize(job.title) + "." + ext
        // Typ passend zur Endung wählen, sonst hängt Android eine zweite Endung an
        val mime = when (ext) {
            "m4a" -> "audio/mp4"
            "webm" -> "video/webm"
            else -> "video/mp4"
        }
        val doc = root.createFile(mime, name) ?: error("Datei im Zielordner nicht anlegbar")
        val total = file.length().coerceAtLeast(1)
        var copied = 0L
        var last = 0L
        contentResolver.openOutputStream(doc.uri)?.use { out ->
            file.inputStream().use { input ->
                val buf = ByteArray(4 * 1024 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    copied += n
                    val now = System.currentTimeMillis()
                    if (now - last > 700) {
                        last = now
                        val p = copied.toFloat() / total
                        DownloadHub.update(job.id) {
                            it.copy(
                                state = JobState.MUXING, progress = 0.97f + 0.03f * p,
                                message = "Wird gespeichert… ${(p * 100).toInt()} %"
                            )
                        }
                    }
                }
            }
        } ?: error("Zieldatei nicht beschreibbar")
        return doc.uri
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(120).ifEmpty { "video" }

    /** Zeigt in der Liste an, über wie viele YouTube-Server gerade parallel geladen wird. */
    private fun info(job: DownloadJob): (String) -> Unit = { text ->
        DownloadHub.update(job.id) { it.copy(message = text) }
    }

    /** Zeigt Verbindungen/Server und die aktuelle Geschwindigkeit (MB/s) auf der Karte an. */
    private inner class SpeedMeter(val job: DownloadJob) {
        private var base = ""
        private var t = System.currentTimeMillis()
        private var b = 0L
        private var speed = ""
        fun base(text: String) { base = text; push() }
        fun tick(done: Long) {
            val now = System.currentTimeMillis()
            if (now - t >= 1000) {
                val mbs = (done - b) * 1000.0 / (now - t) / 1048576.0
                speed = " · %.1f MB/s".format(mbs)
                t = now; b = done
                push()
            }
        }
        private fun push() = DownloadHub.update(job.id) { it.copy(message = base + speed) }
    }

    private fun report(job: DownloadJob, p: Float) {
        val progress = p.coerceIn(0f, 1f)
        DownloadHub.update(job.id) { it.copy(progress = progress) }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            val run = DownloadHub.jobs.value.filter { it.state == JobState.RUNNING || it.state == JobState.MUXING }
            val title = if (run.size > 1) "${run.size} Downloads laufen" else job.title
            val pct = if (run.size > 1) (run.map { it.progress }.average() * 100).toInt() else (progress * 100).toInt()
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(title, pct, false))
        }
    }

    /** Tippen auf die Benachrichtigung öffnet die App (Reiter bleibt wie zuletzt). */
    private fun openAppIntent(): PendingIntent {
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        return PendingIntent.getActivity(this, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    private fun notification(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ClearTube")
            .setContentText(text)
            .setContentIntent(openAppIntent())
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .build()

    companion object {
        private const val NOTIF_ID = 42
    }
}
