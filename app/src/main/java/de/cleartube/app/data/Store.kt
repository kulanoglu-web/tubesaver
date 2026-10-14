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
            val interrupted = saved == JobState.QUEUED || saved == JobState.RUNNING || saved == JobState.MUXING
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
