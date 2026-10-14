package de.cleartube.app.download

import android.net.Uri
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Option
import de.cleartube.app.data.Store
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Gemeinsamer Zustand zwischen UI und Download-Service; wird dauerhaft gespeichert. */
object DownloadHub {
    val jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val running = ConcurrentHashMap<Long, Job>()
    private val ids = AtomicLong(System.currentTimeMillis())

    /** Beim App-Start: gespeicherte Liste laden. */
    fun restore() {
        jobs.value = Store.loadJobs()
    }

    private fun persist() {
        runCatching { Store.saveJobs(jobs.value) }
    }

    fun enqueue(title: String, thumb: String?, option: Option, tree: Uri, videoUrl: String = "", seriesId: String = ""): DownloadJob {
        val job = DownloadJob(ids.getAndIncrement(), title, thumb, option, tree, videoUrl, seriesId)
        jobs.update { it + job }
        persist()
        return job
    }

    /** Fortschritt wird nicht gespeichert, nur Zustandswechsel (sonst zu viele Schreibvorgänge). */
    fun update(id: Long, f: (DownloadJob) -> DownloadJob) {
        var persistNeeded = false
        jobs.update { list ->
            persistNeeded = false
            list.map {
                if (it.id == id) {
                    val n = f(it)
                    if (n.state != it.state || n.resultUri != it.resultUri) persistNeeded = true
                    n
                } else it
            }
        }
        if (persistNeeded) persist()
    }

    fun nextQueued(): DownloadJob? = jobs.value.firstOrNull { it.state == JobState.QUEUED }

    fun cancel(id: Long) {
        val r = running[id]
        if (r != null) r.cancel()
        else update(id) { if (it.state == JobState.QUEUED) it.copy(state = JobState.CANCELLED) else it }
    }

    /** Fehlgeschlagenen Download erneut einreihen (Adressen werden dabei neu geholt). */
    fun retry(id: Long) {
        update(id) { it.copy(state = JobState.QUEUED, progress = 0f, message = null) }
    }

    fun remove(id: Long) {
        jobs.update { list -> list.filterNot { it.id == id } }
        persist()
    }

    fun clearFinished() {
        jobs.update { list ->
            list.filter { it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.MUXING }
        }
        persist()
    }
}
