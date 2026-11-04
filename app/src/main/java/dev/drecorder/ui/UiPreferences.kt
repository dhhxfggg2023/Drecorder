package dev.drecorder.ui

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * The two things a user may override about how the app looks and speaks.
 *
 * Language and theme describe a person, not a recording: they have to be readable
 * before any UI exists (the theme picks a colour scheme, the language swaps every
 * string), so they live in their own preference file, read from MainActivity.
 *
 * Both default to following the device: a phone set to Chinese shows Chinese, a
 * phone set to English shows English, and an explicit choice exists only for the
 * people who want something else.
 */
object UiPreferences {

    /** How the app resolves its colour scheme. */
    enum class Theme { System, Light, Dark }

    private const val PREFS = "ui"
    private const val KEY_LANGUAGE = "language"
    private const val KEY_THEME = "theme"

    /** The tags the switch offers; the empty string means "follow the device". */
    val LANGUAGES = listOf("", "zh", "en")

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A BCP-47 tag such as "zh" or "en", or empty for the system language. */
    fun language(context: Context): String =
        prefs(context).getString(KEY_LANGUAGE, "").orEmpty()

    fun storeLanguage(context: Context, tag: String) {
        prefs(context).edit { putString(KEY_LANGUAGE, tag) }
    }

    fun theme(context: Context): Theme =
        when (prefs(context).getString(KEY_THEME, "system")) {
            "light" -> Theme.Light
            "dark" -> Theme.Dark
            else -> Theme.System
        }

    fun storeTheme(context: Context, theme: Theme) {
        val value = when (theme) {
            Theme.System -> "system"
            Theme.Light -> "light"
            Theme.Dark -> "dark"
        }
        prefs(context).edit { putString(KEY_THEME, value) }
    }
}
