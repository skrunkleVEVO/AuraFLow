package com.example.auraflow.service

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/** Per-app blocking switches, written by the settings screen and read by [ShortsBlockerService]. */
object BlockerSettings {
    const val INSTAGRAM = "block_instagram"
    const val TIKTOK = "block_tiktok"
    const val YOUTUBE = "block_youtube"

    private const val FILE = "blocker_settings"

    fun prefs(context: Context): SharedPreferences =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    /** Every app is blocked until the user turns it off. */
    fun isEnabled(prefs: SharedPreferences, key: String): Boolean = prefs.getBoolean(key, true)

    fun setEnabled(context: Context, key: String, enabled: Boolean) =
        prefs(context).edit { putBoolean(key, enabled) }
}
