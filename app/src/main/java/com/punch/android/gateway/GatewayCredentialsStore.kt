package com.punch.android.gateway

import android.content.Context
import android.util.Log

/**
 * Stores Pi gateway origin + Basic auth. The password is kept encrypted with a
 * Keystore key; never log it.
 */
class GatewayCredentialsStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    init {
        migrateLegacyPassword()
    }

    fun getOrigin(): String = prefs.getString(KEY_ORIGIN, "").orEmpty()

    fun getUsername(): String = prefs.getString(KEY_USERNAME, "").orEmpty()

    fun getPassword(): String {
        val encrypted = prefs.getString(KEY_PASSWORD_ENC, null) ?: return ""
        return cipher.decrypt(encrypted) ?: run {
            Log.w(TAG, "stored password could not be decrypted; clearing it")
            prefs.edit().remove(KEY_PASSWORD_ENC).apply()
            ""
        }
    }

    fun getSessionId(): String = prefs.getString(KEY_SESSION, "").orEmpty()

    fun isConfigured(): Boolean = getOrigin().isNotBlank()

    fun save(origin: String, username: String, password: String) {
        prefs.edit()
            .putString(KEY_ORIGIN, origin)
            .putString(KEY_USERNAME, username)
            .putString(KEY_PASSWORD_ENC, cipher.encrypt(password))
            .remove(KEY_LEGACY_PASSWORD)
            .apply()
        Log.d(TAG, "credentials saved originConfigured=true userConfigured=${username.isNotBlank()}")
    }

    fun saveSessionId(sessionId: String) {
        prefs.edit().putString(KEY_SESSION, sessionId).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
        Log.d(TAG, "credentials cleared")
    }

    private fun migrateLegacyPassword() {
        val legacy = prefs.getString(KEY_LEGACY_PASSWORD, null) ?: return
        prefs.edit()
            .putString(KEY_PASSWORD_ENC, cipher.encrypt(legacy))
            .remove(KEY_LEGACY_PASSWORD)
            .apply()
        Log.d(TAG, "migrated plaintext password to encrypted storage")
    }

    companion object {
        private const val PREFS = "punch_gateway"
        private const val KEY_ORIGIN = "origin"
        private const val KEY_USERNAME = "username"
        private const val KEY_LEGACY_PASSWORD = "password"
        private const val KEY_PASSWORD_ENC = "password_enc"
        private const val KEY_SESSION = "session_id"
        private const val TAG = "PunchGatewayStore"
    }
}
