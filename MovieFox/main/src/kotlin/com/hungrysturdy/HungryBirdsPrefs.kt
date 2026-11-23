package com.hungrysturdy.ui

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject

object HungryBirdsPrefs {
    private const val PREFS_NAME = "hungrysturdy_ultra_prefs"

    const val THEME_PURPLE = "neon_purple"
    const val THEME_BLUE = "cyber_blue"
    const val THEME_EMERALD = "emerald"
    const val THEME_CRIMSON = "crimson"

    private fun prefs(context: Context): SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getTheme(context: Context): String =
        prefs(context).getString("ui_theme", THEME_PURPLE) ?: THEME_PURPLE

    fun setTheme(context: Context, theme: String) {
        prefs(context).edit().putString("ui_theme", theme).apply()
    }

    fun isWebViewCfEnabled(context: Context): Boolean =
        prefs(context).getBoolean("cf_webview_enabled", true)

    fun setWebViewCfEnabled(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("cf_webview_enabled", enabled).apply()
    }

    fun getMovieBoxHost(context: Context): String =
        prefs(context).getString("moviebox_host", "https://sflix.film") ?: "https://sflix.film"

    fun setMovieBoxHost(context: Context, host: String) {
        prefs(context).edit().putString("moviebox_host", host).apply()
    }

    fun getProviderOrder(context: Context, defaults: List<String>): List<String> {
        val raw = prefs(context).getString("provider_order", null) ?: return defaults
        val saved = runCatching {
            val arr = JSONArray(raw)
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf { s -> s.isNotBlank() } }
        }.getOrDefault(emptyList())
        val merged = (saved + defaults).distinct()
        return merged.filter { it in defaults }
    }

    fun setProviderOrder(context: Context, order: List<String>) {
        val arr = JSONArray()
        order.forEach { arr.put(it) }
        prefs(context).edit().putString("provider_order", arr.toString()).apply()
    }

    fun isProviderEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("provider_enabled_$id", true)

    fun setProviderEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("provider_enabled_$id", enabled).apply()
    }

    fun setAllProvidersEnabled(context: Context, ids: List<String>, enabled: Boolean) {
        val editor = prefs(context).edit()
        ids.forEach { editor.putBoolean("provider_enabled_$it", enabled) }
        editor.apply()
    }

    fun isExtractorEnabled(context: Context, id: String): Boolean =
        prefs(context).getBoolean("extractor_enabled_$id", true)

    fun setExtractorEnabled(context: Context, id: String, enabled: Boolean) {
        prefs(context).edit().putBoolean("extractor_enabled_$id", enabled).apply()
    }

    fun getCollectionJson(context: Context): String =
        prefs(context).getString("collection_json", """{"movies":[],"series":[]}""")
            ?: """{"movies":[],"series":[]}"""

    fun setCollectionJson(context: Context, json: String) {
        prefs(context).edit().putString("collection_json", json).apply()
    }

    fun addCollectionTitle(context: Context, title: String, isSeries: Boolean) {
        val clean = title.trim()
        if (clean.isEmpty()) return
        val root = runCatching { JSONObject(getCollectionJson(context)) }
            .getOrElse { JSONObject().put("movies", JSONArray()).put("series", JSONArray()) }
        val key = if (isSeries) "series" else "movies"
        val arr = root.optJSONArray(key) ?: JSONArray()
        arr.put(JSONObject().put("title", clean).put("addedAt", System.currentTimeMillis()))
        root.put(key, arr)
        setCollectionJson(context, root.toString(2))
    }

    fun appendLog(context: Context, tag: String, message: String) {
        val current = prefs(context).getString("diag_logs", "") ?: ""
        val line = "[$tag] $message"
        val lines = (current.lines().filter { it.isNotBlank() } + line).takeLast(60)
        prefs(context).edit().putString("diag_logs", lines.joinToString("\n")).apply()
    }

    fun getLogs(context: Context): String =
        prefs(context).getString("diag_logs", "") ?: ""

    fun clearLogs(context: Context) {
        prefs(context).edit().remove("diag_logs").apply()
    }

    fun resetAll(context: Context) {
        prefs(context).edit().clear().apply()
    }
}
