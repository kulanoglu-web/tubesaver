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
import de.cleartube.app.data.VideoDetails
import de.cleartube.app.data.VideoItem
import de.cleartube.app.data.YouTubeRepo
import de.cleartube.app.download.DownloadHub
import de.cleartube.app.download.DownloadService
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("cleartube", 0)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<VideoItem>>(emptyList())
    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var details by mutableStateOf<VideoDetails?>(null)
    var detailsLoading by mutableStateOf(false)
    var treeUri by mutableStateOf(prefs.getString("tree", null)?.let(Uri::parse))
        private set

    private var session: SearchSession? = null
    private var loadingMore = false
    private var detailsJob: Job? = null

    fun submit() {
        val q = query.trim()
        if (q.isEmpty()) return
        if (YouTubeRepo.isYoutubeUrl(q)) {
            openUrl(q)
            return
        }
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val (items, s) = YouTubeRepo.search(q)
                results = items
                session = s
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
            } catch (_: Throwable) {
            } finally {
                loadingMore = false
            }
        }
    }

    fun open(item: VideoItem) = openUrl(item.url)

    fun openUrl(url: String) {
        detailsJob?.cancel()
        detailsLoading = true
        error = null
        detailsJob = viewModelScope.launch {
            try {
                details = YouTubeRepo.details(url)
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                error = "Video konnte nicht geladen werden: ${e.message}"
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
        prefs.edit().putString("tree", uri.toString()).apply()
    }

    fun download(option: Option) {
        val d = details ?: return
        val tree = treeUri ?: return
        DownloadHub.enqueue(d.title, d.thumbnail, option, tree)
        val ctx = getApplication<Application>()
        ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
        closeDetails()
    }
}
