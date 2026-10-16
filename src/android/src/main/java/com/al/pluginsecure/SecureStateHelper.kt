package com.al.pluginsecure

import android.content.Context
import android.content.SharedPreferences
import android.webkit.WebView

/**
 * Manages app state preservation across app switching, backgrounding,
 * and process death so the user does not get sent back to the initial page.
 */
class SecureStateHelper(context: Context) {
    companion object {
        private const val PREFS_NAME = "plugin_secure_state_prefs"
        private const val KEY_LAST_URL = "last_active_url"
        private const val KEY_LAST_ROUTE = "last_active_route"
        private const val KEY_LAST_TIMESTAMP = "last_active_timestamp"
        private const val KEY_EXTRA_DATA = "last_extra_data"
        private const val KEY_ENABLED = "state_preservation_enabled"
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Value used when the runtime override (`enableStatePreservation`) has never
     * been set. Comes from the plugin config (`plugins.PluginSecure.statePreservation`).
     */
    private var defaultEnabled: Boolean = true

    fun setDefaultEnabled(enabled: Boolean) {
        defaultEnabled = enabled
    }

    fun isEnabled(): Boolean = prefs.getBoolean(KEY_ENABLED, defaultEnabled)

    fun setEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    /**
     * Persist current state.
     */
    fun saveState(url: String?, route: String? = null, extraData: String? = null) {
        if (!isEnabled()) return
        if (url.isNullOrBlank() || url.startsWith("about:blank") || url.startsWith("data:") || url.contains("chrome-error")) {
            return
        }

        prefs.edit().apply {
            putString(KEY_LAST_URL, url)
            if (route != null) putString(KEY_LAST_ROUTE, route)
            putString(KEY_EXTRA_DATA, extraData)
            putLong(KEY_LAST_TIMESTAMP, System.currentTimeMillis())
            apply()
        }
    }

    fun getLastUrl(): String? = prefs.getString(KEY_LAST_URL, null)
    fun getLastRoute(): String? = prefs.getString(KEY_LAST_ROUTE, null)
    fun getLastTimestamp(): Long = prefs.getLong(KEY_LAST_TIMESTAMP, 0L)
    fun getExtraData(): String? = prefs.getString(KEY_EXTRA_DATA, null)

    fun isExpired(maxAgeMinutes: Long = 1440L): Boolean {
        val lastTime = getLastTimestamp()
        if (lastTime == 0L) return true
        val maxAgeMs = maxAgeMinutes * 60 * 1000
        return (System.currentTimeMillis() - lastTime) > maxAgeMs
    }

    /**
     * Attempts to restore the saved state to the WebView if valid and not expired.
     * Returns true if restoration was performed.
     */
    fun restoreToWebView(webView: WebView, maxAgeMinutes: Long = 1440L): Boolean {
        if (!isEnabled() || isExpired(maxAgeMinutes)) return false

        val savedUrl = getLastUrl() ?: return false
        val currentUrl = webView.url

        // Avoid infinite reload if already on the saved URL
        if (currentUrl == savedUrl) return false

        webView.post {
            webView.loadUrl(savedUrl)
        }
        return true
    }

    fun clearState() {
        prefs.edit().clear().commit()
    }
}

