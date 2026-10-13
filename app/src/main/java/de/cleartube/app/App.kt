package de.cleartube.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import de.cleartube.app.net.Http
import de.cleartube.app.net.NpDownloader
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        NewPipe.init(
            NpDownloader(Http.client),
            Localization("de", "DE"),
            ContentCountry("DE")
        )
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Downloads", NotificationManager.IMPORTANCE_LOW)
        )
    }

    companion object {
        const val CHANNEL_ID = "downloads"
    }
}
