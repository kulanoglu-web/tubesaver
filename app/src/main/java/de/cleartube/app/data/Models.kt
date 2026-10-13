package de.cleartube.app.data

import android.net.Uri

data class VideoItem(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val durationSec: Long,
    val views: Long
)

enum class Kind { VIDEO, AUDIO }

/**
 * Eine wählbare Download-Variante.
 * videoUrls / audioUrls sind Kandidatenlisten: schlägt die erste Quelle fehl,
 * wird automatisch die nächste (alternativer Stream/Server) versucht.
 */
data class Option(
    val kind: Kind,
    val label: String,
    val ext: String,
    val webm: Boolean,
    val videoUrls: List<String>,
    val audioUrls: List<String>,
    val sizeBytes: Long
)

data class VideoDetails(
    val url: String,
    val title: String,
    val uploader: String,
    val thumbnail: String?,
    val options: List<Option>
)

enum class JobState { QUEUED, RUNNING, MUXING, DONE, FAILED, CANCELLED }

data class DownloadJob(
    val id: Long,
    val title: String,
    val thumbnail: String?,
    val option: Option,
    val tree: Uri,
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val message: String? = null
)
