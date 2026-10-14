package de.cleartube.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cleartube.app.data.Option
import de.cleartube.app.data.SearchSession
import de.cleartube.app.data.Store
import de.cleartube.app.data.VideoDetails
import de.cleartube.app.data.VideoItem
import de.cleartube.app.data.YouTubeRepo
import de.cleartube.app.download.DownloadHub
import de.cleartube.app.download.DownloadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {

    // Letzte Suche und Ergebnisse werden beim Start wiederhergestellt
    var query by mutableStateOf(Store.lastQuery)
    var results by mutableStateOf(Store.loadResults())
    var recents by mutableStateOf(Store.recents())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var details by mutableStateOf<VideoDetails?>(null)
    var detailsLoading by mutableStateOf(false)

    /** Gesetzt, wenn das Fenster aus einer Serie geöffnet wurde (dann kann die Serie gemerkt werden) */
    var seriesContext by mutableStateOf<de.cleartube.app.data.SeriesHit?>(null)

    fun openFromSeries(url: String, hit: de.cleartube.app.data.SeriesHit?) {
        openUrl(url, false)
        seriesContext = hit
    }

    /** true: das Download-Fenster öffnet direkt auf "Audio" */
    var startAudio by mutableStateOf(false)

    var treeUri by mutableStateOf(Store.tree?.let(Uri::parse))
        private set

    var cleanupEnabled by mutableStateOf(Store.cleanupEnabled)
        private set
    var cleanupDays by mutableStateOf(Store.cleanupDays)
        private set
    var cleanupMb by mutableStateOf(Store.cleanupMb)
        private set

    init {
        if (treeUri == null && isTelevision(app)) setTree(appFolder(app))
    }

    private var session: SearchSession? = null
    private var loadingMore = false
    private var detailsJob: Job? = null

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (YouTubeRepo.isYoutubeUrl(q)) {
            openUrl(q, audio = false)
            return
        }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val (items, s) = YouTubeRepo.search(q)
                results = items
                session = s
                Store.lastQuery = q
                Store.saveResults(items)
                Store.addRecent(q)
                recents = Store.recents()
            } catch (e: Throwable) {
                error = "Suche fehlgeschlagen: ${e.message}"
            } finally {
                loading = false
            }
        }
    }

    fun loadMore() {
        val s = session ?: return
        if (loadingMore || s.next == null) return
        loadingMore = true
        viewModelScope.launch {
            try {
                results = results + YouTubeRepo.more(s)
                Store.saveResults(results)
            } catch (_: Throwable) {
            } finally {
                loadingMore = false
            }
        }
    }

    fun searchFor(q: String) {
        query = q
        submit()
    }

    fun open(item: VideoItem, audio: Boolean = false) = openUrl(item.url, audio)

    /** Zurück-Taste in der Ergebnisliste: zurück zur leeren Suche (gespeicherte Suche bleibt erhalten). */
    fun clearResults() {
        results = emptyList()
        error = null
        session = null
    }

    fun clearRecents() {
        Store.clearRecents()
        recents = emptyList()
    }

    fun openUrl(url: String, audio: Boolean = false) {
        detailsJob?.cancel()
        seriesContext = null
        startAudio = audio
        detailsLoading = true
        error = null
        detailsJob = viewModelScope.launch {
            try {
                details = YouTubeRepo.details(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Video konnte nicht geladen werden (${e.javaClass.simpleName}): ${e.message}"
            } finally {
                detailsLoading = false
            }
        }
    }

    fun handleShared(text: String) {
        val url = YouTubeRepo.extractUrl(text) ?: return
        query = url
        openUrl(url)
    }

    fun closeDetails() {
        detailsJob?.cancel()
        details = null
        detailsLoading = false
    }

    fun setTree(uri: Uri) {
        treeUri = uri
        Store.tree = uri.toString()
    }

    fun updateCleanupEnabled(v: Boolean) {
        cleanupEnabled = v
        Store.cleanupEnabled = v
    }

    fun updateCleanupDays(v: Int) {
        cleanupDays = v
        Store.cleanupDays = v
    }

    fun updateCleanupMb(v: Int) {
        cleanupMb = v
        Store.cleanupMb = v
    }

    fun download(option: Option, seriesId: String = "") {
        val d = details ?: return
        val tree = treeUri ?: return
        DownloadHub.enqueue(d.title, d.thumbnail, option, tree, d.url, seriesId)
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        closeDetails()
    }
}
