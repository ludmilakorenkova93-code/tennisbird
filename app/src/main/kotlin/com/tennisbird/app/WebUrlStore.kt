package com.tennisbird.app

import android.content.Context
import android.webkit.URLUtil

object WebUrlStore {
    private const val PREFS_NAME = "tennis_bird_web"
    private const val KEY_CACHED_URL = "cached_url"

    fun getCachedUrl(context: Context): String? {
        val url = context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_CACHED_URL, null)
            ?.trim()

        return url?.takeIf(::isWebUrl)
    }

    fun saveUrl(context: Context, url: String) {
        val normalized = url.trim()
        if (!isWebUrl(normalized)) return

        context
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_CACHED_URL, normalized)
            .apply()
    }

    fun isWebUrl(url: String): Boolean =
        URLUtil.isValidUrl(url) && (url.startsWith("https://") || url.startsWith("http://"))
}
