package de.cleartube.app.data

import de.cleartube.app.net.Http
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

/**
 * Zweiter Weg zu den Videodaten, falls der NewPipe-Extractor scheitert: Die YouTube-Schnittstelle
 * wird gleichzeitig über mehrere Clients (Android-VR, iOS) abgefragt. Die Formate aller Antworten
 * werden zusammengeführt; derselbe Stream von verschiedenen Clients dient als alternative Quelle.
 */
object InnerTube {
    const val VR_UA =
        "com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 12L; eureka-user Build/SQ3A.220605.009.A1) gzip"
    const val IOS_UA = "com.google.ios.youtube/20.10.4 (iPhone16,2; U; CPU iOS 18_3_2 like Mac OS X;)"

    private class Client(
        val name: String,
        val id: Int,
        val version: String,
        val ua: String,
        val extra: JSONObject.() -> Unit
    )

    private val clients = listOf(
        Client("ANDROID_VR", 28, "1.60.19", VR_UA) {
            put("deviceMake", "Oculus")
            put("deviceModel", "Quest 3")
            put("osName", "Android")
            put("osVersion", "12L")
            put("androidSdkVersion", 32)
        },
        Client("IOS", 5, "20.10.4", IOS_UA) {
            put("deviceMake", "Apple")
            put("deviceModel", "iPhone16,2")
            put("osName", "iPhone")
            put("osVersion", "18.3.2.22D82")
        }
    )

    class Fmt(
        val itag: Int,
        val url: String,
        val mime: String,
        val codecs: String,
        val bitrate: Int,
        val height: Int,
        val fps: Int,
        val size: Long,
        val hasAudio: Boolean
    )

    class Result(
        val title: String,
        val uploader: String,
        val thumbnail: String?,
        val formats: List<Fmt>
    )

    fun videoId(url: String): String? =
        Regex("(?:v=|youtu\\.be/|/shorts/|/embed/|/live/)([A-Za-z0-9_-]{11})")
            .find(url)?.groupValues?.get(1)

    /** Fragt alle Clients parallel ab und führt die Ergebnisse zusammen. */
    suspend fun fetch(videoId: String): Result = coroutineScope {
        val responses = clients.map { c ->
            async(Dispatchers.IO) { runCatching { request(c, videoId) }.getOrNull() }
        }.awaitAll().filterNotNull()

        if (responses.isEmpty()) throw IOException("Kein YouTube-Zugang hat geantwortet")

        val details = responses.firstNotNullOfOrNull { it.optJSONObject("videoDetails") }
        val title = details?.optString("title").orEmpty().ifEmpty { "video" }
        val uploader = details?.optString("author").orEmpty()
        val thumbs = details?.optJSONObject("thumbnail")?.optJSONArray("thumbnails")
        val thumbnail = thumbs?.optJSONObject((thumbs.length() - 1).coerceAtLeast(0))
            ?.optString("url")?.takeIf { it.isNotEmpty() }

        Result(title, uploader, thumbnail, responses.flatMap { parse(it) })
    }

    private fun request(c: Client, videoId: String): JSONObject? {
        val client = JSONObject().apply {
            put("clientName", c.name)
            put("clientVersion", c.version)
            c.extra(this)
            put("hl", "de")
            put("gl", "DE")
            put("utcOffsetMinutes", 0)
        }
        val body = JSONObject().apply {
            put("context", JSONObject().put("client", client))
            put("videoId", videoId)
            put("contentCheckOk", true)
            put("racyCheckOk", true)
        }
        val req = Request.Builder()
            .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
            .header("User-Agent", c.ua)
            .header("X-YouTube-Client-Name", c.id.toString())
            .header("X-YouTube-Client-Version", c.version)
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) return null
            val text = r.body?.string() ?: return null
            val json = JSONObject(text)
            val status = json.optJSONObject("playabilityStatus")?.optString("status")
            return if (status == "OK") json else null
        }
    }

    private fun parse(json: JSONObject): List<Fmt> {
        val sd = json.optJSONObject("streamingData") ?: return emptyList()
        val out = mutableListOf<Fmt>()

        fun add(arr: JSONArray?, muxed: Boolean) {
            if (arr == null) return
            for (i in 0 until arr.length()) {
                val f = arr.optJSONObject(i) ?: continue
                // Formate mit verschlüsselter Adresse (signatureCipher) werden übersprungen
                val url = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                val mimeFull = f.optString("mimeType")
                val codecs = Regex("codecs=\"([^\"]+)\"").find(mimeFull)?.groupValues?.get(1).orEmpty()
                out += Fmt(
                    itag = f.optInt("itag"),
                    url = url,
                    mime = mimeFull.substringBefore(';').trim(),
                    codecs = codecs,
                    bitrate = f.optInt("bitrate"),
                    height = f.optInt("height"),
                    fps = f.optInt("fps"),
                    size = f.optString("contentLength").toLongOrNull() ?: 0L,
                    hasAudio = muxed
                )
            }
        }
        add(sd.optJSONArray("adaptiveFormats"), false)
        add(sd.optJSONArray("formats"), true)
        return out
    }

    /**
     * Hängt an jede Variante die gleichen Streams (gleiche Formatnummer) der YouTube-Zugänge an.
     * Die Quelle des Hauptzugangs bleibt vorn; die anderen sind zusätzliche Server derselben Datei.
     */
    fun mergeSources(options: List<Option>, extra: List<Fmt>): List<Option> {
        fun urlsFor(itag: Int): List<String> =
            if (itag == 0) emptyList() else extra.filter { it.itag == itag }.map { it.url }
        return options.map { o ->
            o.copy(
                videoUrls = (o.videoUrls.take(1) + urlsFor(o.videoItag) + o.videoUrls.drop(1)).distinct(),
                audioUrls = (o.audioUrls.take(1) + urlsFor(o.audioItag) + o.audioUrls.drop(1)).distinct()
            )
        }
    }

    private fun mb(bytes: Long) = if (bytes > 0) " · ~%.1f MB".format(bytes / 1048576.0) else ""

    /** Baut aus den zusammengeführten Formaten die wählbaren Download-Varianten. */
    fun options(formats: List<Fmt>): List<Option> {
        val audioM4a = formats.filter { it.mime == "audio/mp4" }.sortedByDescending { it.bitrate }
        val audioWebm = formats.filter { it.mime == "audio/webm" }.sortedByDescending { it.bitrate }
        val videoOnly = formats.filter { it.mime.startsWith("video/") && !it.hasAudio && it.height > 0 }
        val muxed = formats.filter { it.mime.startsWith("video/") && it.hasAudio && it.height > 0 }

        val videoOpts = mutableListOf<Pair<Int, Option>>()
        val covered = mutableSetOf<Int>()

        for ((height, group) in videoOnly.groupBy { it.height }) {
            val mp4 = group.filter { it.mime == "video/mp4" && it.codecs.startsWith("avc1") }
                .sortedByDescending { it.bitrate }
            val webm = group.filter { it.mime == "video/webm" && it.codecs.startsWith("vp") }
                .sortedByDescending { it.bitrate }
            val useWebm = mp4.isEmpty()
            val vids = if (useWebm) webm else mp4
            val auds = if (useWebm) audioWebm else audioM4a
            if (vids.isEmpty() || auds.isEmpty()) continue

            val size = vids.first().size + auds.first().size
            val fps = vids.first().fps
            videoOpts += height to Option(
                kind = Kind.VIDEO,
                label = "${height}p" + (if (fps > 30) "$fps" else "") +
                    " · " + (if (useWebm) "WEBM" else "MP4") + mb(size),
                ext = if (useWebm) "webm" else "mp4",
                webm = useWebm,
                videoUrls = vids.map { it.url },
                audioUrls = auds.map { it.url },
                sizeBytes = size,
                videoItag = vids.first().itag,
                audioItag = auds.first().itag
            )
            covered += height
        }

        // Fertige Streams mit Ton für Höhen, die oben fehlen
        for ((height, group) in muxed.groupBy { it.height }) {
            if (height in covered) continue
            val list = group.filter { it.mime == "video/mp4" }.sortedByDescending { it.bitrate }
            if (list.isEmpty()) continue
            videoOpts += height to Option(
                kind = Kind.VIDEO,
                label = "${height}p · MP4 (mit Ton)" + mb(list.first().size),
                ext = "mp4",
                webm = false,
                videoUrls = list.map { it.url },
                audioUrls = emptyList(),
                sizeBytes = list.first().size,
                videoItag = list.first().itag
            )
        }

        val audioOpts = mutableListOf<Pair<Int, Option>>()
        for ((list, ext, name) in listOf(
            Triple(audioM4a, "m4a", "M4A (AAC)"),
            Triple(audioWebm, "webm", "WEBM (Opus)")
        )) {
            val seen = mutableSetOf<Int>()
            for (a in list) {
                val kbps = a.bitrate / 1000
                if (!seen.add(kbps)) continue
                if (seen.size > 3) break
                audioOpts += kbps to Option(
                    kind = Kind.AUDIO,
                    label = "$name · $kbps kbps" + mb(a.size),
                    ext = ext,
                    webm = ext == "webm",
                    videoUrls = emptyList(),
                    audioUrls = listOf(a.url) + list.filter { it !== a }.map { it.url },
                    sizeBytes = a.size,
                    audioItag = a.itag
                )
            }
        }

        return videoOpts.sortedByDescending { it.first }.map { it.second } +
            audioOpts.sortedWith(
                compareByDescending<Pair<Int, Option>> { it.second.ext == "m4a" }.thenByDescending { it.first }
            ).map { it.second }
    }
}
