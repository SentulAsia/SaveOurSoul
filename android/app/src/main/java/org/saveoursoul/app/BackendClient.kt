package org.saveoursoul.app

import android.net.Network
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.saveoursoul.core.FrameCodec
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

class BackendException(message: String) : IOException(message)

/** Minimal HTTPS client. Every call can be pinned to a specific [Network] (Wi-Fi or cellular). */
class BackendClient(private val baseUrl: () -> String) {

    data class Enrollment(val deviceId: UUID, val secret: ByteArray)

    /** Per-frame result from POST /api/v1/signals. */
    data class FrameResult(val frameId: UUID?, val status: String, val reason: String?, val alertStatus: String?) {
        /** The backend has the frame (or will never accept it) — stop retrying. */
        val terminal get() = status == "accepted" || status == "duplicate" || status == "rejected"
    }

    data class AlertStatus(val status: String, val channels: List<String>)

    suspend fun enroll(name: String, phone: String?, enrollToken: String?): Enrollment {
        val body = JSONObject().put("name", name).apply { if (!phone.isNullOrBlank()) put("phone", phone) }
        val res = request("POST", "/api/v1/devices", body, null, enrollToken)
        return Enrollment(UUID.fromString(res.getString("deviceId")), FrameCodec.fromBase64Url(res.getString("secret")))
    }

    suspend fun postSignals(frames: List<ByteArray>, channel: String, relayDeviceId: String?, network: Network?): List<FrameResult> {
        val body = JSONObject()
            .put("frames", JSONArray(frames.map(FrameCodec::toBase64Url)))
            .put("channel", channel)
        if (relayDeviceId != null) body.put("relayDeviceId", relayDeviceId)
        val results = request("POST", "/api/v1/signals", body, network).getJSONArray("results")
        return (0 until results.length()).map { i ->
            val r = results.getJSONObject(i)
            FrameResult(
                frameId = r.optString("frameId").takeIf { it.isNotEmpty() }?.let(UUID::fromString),
                status = r.getString("status"),
                reason = r.optString("reason").takeIf { it.isNotEmpty() },
                alertStatus = r.optString("alertStatus").takeIf { it.isNotEmpty() },
            )
        }
    }

    suspend fun alertStatus(alertId: UUID, network: Network? = null): AlertStatus? = try {
        val r = request("GET", "/api/v1/alerts/$alertId/status", null, network)
        val ch = r.optJSONArray("channels") ?: JSONArray()
        AlertStatus(r.getString("status"), (0 until ch.length()).map { ch.getString(it) })
    } catch (e: BackendException) {
        null
    }

    private suspend fun request(method: String, path: String, body: JSONObject?, network: Network?, bearer: String? = null): JSONObject =
        withContext(Dispatchers.IO) {
            val url = URL(baseUrl() + path)
            val conn = (network?.openConnection(url) ?: url.openConnection()) as HttpURLConnection
            try {
                conn.requestMethod = method
                conn.connectTimeout = 8_000
                conn.readTimeout = 10_000
                conn.setRequestProperty("Accept", "application/json")
                if (bearer != null) conn.setRequestProperty("Authorization", "Bearer $bearer")
                if (body != null) {
                    conn.doOutput = true
                    conn.setRequestProperty("Content-Type", "application/json")
                    conn.outputStream.use { it.write(body.toString().toByteArray()) }
                }
                val code = conn.responseCode
                val text = (if (code in 200..299) conn.inputStream else conn.errorStream)?.bufferedReader()?.use { it.readText() }.orEmpty()
                if (code !in 200..299) throw BackendException("HTTP $code: ${text.take(200)}")
                JSONObject(text)
            } finally {
                conn.disconnect()
            }
        }
}
