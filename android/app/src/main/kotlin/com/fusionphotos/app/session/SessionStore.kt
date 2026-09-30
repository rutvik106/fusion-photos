package com.fusionphotos.app.session

import android.content.Context

/**
 * Where the app remembers the server it talks to and the current session.
 *
 * Only the *session token*, its account id and the base URL live here. The vault key is
 * never persisted — it exists in memory only, after the passphrase unlock, so a stolen
 * device backup cannot decrypt a library (the plan's owner-only promise).
 */
class SessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("fusion.session", Context.MODE_PRIVATE)

    /** Default targets the developer's machine from an emulator (10.0.2.2 == host localhost). */
    var baseUrl: String
        get() = prefs.getString(KEY_BASE_URL, DEFAULT_BASE_URL) ?: DEFAULT_BASE_URL
        set(value) = prefs.edit().putString(KEY_BASE_URL, value.trim().trimEnd('/')).apply()

    var token: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var userId: String?
        get() = prefs.getString(KEY_USER_ID, null)
        set(value) = prefs.edit().putString(KEY_USER_ID, value).apply()

    fun clear() {
        prefs.edit().remove(KEY_TOKEN).remove(KEY_USER_ID).apply()
    }

    companion object {
        const val DEFAULT_BASE_URL = "http://10.0.2.2:8080"
        private const val KEY_BASE_URL = "baseUrl"
        private const val KEY_TOKEN = "token"
        private const val KEY_USER_ID = "userId"
    }
}
