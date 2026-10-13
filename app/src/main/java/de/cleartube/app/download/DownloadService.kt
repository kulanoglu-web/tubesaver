package de.cleartube.app.download

import android.app.Notification
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.documentfile.provider.DocumentFile
import de.cleartube.app.App
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Kind
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.File

class DownloadService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
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
        while (true) {
            val job = DownloadHub.nextQueued() ?: break
            val task = scope.launch { process(job) }
            DownloadHub.running[job.id] = task
            task.join()
            DownloadHub.running.remove(job.id)
        }
    }

    private suspend fun process(job: DownloadJob) {
        val dir = File(cacheDir, "dl/${job.id}").apply { mkdirs() }
        val o = job.option
        try {
            DownloadHub.update(job.id) { it.copy(state = JobState.RUNNING, progress = 0f) }

            val hasMux = o.kind == Kind.VIDEO && o.audioUrls.isNotEmpty()
            val result: File

            if (hasMux) {
                val vFile = File(dir, "video.part")
                val aFile = File(dir, "audio.part")
                // Video 0–76 %, Audio 76–95 %, Zusammenfügen/Kopieren bis 100 %
                DownloadEngine.downloadWithFallback(o.videoUrls, vFile, info(job)) { d, t ->
                    report(job, 0.76f * d / t)
                }
                DownloadEngine.downloadWithFallback(o.audioUrls, aFile, info(job)) { d, t ->
                    report(job, 0.76f + 0.19f * d / t)
                }
                DownloadHub.update(job.id) { it.copy(state = JobState.MUXING, progress = 0.95f) }
                result = File(dir, "out." + o.ext)
                Muxer.mux(vFile, aFile, result, o.webm)
            } else {
                val urls = if (o.kind == Kind.VIDEO) o.videoUrls else o.audioUrls
                result = File(dir, "out." + o.ext)
                DownloadEngine.downloadWithFallback(urls, result, info(job)) { d, t ->
                    report(job, 0.95f * d / t)
                }
            }

            copyToTree(job, result)
            DownloadHub.update(job.id) { it.copy(state = JobState.DONE, progress = 1f, message = null) }
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

    private fun copyToTree(job: DownloadJob, file: File) {
        val root = DocumentFile.fromTreeUri(this, job.tree)
            ?: error("Speicherort nicht mehr verfügbar – bitte neu wählen")
        val ext = job.option.ext
        val name = sanitize(job.title) + "." + ext
        val mime = when {
            job.option.kind == Kind.AUDIO -> "application/octet-stream"
            ext == "webm" -> "video/webm"
            else -> "video/mp4"
        }
        val doc = root.createFile(mime, name) ?: error("Datei im Zielordner nicht anlegbar")
        contentResolver.openOutputStream(doc.uri)?.use { out ->
            file.inputStream().use { it.copyTo(out, 256 * 1024) }
        } ?: error("Zieldatei nicht beschreibbar")
    }

    private fun sanitize(s: String): String =
        s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim().trimEnd('.').take(120).ifEmpty { "video" }

    /** Zeigt in der Liste an, über wie viele YouTube-Server gerade parallel geladen wird. */
    private fun info(job: DownloadJob): (String) -> Unit = { text ->
        DownloadHub.update(job.id) { it.copy(message = text) }
    }

    private fun report(job: DownloadJob, p: Float) {
        val progress = p.coerceIn(0f, 1f)
        DownloadHub.update(job.id) { it.copy(progress = progress) }
        val now = System.currentTimeMillis()
        if (now - lastNotify > 1000) {
            lastNotify = now
            getSystemService(NotificationManager::class.java)
                .notify(NOTIF_ID, notification(job.title, (progress * 100).toInt(), false))
        }
    }

    private fun notification(text: String, pct: Int, indeterminate: Boolean): Notification =
        NotificationCompat.Builder(this, App.CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("ClearTube")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, pct, indeterminate)
            .build()

    companion object {
        private const val NOTIF_ID = 42
    }
}
