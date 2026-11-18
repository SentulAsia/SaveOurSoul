package org.saveoursoul.app

import android.content.Context
import android.security.keystore.KeyProperties
import android.security.keystore.KeyProtection
import androidx.core.content.edit
import org.saveoursoul.core.FrameSigner
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Device identity issued by the backend at enrollment. The HMAC secret is imported into the
 * Android Keystore and the plaintext is discarded, so it cannot be read back out of the app.
 */
class DeviceCredentials(context: Context) {
    private val prefs = context.getSharedPreferences("sos_identity", Context.MODE_PRIVATE)
    private val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }

    val deviceId: UUID?
        get() = prefs.getString("device_id", null)?.takeIf { keyStore.containsAlias(ALIAS) }?.let(UUID::fromString)

    val isEnrolled: Boolean get() = deviceId != null

    fun store(deviceId: UUID, secret: ByteArray) {
        keyStore.setEntry(
            ALIAS,
            KeyStore.SecretKeyEntry(SecretKeySpec(secret, KeyProperties.KEY_ALGORITHM_HMAC_SHA256)),
            KeyProtection.Builder(KeyProperties.PURPOSE_SIGN).build(),
        )
        secret.fill(0)
        prefs.edit(commit = true) { putString("device_id", deviceId.toString()) }
    }

    fun clear() {
        keyStore.deleteEntry(ALIAS)
        prefs.edit(commit = true) { clear() }
    }

    val signer = FrameSigner { data ->
        val key = keyStore.getKey(ALIAS, null) as SecretKey
        Mac.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256).run {
            init(key)
            doFinal(data)
        }
    }

    private companion object {
        const val KEYSTORE = "AndroidKeyStore"
        const val ALIAS = "sos_device_hmac"
    }
}
