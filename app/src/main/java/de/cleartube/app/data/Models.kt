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
    val sizeBytes: Long,
    /** YouTube-Formatnummern: gleiche Nummer von anderem Zugang = gleiche Datei auf anderem Server */
    val videoItag: Int = 0,
    val audioItag: Int = 0
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
    /** YouTube-Adresse, damit abgelaufene oder fehlende Download-Adressen neu geholt werden können */
    val videoUrl: String = "",
    /** Gehört zu einer abonnierten Serie (Adresse der Playlist), sonst leer */
    val seriesId: String = "",
    /** Kanal, von dem das Video stammt (zur Kontrolle gegen Fälschungen) */
    val channel: String = "",
    val state: JobState = JobState.QUEUED,
    val progress: Float = 0f,
    val message: String? = null,
    /** Fertige Datei im gewählten Ordner, zum Öffnen per Antippen */
    val resultUri: Uri? = null,
    val openMime: String? = null,
    /** Zeitpunkt des Abschlusses und tatsächliche Dateigröße, für den Aufräum-Vorschlag */
    val finishedAt: Long = 0L,
    val fileSize: Long = 0L
)

/** Abonnierte Serie = offizielle Playlist eines verifizierten Senderkanals. */
data class Series(
    val id: String,            // Playlist-Adresse
    val name: String,
    val thumbnail: String?,
    val channelName: String,
    val addedAt: Long,
    /** Adressen aller bisher bekannten Folgen; neue Folgen = nicht in dieser Menge */
    val known: Set<String>,
    val lastCheck: Long = 0L,
    /** Gemerkte Qualität (z. B. "720p · MP4"), mit der neue Folgen automatisch geladen werden */
    val pref: String = "",
    /** Adresse des gemerkten (offiziellen) Kanals; nur von dort werden Folgen automatisch geladen */
    val channelUrl: String = ""
)

/** Eine Folge einer Serie (Eintrag einer Playlist). */
data class Episode(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val durationSec: Long,
    val uploadedText: String?,
    val uploaderName: String = "",
    val uploaderUrl: String = ""
) {
    /** Ganze Folge: mindestens die eingestellte Länge; unbekannte Dauer (Livestream) zählt nicht. */
    fun isFull(): Boolean = durationSec >= Store.seriesMinMinutes * 60L
}

/** Kanal aus der Suche; nur verifizierte gelten als offiziell. */
data class ChannelHit(val url: String, val name: String, val thumbnail: String?, val verified: Boolean)

/** Playlist (= Serie) eines Kanals. */
data class SeriesHit(
    val url: String,
    val name: String,
    val thumbnail: String?,
    val channelName: String,
    val verified: Boolean,
    val episodes: Long,
    val channelUrl: String = ""
)
