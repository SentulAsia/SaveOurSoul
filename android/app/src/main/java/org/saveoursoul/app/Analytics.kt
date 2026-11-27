package org.saveoursoul.app

import android.content.Context
import android.os.Bundle
import android.util.Log
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.analytics.FirebaseAnalytics.ConsentStatus
import com.google.firebase.analytics.FirebaseAnalytics.ConsentType

/**
 * Anonymous, opt-in usage analytics (Firebase, one project per environment). Rules, which the
 * iOS app follows too (docs/ANALYTICS.md):
 *
 *  - Off until the user explicitly agrees; collection is disabled in the manifest by default.
 *  - Never on the SOS path: every call is fire-and-forget, swallows errors, and is a no-op when
 *    Firebase is not configured (no google-services.json) or the user has not consented.
 *  - Never personal data: no location, device/alert/frame ids, names, phone numbers or contacts.
 *    Parameters are categories and coarse time buckets only.
 *  - No advertising: ad id collection, ad storage and ad personalisation are always denied.
 */
class Analytics(private val context: Context, private val settings: Settings) {

    private val firebase: FirebaseAnalytics? by lazy {
        runCatching {
            if (FirebaseApp.getApps(context).isEmpty()) null else FirebaseAnalytics.getInstance(context)
        }.getOrNull()
    }

    /** False when this build has no Firebase config: then the consent prompt is not shown either. */
    val available: Boolean get() = firebase != null

    /** null = not asked yet. */
    var consent: Boolean?
        get() = settings.analyticsConsent
        set(value) {
            settings.analyticsConsent = value
            applyConsent()
        }

    fun applyConsent() {
        val fa = firebase ?: return
        val granted = settings.analyticsConsent == true
        runCatching {
            fa.setConsent(
                mapOf(
                    ConsentType.ANALYTICS_STORAGE to if (granted) ConsentStatus.GRANTED else ConsentStatus.DENIED,
                    ConsentType.AD_STORAGE to ConsentStatus.DENIED,
                    ConsentType.AD_USER_DATA to ConsentStatus.DENIED,
                    ConsentType.AD_PERSONALIZATION to ConsentStatus.DENIED,
                ),
            )
            fa.setAnalyticsCollectionEnabled(granted)
            if (!granted) fa.resetAnalyticsData()
        }.onFailure { Log.w(TAG, "consent update failed: ${it.message}") }
    }

    /** Logs an event if allowed. Values must be String, Int/Long or Double, never personal data. */
    fun event(name: String, vararg params: Pair<String, Any>) {
        if (settings.analyticsConsent != true) return
        val fa = firebase ?: return
        runCatching {
            val bundle = Bundle()
            for ((k, v) in params) when (v) {
                is String -> bundle.putString(k, v)
                is Int -> bundle.putLong(k, v.toLong())
                is Long -> bundle.putLong(k, v)
                is Double -> bundle.putDouble(k, v)
                is Boolean -> bundle.putString(k, if (v) "yes" else "no")
                else -> bundle.putString(k, v.toString())
            }
            fa.logEvent(name, bundle)
        }.onFailure { Log.w(TAG, "event $name dropped: ${it.message}") }
    }

    companion object {
        private const val TAG = "SosAnalytics"

        /** Coarse duration buckets: precise timings could help re-identify a person. */
        fun timeBucket(seconds: Long): String = when {
            seconds < 10 -> "<10s"
            seconds < 30 -> "10-30s"
            seconds < 120 -> "30s-2m"
            seconds < 600 -> "2-10m"
            seconds < 3600 -> "10-60m"
            else -> ">1h"
        }
    }
}
