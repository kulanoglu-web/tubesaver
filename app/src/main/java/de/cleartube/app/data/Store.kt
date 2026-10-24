package de.cleartube.app.data

import android.content.Context
import android.content.SharedPreferences
import android.net.Uri
import org.json.JSONArray
import org.json.JSONObject

/** Dauerhafte Speicherung: Einstellungen, letzte Suche und Downloadliste (überlebt App-Neustart). */
object Store {
    private lateinit var prefs: SharedPreferences

    fun init(ctx: Context) {
        prefs = ctx.applicationContext.getSharedPreferences("cleartube", Context.MODE_PRIVATE)
    }

    // ------------------------------------------------------------ Einstellungen

    var tree: String?
        get() = prefs.getString("tree", null)
        set(v) = prefs.edit().putString("tree", v).apply()

    var cleanupEnabled: Boolean
        get() = prefs.getBoolean("cl_on", true)
        set(v) = prefs.edit().putBoolean("cl_on", v).apply()

    var cleanupDays: Int
        get() = prefs.getInt("cl_days", 30)
        set(v) = prefs.edit().putInt("cl_days", v).apply()

    var cleanupMb: Int
        get() = prefs.getInt("cl_mb", 150)
        set(v) = prefs.edit().putInt("cl_mb", v).apply()

    var cleanupLastPrompt: Long
        get() = prefs.getLong("cl_last", 0L)
        set(v) = prefs.edit().putLong("cl_last", v).apply()

    /** "vlc", "exo" oder "extern" */
    var player: String
        get() = prefs.getString("player", "vlc") ?: "vlc"
        set(v) = prefs.edit().putString("player", v).apply()

    /** Wie viele Downloads gleichzeitig laufen (1–4) */
    var parallel: Int
        get() = prefs.getInt("parallel", 3).coerceIn(1, 4)
        set(v) = prefs.edit().putInt("parallel", v.coerceIn(1, 4)).apply()

    var seriesAutoDelete: Boolean
        get() = prefs.getBoolean("s_del", true)
        set(v) = prefs.edit().putBoolean("s_del", v).apply()

    var seriesKeepDays: Int
        get() = prefs.getInt("s_days", 21)
        set(v) = prefs.edit().putInt("s_days", v).apply()

    /** Wunschhöhe für automatisch geladene Folgen (z. B. 720) */
    var seriesHeight: Int
        get() = prefs.getInt("s_h", 720)
        set(v) = prefs.edit().putInt("s_h", v).apply()

    /** Mindestlänge in Minuten, ab der ein Video als ganze Folge gilt (kürzere sind Vorschauen/Ausschnitte) */
    var seriesMinMinutes: Int
        get() = prefs.getInt("s_min", 60)
        set(v) = prefs.edit().putInt("s_min", v).apply()

    var seriesAutoDownload: Boolean
        get() = prefs.getBoolean("s_auto", true)
        set(v) = prefs.edit().putBoolean("s_auto", v).apply()

    // Abspielposition und "gesehen" je Download
    fun position(jobId: Long): Long = prefs.getLong("pos_$jobId", 0L)
    fun setPosition(jobId: Long, ms: Long) = prefs.edit().putLong("pos_$jobId", ms).apply()
    fun duration(jobId: Long): Long = prefs.getLong("dur_$jobId", 0L)
    fun setDuration(jobId: Long, ms: Long) = prefs.edit().putLong("dur_$jobId", ms).apply()
    fun watched(jobId: Long): Boolean = prefs.getBoolean("seen_$jobId", false)
    fun setWatched(jobId: Long, v: Boolean) = prefs.edit().putBoolean("seen_$jobId", v).apply()

    // ------------------------------------------------------------ Serien

    fun loadSeries(): List<Series> = runCatching {
        val arr = JSONArray(prefs.getString("series", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val k = o.optJSONArray("known") ?: JSONArray()
            Series(
                id = o.getString("id"),
                name = o.optString("name"),
                thumbnail = o.str("th"),
                channelName = o.optString("ch"),
                addedAt = o.optLong("added"),
                known = (0 until k.length()).map { k.getString(it) }.toSet(),
                lastCheck = o.optLong("last"),
                pref = o.optString("pref"),
                channelUrl = o.optString("chu")
            )
        }
    }.getOrDefault(emptyList())

    fun saveSeries(list: List<Series>) {
        val arr = JSONArray()
        list.forEach { s ->
            arr.put(
                JSONObject().apply {
                    put("id", s.id)
                    put("name", s.name)
                    put("th", s.thumbnail ?: JSONObject.NULL)
                    put("ch", s.channelName)
                    put("added", s.addedAt)
                    put("known", JSONArray(s.known.toList().takeLast(400)))
                    put("last", s.lastCheck)
                    put("pref", s.pref)
                    put("chu", s.channelUrl)
                }
            )
        }
        prefs.edit().putString("series", arr.toString()).apply()
    }

    // ------------------------------------------------------------ Letzte Suche

    var lastQuery: String
        get() = prefs.getString("q", "") ?: ""
        set(v) = prefs.edit().putString("q", v).apply()

    private fun JSONObject.str(key: String): String? = if (isNull(key)) null else optString(key)

    fun saveResults(items: List<VideoItem>) {
        val arr = JSONArray()
        items.take(40).forEach { v ->
            arr.put(
                JSONObject().apply {
                    put("url", v.url)
                    put("title", v.title)
                    put("up", v.uploader)
                    put("th", v.thumbnail ?: JSONObject.NULL)
                    put("dur", v.durationSec)
                    put("views", v.views)
                }
            )
        }
        prefs.edit().putString("results", arr.toString()).apply()
    }

    fun loadResults(): List<VideoItem> = runCatching {
        val arr = JSONArray(prefs.getString("results", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            VideoItem(
                url = o.getString("url"),
                title = o.optString("title"),
                uploader = o.optString("up"),
                thumbnail = o.str("th"),
                durationSec = o.optLong("dur"),
                views = o.optLong("views", -1L)
            )
        }
    }.getOrDefault(emptyList())

    fun recents(): List<String> = runCatching {
        val arr = JSONArray(prefs.getString("recents", "[]") ?: "[]")
        (0 until arr.length()).map { arr.getString(it) }
    }.getOrDefault(emptyList())

    fun addRecent(q: String) {
        val list = (listOf(q) + recents().filter { !it.equals(q, ignoreCase = true) }).take(12)
        prefs.edit().putString("recents", JSONArray(list).toString()).apply()
    }

    fun clearRecents() {
        prefs.edit().remove("recents").apply()
    }

    // ------------------------------------------------------------ Downloadliste

    fun saveJobs(jobs: List<DownloadJob>) {
        val arr = JSONArray()
        jobs.takeLast(100).forEach { j ->
            arr.put(
                JSONObject().apply {
                    put("id", j.id)
                    put("title", j.title)
                    put("thumb", j.thumbnail ?: JSONObject.NULL)
                    put("kind", j.option.kind.name)
                    put("label", j.option.label)
                    put("ext", j.option.ext)
                    put("webm", j.option.webm)
                    put("osize", j.option.sizeBytes)
                    put("tree", j.tree.toString())
                    put("vurl", j.videoUrl)
                    put("sid", j.seriesId)
                    put("chan", j.channel)
                    put("state", j.state.name)
                    put("progress", j.progress.toDouble())
                    put("msg", j.message ?: JSONObject.NULL)
                    put("uri", j.resultUri?.toString() ?: JSONObject.NULL)
                    put("mime", j.openMime ?: JSONObject.NULL)
                    put("fin", j.finishedAt)
                    put("fsize", j.fileSize)
                }
            )
        }
        prefs.edit().putString("jobs", arr.toString()).apply()
    }

    fun loadJobs(): List<DownloadJob> = runCatching {
        val arr = JSONArray(prefs.getString("jobs", "[]") ?: "[]")
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val saved = JobState.valueOf(o.getString("state"))
            val interrupted = saved == JobState.RUNNING || saved == JobState.MUXING
            DownloadJob(
                id = o.getLong("id"),
                title = o.optString("title"),
                thumbnail = o.str("thumb"),
                option = Option(
                    kind = Kind.valueOf(o.getString("kind")),
                    label = o.optString("label"),
                    ext = o.optString("ext"),
                    webm = o.optBoolean("webm"),
                    videoUrls = emptyList(),
                    audioUrls = emptyList(),
                    sizeBytes = o.optLong("osize")
                ),
                tree = Uri.parse(o.getString("tree")),
                videoUrl = o.optString("vurl"),
                seriesId = o.optString("sid"),
                channel = o.optString("chan"),
                state = if (interrupted) JobState.FAILED else saved,
                progress = o.optDouble("progress", 0.0).toFloat(),
                message = if (interrupted) "Unterbrochen – bitte erneut starten" else o.str("msg"),
                resultUri = o.str("uri")?.let(Uri::parse),
                openMime = o.str("mime"),
                finishedAt = o.optLong("fin"),
                fileSize = o.optLong("fsize")
            )
        }
    }.getOrDefault(emptyList())
}
