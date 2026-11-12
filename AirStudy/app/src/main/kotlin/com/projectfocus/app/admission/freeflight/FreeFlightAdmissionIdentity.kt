package com.projectfocus.app.admission.freeflight

import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import com.projectfocus.service.focus.freeflight.FreeFlightOwnerToken
import java.io.IOException
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Root's shared actual owner/account adapter supplies this atomically; no fixed or guessed owner. */
data class CapturedFreeFlightAdmissionIdentity(val owner: FreeFlightOwnerToken, val bearer: String?) {
    val bearerFingerprint: String get() = admissionHash(bearer.orEmpty())
    override fun toString() = "CapturedFreeFlightAdmissionIdentity(owner=$owner, bearer=<redacted>)"
}
interface FreeFlightAdmissionIdentityProvider {
    suspend fun capture(owner: FreeFlightOwnerToken): CapturedFreeFlightAdmissionIdentity?
    suspend fun current(): CapturedFreeFlightAdmissionIdentity?
    suspend fun isCurrent(identity: CapturedFreeFlightAdmissionIdentity): Boolean
}

data class FreeFlightDeviceIdentity(val hwid: String, val credential: String) {
    override fun toString() = "FreeFlightDeviceIdentity(hwid=$hwid, credential=<redacted>)"
}
fun interface FreeFlightDeviceIdentityProvider { fun capture(): FreeFlightDeviceIdentity }

/**
 * Own Android-scoped ID+package hash and random installation credential. This is
 * an actual local provider, not hardware attestation or the original fingerprint.
 * Missing/changed Android ID never generates a pretend device or another grant.
 */
class AndroidFreeFlightDeviceIdentity(
    private val context: Context,
    private val preferences: SharedPreferences = context.getSharedPreferences("freeflight_device_identity_v1", Context.MODE_PRIVATE),
    private val random: SecureRandom = SecureRandom(),
) : FreeFlightDeviceIdentityProvider {
    @Serializable private data class Stored(val hwid: String, val credential: String)
    private val json = Json { encodeDefaults = true }
    override fun capture(): FreeFlightDeviceIdentity = synchronized(lock) {
        val androidId = Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
            ?.takeIf { it.isNotBlank() } ?: throw IOException("Device identity is unavailable")
        val hwid = admissionHash("freeflight-own-device-v1\u0000" + context.packageName + "\u0000" + androidId)
        val raw = preferences.getString("identity", null)
        val stored = if (raw != null) json.decodeFromString(Stored.serializer(), raw).also {
            if (it.hwid != hwid) throw IOException("Device identity changed; explicit enrollment recovery is required")
        } else {
            val bytes = ByteArray(32).also(random::nextBytes)
            Stored(hwid, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)).also {
                val encoded = json.encodeToString(Stored.serializer(), it)
                if (!preferences.edit().putString("identity", encoded).commit() || preferences.getString("identity", null) != encoded)
                    throw IOException("Device credential could not be durably saved")
            }
        }
        if (!stored.hwid.matches(Regex("[0-9a-f]{64}")) || !stored.credential.matches(Regex("[A-Za-z0-9_-]{43}"))) throw IOException("Saved device credential is invalid")
        FreeFlightDeviceIdentity(stored.hwid, stored.credential)
    }
    companion object { private val lock = Any() }
}

internal fun admissionHash(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
