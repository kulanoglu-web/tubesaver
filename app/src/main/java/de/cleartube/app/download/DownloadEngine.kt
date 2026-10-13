package de.cleartube.app.download

import de.cleartube.app.net.Http
import de.cleartube.app.net.USER_AGENT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.min

/**
 * Lädt eine Datei über mehrere parallele Verbindungen (HTTP-Range-Segmente) vom
 * YouTube-Server und probiert bei Fehlern automatisch die nächste Quelle.
 */
object DownloadEngine {
    private const val CONNECTIONS = 6
    private const val CHUNK = 4L * 1024 * 1024
    private const val RETRIES = 3

    /** Probiert die Quellen nacheinander durch, bis eine komplett geladen wurde. */
    suspend fun downloadWithFallback(
        urls: List<String>,
        dest: File,
        onProgress: (done: Long, total: Long) -> Unit
    ) {
        var last: Throwable? = null
        for (url in urls) {
            try {
                download(url, dest, onProgress)
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

    private fun probeLength(url: String): Long {
        val req = Request.Builder().url(url)
            .header("Range", "bytes=0-0")
            .header("User-Agent", USER_AGENT)
            .build()
        Http.client.newCall(req).execute().use { r ->
            if (!r.isSuccessful) throw IOException("HTTP ${r.code}")
            r.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { return it }
            return r.header("Content-Length")?.toLongOrNull()
                ?: throw IOException("Dateigröße unbekannt")
        }
    }

    private suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) =
        coroutineScope {
            val total = probeLength(url)
            RandomAccessFile(dest, "rw").use { it.setLength(total) }

            val count = ((total + CHUNK - 1) / CHUNK).toInt()
            val next = AtomicInteger(0)
            val done = AtomicLong(0)
            onProgress(0, total)

            (1..min(CONNECTIONS, count)).map {
                async(Dispatchers.IO) {
                    RandomAccessFile(dest, "rw").use { raf ->
                        while (true) {
                            ensureActive()
                            val i = next.getAndIncrement()
                            if (i >= count) break
                            val start = i * CHUNK
                            val end = min(total - 1, start + CHUNK - 1)
                            fetchRange(url, start, end, raf, done) { onProgress(done.get(), total) }
                        }
                    }
                }
            }.awaitAll()
        }

    private suspend fun fetchRange(
        url: String,
        start: Long,
        end: Long,
        raf: RandomAccessFile,
        done: AtomicLong,
        tick: () -> Unit
    ) {
        var attempt = 0
        while (true) {
            var written = 0L
            try {
                val req = Request.Builder().url(url)
                    .header("Range", "bytes=$start-$end")
                    .header("User-Agent", USER_AGENT)
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
                if (++attempt >= RETRIES) throw e
            }
        }
    }
}
