package org.saveoursoul.app

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.saveoursoul.app.ui.MainActivity
import org.saveoursoul.core.SosKind

/**
 * Foreground service that keeps the process (and the Bluetooth/Wi-Fi mesh radio) alive while
 * an SOS is active, or permanently in relay mode so this phone can carry other people's SOS.
 */
class SosService : LifecycleService() {
    private val engine by lazy { SosApp.engine(this) }

    private var observing = false

    override fun onCreate() {
        super.onCreate()
        goForeground(engine.state.value)
    }

    /** Started only after the first command is handled, so a trigger is never mistaken for "nothing to do". */
    private fun observeState() {
        if (observing) return
        observing = true
        lifecycleScope.launch {
            engine.state
                .map { Triple(it.isActive, it.responderStatus, it.mesh.detail) }
                .distinctUntilChanged()
                .collect {
                    val s = engine.state.value
                    goForeground(s)
                    if (engine.settings.activeAlertId == null && !engine.settings.relayEnabled) stopSelf()
                }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        when (intent?.action) {
            ACTION_TRIGGER -> engine.trigger(SosKind.entries[intent.getIntExtra(EXTRA_KIND, 0)], intent.getBooleanExtra(EXTRA_TEST, false))
            ACTION_CANCEL -> engine.cancel()
        }
        engine.updateMeshRunning()
        observeState()
        return START_STICKY
    }

    override fun onDestroy() {
        engine.updateMeshRunning()
        super.onDestroy()
    }

    private fun goForeground(s: SosEngine.State) {
        val hasLocation = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        var type = ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
        if (s.isActive && hasLocation) type = type or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(s), type)
        } catch (e: Exception) {
            // e.g. location FGS not allowed when restarted from the background: keep the mesh alive at least.
            Log.w("SosService", "foreground with location failed, retrying without: ${e.message}")
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(s), ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        }
    }

    private fun notification(s: SosEngine.State): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val b = NotificationCompat.Builder(this, if (s.isActive) SosApp.CHANNEL_ACTIVE else SosApp.CHANNEL_RELAY)
            .setSmallIcon(R.drawable.ic_sos)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
        return if (s.isActive) {
            val status = when (s.responderStatus) {
                "acknowledged" -> "Help acknowledged your SOS"
                "open" -> "SOS received by the server — waiting for a responder"
                else -> "Sending SOS over every available channel…"
            }
            b.setContentTitle("SOS ACTIVE").setContentText(status)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .build()
        } else {
            b.setContentTitle("SOS relay on").setContentText("Ready to relay SOS signals for people nearby · ${s.mesh.detail}")
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .build()
        }
    }

    companion object {
        private const val NOTIFICATION_ID = 911
        const val ACTION_TRIGGER = "org.saveoursoul.TRIGGER"
        const val ACTION_CANCEL = "org.saveoursoul.CANCEL"
        const val EXTRA_KIND = "kind"
        const val EXTRA_TEST = "test"

        fun start(context: Context) = startWith(context, Intent(context, SosService::class.java))

        fun trigger(context: Context, kind: SosKind, test: Boolean = false) = startWith(
            context,
            Intent(context, SosService::class.java).setAction(ACTION_TRIGGER).putExtra(EXTRA_KIND, kind.ordinal).putExtra(EXTRA_TEST, test),
        )

        fun cancel(context: Context) = startWith(context, Intent(context, SosService::class.java).setAction(ACTION_CANCEL))

        private fun startWith(context: Context, intent: Intent) {
            try {
                ContextCompat.startForegroundService(context, intent)
            } catch (e: Exception) {
                // Background-start restrictions: the SOS itself must still go out.
                Log.w("SosService", "could not start foreground service: ${e.message}")
                val engine = SosApp.engine(context)
                when (intent.action) {
                    ACTION_TRIGGER -> engine.trigger(SosKind.entries[intent.getIntExtra(EXTRA_KIND, 0)], intent.getBooleanExtra(EXTRA_TEST, false))
                    ACTION_CANCEL -> engine.cancel()
                }
            }
        }
    }
}
