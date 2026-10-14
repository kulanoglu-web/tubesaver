package de.cleartube.app.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Serien = Playlists. Als offiziell gelten nur Kanäle mit dem Verifizierungshaken von YouTube;
 * Serien anderer Kanäle lassen sich nicht abonnieren (Schutz vor Fälschungen und Fake-Uploads).
 */
object SeriesRepo {
    private val yt get() = ServiceList.YouTube

    /** Türkische Sender zum schnellen Stöbern; gesucht wird nach dem Namen, angezeigt werden nur verifizierte Kanäle. */
    val BROADCASTERS = listOf("Show TV", "Star TV", "ATV", "Kanal D", "NOW", "TRT 1", "TV8", "Kanal 7")

    suspend fun searchChannels(query: String): List<ChannelHit> = withContext(Dispatchers.IO) {
        val h = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.CHANNELS), "")
        SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<ChannelInfoItem>()
            .map { ChannelHit(it.url, it.name ?: "", it.thumbnails.firstOrNull()?.url, it.isVerified) }
    }

    suspend fun searchPlaylists(query: String): List<SeriesHit> = withContext(Dispatchers.IO) {
        val h = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.PLAYLISTS), "")
        SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<PlaylistInfoItem>().map { it.toHit(it.isUploaderVerified) }
    }

    /** Playlists (Serien) eines Kanals; Verifizierung kommt vom Kanal selbst. */
    suspend fun channelSeries(channelUrl: String): List<SeriesHit> = withContext(Dispatchers.IO) {
        val ch = ChannelInfo.getInfo(yt, channelUrl)
        val tab = ch.tabs.firstOrNull { ChannelTabs.PLAYLISTS in it.contentFilters } ?: return@withContext emptyList()
        val info = ChannelTabInfo.getInfo(yt, tab)
        info.relatedItems.filterIsInstance<PlaylistInfoItem>()
            .map { it.toHit(ch.isVerified, ch.name) }
            .filter { it.episodes != 0L }
    }

    private fun PlaylistInfoItem.toHit(verified: Boolean, channel: String? = null) = SeriesHit(
        url = url,
        name = name ?: "",
        thumbnail = thumbnails.firstOrNull()?.url,
        channelName = channel ?: uploaderName ?: "",
        verified = verified,
        episodes = streamCount
    )

    class EpisodePage(val items: List<Episode>, val next: Page?)

    /** Eine Serie ist entweder eine Playlist (mit "list=") oder ein ganzer Kanal (Reiter "Videos"). */
    fun isChannelUrl(url: String) = !url.contains("list=")

    private val tabHandlers = java.util.concurrent.ConcurrentHashMap<String, org.schabi.newpipe.extractor.linkhandler.ListLinkHandler>()

    suspend fun episodes(url: String): EpisodePage = withContext(Dispatchers.IO) {
        if (isChannelUrl(url)) {
            val ch = ChannelInfo.getInfo(yt, url)
            val tab = ch.tabs.firstOrNull { ChannelTabs.VIDEOS in it.contentFilters }
                ?: return@withContext EpisodePage(emptyList(), null)
            tabHandlers[url] = tab
            val info = ChannelTabInfo.getInfo(yt, tab)
            EpisodePage(info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toEpisode() }, info.nextPage)
        } else {
            val info = PlaylistInfo.getInfo(yt, url)
            EpisodePage(info.relatedItems.filterIsInstance<StreamInfoItem>().map { it.toEpisode() }, info.nextPage)
        }
    }

    suspend fun moreEpisodes(url: String, page: Page): EpisodePage = withContext(Dispatchers.IO) {
        if (isChannelUrl(url)) {
            val h = tabHandlers[url] ?: error("Kanal nicht geladen")
            val res = ChannelTabInfo.getMoreItems(yt, h, page)
            EpisodePage(res.items.filterIsInstance<StreamInfoItem>().map { it.toEpisode() }, res.nextPage)
        } else {
            val res = PlaylistInfo.getMoreItems(yt, url, page)
            EpisodePage(res.items.filterIsInstance<StreamInfoItem>().map { it.toEpisode() }, res.nextPage)
        }
    }

    /** Videosuche: findet ganze Folgen direkt und über sie die Kanäle der Serien. */
    class VideoHit(val ep: Episode, val channelName: String, val channelUrl: String?, val verified: Boolean)

    suspend fun searchVideos(query: String): List<VideoHit> = withContext(Dispatchers.IO) {
        val h = yt.searchQHFactory.fromQuery(query, listOf(YoutubeSearchQueryHandlerFactory.VIDEOS), "")
        SearchInfo.getInfo(yt, h).relatedItems.filterIsInstance<StreamInfoItem>()
            .map { VideoHit(it.toEpisode(), it.uploaderName ?: "", it.uploaderUrl, it.isUploaderVerified) }
    }

    private fun StreamInfoItem.toEpisode() = Episode(
        url = url,
        title = name ?: "",
        thumbnail = thumbnails.firstOrNull()?.url,
        durationSec = duration,
        uploadedText = textualUploadDate
    )

    /** Wählt aus den Varianten die beste bis zur Wunschhöhe, möglichst fertig mit Ton. */
    fun pickOption(options: List<Option>, maxHeight: Int): Option? {
        fun height(o: Option) = Regex("^(\\d+)p").find(o.label)?.groupValues?.get(1)?.toIntOrNull() ?: 0
        val videos = options.filter { it.kind == Kind.VIDEO }
        return videos.filter { height(it) in 1..maxHeight }.maxByOrNull { height(it) }
            ?: videos.minByOrNull { height(it) }
    }
}
