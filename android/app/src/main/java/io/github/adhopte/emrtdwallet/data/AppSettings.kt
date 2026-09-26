package io.github.adhopte.emrtdwallet.data

import android.content.Context
import io.github.adhopte.emrtdwallet.BuildConfig

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var issuerUrl: String
        get() = prefs.getString(KEY_ISSUER_URL, null) ?: BuildConfig.DEFAULT_ISSUER_URL
        set(value) = prefs.edit().putString(KEY_ISSUER_URL, value.trim().trimEnd('/')).apply()

    /** Whether the onboarding tutorial has been shown (it opens automatically on first launch). */
    var tutorialSeen: Boolean
        get() = prefs.getBoolean(KEY_TUTORIAL_SEEN, false)
        set(value) = prefs.edit().putBoolean(KEY_TUTORIAL_SEEN, value).apply()

    private companion object {
        const val KEY_ISSUER_URL = "issuer_url"
        const val KEY_TUTORIAL_SEEN = "tutorial_seen"
    }
}
