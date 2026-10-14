package de.cleartube.app.download

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteBuffer

/** Fügt getrennte Video- und Audiospuren ohne Neukodierung zusammen. */
object Muxer {

    fun mux(video: File, audio: File, out: File, webm: Boolean) {
        val vEx = MediaExtractor().apply { setDataSource(video.absolutePath) }
        val aEx = MediaExtractor().apply { setDataSource(audio.absolutePath) }
        val muxer = MediaMuxer(
            out.absolutePath,
            if (webm) MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM else MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
        )
        try {
            val vi = findTrack(vEx, "video/")
            val ai = findTrack(aEx, "audio/")
            vEx.selectTrack(vi)
            aEx.selectTrack(ai)
            val vTrack = muxer.addTrack(vEx.getTrackFormat(vi))
            val aTrack = muxer.addTrack(aEx.getTrackFormat(ai))
            muxer.start()
            interleave(vEx, vTrack, aEx, aTrack, muxer)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            vEx.release()
            aEx.release()
        }
    }

    /**
     * Packt eine reine Audiodatei (YouTube liefert M4A in Stücken) in eine saubere M4A-Datei um,
     * damit sie in allen Playern mit Laufzeit und Spulen funktioniert. Keine Neukodierung.
     */
    fun remuxAudio(src: File, out: File) {
        val ex = MediaExtractor().apply { setDataSource(src.absolutePath) }
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        try {
            val i = findTrack(ex, "audio/")
            ex.selectTrack(i)
            val t = muxer.addTrack(ex.getTrackFormat(i))
            muxer.start()
            copySamples(ex, muxer, t)
            muxer.stop()
        } finally {
            runCatching { muxer.release() }
            ex.release()
        }
    }

    private fun findTrack(ex: MediaExtractor, prefix: String): Int {
        for (i in 0 until ex.trackCount) {
            val mime = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(prefix)) return i
        }
        error("Keine Spur '$prefix' gefunden")
    }

    /** Schreibt Bild und Ton abwechselnd nach Zeitstempel: schneller und speicherschonend. */
    private fun interleave(vEx: MediaExtractor, vTrack: Int, aEx: MediaExtractor, aTrack: Int, muxer: MediaMuxer) {
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            val vt = vEx.sampleTime
            val at = aEx.sampleTime
            if (vt < 0 && at < 0) break
            val useVideo = at < 0 || (vt in 0..at)
            val ex = if (useVideo) vEx else aEx
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) {
                ex.advance()
                continue
            }
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(if (useVideo) vTrack else aTrack, buf, info)
            ex.advance()
        }
    }

    private fun copySamples(ex: MediaExtractor, muxer: MediaMuxer, track: Int) {
        val buf = ByteBuffer.allocate(2 * 1024 * 1024)
        val info = MediaCodec.BufferInfo()
        while (true) {
            buf.clear()
            val size = ex.readSampleData(buf, 0)
            if (size < 0) break
            val flags = if (ex.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            info.set(0, size, ex.sampleTime, flags)
            muxer.writeSampleData(track, buf, info)
            ex.advance()
        }
    }
}
