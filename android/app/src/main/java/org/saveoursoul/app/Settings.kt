package org.saveoursoul.app

import android.content.Context
import androidx.core.content.edit
import org.saveoursoul.core.SosKind
import java.util.UUID

/** User preferences plus the currently active alert (so an SOS survives the process being killed). */
class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("sos_settings", Context.MODE_PRIVATE)

    var backendUrl: String
        get() = prefs.getString("backend_url", BuildConfig.DEFAULT_BACKEND_URL)!!
        set(v) = prefs.edit { putString("backend_url", v.trim().trimEnd('/')) }

    /** Name used in texts to emergency contacts. */
    var userName: String
        get() = prefs.getString("user_name", "")!!
        set(v) = prefs.edit { putString("user_name", v.trim()) }

    /** Phone number of the SMS gateway (e.g. a Twilio number) whose webhook posts to /api/v1/sms/inbound. */
    var smsGatewayNumber: String
        get() = prefs.getString("sms_gateway", "")!!
        set(v) = prefs.edit { putString("sms_gateway", v.trim()) }

    /** Personal contacts that get a human-readable SMS with a map link. */
    var emergencyContacts: List<String>
        get() = prefs.getString("contacts", "")!!.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        set(v) = prefs.edit { putString("contacts", v.joinToString(",")) }

    /** Seconds to wait for an internet confirmation before also using the SMS gateway. 0 = send SMS immediately. */
    var smsFallbackDelaySec: Int
        get() = prefs.getInt("sms_delay", 15)
        set(v) = prefs.edit { putInt("sms_delay", v.coerceAtLeast(0)) }

    /** Keep listening on Bluetooth/Wi-Fi to relay other people's SOS signals. */
    var relayEnabled: Boolean
        get() = prefs.getBoolean("relay", true)
        set(v) = prefs.edit { putBoolean("relay", v) }

    /** Whether this phone may spend its own SMS to forward strangers' frames when it has no data. */
    var relayViaSms: Boolean
        get() = prefs.getBoolean("relay_sms", false)
        set(v) = prefs.edit { putBoolean("relay_sms", v) }

    var activeAlertId: UUID?
        get() = prefs.getString("active_alert", null)?.let(UUID::fromString)
        set(v) = prefs.edit { putString("active_alert", v?.toString()) }

    var activeKind: SosKind
        get() = SosKind.entries.getOrElse(prefs.getInt("active_kind", 0)) { SosKind.GENERAL }
        set(v) = prefs.edit { putInt("active_kind", v.ordinal) }

    var activeSince: Long
        get() = prefs.getLong("active_since", 0)
        set(v) = prefs.edit { putLong("active_since", v) }
}
