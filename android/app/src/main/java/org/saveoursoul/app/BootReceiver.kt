package org.saveoursoul.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Restores relay mode (and any SOS that was still active) after a reboot or app update. */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // SosApp.onCreate already starts the service when needed; touching the engine is enough.
        SosApp.engine(context)
    }
}
