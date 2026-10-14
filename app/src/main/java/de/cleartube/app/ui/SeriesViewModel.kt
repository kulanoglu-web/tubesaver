package de.cleartube.app.ui

import android.app.Application
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import de.cleartube.app.data.ChannelHit
import de.cleartube.app.data.Episode
import de.cleartube.app.data.Series
import de.cleartube.app.data.SeriesHit
import de.cleartube.app.data.SeriesRepo
import de.cleartube.app.data.Store
import de.cleartube.app.data.YouTubeRepo
import de.cleartube.app.download.DownloadHub
import de.cleartube.app.download.DownloadService
import de.cleartube.app.download.SeriesSync
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page

/** Bildschirme im Serien-Reiter */
enum class SView { HOME, DISCOVER, CHANNEL, DETAIL }

class SeriesViewModel(app: Application) : AndroidViewModel(app) {
    var mine by mutableStateOf(Store.loadSeries())
    var view by mutableStateOf(SView.HOME)
    var detailFrom by mutableStateOf(SView.HOME)

    var query by mutableStateOf("")
    var channels by mutableStateOf<List<ChannelHit>>(emptyList())
    var videos by mutableStateOf<List<SeriesRepo.VideoHit>>(emptyList())
    var pendingUnverified by mutableStateOf<SeriesHit?>(null)
    var channelSelf by mutableStateOf<SeriesHit?>(null)
    var playlists by mutableStateOf<List<SeriesHit>>(emptyList())
    var searched by mutableStateOf(false)

    var channelName by mutableStateOf("")
    var channelSeries by mutableStateOf<List<SeriesHit>>(emptyList())

    var detail by mutableStateOf<SeriesHit?>(null)
    var episodes by mutableStateOf<List<Episode>>(emptyList())
    /** false: nur ganze Folgen (Standard), true: alles inkl. Vorschauen und Ausschnitte */
    var showAll by mutableStateOf(false)
    val visible get() = if (showAll) episodes else episodes.filter { it.isFull() }
    val hiddenCount get() = episodes.size - episodes.count { it.isFull() }
    private var nextPage: Page? = null
    val hasMore get() = nextPage != null

    var loading by mutableStateOf(false)
    var error by mutableStateOf<String?>(null)
    var message by mutableStateOf<String?>(null)
    var checking by mutableStateOf(false)
    val busy = mutableStateListOf<String>()

    fun isSubscribed(id: String) = mine.any { it.id == id }

    private fun fail(e: Throwable) {
        if (e is CancellationException) throw e
        error = e.message ?: e.javaClass.simpleName
    }

    // ------------------------------------------------------------ Entdecken

    fun search(q: String, asBroadcaster: Boolean = false) {
        val text = q.trim()
        if (text.isEmpty()) return
        query = text
        view = SView.DISCOVER
        loading = true
        error = null
        viewModelScope.launch {
            try {
                val ch = async { runCatching { SeriesRepo.searchChannels(text) }.getOrDefault(emptyList()) }
                val pl = async {
                    SeriesRepo.searchPlaylists(if (asBroadcaster) "$text dizi" else text)
                }
                val vd = async {
                    SeriesRepo.searchVideos(if (asBroadcaster) "$text dizi son bölüm" else text)
                }
                val vids = runCatching { vd.await() }.getOrDefault(emptyList())
                // Nur ganze Folgen; Kanäle der Treffer kommen zur Kanalsuche dazu (Serien mit eigenem Kanal)
                videos = vids.filter { it.ep.isFull() }.take(15)
                val fromVideos = vids.filter { it.channelUrl != null && it.channelName.isNotEmpty() }
                    .map { ChannelHit(it.channelUrl!!, it.channelName, null, it.verified) }
                channels = (ch.await() + fromVideos)
                    .distinctBy { it.url }
                    .sortedByDescending { it.verified }
                    .take(8)
                playlists = runCatching { pl.await() }.getOrDefault(emptyList())
                searched = true
            } catch (e: Throwable) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun openChannel(c: ChannelHit) {
        channelName = c.name
        channelSelf = SeriesHit(c.url, c.name, c.thumbnail, c.name, c.verified, -1)
        channelSeries = emptyList()
        view = SView.CHANNEL
        loading = true
        error = null
        viewModelScope.launch {
            try {
                channelSeries = runCatching { SeriesRepo.channelSeries(c.url) }.getOrDefault(emptyList())
            } catch (e: Throwable) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun openSeries(hit: SeriesHit, from: SView) {
        detail = hit
        detailFrom = from
        episodes = emptyList()
        nextPage = null
        showAll = false
        view = SView.DETAIL
        loading = true
        error = null
        viewModelScope.launch {
            try {
                var p = SeriesRepo.episodes(hit.url)
                episodes = p.items
                nextPage = p.next
                // Es sollen genug ganze Folgen sichtbar sein: bis zu 4 weitere Seiten nachladen
                var guard = 0
                while (nextPage != null && episodes.count { it.isFull() } < 8 && guard++ < 4) {
                    p = SeriesRepo.moreEpisodes(hit.url, nextPage!!)
                    episodes = episodes + p.items
                    nextPage = p.next
                }
            } catch (e: Throwable) {
                fail(e)
            } finally {
                loading = false
            }
        }
    }

    fun openMine(s: Series) =
        openSeries(SeriesHit(s.id, s.name, s.thumbnail, s.channelName, verified = true, episodes = -1), SView.HOME)

    fun loadMore() {
        val d = detail ?: return
        val page = nextPage ?: return
        nextPage = null
        viewModelScope.launch {
            try {
                val p = SeriesRepo.moreEpisodes(d.url, page)
                episodes = episodes + p.items
                nextPage = p.next
            } catch (e: Throwable) {
                nextPage = page
            }
        }
    }

    /** Zurück-Taste: gibt true zurück, wenn dabei etwas geschlossen wurde. */
    fun back(): Boolean = when (view) {
        SView.HOME -> false
        SView.DETAIL -> { view = detailFrom; error = null; true }
        SView.CHANNEL -> { view = SView.DISCOVER; error = null; true }
        SView.DISCOVER -> { view = SView.HOME; error = null; true }
    }

    // ------------------------------------------------------------ Abonnieren

    /** Nimmt die Serie auf; alle jetzt vorhandenen Folgen gelten als bekannt, nur neue werden automatisch geladen. */
    fun subscribe(hit: SeriesHit, confirmed: Boolean = false) {
        // Nicht verifizierte Kanäle nur nach ausdrücklicher Bestätigung (Schutz vor Fälschungen)
        if (!hit.verified && !confirmed) {
            pendingUnverified = hit
            return
        }
        if (isSubscribed(hit.url)) return
        viewModelScope.launch {
            try {
                val known = LinkedHashSet<String>()
                var page = SeriesRepo.episodes(hit.url)
                known += page.items.map { it.url }
                var guard = 0
                while (page.next != null && guard++ < 3) {
                    page = SeriesRepo.moreEpisodes(hit.url, page.next!!)
                    known += page.items.map { it.url }
                }
                val s = Series(hit.url, hit.name, hit.thumbnail, hit.channelName, System.currentTimeMillis(), known)
                mine = mine + s
                Store.saveSeries(mine)
                message = "„${hit.name}“ hinzugefügt – neue Folgen werden automatisch geladen"
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = "Hinzufügen fehlgeschlagen: ${e.message}"
            }
        }
    }

    fun unsubscribe(id: String) {
        mine = mine.filterNot { it.id == id }
        Store.saveSeries(mine)
    }

    fun refreshMine() {
        mine = Store.loadSeries()
    }

    // ------------------------------------------------------------ Laden

    fun downloadEpisode(ep: Episode, seriesId: String) {
        val tree = Store.tree?.let(Uri::parse)
        if (tree == null) {
            message = "Bitte zuerst unter Einstellungen einen Speicherort wählen"
            return
        }
        if (ep.url in busy) return
        busy += ep.url
        viewModelScope.launch {
            try {
                val d = YouTubeRepo.details(ep.url)
                val opt = SeriesRepo.pickOption(d.options, Store.seriesHeight) ?: error("Keine Variante gefunden")
                DownloadHub.enqueue(d.title, d.thumbnail ?: ep.thumbnail, opt, tree, ep.url, seriesId)
                val ctx = getApplication<Application>()
                ContextCompat.startForegroundService(ctx, Intent(ctx, DownloadService::class.java))
                message = "Wird geladen: ${ep.title}"
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = "Fehler: ${e.message}"
            } finally {
                busy -= ep.url
            }
        }
    }

    /** Sofort nach neuen Folgen suchen und alte Folgen aufräumen. */
    fun checkNow() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            try {
                val ctx = getApplication<Application>()
                val removed = withContext(Dispatchers.IO) { SeriesSync.autoDelete(ctx) }
                val added = SeriesSync.check(ctx)
                refreshMine()
                message = when {
                    added > 0 -> "$added neue Folge(n) werden geladen"
                    removed > 0 -> "Keine neuen Folgen, $removed alte Folge(n) gelöscht"
                    else -> "Keine neuen Folgen"
                }
            } catch (e: Throwable) {
                if (e is CancellationException) throw e
                message = "Prüfung fehlgeschlagen: ${e.message}"
            } finally {
                checking = false
            }
        }
    }
}
