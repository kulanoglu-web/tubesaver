package de.cleartube.app.download

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import de.cleartube.app.data.DownloadJob
import de.cleartube.app.data.JobState
import de.cleartube.app.data.Store

/** Findet große, alte Downloads und löscht sie auf Wunsch. */
object Cleanup {
    class Candidate(val job: DownloadJob, val sizeBytes: Long, val ageDays: Int)

    private const val DAY_MS = 86_400_000L

    /** Fertige Downloads, die mindestens X MB groß und mindestens Y Tage alt sind und noch existieren. */
    fun find(ctx: Context): List<Candidate> {
        val now = System.currentTimeMillis()
        val minBytes = Store.cleanupMb * 1024L * 1024L
        val minAge = Store.cleanupDays * DAY_MS
        return DownloadHub.jobs.value
            .filter {
                it.state == JobState.DONE && it.resultUri != null && it.fileSize >= minBytes &&
                    it.finishedAt > 0 && now - it.finishedAt >= minAge
            }
            .filter { exists(ctx, it.resultUri!!) }
            .map { Candidate(it, it.fileSize, ((now - it.finishedAt) / DAY_MS).toInt()) }
            .sortedByDescending { it.sizeBytes }
    }

    fun exists(ctx: Context, uri: Uri): Boolean =
        runCatching { DocumentFile.fromSingleUri(ctx, uri)?.exists() == true }.getOrDefault(false)

    fun delete(ctx: Context, uri: Uri): Boolean =
        runCatching { DocumentFile.fromSingleUri(ctx, uri)?.delete() == true }.getOrDefault(false)
}
