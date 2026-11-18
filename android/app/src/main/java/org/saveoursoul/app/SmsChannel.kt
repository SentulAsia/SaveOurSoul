package org.saveoursoul.app

import android.Manifest
import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.telephony.SmsManager
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import org.saveoursoul.core.FrameCodec
import org.saveoursoul.core.PanicFrame
import org.saveoursoul.core.SosKind
import java.util.UUID
import kotlin.coroutines.resume

/**
 * SMS works with zero mobile data and on 2G. Two kinds of message:
 *  - to the gateway number: the signed machine frame ("SOS1:<base64url>"), forwarded by the
 *    gateway's webhook to the backend;
 *  - to personal emergency contacts: plain text with a map link they can act on themselves.
 */
class SmsChannel(private val context: Context) {

    fun canSend(): Boolean =
        context.packageManager.hasSystemFeature(PackageManager.FEATURE_TELEPHONY) &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.SEND_SMS) == PackageManager.PERMISSION_GRANTED

    suspend fun sendFrame(gatewayNumber: String, frame: ByteArray): Boolean = send(gatewayNumber, FrameCodec.toSms(frame))

    suspend fun notifyContacts(contacts: List<String>, frame: PanicFrame, name: String): Int {
        val text = humanText(frame, name)
        return contacts.count { send(it, text) }
    }

    private fun humanText(f: PanicFrame, name: String): String {
        if (f.isCancel) return "SaveOurSoul: $name is safe now. Earlier SOS cancelled."
        val kind = SosKind.entries.getOrElse(f.kind) { SosKind.GENERAL }.name.lowercase()
        val where = if (f.hasLocation) {
            val lat = f.latE7 / 1e7
            val lon = f.lonE7 / 1e7
            " Location: https://maps.google.com/?q=%.6f,%.6f".format(java.util.Locale.ROOT, lat, lon)
        } else " Location unavailable."
        return "SOS from $name ($kind emergency).$where Sent by SaveOurSoul."
    }

    @Suppress("DEPRECATION")
    private fun smsManager(): SmsManager =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) context.getSystemService(SmsManager::class.java) else SmsManager.getDefault()

    /** Sends one SMS (multipart if needed) and waits for the radio's "sent" result. */
    private suspend fun send(number: String, text: String): Boolean {
        if (number.isBlank() || !canSend()) return false
        val action = "org.saveoursoul.SMS_SENT." + UUID.randomUUID()
        return withTimeoutOrNull(60_000) {
            suspendCancellableCoroutine { cont ->
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(ctx: Context, intent: Intent) {
                        context.unregisterReceiver(this)
                        if (cont.isActive) cont.resume(resultCode == Activity.RESULT_OK)
                    }
                }
                ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
                cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
                val sent = PendingIntent.getBroadcast(
                    context, 0, Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT,
                )
                try {
                    val sms = smsManager()
                    val parts = sms.divideMessage(text)
                    if (parts.size == 1) {
                        sms.sendTextMessage(number, null, text, sent, null)
                    } else {
                        // Only the last part reports back; good enough for a delivery hint.
                        sms.sendMultipartTextMessage(number, null, parts, ArrayList(List(parts.size) { i -> if (i == parts.lastIndex) sent else null }), null)
                    }
                } catch (e: Exception) {
                    Log.w("SosSms", "send to $number failed: ${e.message}")
                    runCatching { context.unregisterReceiver(receiver) }
                    if (cont.isActive) cont.resume(false)
                }
            }
        } ?: false
    }
}
