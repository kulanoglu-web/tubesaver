package de.cleartube.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import de.cleartube.app.data.Store
import de.cleartube.app.download.SeriesWorker
import java.util.concurrent.TimeUnit
import de.cleartube.app.download.DownloadHub
import de.cleartube.app.net.Http
import de.cleartube.app.net.NpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Store.init(this)
        DownloadHub.restore()
        NewPipe.init(
            NpDownloader(Http.client),
            Localization("de", "DE"),
            ContentCountry("TR")
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
        nm.createNotificationChannel(
            NotificationChannel(SERIES_CHANNEL_ID, "Neue Folgen", NotificationManager.IMPORTANCE_DEFAULT)
        )

        // Alle 6 Stunden nach neuen Folgen suchen und alte Folgen aufräumen
        val work = PeriodicWorkRequestBuilder<SeriesWorker>(6, TimeUnit.HOURS)
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .build()
        WorkManager.getInstance(this)
            .enqueueUniquePeriodicWork("series-check", ExistingPeriodicWorkPolicy.KEEP, work)
    }

    companion object {
        const val CHANNEL_ID = "downloads"
        const val SERIES_CHANNEL_ID = "series"
    }
}
