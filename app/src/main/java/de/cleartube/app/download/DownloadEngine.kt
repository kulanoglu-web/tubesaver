package de.cleartube.app.download

import de.cleartube.app.data.InnerTube
import de.cleartube.app.net.Http
import de.cleartube.app.net.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Leitet aus einer googlevideo-URL weitere Edge-Server desselben Videos ab.
 * YouTube nennt sie im Parameter "mn" (z. B. mn=sn-aaa,sn-bbb); der Hostname enthält
 * den ersten Eintrag (rr1---sn-aaa.googlevideo.com).
 */
object Mirrors {
    private val hostRe = Regex("""^(r{1,2}\d+---)?(sn-[a-z0-9]+)(\.googlevideo\.com)$""")
    private val hostOfUrl = Regex("""^https?://([^/:]+)""")
    private val mnParam = Regex("""[?&]mn=([^&]+)""")

    fun expand(url: String): List<String> {
        val host = hostOfUrl.find(url)?.groupValues?.get(1) ?: return listOf(url)
        val m = hostRe.find(host) ?: return listOf(url)
        val mn = mnParam.find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.split(',')
            ?.filter { it.startsWith("sn-") }
            ?: return listOf(url)
        val prefix = m.groupValues[1]
        val suffix = m.groupValues[3]
        val others = mn.filter { it != m.groupValues[2] }.distinct().take(3)
        return listOf(url) + others.map { url.replaceFirst(host, "$prefix$it$suffix") }
    }
}

/**
 * Lädt eine Datei in Segmenten (HTTP-Range) über mehrere parallele Verbindungen, verteilt auf
 * mehrere YouTube-Server. Fällt ein Server aus, übernehmen die übrigen; schlägt eine ganze
 * Quelle fehl, wird automatisch der nächste alternative Stream versucht.
 */
object DownloadEngine {
    private const val CONNECTIONS = 8
    private const val CHUNK = 4L * 1024 * 1024
    private const val MAX_ATTEMPTS = 6

    /** Streams aus dem zweiten Abfrageweg erwarten die Kennung ihres Clients. */
    private fun uaFor(url: String): String = when {
        "c=ANDROID_VR" in url -> InnerTube.VR_UA
        "c=IOS" in url -> InnerTube.IOS_UA
        else -> USER_AGENT
    }

    private val probeClient: OkHttpClient by lazy {
        Http.client.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** Verwaltet die Server einer Quelle; defekte Server werden übersprungen. */
    private class Pool(val urls: List<String>) {
        private val dead = ConcurrentHashMap.newKeySet<String>()

        fun order(pref: Int): List<String> {
            val alive = urls.filterNot { it in dead }.ifEmpty { urls }
            return alive.indices.map { alive[(pref + it) % alive.size] }
        }

        fun markDead(u: String) {
            if (u != urls.first()) dead.add(u) // der Haupt-Server bleibt immer im Spiel
        }
    }

    /** Probiert die Quellen nacheinander durch, bis eine komplett geladen wurde. */
    suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        onInfo: (String) -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        var last: Throwable? = null
        for (url in urls) {
            try {
                download(url, dest, onInfo, onProgress)
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                last = e
                dest.delete()
            }
        }
        throw IOException("Alle Quellen fehlgeschlagen: ${last?.message}", last)
    }

    private fun probeLength(client: OkHttpClient, url: String): Long {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", uaFor(url))
            .build()
        client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { return it }
            return r.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("Dateigröße unbekannt")
        }
    }

    private suspend fun download(
        url: String,
        dest: File,
        onInfo: (String) -> Unit,
        onProgress: (Long, Long) -> Unit
    ) = coroutineScope {
        // Haupt-Server muss antworten, sonst geht es mit der nächsten Quelle weiter
        val total = withContext(Dispatchers.IO) { probeLength(Http.client, url) }

        // Weitere Server desselben Videos parallel prüfen, nur gleich große Antworten zählen
        val extra = Mirrors.expand(url).drop(1).map { m ->
            async(Dispatchers.IO) {
                runCatching { probeLength(probeClient, m) }.getOrNull()?.takeIf { it == total }?.let { m }
            }
        }.awaitAll().filterNotNull()

        val pool = Pool(listOf(url) + extra)
        onInfo("${pool.urls.size} Server")

        RandomAccessFile(dest, "rw").use { it.setLength(total) }

        val count = ((total + CHUNK - 1) / CHUNK).toInt()
        val next = AtomicInteger(0)
        val done = AtomicLong(0)
        onProgress(0, total)

        (0 until min(CONNECTIONS, count)).map { worker ->
            async(Dispatchers.IO) {
                RandomAccessFile(dest, "rw").use { raf ->
                    while (true) {
                        ensureActive()
                        val i = next.getAndIncrement()
                        if (i >= count) break
                        val start = i * CHUNK
                        val end = min(total - 1, start + CHUNK - 1)
                        fetchRange(pool, worker, start, end, raf, done) { onProgress(done.get(), total) }
                    }
                }
            }
        }.awaitAll()
    }

    private suspend fun fetchRange(
        pool: Pool,
        pref: Int,
        start: Long,
        end: Long,
        raf: RandomAccessFile,
        done: AtomicLong,
        tick: () -> Unit
    ) {
        var attempt = 0
        while (true) {
            val order = pool.order(pref)
            val server = order[attempt % order.size]
            var written = 0L
            try {
                val req = Request.Builder().url(server)
                    .header("Range", "bytes=$start-$end")
                    .header("User-Agent", uaFor(server))
                    .build()
                Http.client.newCall(req).execute().use { r ->
                    if (r.code != 206 && r.code != 200) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Leere Antwort")
                    val buf = ByteArray(64 * 1024)
                    body.byteStream().use { input ->
                        raf.seek(start)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf)
                            if (n < 0) break
                            raf.write(buf, 0, n)
                            written += n
                            done.addAndGet(n.toLong())
                            tick()
                        }
                    }
                }
                if (written != end - start + 1) throw IOException("Segment unvollständig")
                return
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                done.addAndGet(-written)
                pool.markDead(server)
                if (++attempt >= MAX_ATTEMPTS) throw e
            }
        }
    }
}
