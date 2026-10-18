package dev.drecorder.update

import android.content.Context
import androidx.core.content.edit
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Where the update manifest is fetched from, and when we last looked.
 *
 * The url is a setting rather than a constant so the app can be pointed at a mirror
 * (or at a local file server during testing) without a rebuild. Checking is
 * rate-limited to once per day: the manifest is small, but a self-hosted static file
 * does not deserve a request on every cold start.
 */
object UpdateSettings {

    private const val PREFS = "settings"
    private const val KEY_MANIFEST_URL = "updateManifestUrl"
    private const val KEY_AUTO_CHECK = "updateAutoCheck"
    private const val KEY_LAST_CHECK = "updateLastCheckAt"

    /** Placeholder until the repository has a published manifest (docs/DESIGN.md §11.2). */
    const val DEFAULT_MANIFEST_URL =
        "https://raw.githubusercontent.com/dhhxfggg2023/Drecorder/main/docs/update.json"

    private const val MIN_CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000

    fun manifestUrl(context: Context): String =
        prefs(context).getString(KEY_MANIFEST_URL, DEFAULT_MANIFEST_URL) ?: DEFAULT_MANIFEST_URL

    fun storeManifestUrl(context: Context, url: String) {
        prefs(context).edit { putString(KEY_MANIFEST_URL, url.trim()) }
    }

    /**
     * Off by default: this build is self-hosted, so there is no published manifest to
     * check until its author publishes one. Turning it on is a deliberate opt-in from
     * the settings screen, which is also the only place the url can be changed.
     */
    fun autoCheckEnabled(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUTO_CHECK, false)

    fun storeAutoCheck(context: Context, enabled: Boolean) {
        prefs(context).edit { putBoolean(KEY_AUTO_CHECK, enabled) }
    }

    fun lastCheckAt(context: Context): Long = prefs(context).getLong(KEY_LAST_CHECK, 0L)

    fun storeLastCheckAt(context: Context, timestamp: Long) {
        prefs(context).edit { putLong(KEY_LAST_CHECK, timestamp) }
    }

    /** True when a silent background check is allowed right now. */
    fun shouldAutoCheck(context: Context): Boolean {
        if (!autoCheckEnabled(context)) return false
        val elapsed = System.currentTimeMillis() - lastCheckAt(context)
        return elapsed >= MIN_CHECK_INTERVAL_MS
    }

    fun lastCheckLabel(context: Context): String? {
        val at = lastCheckAt(context)
        if (at <= 0L) return null
        return SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(at))
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
