package dev.snapecg.holter.connector

import android.content.Context
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Encrypted-at-rest persistence for the most recent connector session key.
 *
 * Backed by Android Keystore via [EncryptedSharedPreferences]: the master
 * key is hardware-bound on devices that have a TEE/StrongBox, so the
 * stored AES-GCM key cannot be read off a stolen file system without
 * also unlocking the keystore.
 *
 * Stores at most one (peerAddress, sessionKey) tuple — the goal isn't a
 * device database, just "let the same PC reconnect after a Wi-Fi drop or
 * a quick app restart without re-entering the pairing code".
 */
class PairingStore(context: Context) {

    companion object {
        private const val TAG = "PairingStore"
        private const val FILE = "snapecg_pairing"
        private const val KEY_PEER = "peer_address"
        private const val KEY_SESSION = "session_key_hex"
        private const val KEY_TS = "saved_at_ms"

        /**
         * Window after which a saved key is considered stale and re-pairing
         * is required regardless of peer address. 7 days mirrors the
         * "trust on first use" threshold used by SSH known_hosts in many
         * managed deployments.
         */
        const val MAX_AGE_MS: Long = 7L * 24 * 60 * 60 * 1000
    }

    private val prefs by lazy {
        try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (e: Exception) {
            // EncryptedSharedPreferences is fragile after MasterKey schema
            // changes (it can throw on first read of a stale preference
            // file). Wipe and retry once — the worst-case outcome is that
            // the user has to re-pair, which is the same baseline we had
            // before this feature.
            Log.w(TAG, "EncryptedSharedPreferences init failed (${e.message}); resetting store")
            context.deleteSharedPreferences(FILE)
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context, FILE, masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }

    fun save(peerAddress: String, sessionKey: ByteArray) {
        prefs.edit()
            .putString(KEY_PEER, peerAddress)
            .putString(KEY_SESSION, sessionKey.toHex())
            .putLong(KEY_TS, System.currentTimeMillis())
            .apply()
        Log.i(TAG, "Saved session key for $peerAddress")
    }

    /** Returns the saved key iff (peerAddress matches AND age ≤ MAX_AGE_MS). */
    fun loadIfFresh(peerAddress: String): ByteArray? {
        val storedPeer = prefs.getString(KEY_PEER, null) ?: return null
        if (storedPeer != peerAddress) return null
        val ts = prefs.getLong(KEY_TS, 0)
        if (System.currentTimeMillis() - ts > MAX_AGE_MS) {
            Log.i(TAG, "Saved pairing for $peerAddress expired; needs re-pair")
            return null
        }
        val hex = prefs.getString(KEY_SESSION, null) ?: return null
        return ConnectorCrypto.hexToBytes(hex)
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private fun ByteArray.toHex(): String =
        joinToString("") { "%02x".format(it.toInt() and 0xFF) }
}
