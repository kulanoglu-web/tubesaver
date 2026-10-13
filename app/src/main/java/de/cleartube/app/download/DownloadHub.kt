package de.cleartube.app.download

import android.net.Uri
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Option
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/** Gemeinsamer Zustand zwischen UI und Download-Service. */
object DownloadHub {
    val jobs = MutableStateFlow<List<DownloadJob>>(emptyList())
    val running = ConcurrentHashMap<Long, Job>()
    private val ids = AtomicLong(1)

    fun enqueue(title: String, thumb: String?, option: Option, tree: Uri): DownloadJob {
        val job = DownloadJob(ids.getAndIncrement(), title, thumb, option, tree)
        jobs.update { it + job }
        return job
    }

    fun update(id: Long, f: (DownloadJob) -> DownloadJob) {
        jobs.update { list -> list.map { if (it.id == id) f(it) else it } }
    }

    fun nextQueued(): DownloadJob? = jobs.value.firstOrNull { it.state == JobState.QUEUED }

    fun cancel(id: Long) {
        val r = running[id]
        if (r != null) r.cancel()
        else update(id) { if (it.state == JobState.QUEUED) it.copy(state = JobState.CANCELLED) else it }
    }

    fun remove(id: Long) {
        jobs.update { list -> list.filterNot { it.id == id } }
    }

    fun clearFinished() {
        jobs.update { list ->
            list.filter { it.state == JobState.QUEUED || it.state == JobState.RUNNING || it.state == JobState.MUXING }
        }
    }
}
