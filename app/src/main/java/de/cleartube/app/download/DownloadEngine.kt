package de.cleartube.app.download

import de.cleartube.app.data.InnerTube
import de.cleartube.app.net.Http
import de.cleartube.app.net.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.asCoroutineDispatcher
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
 * Leitet aus einer googlevideo-URL weitere Server desselben Videos ab.
 * Der Hostname hat die Form rr1---sn-aaa.googlevideo.com. Dieselbe Datei liegt auf mehreren
 * Front-Servern (rr1 bis rr4) und auf weiteren Knoten, die YouTube im Parameter "mn" nennt.
 * Ob ein Server wirklich antwortet, wird vor dem Download geprüft.
 */
object Mirrors {
    private val hostRe = Regex("^(r{1,2})(\\d+)---(sn-[a-z0-9]+)(\\.googlevideo\\.com)$")
    private val hostOfUrl = Regex("^https?://([^/:]+)")
    private val mnParam = Regex("[?&]mn=([^&]+)")

    fun hostOf(url: String): String = hostOfUrl.find(url)?.groupValues?.get(1) ?: url

    /** Zusätzliche Server-Adressen (ohne die Original-URL). */
    fun expand(url: String): List<String> {
        val host = hostOfUrl.find(url)?.groupValues?.get(1) ?: return emptyList()
        val m = hostRe.find(host) ?: return emptyList()
        val letters = m.groupValues[1]
        val node = m.groupValues[3]
        val suffix = m.groupValues[4]

        val otherNodes = mnParam.find(url)?.groupValues?.get(1)
            ?.let { URLDecoder.decode(it, "UTF-8") }
            ?.split(',')
            ?.filter { it.startsWith("sn-") && it != node }
            ?.distinct()
            ?.take(2)
            ?: emptyList()

        val variants = LinkedHashSet<String>()
        for (n in listOf(node) + otherNodes) {
            for (rr in 1..4) {
                val h = "$letters$rr---$n$suffix"
                if (h != host) variants += h
            }
        }
        return variants.take(6).map { url.replaceFirst(host, it) }
    }
}

/**
 * Lädt eine Datei in Segmenten (HTTP-Range) über mehrere parallele Verbindungen, verteilt auf
 * mehrere YouTube-Server. Fällt ein Server aus, übernehmen die übrigen; schlägt eine ganze
 * Quelle fehl, wird zuerst im Schonmodus (wenige Verbindungen, nur Originalserver) und dann mit
 * dem nächsten alternativen Stream versucht.
 */
/** Eigener Thread-Pool: mehrere parallele Downloads mit je 16+ Verbindungen sollen sich nicht gegenseitig aufhalten. */
internal val NetIO: kotlinx.coroutines.CoroutineDispatcher =
    java.util.concurrent.Executors.newCachedThreadPool().asCoroutineDispatcher()

object DownloadEngine {
    private const val CONNECTIONS = 16
    private const val SAFE_CONNECTIONS = 2
    private const val CHUNK = 4L * 1024 * 1024
    private const val MAX_ATTEMPTS = 6

    /** Wie der Bereich angefragt wird: per Range-Kopfzeile oder per Adress-Parameter "range=". */
    private enum class Mode { HEADER, PARAM }

    private class Probe(val total: Long, val mode: Mode)

    /** Streams aus dem zweiten Abfrageweg erwarten die Kennung ihres Clients. */
    private fun uaFor(url: String): String = when {
        "c=ANDROID_VR" in url -> InnerTube.VR_UA
        "c=IOS" in url -> InnerTube.IOS_UA
        else -> USER_AGENT
    }

    private fun describe(url: String): String {
        val client = Regex("[?&]c=([A-Z_]+)").find(url)?.groupValues?.get(1) ?: "WEB"
        return "$client/${Mirrors.hostOf(url).substringBefore('.')}"
    }

    private fun clen(url: String): Long? =
        Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()

    private fun build(url: String, start: Long, end: Long, mode: Mode): Request {
        val b = Request.Builder().header("User-Agent", uaFor(url))
        return when (mode) {
            Mode.HEADER -> b.url(url).header("Range", "bytes=$start-$end").build()
            Mode.PARAM -> b.url(url + (if ('?' in url) "&" else "?") + "range=$start-$end").build()
        }
    }

    /**
     * Download-Client: nur HTTP/1.1. Mit HTTP/2 würden alle parallelen Anfragen über eine einzige
     * Verbindung zum Server laufen und sich ihre Geschwindigkeit teilen; mit HTTP/1.1 bekommt jede
     * Anfrage eine eigene Verbindung und die Leitung wird wirklich ausgelastet.
     */
    private val dlClient: OkHttpClient by lazy {
        Http.client.newBuilder()
            .protocols(listOf(okhttp3.Protocol.HTTP_1_1))
            .connectionPool(okhttp3.ConnectionPool(48, 2, TimeUnit.MINUTES))
            .build()
    }

    private val probeClient: OkHttpClient by lazy {
        dlClient.newBuilder().callTimeout(6, TimeUnit.SECONDS).build()
    }

    /** Verwaltet die Server einer Quelle; defekte Server werden übersprungen. */
    private class Pool(val urls: List<String>, val mode: Mode) {
        private val dead = ConcurrentHashMap.newKeySet<String>()

        fun order(pref: Int): List<String> {
            val alive = urls.filterNot { it in dead }.ifEmpty { urls }
            return alive.indices.map { alive[(pref + it) % alive.size] }
        }

        fun markDead(u: String) {
            if (u != urls.first()) dead.add(u) // der Haupt-Server bleibt immer im Spiel
        }
    }

    /**
     * Probiert die Quellen nacheinander durch, bis eine komplett geladen wurde. Die übrigen
     * Adressen der Liste dienen dabei als zusätzliche Server, sofern sie dieselbe Datei liefern.
     */
    suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        onInfo: (String) -> Unit = {},
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        if (urls.isEmpty()) throw IOException("Keine Download-Adresse vorhanden")
        val errors = LinkedHashSet<String>()
        for ((i, url) in urls.withIndex()) {
            val others = urls.filterIndexed { j, _ -> j != i }
            for (safe in listOf(false, true)) {
                try {
                    download(url, others, dest, onInfo, onProgress, safe)
                    return
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    errors += "${describe(url)}${if (safe) " (schonend)" else ""}: ${e.message ?: e.javaClass.simpleName}"
                    dest.delete()
                }
            }
        }
        val text = errors.take(3).joinToString(" | ")
        throw IOException("Alle ${urls.size} Quellen fehlgeschlagen – $text")
    }

    private fun probe(client: OkHttpClient, url: String, modes: List<Mode> = Mode.values().toList()): Probe {
        val errs = mutableListOf<String>()
        for (mode in modes) {
            try {
                client.newCall(build(url, 0, 0, mode)).execute().use { r ->
                    if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
                    val fromRange = r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                    val fromFull = if (r.code == 200 && mode == Mode.HEADER) {
                        r.header("Content-Length")?.toLongOrNull()
                    } else null
                    val total = fromRange ?: clen(url) ?: fromFull ?: throw IOException("Dateigröße unbekannt")
                    return Probe(total, mode)
                }
            } catch (e: IOException) {
                errs += "${e.message} (${mode.name.lowercase()})"
            }
        }
        throw IOException(errs.joinToString(", "))
    }

    private suspend fun download(
        url: String,
        otherUrls: List<String>,
        dest: File,
        onInfo: (String) -> Unit,
        onProgress: (Long, Long) -> Unit,
        safe: Boolean
    ) = coroutineScope {
        // Haupt-Server muss antworten, sonst geht es mit der nächsten Quelle weiter
        val main = withContext(NetIO) { probe(dlClient, url) }
        val total = main.total

        // Alle weiteren Server parallel prüfen, nur gleich große Antworten zählen
        val candidates = if (safe) emptyList() else {
            (otherUrls + Mirrors.expand(url) + otherUrls.flatMap { Mirrors.expand(it) })
                .distinct().filter { it != url }.take(12)
        }
        val extra = candidates.map { m ->
            async(NetIO) {
                runCatching { probe(probeClient, m, listOf(main.mode)) }.getOrNull()
                    ?.takeIf { it.total == total }?.let { m }
            }
        }.awaitAll().filterNotNull()

        val pool = Pool(listOf(url) + extra, main.mode)
        val servers = pool.urls.map { Mirrors.hostOf(it) }.distinct().size
        onInfo(if (servers == 1) "1 Server" else "$servers Server")

        RandomAccessFile(dest, "rw").use { it.setLength(total) }

        val count = ((total + CHUNK - 1) / CHUNK).toInt()
        val next = AtomicInteger(0)
        val done = AtomicLong(0)
        onProgress(0, total)

        val connections = if (safe) SAFE_CONNECTIONS else CONNECTIONS
        (0 until min(connections, count)).map { worker ->
            async(NetIO) {
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
                val req = build(server, start, end, pool.mode)
                dlClient.newCall(req).execute().use { r ->
                    // Ein Server, der den Bereich ignoriert (200), ist nur für das erste Segment brauchbar
                    val ok = r.code == 206 ||
                        (r.code == 200 && (start == 0L || pool.mode == Mode.PARAM))
                    if (!ok) throw IOException("HTTP ${r.code}")
                    val body = r.body ?: throw IOException("Leere Antwort")
                    val buf = ByteArray(256 * 1024)
                    val expected = end - start + 1
                    body.byteStream().use { input ->
                        raf.seek(start)
                        while (written < expected) {
                            currentCoroutineContext().ensureActive()
                            val n = input.read(buf, 0, min(buf.size.toLong(), expected - written).toInt())
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
                if (++attempt >= MAX_ATTEMPTS) {
                    throw IOException("${describe(server)} ${e.message ?: e.javaClass.simpleName}", e)
                }
            }
        }
    }
}
