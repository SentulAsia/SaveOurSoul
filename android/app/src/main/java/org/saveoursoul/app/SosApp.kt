package org.saveoursoul.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import org.saveoursoul.core.Outbox
import org.saveoursoul.core.SeenCache
import java.io.File
import java.util.concurrent.TimeUnit

class SosApp : Application() {
    lateinit var engine: SosEngine
        private set

    override fun onCreate() {
        super.onCreate()
        val settings = Settings(this)
        val backend = BackendClient { settings.backendUrl }
        val analytics = Analytics(this, settings).also { it.applyConsent() }
        engine = SosEngine(
            context = this,
            settings = settings,
            credentials = DeviceCredentials(this),
            backend = backend,
            internet = InternetChannel(this, backend),
            sms = SmsChannel(this),
            location = LocationProvider(this),
            outbox = Outbox(File(noBackupFilesDir, "outbox.txt")),
            seen = SeenCache(),
            analytics = analytics,
        )
        createNotificationChannels()

        // Backstop for store-and-forward: even if the service is killed, queued frames
        // (ours or relayed for strangers) are uploaded once the phone is online again.
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "sos-outbox-flush",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<FlushWorker>(15, TimeUnit.MINUTES)
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .build(),
        )

        if (settings.activeAlertId != null || settings.relayEnabled) SosService.start(this)
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ACTIVE, "Active SOS", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Shown while an SOS is being sent"
            },
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_RELAY, "Relay mode", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Shown while this phone listens to relay SOS signals for people nearby"
            },
        )
    }

    companion object {
        const val CHANNEL_ACTIVE = "sos_active"
        const val CHANNEL_RELAY = "sos_relay"

        fun engine(context: Context): SosEngine = (context.applicationContext as SosApp).engine
    }
}

class FlushWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        SosApp.engine(applicationContext).flush()
        return Result.success()
    }
}
