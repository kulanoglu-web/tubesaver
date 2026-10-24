package de.cleartube.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.linkhandler.SearchQueryHandler
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.VideoStream

class SearchSession(val handler: SearchQueryHandler, var next: Page?)

object YouTubeRepo {
    private val yt get() = ServiceList.YouTube

    fun isYoutubeUrl(s: String): Boolean {
        val t = s.trim()
        return Regex("""(https?://)?(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/\S+""").matches(t)
    }

    fun extractUrl(text: String): String? =
        Regex("""https?://(www\.|m\.|music\.)?(youtube\.com|youtu\.be)/\S+""").find(text)?.value

    suspend fun search(query: String): Pair<List<VideoItem>, SearchSession> = withContext(Dispatchers.IO) {
        val handler = yt.searchQHFactory.fromQuery(
            query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), ""
        )
        val info = SearchInfo.getInfo(yt, handler)
        val items = info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toVideoItem() }
        items to SearchSession(handler, info.nextPage)
    }

    /** Suchvorschläge von YouTube passend zur Eingabe. */
    suspend fun suggest(query: String): List<String> = withContext(Dispatchers.IO) {
        runCatching { yt.suggestionExtractor.suggestionList(query).take(8) }.getOrDefault(emptyList())
    }

    suspend fun more(session: SearchSession): List<VideoItem> = withContext(Dispatchers.IO) {
        val page = session.next ?: return@withContext emptyList()
        val res = SearchInfo.getMoreItems(yt, session.handler, page)
        session.next = res.nextPage
        res.items.filterIsInstance<StreamInfoItem>().map { it.toVideoItem() }
    }

    private fun StreamInfoItem.toVideoItem() = VideoItem(
        url = url,
        title = name ?: "",
        uploader = uploaderName ?: "",
        thumbnail = thumbnails.firstOrNull()?.url,
        durationSec = duration,
        views = viewCount
    )

    /**
     * Lädt die Videodaten. YouTube weist manche Abfragen zufällig ab ("The page needs to be
     * reloaded"); ein erneuter Versuch klappt dann meist, daher wird bis zu 4-mal wiederholt.
     */
    suspend fun details(url: String): VideoDetails = coroutineScope {
        // Gleichzeitig: der zweite Zugang liefert dieselben Streams von anderen YouTube-Servern,
        // die der Download später parallel mitnutzt.
        val extra = async(Dispatchers.IO) {
            runCatching {
                val id = InnerTube.videoId(url) ?: return@runCatching null
                withTimeout(9000) { InnerTube.fetch(id).formats }
            }.getOrNull()
        }
        val base = primaryDetails(url)
        val more = extra.await()
        if (more.isNullOrEmpty()) base else base.copy(options = InnerTube.mergeSources(base.options, more))
    }

    private suspend fun primaryDetails(url: String): VideoDetails = withContext(Dispatchers.IO) {
        var last: Throwable? = null
        for (attempt in 0 until 3) {
            try {
                val info = StreamInfo.getInfo(yt, url)
                return@withContext VideoDetails(
                    url = url,
                    title = info.name ?: "video",
                    uploader = info.uploaderName ?: "",
                    thumbnail = info.thumbnails.firstOrNull()?.url,
                    options = buildOptions(info)
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                if (!retryable(e) || attempt == 2) break
                delay(700L * (attempt + 1))
            }
        }
        val primary = last ?: IllegalStateException("Unbekannter Fehler")
        // Altersbeschränkt, privat, gesperrt usw.: ein zweiter Weg hilft hier nicht
        if (!retryable(primary)) throw primary

        // Zweiter Weg: mehrere YouTube-Zugänge (Android-VR, iOS) gleichzeitig abfragen
        try {
            val id = InnerTube.videoId(url) ?: throw IllegalArgumentException("Video-ID nicht erkennbar")
            val r = InnerTube.fetch(id)
            val opts = InnerTube.options(r.formats)
            if (opts.isEmpty()) throw java.io.IOException("keine ladbaren Formate gefunden")
            VideoDetails(
                url = url,
                title = r.title,
                uploader = r.uploader,
                thumbnail = r.thumbnail,
                options = opts
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            throw IllegalStateException("${primary.message} | Zweiter Weg: ${e.message}", e)
        }
    }

    private fun retryable(e: Throwable): Boolean =
        e is java.io.IOException ||
            e is org.schabi.newpipe.extractor.exceptions.ParsingException ||
            e.message.orEmpty().contains("reload", ignoreCase = true)

    // ---------------------------------------------------------------- Optionen

    private fun sizeOf(s: VideoStream): Long = s.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L
    private fun sizeOf(s: AudioStream): Long = s.itagItem?.contentLength?.takeIf { it > 0 } ?: 0L

    private fun mb(bytes: Long) = if (bytes > 0) " · ~%.1f MB".format(bytes / 1048576.0) else ""

    private fun buildOptions(info: StreamInfo): List<Option> {
        val audios = info.audioStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val videoOnly = info.videoOnlyStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val muxed = info.videoStreams.filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }

        fun audioCandidates(suffix: String) =
            audios.filter { it.format?.suffix == suffix }.sortedByDescending { it.averageBitrate }

        val m4a = audioCandidates("m4a")
        val webmA = audioCandidates("webm")

        val videoOpts = mutableListOf<Option>()
        val coveredHeights = mutableSetOf<Int>()

        // Video-only (hohe Qualität) + Audio, wird später zusammengefügt
        for ((height, group) in videoOnly.groupBy { it.height }.toSortedMap(compareByDescending { it })) {
            val mp4 = group.filter { it.format?.suffix == "mp4" && it.codec?.startsWith("avc1") == true }
                .sortedByDescending { it.bitrate }
            val webm = group.filter { it.format?.suffix == "webm" && it.codec?.startsWith("vp") == true }
                .sortedByDescending { it.bitrate }

            val useWebm = mp4.isEmpty()
            val vids = if (useWebm) webm else mp4
            val auds = if (useWebm) webmA else m4a
            if (vids.isEmpty() || auds.isEmpty()) continue

            val fps = vids.first().fps
            val label = "${height}p" + (if (fps > 30) "$fps" else "") +
                " · " + (if (useWebm) "WEBM" else "MP4") +
                mb(sizeOf(vids.first()) + sizeOf(auds.first()))
            videoOpts += Option(
                kind = Kind.VIDEO,
                label = label,
                ext = if (useWebm) "webm" else "mp4",
                webm = useWebm,
                videoUrls = vids.map { it.content },
                audioUrls = auds.map { it.content },
                sizeBytes = sizeOf(vids.first()) + sizeOf(auds.first()),
                videoItag = vids.first().itagItem?.id ?: 0,
                audioItag = auds.first().itagItem?.id ?: 0
            )
            coveredHeights += height
        }

        // Fertige Streams mit Ton (meist 360p/720p) für Höhen, die oben fehlen
        for (s in muxed.sortedByDescending { it.height }) {
            if (s.height in coveredHeights) continue
            val ext = s.format?.suffix ?: "mp4"
            videoOpts += Option(
                kind = Kind.VIDEO,
                label = "${s.height}p · ${ext.uppercase()} (mit Ton)" + mb(sizeOf(s)),
                ext = ext,
                webm = ext == "webm",
                videoUrls = listOf(s.content),
                audioUrls = emptyList(),
                sizeBytes = sizeOf(s),
                videoItag = s.itagItem?.id ?: 0
            )
            coveredHeights += s.height
        }
        videoOpts.sortByDescending { o -> Regex("""^(\d+)p""").find(o.label)?.groupValues?.get(1)?.toInt() ?: 0 }

        // Nur Audio: pro Format die besten Bitraten
        val audioOpts = mutableListOf<Option>()
        for ((suffix, list) in listOf("m4a" to m4a, "webm" to webmA)) {
            val seen = mutableSetOf<Int>()
            for (a in list) {
                if (!seen.add(a.averageBitrate)) continue
                if (seen.size > 3) break
                val name = if (suffix == "m4a") "M4A (AAC)" else "WEBM (Opus)"
                audioOpts += Option(
                    kind = Kind.AUDIO,
                    label = "$name · ${a.averageBitrate} kbps" + mb(sizeOf(a)),
                    ext = suffix,
                    webm = suffix == "webm",
                    videoUrls = emptyList(),
                    // Alternativen mit gleichem Format als Fallback
                    audioUrls = listOf(a.content) + list.filter { it !== a }.map { it.content },
                    sizeBytes = sizeOf(a),
                    audioItag = a.itagItem?.id ?: 0
                )
            }
        }
        // M4A zuerst (läuft auf jedem Gerät), darin die höchste Bitrate vorn
        audioOpts.sortWith(
            compareByDescending<Option> { it.ext == "m4a" }
                .thenByDescending { o -> Regex("""(\d+) kbps""").find(o.label)?.groupValues?.get(1)?.toInt() ?: 0 }
        )

        return videoOpts + audioOpts
    }
}
