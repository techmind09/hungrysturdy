package com.hungrysturdy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

object MovieFoxSettingsDialog {

    private const val PREFS_NAME = "moviefox_ultra_max_v16"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_NUVIO_MANIFESTS = "mf_nuvio_manifests_json"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_QUALITY = "mf_preferred_quality"
    private const val KEY_Q_4K = "mf_q_4k_1440p"
    private const val KEY_Q_1080 = "mf_q_1080p"
    private const val KEY_Q_720 = "mf_q_720p"
    private const val KEY_Q_480 = "mf_q_480p"
    private const val KEY_HINDI_ONLY = "mf_hindi_providers_only"
    private const val KEY_LOW_BUFFER = "mf_low_buffer_mode"
    private const val KEY_DOWNLOAD_ONLY = "mf_download_only_mode"
    private const val KEY_FAST_FETCH = "mf_fast_fetch_mode"
    private const val KEY_CONCURRENCY = "mf_concurrency"
    private const val KEY_TIMEOUT_SEC = "mf_timeout_sec"
    private const val KEY_METADATA_SOURCE = "mf_metadata_source"
    private const val KEY_SHUFFLE_HOME = "mf_shuffle_home_categories"
    private const val KEY_HOME_CATEGORIES = "mf_home_categories_json"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    private var cachedPrefs: SharedPreferences? = null
    private var cachedManifests: MutableList<NuvioManifestConfig>? = null
    private var cachedCs3Repos: MutableList<Cs3RepoConfig>? = null
    private val cachedCs3Servers = CopyOnWriteArrayList<ServerItem>()
    private var cs3Loaded = false

    data class ServerItem(
        val key: String,
        val name: String,
        val tag: String,
        val probeUrl: String,
        val accentHex: String,
        val isCustom: Boolean = false,
        val isHindi: Boolean = true,
        val category: String = "Movies"
    )

    data class HomeCategoryItem(
        val id: String,
        var title: String,
        var visible: Boolean = true,
        var badge: String = "Mixed"
    )

    data class NuvioManifestConfig(
        val id: String,
        var label: String,
        var manifestUrl: String,
        var enabled: Boolean = true
    )

    data class Cs3RepoConfig(
        val id: String,
        val name: String,
        val repoUrl: String,
        var enabled: Boolean = true,
        var serverCount: Int = 0
    )

    data class CustomExternalSite(
        val id: String,
        val name: String,
        val baseUrl: String,
        var enabled: Boolean = true
    )

    val VEGA_ALLOWED_HOSTS = listOf("pixeldrain", "fsl2", "fsl", "gdrive", "gdflix", "hubcloud")

    val BASE_SERVERS = listOf(
        ServerItem("sflix", "Sflix", "HLS/MP4", "https://sflix.film/", "#F97316", false, true, "Series"),
        ServerItem("prmovies", "Prmovies", "HI/M3U8", "https://prmovies.coupons/", "#F59E0B", false, true, "Hindi"),
        ServerItem("hindidrama", "HindiDrama", "K-DRAMA", "https://hindidrama.net/", "#EC4899", false, true, "Series"),
        ServerItem("vegamovies", "VegaMovies", "PXL•FSL•GDRIVE", "https://vegamovies.lease/", "#EC4899", false, true, "Hindi"),
        ServerItem("purstream", "Purstream", "API", "https://moviefox.pro/api/stream/purstream", "#38BDF8", false, true, "Movies"),
        ServerItem("castle", "Castle", "API", "https://moviefox.pro/api/stream/castle", "#818CF8", false, true, "Movies"),
        ServerItem("watchout", "Watchout", "API", "https://moviefox.pro/api/stream/watchout", "#2DD4BF", false, false, "Movies"),
        ServerItem("cinetv_api", "CineTV API", "WEB/API", "https://cinetv.app/en", "#F43F5E", false, true, "Movies"),
        ServerItem("cinetv_hls", "CineTV HLS", "WORKER", "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls/", "#FB7185", false, true, "Movies"),
        ServerItem("rogmovies", "RogMovies", "PXL•FSL•GDRIVE", "https://rogmovies.im/", "#EC4899", false, true, "Hindi"),
        ServerItem("bollyflix", "Bollyflix", "HI/4K", "https://new1.bollyflix.tours/", "#38BDF8", false, true, "Hindi"),
        ServerItem("play4u", "Play4u", "HLS", "https://play4u.org/", "#FB7185", false, true, "Movies"),
        ServerItem("hdhub4u", "HDHub4u", "SITE", "https://new15.hdhub4u.cl/?utm=mn1", "#34D399", false, true, "Hindi"),
        ServerItem("dramaverse", "DramaVerse", "XCLOUD", "https://mydverse.com/", "#A855F7", false, true, "Series"),
        ServerItem("gokuhd", "GokuHD", "ANIME", "https://gokuhd.nl/", "#38BDF8", false, true, "Anime"),
        ServerItem("nuvio", "Nuvio Addon", "MANIFEST", "https://nuvio.app/", "#A855F7", false, true, "Series"),
        ServerItem("moviebox", "MovieBox", "BFF", "https://h5-api.aoneroom.com/wefeed-h5api-bff", "#34D399", false, true, "Movies"),
        ServerItem("netnaija", "NetNaija", "SITE", "https://www.thenetnaija.net/", "#FACC15", false, false, "Series"),
        ServerItem("hicine", "HiCine", "HAPI", "https://hicine.biz/", "#38BDF8", false, true, "Hindi"),
        ServerItem("hindmoviez", "HindMoviez", "SITE", "https://v3.hindmovie.im/", "#FACC15", false, true, "Hindi"),
        ServerItem("movielinkbd", "MovieLinkBD", "SITE", "https://drjuea.movielinkbd.at/", "#818CF8", false, false, "Movies"),
        ServerItem("netmirror", "NetMirror", "OTT", "https://netmirror.gold/", "#F43F5E", false, true, "Series"),
        ServerItem("movy", "Movy", "API", "https://www.movy.sx/", "#22D3EE", false, true, "Movies"),
        ServerItem("4khdhub", "4KHDHub", "4K", "https://4khdhub.one/", "#C084FC", false, true, "Hindi"),
        ServerItem("movies4u", "Movies4u", "SITE", "https://movies4u.vet/", "#FB923C", false, true, "Hindi"),
        ServerItem("multimovies", "Multimovies", "ANIME/HI", "https://m1.multimovies.courses/", "#22D3EE", false, true, "Anime")
    )

    private val DEFAULT_HOME_CATEGORIES = listOf(
        HomeCategoryItem("trending", "Trending Now", true, "Mixed"),
        HomeCategoryItem("popular_movies", "Popular Movies", true, "Movies"),
        HomeCategoryItem("popular_tv", "Popular TV Shows", true, "Series"),
        HomeCategoryItem("hindi_movies", "Indian Movies (Hindi)", true, "Movies"),
        HomeCategoryItem("hindi_tv", "Indian TV Shows", true, "Series"),
        HomeCategoryItem("kdrama", "K-Drama", true, "Series"),
        HomeCategoryItem("anime", "Anime", true, "Anime"),
        HomeCategoryItem("hollywood", "Hollywood Movies", true, "Movies"),
        HomeCategoryItem("tamil_telugu", "South Indian (Tamil/Telugu)", true, "Movies"),
        HomeCategoryItem("cdrama", "Chinese & Turkish Dramas", true, "Series")
    )

    private val METADATA_SOURCES = listOf("TMDB", "Cinemeta", "Stremio")

    private val PRESET_CATALOGS = listOf(
        "Streaming Catalogs Plus" to "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/manifest.json",
        "Anime Kitsu" to "https://anime-kitsu.strem.fun/manifest.json",
        "Cinemeta" to "https://v3-cinemeta.strem.io/manifest.json"
    )

    private val AVATAR_PALETTE = listOf(
        "#38BDF8", "#A855F7", "#F43F5E", "#34D399",
        "#FB923C", "#818CF8", "#22D3EE", "#EC4899", "#FACC15"
    )

    val SERVERS: List<ServerItem>
        get() = BASE_SERVERS + cachedCs3Servers.toList()

    private fun isContextAlive(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return false
        return true
    }

    private fun safeColor(hex: String, fallback: String = "#94A3B8"): Int =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.parseColor(fallback) }

    private fun safeToast(ctx: Context?, msg: String) {
        if (!isContextAlive(ctx)) return
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(ctx: Context, px: Int): Int =
        (px * ctx.resources.displayMetrics.density).toInt()

    private fun cardBg(
        bgHex: String = "#141822",
        strokeHex: String = "#252E42",
        radius: Float = 22f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#141822"))
        cornerRadius = radius
        setStroke(strokeWidth, safeColor(strokeHex, "#252E42"))
    }

    private fun pillBg(bgHex: String, radius: Float = 16f): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#1C2230"))
        cornerRadius = radius
    }

    private fun prefs(ctx: Context): SharedPreferences =
        cachedPrefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also {
                cachedPrefs = it
                if (!cs3Loaded) {
                    cs3Loaded = true
                    cachedCs3Servers.clear()
                    cachedCs3Servers.addAll(loadCs3ServersInternal(it))
                }
            }

    fun isVegaAllowedHost(linkNameOrUrl: String): Boolean {
        val lower = linkNameOrUrl.lowercase()
        return VEGA_ALLOWED_HOSTS.any { lower.contains(it) }
    }

    fun getCookieForDomain(ctx: Context?, urlOrDomain: String): String? = null
    fun hasSavedCookieForDomain(ctx: Context, urlOrDomain: String): Boolean = true

    fun isHindiOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_HINDI_ONLY, false)
    fun setHindiOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_HINDI_ONLY, enabled).apply()

    fun isQuality4kEnabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_Q_4K, true)
    fun setQuality4kEnabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_Q_4K, enabled).apply()

    fun isQuality1080Enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_Q_1080, true)
    fun setQuality1080Enabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_Q_1080, enabled).apply()

    fun isQuality720Enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_Q_720, true)
    fun setQuality720Enabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_Q_720, enabled).apply()

    fun isQuality480Enabled(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_Q_480, true)
    fun setQuality480Enabled(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_Q_480, enabled).apply()

    fun getPreferredQuality(ctx: Context): String {
        return when {
            isQuality4kEnabled(ctx) -> "4K"
            isQuality1080Enabled(ctx) -> "1080p"
            isQuality720Enabled(ctx) -> "720p"
            isQuality480Enabled(ctx) -> "480p"
            else -> "AUTO"
        }
    }

    fun setPreferredQuality(ctx: Context, quality: String) = prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()

    fun isLowBufferMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)
    fun setLowBufferMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()

    fun isDownloadOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DOWNLOAD_ONLY, false)
    fun setDownloadOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_DOWNLOAD_ONLY, enabled).apply()

    fun isFastFetchMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FAST_FETCH, true)
    fun setFastFetchMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_FAST_FETCH, enabled).apply()

    fun isStreamOnly(ctx: Context): Boolean = isLowBufferMode(ctx)
    fun getParallelServers(ctx: Context): Int = getConcurrency(ctx)

    fun getConcurrency(ctx: Context): Int = prefs(ctx).getInt(KEY_CONCURRENCY, 15)
    fun setConcurrency(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 50)).apply()

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_TIMEOUT_SEC, 8)
    fun setTimeoutSeconds(ctx: Context, sec: Int) = prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(4, 20)).apply()

    fun getMetadataSource(ctx: Context): String = prefs(ctx).getString(KEY_METADATA_SOURCE, "TMDB") ?: "TMDB"
    fun setMetadataSource(ctx: Context, src: String) = prefs(ctx).edit().putString(KEY_METADATA_SOURCE, src).apply()

    fun isShuffleHomeCategories(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHUFFLE_HOME, false)
    fun setShuffleHomeCategories(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHUFFLE_HOME, enabled).apply()

    fun getHomeCategories(ctx: Context): MutableList<HomeCategoryItem> {
        val raw = prefs(ctx).getString(KEY_HOME_CATEGORIES, null)
        if (raw.isNullOrBlank()) return DEFAULT_HOME_CATEGORIES.map { it.copy() }.toMutableList()
        val list = mutableListOf<HomeCategoryItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val title = o.optString("title").trim()
                val badge = o.optString("badge", "Mixed").trim()
                if (id.isNotBlank() && title.isNotBlank()) {
                    list.add(HomeCategoryItem(id, title, o.optBoolean("visible", true), badge))
                }
            }
        }
        return if (list.isEmpty()) DEFAULT_HOME_CATEGORIES.map { it.copy() }.toMutableList() else list
    }

    fun saveHomeCategories(ctx: Context, list: List<HomeCategoryItem>) {
        val arr = JSONArray()
        list.forEach { c ->
            arr.put(JSONObject().put("id", c.id).put("title", c.title).put("visible", c.visible).put("badge", c.badge))
        }
        prefs(ctx).edit().putString(KEY_HOME_CATEGORIES, arr.toString()).apply()
    }

    fun isServerEnabled(ctx: Context, key: String): Boolean {
        val enabled = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)
        if (!enabled) return false
        if (isHindiOnly(ctx)) {
            val item = SERVERS.firstOrNull { it.key.equals(key, true) }
            if (item != null && !item.isCustom && !item.isHindi) return false
        }
        return true
    }

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled).apply()
    }

    fun getServerOrder(ctx: Context): List<String> {
        prefs(ctx)
        val allKeys = SERVERS.map { it.key }
        val saved = prefs(ctx).getString(KEY_SRV_ORDER, null)?.split(",")?.filter { it.isNotBlank() } ?: return allKeys
        val valid = saved.filter { it in allKeys }
        return valid + (allKeys - valid.toSet())
    }

    fun saveServerOrder(ctx: Context, orderedKeys: List<String>) {
        prefs(ctx).edit().putString(KEY_SRV_ORDER, orderedKeys.joinToString(",")).apply()
    }

    fun getOrderedEnabledKeys(ctx: Context): List<String> =
        getServerOrder(ctx).filter { isServerEnabled(ctx, it) }

    fun reportServerResult(ctx: Context?, key: String, success: Boolean, latencyMs: Long = 0L) {}
    fun getServerFailCount(ctx: Context, key: String): Int = 0
    fun getServerLastMs(ctx: Context, key: String): Long = -1L
    fun resetAllHealthStats(ctx: Context) {}

    private fun toRawGithubUrl(raw: String): String {
        val trimmed = raw.trim()
        val fixed = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) trimmed else "https://$trimmed"
        return when {
            fixed.contains("github.com/") && fixed.contains("/blob/") ->
                fixed.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/blob/", "/")
            fixed.contains("github.com/") && fixed.contains("/raw/") ->
                fixed.replace("https://github.com/", "https://raw.githubusercontent.com/").replace("/raw/", "/")
            else -> fixed
        }
    }

    private fun normalizeNuvioManifestUrl(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) u = "https://" + u.substringAfter("://")
        if (!u.startsWith("http://") && !u.startsWith("https://")) u = "https://$u"
        val qIdx = u.indexOf('?')
        val pathPart = if (qIdx >= 0) u.substring(0, qIdx) else u
        val queryPart = if (qIdx >= 0) u.substring(qIdx) else ""
        val cleanPath = pathPart.trimEnd('/')
        val withManifest = if (cleanPath.endsWith("/manifest.json", ignoreCase = true)) cleanPath else "$cleanPath/manifest.json"
        return "$withManifest$queryPart"
    }

    private fun loadCs3ServersInternal(p: SharedPreferences): List<ServerItem> {
        val out = mutableListOf<ServerItem>()
        val raw = p.getString(KEY_CS3_SERVERS, "[]") ?: "[]"
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val key = obj.optString("key").trim()
                val name = obj.optString("name").trim()
                val probeUrl = obj.optString("probeUrl").trim()
                if (key.isNotBlank() && name.isNotBlank() && probeUrl.isNotBlank()) {
                    out.add(ServerItem(key, name, obj.optString("tag", "CS3").ifBlank { "CS3" }, probeUrl, obj.optString("accentHex", "#38BDF8"), true, true, "Movies"))
                }
            }
        }
        return out
    }

    private fun saveCs3Servers(ctx: Context, list: List<ServerItem>) {
        val deduped = list.distinctBy { it.key }
        cachedCs3Servers.clear()
        cachedCs3Servers.addAll(deduped)
        val arr = JSONArray()
        deduped.forEach { srv ->
            arr.put(JSONObject().put("key", srv.key).put("name", srv.name).put("tag", srv.tag).put("probeUrl", srv.probeUrl).put("accentHex", srv.accentHex))
        }
        prefs(ctx).edit().putString(KEY_CS3_SERVERS, arr.toString()).apply()
    }

    fun getCs3Repos(ctx: Context): MutableList<Cs3RepoConfig> {
        cachedCs3Repos?.let { return it }
        val raw = prefs(ctx).getString(KEY_CS3_REPOS, "[]") ?: "[]"
        val list = mutableListOf<Cs3RepoConfig>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("repoUrl").trim()
                if (url.isNotBlank()) {
                    list.add(Cs3RepoConfig(obj.optString("id", System.currentTimeMillis().toString()), obj.optString("name", deriveManifestLabel(url)), url, obj.optBoolean("enabled", true), obj.optInt("serverCount", 0)))
                }
            }
        }
        cachedCs3Repos = list
        return list
    }

    fun saveCs3Repos(ctx: Context, list: List<Cs3RepoConfig>) {
        cachedCs3Repos = list.toMutableList()
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(JSONObject().put("id", r.id).put("name", r.name).put("repoUrl", r.repoUrl).put("enabled", r.enabled).put("serverCount", r.serverCount))
        }
        prefs(ctx).edit().putString(KEY_CS3_REPOS, arr.toString()).apply()
    }

    fun getEnabledCs3Servers(ctx: Context): List<ServerItem> {
        prefs(ctx)
        return cachedCs3Servers.filter { isServerEnabled(ctx, it.key) }
    }

    fun getNuvioManifests(ctx: Context): MutableList<NuvioManifestConfig> {
        cachedManifests?.let { return it }
        val raw = prefs(ctx).getString(KEY_NUVIO_MANIFESTS, null)
        if (raw.isNullOrBlank()) {
            val seeded = PRESET_CATALOGS.mapIndexed { idx, (label, url) ->
                NuvioManifestConfig("preset_${idx + 1}", label, url, true)
            }.toMutableList()
            cachedManifests = seeded
            saveNuvioManifests(ctx, seeded)
            return seeded
        }
        val list = mutableListOf<NuvioManifestConfig>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("manifestUrl").trim()
                if (url.isNotBlank()) {
                    list.add(NuvioManifestConfig(obj.optString("id", System.currentTimeMillis().toString()), obj.optString("label", deriveManifestLabel(url)), url, obj.optBoolean("enabled", true)))
                }
            }
        }
        cachedManifests = list
        return list
    }

    fun saveNuvioManifests(ctx: Context, list: List<NuvioManifestConfig>) {
        cachedManifests = list.toMutableList()
        val arr = JSONArray()
        list.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("label", m.label).put("manifestUrl", m.manifestUrl).put("enabled", m.enabled))
        }
        prefs(ctx).edit().putString(KEY_NUVIO_MANIFESTS, arr.toString()).apply()
    }

    fun getCustomSites(ctx: Context): List<CustomExternalSite> =
        getNuvioManifests(ctx).map { CustomExternalSite(it.id, it.label, it.manifestUrl, it.enabled) }

    fun deriveManifestLabel(rawUrl: String): String = try {
        val host = URI(normalizeNuvioManifestUrl(rawUrl)).host.orEmpty().removePrefix("www.")
        host.replaceFirstChar { it.uppercase() }.ifBlank { "Catalog Addon" }
    } catch (_: Exception) {
        "Catalog Addon"
    }

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 7000): ByteArray? = runCatching {
        val conn = (URL(toRawGithubUrl(urlStr)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CF_BYPASS_UA)
            setRequestProperty("Accept", "application/json, application/octet-stream, text/plain, */*")
        }
        val bytes = if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        conn.disconnect()
        bytes
    }.getOrNull()

    private fun parseJsonServerEntries(body: String, idTag: String, defaultUrl: String, out: MutableList<ServerItem>) {
        runCatching {
            val entries = mutableListOf<JSONObject>()
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
            } else if (body.startsWith("{")) {
                val root = JSONObject(body)
                listOf("plugins", "items", "providers", "servers", "sources").forEach { key ->
                    root.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
                    }
                }
            }
            entries.forEachIndexed { idx, pObj ->
                val finalName = pObj.optString("name").trim().ifBlank { pObj.optString("internalName").trim() }
                if (finalName.isBlank()) return@forEachIndexed
                val url = pObj.optString("url").ifBlank { pObj.optString("mainUrl") }.ifBlank { pObj.optString("pluginUrl") }.ifBlank { defaultUrl }.trim()
                val slug = finalName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                out.add(ServerItem("cs3_${idTag}_${slug.ifBlank { "srv$idx" }}", finalName, "CS3", toRawGithubUrl(url), AVATAR_PALETTE[idx % AVATAR_PALETTE.size], true, true, "Movies"))
            }
        }
    }

    private suspend fun fetchServersFromCs3Repo(repo: Cs3RepoConfig): List<ServerItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ServerItem>()
        val rawUrl = toRawGithubUrl(repo.repoUrl)
        val bytes = readHttpBytes(rawUrl) ?: return@withContext emptyList()
        if (bytes.isEmpty()) return@withContext emptyList()
        val idTag = repo.id.takeLast(6)

        if (rawUrl.substringBefore("?").endsWith(".cs3", true)) {
            var pluginName = ""
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory) {
                            val lower = entry.name.lowercase()
                            when {
                                lower.endsWith("manifest.json") -> {
                                    val mObj = JSONObject(zis.bufferedReader().readText())
                                    pluginName = mObj.optString("name").trim().ifBlank { mObj.optString("internalName").trim() }
                                }
                                lower.endsWith(".json") -> parseJsonServerEntries(zis.bufferedReader().readText().trim(), idTag, rawUrl, out)
                                lower.endsWith(".dex") -> {
                                    val rawDex = String(zis.readBytes(), Charsets.ISO_8859_1)
                                    val seen = mutableSetOf<String>()
                                    var idx = 0
                                    Regex("""L[a-zA-Z0-9_/]+/([A-Z][a-zA-Z0-9]{2,28})(?:Provider|Plugin|Source);""").findAll(rawDex).forEach { m ->
                                        val sName = m.groupValues[1]
                                        if (sName !in setOf("Main", "Base", "Abstract", "Custom", "Cloudstream", "Stream") && seen.add(sName.lowercase())) {
                                            out.add(ServerItem("cs3_${idTag}_${sName.lowercase()}", sName, "CS3", rawUrl, AVATAR_PALETTE[idx % AVATAR_PALETTE.size], true, true, "Movies"))
                                            idx++
                                        }
                                    }
                                }
                            }
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            if (out.isEmpty()) {
                val fName = pluginName.ifBlank { rawUrl.substringBefore("?").substringAfterLast("/").removeSuffix(".cs3") }.ifBlank { "CS3 Plugin" }
                out.add(ServerItem("cs3_${idTag}_${fName.lowercase().replace(Regex("[^a-z0-9]+"), "_")}", fName, "CS3", rawUrl, "#34D399", true, true, "Movies"))
            }
            return@withContext out.distinctBy { it.key }
        }

        val body = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
        if (body.isBlank()) return@withContext emptyList()
        parseJsonServerEntries(body, idTag, rawUrl, out)
        runCatching {
            if (body.startsWith("{")) {
                val root = JSONObject(body)
                val listUrls = mutableListOf<String>()
                root.optJSONArray("pluginLists")?.let { arr ->
                    for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { listUrls.add(it) }
                }
                root.optString("manifestList").trim().takeIf { it.isNotBlank() }?.let { listUrls.add(it) }
                listUrls.distinct().forEach { u ->
                    val resolved = if (u.startsWith("http")) toRawGithubUrl(u) else "${rawUrl.substringBeforeLast('/')}/${u.trimStart('/')}"
                    readHttpBytes(resolved)?.let { b -> parseJsonServerEntries(String(b, Charsets.UTF_8).trim(), idTag, rawUrl, out) }
                }
            }
        }
        out.distinctBy { it.key }
    }

    private class ServerListAdapter(
        private val ctx: Context,
        private val onToggleChanged: () -> Unit,
        private val onMoveUp: (ServerItem) -> Unit,
        private val onMoveDown: (ServerItem) -> Unit
    ) : BaseAdapter() {

        private var items: List<ServerItem> = emptyList()

        fun submitList(newList: List<ServerItem>) {
            items = newList
            notifyDataSetChanged()
        }

        private class VH(
            val root: LinearLayout,
            val avatarTv: TextView,
            val nameTv: TextView,
            val tagTv: TextView,
            val langTv: TextView,
            val urlTv: TextView,
            val upBtn: TextView,
            val downBtn: TextView,
            val sw: Switch
        )

        override fun getCount(): Int = items.size
        override fun getItem(position: Int): ServerItem = items[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val holder: VH
            val rowView: View

            if (convertView == null) {
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg("#121621", "#232C40", 20f)
                    setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
                }
                val avatarTv = TextView(ctx).apply {
                    textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 34), dp(ctx, 34)).apply { marginEnd = dp(ctx, 10) }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val topLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val nameTv = TextView(ctx).apply {
                    textSize = 13.5f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                }
                val tagTv = TextView(ctx).apply {
                    textSize = 8.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 5) }
                }
                val langTv = TextView(ctx).apply {
                    textSize = 8.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 5) }
                }
                topLine.addView(nameTv); topLine.addView(tagTv); topLine.addView(langTv)

                val urlTv = TextView(ctx).apply {
                    textSize = 10f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#7C869C")); setPadding(0, dp(ctx, 2), dp(ctx, 4), 0)
                }
                col.addView(topLine); col.addView(urlTv)

                val upBtn = TextView(ctx).apply {
                    text = "↑"; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
                }
                val downBtn = TextView(ctx).apply {
                    text = "↓"; textSize = 13f; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 6), dp(ctx, 4))
                }
                val sw = Switch(ctx)

                card.addView(avatarTv); card.addView(col); card.addView(upBtn); card.addView(downBtn); card.addView(sw)

                holder = VH(card, avatarTv, nameTv, tagTv, langTv, urlTv, upBtn, downBtn, sw)
                card.tag = holder
                rowView = card
            } else {
                rowView = convertView
                holder = convertView.tag as VH
            }

            val srv = items[position]
            holder.avatarTv.text = srv.name.take(1).uppercase()
            holder.avatarTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.avatarTv.background = pillBg("#192236", 12f)

            holder.nameTv.text = srv.name
            holder.tagTv.text = srv.tag
            holder.tagTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.tagTv.background = pillBg("#192236", 8f)

            if (srv.isHindi) {
                holder.langTv.visibility = View.VISIBLE
                holder.langTv.text = "HI"
                holder.langTv.setTextColor(safeColor("#34D399"))
                holder.langTv.background = pillBg("#0D2B22", 8f)
            } else {
                holder.langTv.visibility = View.GONE
            }

            holder.urlTv.text = srv.probeUrl
            holder.upBtn.setTextColor(safeColor(if (position > 0) "#38BDF8" else "#334155"))
            holder.downBtn.setTextColor(safeColor(if (position < items.lastIndex) "#38BDF8" else "#334155"))
            holder.upBtn.setOnClickListener { if (position > 0) onMoveUp(srv) }
            holder.downBtn.setOnClickListener { if (position < items.lastIndex) onMoveDown(srv) }

            holder.sw.setOnCheckedChangeListener(null)
            holder.sw.isChecked = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", true)
            holder.sw.setOnCheckedChangeListener { _, checked ->
                setServerEnabled(ctx, srv.key, checked)
                onToggleChanged()
            }
            holder.root.setOnClickListener { holder.sw.isChecked = !holder.sw.isChecked }
            return rowView
        }
    }

    fun show(ctx: Context) {
        if (!isContextAlive(ctx)) return
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val nuvioList = getNuvioManifests(ctx).toMutableList()
        val cs3RepoList = getCs3Repos(ctx).toMutableList()
        val homeCats = getHomeCategories(ctx)
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        var currentQuery = ""
        var selectedServerCat = "All"
        var currentConcurrency = getConcurrency(ctx)
        var currentTimeout = getTimeoutSeconds(ctx)
        var selectedMeta = getMetadataSource(ctx)
        var hindiOnly = isHindiOnly(ctx)
        var downloadOnly = isDownloadOnly(ctx)
        var shuffleHome = isShuffleHomeCategories(ctx)
        var q4k = isQuality4kEnabled(ctx)
        var q1080 = isQuality1080Enabled(ctx)
        var q720 = isQuality720Enabled(ctx)
        var q480 = isQuality480Enabled(ctx)
        var lowBuffer = isLowBufferMode(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#07090F", "#1E273B", 34f, 3)
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        }

        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor("#0B192C"), safeColor("#1E153D"), safeColor("#08272E"))
            ).apply {
                cornerRadius = 24f
                setStroke(2, safeColor("#2E3D59"))
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
        }
        topRow.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX"
            textSize = 17f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        topRow.addView(TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#34D399")); background = pillBg("#0D2B22", 14f)
            setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { marginEnd = dp(ctx, 6) }
        })
        topRow.addView(TextView(ctx).apply {
            text = "ⓘ"
            textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#C084FC")); background = cardBg("#1A1333", "#7C3AED", 14f, 2)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
            setOnClickListener {
                AlertDialog.Builder(ctx)
                    .setTitle("MovieFox Ultra Max")
                    .setMessage("• Multi-Source Parallel Engine (Sflix, Prmovies, HindiDrama, VegaMovies PXL/FSL/GDrive)\n• Smart Title Normalization (All servers show automatically)\n• Zero-Buffer HLS/MP4 Priority")
                    .setPositiveButton("OK", null)
                    .show()
            }
        })
        header.addView(topRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 10) }
        }

        fun statChip(label: String, colorHex: String, marginEndDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = cardBg("#0A0E17", "#26324A", 14f, 2)
                setPadding(dp(ctx, 9), dp(ctx, 6), dp(ctx, 9), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (marginEndDp > 0) marginEnd = dp(ctx, marginEndDp)
                }
            }
            val valTv = TextView(ctx).apply {
                text = "-"; textSize = 13f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex))
            }
            val lblTv = TextView(ctx).apply {
                text = label; textSize = 8.5f; isSingleLine = true; setTextColor(safeColor("#94A3B8"))
            }
            box.addView(valTv); box.addView(lblTv)
            return box to valTv
        }

        val (srvChip, srvValTv) = statChip("SERVERS", "#38BDF8", 6)
        val (catChip, catValTv) = statChip("CATALOGS", "#C084FC", 6)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)
        statsRow.addView(srvChip); statsRow.addView(catChip); statsRow.addView(spdChip)
        header.addView(statsRow)

        root.addView(
            header,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 10) }
        )

        fun refreshStats() {
            val all = SERVERS
            val srvOn = all.count { isServerEnabled(ctx, it.key) }
            srvValTv.text = "$srvOn/${all.size}"
            catValTv.text = "${nuvioList.count { it.enabled }} • $selectedMeta"
            spdValTv.text = "${currentConcurrency}x • ${getPreferredQuality(ctx)}"
        }
        refreshStats()

        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 18f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("Providers", "Streaming", "Catalogs", "Home Page", "CS3 Repos")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title; textSize = 11.5f; isAllCaps = false; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        root.addView(
            HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 8) }
        )

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 6) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search providers..."
            setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#10141E", "#253046", 15f)
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val allOnBtn = Button(ctx).apply {
            text = "ENABLE ALL"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); background = pillBg("#7C3AED", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)).apply { marginEnd = dp(ctx, 5) }
        }
        val allOffBtn = Button(ctx).apply {
            text = "DISABLE ALL"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); background = pillBg("#334155", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36))
        }
        actionRow.addView(searchBox); actionRow.addView(allOnBtn); actionRow.addView(allOffBtn)
        root.addView(actionRow)

        val catFilterRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val catFilterScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(catFilterRow)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 8) }
        }
        val catFilterChips = mutableMapOf<String, TextView>()
        lateinit var applyServerFilter: () -> Unit

        listOf("All", "Movies", "Series", "Anime", "Hindi").forEach { catName ->
            val chip = TextView(ctx).apply {
                text = catName; textSize = 11f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 13), dp(ctx, 5), dp(ctx, 13), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    selectedServerCat = catName
                    catFilterChips.forEach { (k, tv) ->
                        tv.background = if (k == selectedServerCat) pillBg("#7C3AED", 16f) else cardBg("#10141E", "#232C40", 16f)
                        tv.setTextColor(if (k == selectedServerCat) Color.WHITE else safeColor("#94A3B8"))
                    }
                    applyServerFilter()
                }
            }
            chip.background = if (catName == "All") pillBg("#7C3AED", 16f) else cardBg("#10141E", "#232C40", 16f)
            chip.setTextColor(if (catName == "All") Color.WHITE else safeColor("#94A3B8"))
            catFilterChips[catName] = chip
            catFilterRow.addView(chip)
        }
        root.addView(catFilterScroll)

        val contentHeight = dp(ctx, 335)

        lateinit var serverAdapter: ServerListAdapter
        serverAdapter = ServerListAdapter(
            ctx = ctx,
            onToggleChanged = { refreshStats() },
            onMoveUp = { srv ->
                val order = getServerOrder(ctx).toMutableList()
                val idx = order.indexOf(srv.key)
                if (idx > 0) {
                    order[idx] = order[idx - 1].also { order[idx - 1] = order[idx] }
                    saveServerOrder(ctx, order)
                    applyServerFilter()
                }
            },
            onMoveDown = { srv ->
                val order = getServerOrder(ctx).toMutableList()
                val idx = order.indexOf(srv.key)
                if (idx in 0 until order.lastIndex) {
                    order[idx] = order[idx + 1].also { order[idx + 1] = order[idx] }
                    saveServerOrder(ctx, order)
                    applyServerFilter()
                }
            }
        )

        val serversListView = ListView(ctx).apply {
            adapter = serverAdapter
            divider = GradientDrawable().apply { setColor(Color.TRANSPARENT) }
            dividerHeight = dp(ctx, 7)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        applyServerFilter = {
            val orderMap = getServerOrder(ctx).withIndex().associate { it.value to it.index }
            val ordered = SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val byHindi = if (hindiOnly) ordered.filter { it.isHindi || it.isCustom } else ordered
            val byCat = when (selectedServerCat) {
                "All" -> byHindi
                "Hindi" -> byHindi.filter { it.isHindi }
                else -> byHindi.filter { it.category.equals(selectedServerCat, true) }
            }
            val filtered = if (currentQuery.isEmpty()) byCat else byCat.filter {
                it.name.lowercase().contains(currentQuery) || it.tag.lowercase().contains(currentQuery)
            }
            serverAdapter.submitList(filtered)
            refreshStats()
        }
        applyServerFilter()

        allOnBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", true) }
            ed.apply(); applyServerFilter(); safeToast(ctx, "All providers enabled!")
        }
        allOffBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", false) }
            ed.apply(); applyServerFilter(); safeToast(ctx, "All providers disabled!")
        }
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                currentQuery = s?.toString().orEmpty().trim().lowercase()
                applyServerFilter()
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false; visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 36))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val catalogPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val homePane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10.5f; setTextColor(safeColor("#8B93A7")) })
            val sw = Switch(ctx).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col); card.addView(sw)
            parent.addView(card)
        }

        streamPane.addView(TextView(ctx).apply {
            text = "⚡ Preferred Quality"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8"))
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 6))
        })
        makeToggleCard(streamPane, "4K + 1440p", "Ultra HD & QHD streams", q4k) { q4k = it; setQuality4kEnabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "1080p FHD", "Full HD streams", q1080) { q1080 = it; setQuality1080Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "720p HD & Web", "Standard HD & WebRip", q720) { q720 = it; setQuality720Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "480p & Below", "Data saver streams", q480) { q480 = it; setQuality480Enabled(ctx, it); refreshStats() }

        streamPane.addView(TextView(ctx).apply {
            text = "Playback & Performance Controls"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#C084FC"))
            setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 6))
        })
        makeToggleCard(streamPane, "Hindi Providers Only 🇮🇳", "Hide English-only providers, show Hindi links only", hindiOnly) {
            hindiOnly = it; setHindiOnly(ctx, it); applyServerFilter()
        }
        makeToggleCard(streamPane, "Download Only Links", "Prefer direct MP4/MKV links optimized for downloading", downloadOnly) {
            downloadOnly = it; setDownloadOnly(ctx, it)
        }
        makeToggleCard(streamPane, "Zero-Buffer Mode", "Prioritize fast HLS/MP4 streams first", lowBuffer) {
            lowBuffer = it; setLowBufferMode(ctx, it)
        }

        fun makeStepper(title: String, desc: String, initVal: Int, minV: Int, maxV: Int, step: Int, suffix: String, onChange: (Int) -> Unit) {
            var v = initVal
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                    .apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10.5f; setTextColor(safeColor("#8B93A7")) })
            val valTv = TextView(ctx).apply {
                text = "$v$suffix"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8"))
                setPadding(dp(ctx, 9), 0, dp(ctx, 9), 0)
            }
            card.addView(col)
            card.addView(Button(ctx).apply {
                text = "−"; setTextColor(Color.WHITE); background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener { if (v - step >= minV) { v -= step; valTv.text = "$v$suffix"; onChange(v) } }
            })
            card.addView(valTv)
            card.addView(Button(ctx).apply {
                text = "+"; setTextColor(Color.WHITE); background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener { if (v + step <= maxV) { v += step; valTv.text = "$v$suffix"; onChange(v) } }
            })
            streamPane.addView(card)
        }

        makeStepper("Concurrent Threads", "Up to 50 parallel requests (10-15 optimal)", currentConcurrency, 2, 50, 1, "x") {
            currentConcurrency = it; setConcurrency(ctx, it); refreshStats()
        }
        makeStepper("Server Timeout", "Wait limit per source", currentTimeout, 4, 20, 1, "s") {
            currentTimeout = it; setTimeoutSeconds(ctx, it)
        }

        val metaCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { bottomMargin = dp(ctx, 8) }
        }
        metaCard.addView(TextView(ctx).apply { text = "Active Metadata Source"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val metaRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8) }
        }
        val metaChips = mutableMapOf<String, TextView>()
        fun refreshMetaChips() {
            metaChips.forEach { (m, tv) ->
                tv.background = if (m == selectedMeta) pillBg("#7C3AED", 14f) else cardBg("#0B0E16", "#232C40", 14f)
                tv.setTextColor(if (m == selectedMeta) Color.WHITE else safeColor("#94A3B8"))
            }
        }
        METADATA_SOURCES.forEach { m ->
            val tv = TextView(ctx).apply {
                text = m; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 14), dp(ctx, 6), dp(ctx, 14), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener { selectedMeta = m; setMetadataSource(ctx, m); refreshMetaChips(); refreshStats() }
            }
            metaChips[m] = tv; metaRow.addView(tv)
        }
        refreshMetaChips()
        metaCard.addView(metaRow)
        catalogPane.addView(metaCard)

        val nuvioAddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#131826", "#314161", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        nuvioAddBox.addView(TextView(ctx).apply { text = "+ Add Stremio / Nuvio manifest.json"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#C084FC")) })

        val presetRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val nuvioListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderNuvioList: () -> Unit

        PRESET_CATALOGS.forEach { (pName, pUrl) ->
            presetRow.addView(TextView(ctx).apply {
                text = "+ $pName"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#E9D5FF"))
                background = cardBg("#241738", "#9333EA", 12f)
                setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    if (nuvioList.none { it.manifestUrl.equals(pUrl, true) }) {
                        nuvioList.add(NuvioManifestConfig(System.currentTimeMillis().toString(), pName, pUrl, true))
                        saveNuvioManifests(ctx, nuvioList); renderNuvioList(); refreshStats()
                        safeToast(ctx, "$pName added!")
                    } else safeToast(ctx, "$pName already added")
                }
            })
        }
        nuvioAddBox.addView(
            HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(presetRow) },
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
        )

        val labelInput = EditText(ctx).apply {
            hint = "Catalog Name (optional)"; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val manifestUrlInput = EditText(ctx).apply {
            hint = "https://.../manifest.json"; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#323E57", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val manifestRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(manifestUrlInput)
            addView(Button(ctx).apply {
                text = "PASTE"; textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#E9D5FF")); background = cardBg("#241738", "#9333EA", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 62), dp(ctx, 38))
                setOnClickListener {
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                    if (clip.isNotBlank()) { manifestUrlInput.setText(clip); manifestUrlInput.setSelection(clip.length) }
                }
            })
        }

        renderNuvioList = {
            nuvioListHolder.removeAllViews()
            if (nuvioList.isEmpty()) {
                nuvioListHolder.addView(TextView(ctx).apply {
                    text = "No custom Stremio/Nuvio manifests added yet."
                    textSize = 11.5f; setTextColor(safeColor("#64748B")); gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14))
                })
            } else {
                nuvioList.forEachIndexed { idx, item ->
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 20f)
                        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
                    }
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(ctx).apply {
                        text = item.label; textSize = 13f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(ctx).apply {
                        text = "DELETE"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#F87171"))
                        background = pillBg("#2A1519", 12f); setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 8) }
                        setOnClickListener { nuvioList.removeAt(idx); saveNuvioManifests(ctx, nuvioList); renderNuvioList(); refreshStats() }
                    })
                    row.addView(Switch(ctx).apply {
                        isChecked = item.enabled
                        setOnCheckedChangeListener { _, checked -> item.enabled = checked; saveNuvioManifests(ctx, nuvioList); refreshStats() }
                    })
                    card.addView(row)
                    card.addView(TextView(ctx).apply {
                        text = item.manifestUrl; textSize = 10f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                        setTextColor(safeColor("#94A3B8")); setPadding(0, dp(ctx, 4), 0, 0)
                    })
                    nuvioListHolder.addView(card)
                }
            }
        }

        nuvioAddBox.addView(labelInput); nuvioAddBox.addView(manifestRow)
        nuvioAddBox.addView(Button(ctx).apply {
            text = "+ ADD MANIFEST.JSON URL"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#7C3AED", 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38))
            setOnClickListener {
                val raw = manifestUrlInput.text.toString().trim()
                if (raw.isBlank()) { safeToast(ctx, "Manifest URL dalein"); return@setOnClickListener }
                val norm = normalizeNuvioManifestUrl(raw)
                val lbl = labelInput.text.toString().trim().ifEmpty { deriveManifestLabel(norm) }
                nuvioList.add(NuvioManifestConfig(System.currentTimeMillis().toString(), lbl, norm, true))
                saveNuvioManifests(ctx, nuvioList); labelInput.setText(""); manifestUrlInput.setText("")
                renderNuvioList(); refreshStats(); safeToast(ctx, "$lbl added!")
            }
        })
        catalogPane.addView(nuvioAddBox); catalogPane.addView(nuvioListHolder)
        renderNuvioList()

        makeToggleCard(homePane, "Shuffle Categories", "Randomize home screen categories on every load", shuffleHome) {
            shuffleHome = it; setShuffleHomeCategories(ctx, it)
        }
        val homeCatHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderHomeCats: () -> Unit

        fun showRenameCategoryDialog(cat: HomeCategoryItem) {
            val inp = EditText(ctx).apply {
                setText(cat.title); setSelection(cat.title.length)
                setTextColor(Color.WHITE); background = cardBg("#0B0E16", "#2B354B", 12f)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 8))
                background = ColorDrawable(safeColor("#0F131C"))
                addView(TextView(ctx).apply { text = "Rename Category"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(ctx, 8)) })
                addView(inp)
            }
            AlertDialog.Builder(ctx).setView(box)
                .setPositiveButton("Rename") { _, _ ->
                    val newName = inp.text.toString().trim()
                    if (newName.isNotBlank()) { cat.title = newName; saveHomeCategories(ctx, homeCats); renderHomeCats() }
                }
                .setNegativeButton("Cancel", null)
                .show()
        }

        renderHomeCats = {
            homeCatHolder.removeAllViews()
            homeCats.forEachIndexed { idx, cat ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    background = cardBg("#121621", "#232C40", 18f)
                    setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
                }
                val titleCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                titleCol.addView(TextView(ctx).apply {
                    text = cat.title; textSize = 13f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD); setTextColor(if (cat.visible) Color.WHITE else safeColor("#64748B"))
                })
                titleCol.addView(TextView(ctx).apply {
                    text = "${cat.badge} • Shuffle ${if (shuffleHome) "on" else "off"}"
                    textSize = 9.5f; setTextColor(safeColor("#94A3B8"))
                })
                card.addView(titleCol)
                card.addView(TextView(ctx).apply {
                    text = "↑"; textSize = 15f; setTextColor(safeColor(if (idx > 0) "#38BDF8" else "#334155"))
                    setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 7), dp(ctx, 4))
                    if (idx > 0) setOnClickListener {
                        homeCats[idx] = homeCats[idx - 1].also { homeCats[idx - 1] = homeCats[idx] }
                        saveHomeCategories(ctx, homeCats); renderHomeCats()
                    }
                })
                card.addView(TextView(ctx).apply {
                    text = "↓"; textSize = 15f; setTextColor(safeColor(if (idx < homeCats.lastIndex) "#38BDF8" else "#334155"))
                    setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 7), dp(ctx, 4))
                    if (idx < homeCats.lastIndex) setOnClickListener {
                        homeCats[idx] = homeCats[idx + 1].also { homeCats[idx + 1] = homeCats[idx] }
                        saveHomeCategories(ctx, homeCats); renderHomeCats()
                    }
                })
                card.addView(TextView(ctx).apply {
                    text = "✏️"; textSize = 12.5f; setPadding(dp(ctx, 6), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    setOnClickListener { showRenameCategoryDialog(cat) }
                })
                card.addView(Switch(ctx).apply {
                    isChecked = cat.visible
                    setOnCheckedChangeListener { _, checked -> cat.visible = checked; saveHomeCategories(ctx, homeCats); renderHomeCats() }
                })
                homeCatHolder.addView(card)
            }
        }
        renderHomeCats()
        homePane.addView(homeCatHolder)

        homePane.addView(Button(ctx).apply {
            text = "+ Add a new category"; textSize = 11f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#7C3AED", 14f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38)).apply { topMargin = dp(ctx, 6) }
            setOnClickListener {
                val inp = EditText(ctx).apply {
                    hint = "Category name (e.g. Sci-Fi & Fantasy)"
                    setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE)
                    background = cardBg("#0B0E16", "#2B354B", 12f)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                }
                val box = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; setPadding(dp(ctx, 18), dp(ctx, 14), dp(ctx, 18), dp(ctx, 8))
                    background = ColorDrawable(safeColor("#0F131C"))
                    addView(TextView(ctx).apply { text = "Add Custom Home Category"; textSize = 14f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE); setPadding(0, 0, 0, dp(ctx, 8)) })
                    addView(inp)
                }
                AlertDialog.Builder(ctx).setView(box)
                    .setPositiveButton("Add") { _, _ ->
                        val title = inp.text.toString().trim()
                        if (title.isNotBlank()) {
                            homeCats.add(HomeCategoryItem("custom_${System.currentTimeMillis()}", title, true, "Custom"))
                            saveHomeCategories(ctx, homeCats); renderHomeCats()
                        }
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
        })

        homePane.addView(Button(ctx).apply {
            text = "RESET DEFAULT HOME CATEGORIES"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#334155", 14f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38)).apply { topMargin = dp(ctx, 6) }
            setOnClickListener {
                homeCats.clear(); homeCats.addAll(DEFAULT_HOME_CATEGORIES.map { it.copy() })
                saveHomeCategories(ctx, homeCats); renderHomeCats(); safeToast(ctx, "Home categories reset!")
            }
        })

        val cs3AddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#131826", "#2563EB", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) }
        }
        cs3AddBox.addView(TextView(ctx).apply { text = "+ Add CS3 Repository / .cs3 & Fetch Servers"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8")) })
        val cs3NameInput = EditText(ctx).apply {
            hint = "Repo Name (optional)"; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
        }
        val cs3UrlInput = EditText(ctx).apply {
            hint = "https://.../repo.json or StreamHubOne.cs3"; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#323E57", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val cs3UrlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(cs3UrlInput)
            addView(Button(ctx).apply {
                text = "PASTE"; textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#BAE6FD")); background = cardBg("#0D2338", "#0284C7", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 62), dp(ctx, 38))
                setOnClickListener {
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                    if (clip.isNotBlank()) { cs3UrlInput.setText(clip); cs3UrlInput.setSelection(clip.length) }
                }
            })
        }

        val cs3RepoListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderCs3Repos: () -> Unit

        fun fetchAndApplyCs3(repo: Cs3RepoConfig) {
            safeToast(ctx, "Fetching CS3 servers...")
            dialogScope.launch {
                val fetched = fetchServersFromCs3Repo(repo)
                val prefix = "cs3_${repo.id.takeLast(6)}_"
                val updated = (cachedCs3Servers.filterNot { it.key.startsWith(prefix) } + fetched).distinctBy { it.key }
                saveCs3Servers(ctx, updated)
                repo.serverCount = fetched.size
                saveCs3Repos(ctx, cs3RepoList)
                if (isContextAlive(ctx)) {
                    renderCs3Repos(); applyServerFilter(); safeToast(ctx, "${fetched.size} CS3 servers loaded!")
                }
            }
        }

        renderCs3Repos = {
            cs3RepoListHolder.removeAllViews()
            if (cs3RepoList.isEmpty()) {
                cs3RepoListHolder.addView(TextView(ctx).apply {
                    text = "No CS3 repository added yet."; textSize = 11.5f; setTextColor(safeColor("#64748B")); gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 14), dp(ctx, 12), dp(ctx, 14))
                })
            } else {
                cs3RepoList.forEachIndexed { idx, repo ->
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 20f)
                        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
                    }
                    val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    row.addView(TextView(ctx).apply {
                        text = "${repo.name} (${repo.serverCount})"; textSize = 13f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    row.addView(TextView(ctx).apply {
                        text = "FETCH"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8"))
                        background = pillBg("#0D2338", 12f); setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                        setOnClickListener { fetchAndApplyCs3(repo) }
                    })
                    row.addView(TextView(ctx).apply {
                        text = "DELETE"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#F87171"))
                        background = pillBg("#2A1519", 12f); setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        setOnClickListener {
                            val removed = cs3RepoList.removeAt(idx)
                            val prefix = "cs3_${removed.id.takeLast(6)}_"
                            saveCs3Servers(ctx, cachedCs3Servers.filterNot { it.key.startsWith(prefix) })
                            saveCs3Repos(ctx, cs3RepoList); renderCs3Repos(); applyServerFilter()
                        }
                    })
                    card.addView(row)
                    card.addView(TextView(ctx).apply {
                        text = repo.repoUrl; textSize = 10f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                        setTextColor(safeColor("#94A3B8")); setPadding(0, dp(ctx, 4), 0, 0)
                    })
                    cs3RepoListHolder.addView(card)
                }
            }
        }

        cs3AddBox.addView(cs3NameInput); cs3AddBox.addView(cs3UrlRow)
        cs3AddBox.addView(Button(ctx).apply {
            text = "ADD & FETCH CS3 SERVERS"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#0284C7", 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38))
            setOnClickListener {
                var u = cs3UrlInput.text.toString().trim()
                if (u.isBlank()) { safeToast(ctx, "CS3 repo URL dalein"); return@setOnClickListener }
                if (!u.startsWith("http")) u = "https://$u"
                val n = cs3NameInput.text.toString().trim().ifEmpty { u.substringBefore("?").substringAfterLast("/").removeSuffix(".cs3").ifBlank { deriveManifestLabel(u) } }
                val newRepo = Cs3RepoConfig(System.currentTimeMillis().toString(), n, u, true, 0)
                cs3RepoList.add(newRepo); saveCs3Repos(ctx, cs3RepoList)
                cs3NameInput.setText(""); cs3UrlInput.setText(""); renderCs3Repos(); fetchAndApplyCs3(newRepo)
            }
        })
        cs3Pane.addView(cs3AddBox); cs3Pane.addView(cs3RepoListHolder)
        renderCs3Repos()

        val scrollPanes = listOf(streamPane, catalogPane, homePane, cs3Pane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            if (idx == 0) {
                serversListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                catFilterScroll.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
            } else {
                serversListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                catFilterScroll.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollPanes.forEachIndexed { i, p -> p.visibility = if (i == idx - 1) View.VISIBLE else View.GONE }
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg("#7C3AED", 15f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(safeColor("#8B93A7"))
                }
            }
        }
        tabButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectTab(i) } }
        selectTab(0)

        root.addView(serversListView)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 10) }
        }
        footer.addView(Button(ctx).apply {
            text = "RESET"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener {
                prefs(ctx).edit().clear().apply()
                applyServerFilter()
                safeToast(ctx, "Settings reset to default!")
                runCatching { dialog.dismiss() }
            }
        })
        footer.addView(Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#7C3AED", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveNuvioManifests(ctx, nuvioList)
                saveCs3Repos(ctx, cs3RepoList)
                saveHomeCategories(ctx, homeCats)
                setQuality4kEnabled(ctx, q4k)
                setQuality1080Enabled(ctx, q1080)
                setQuality720Enabled(ctx, q720)
                setQuality480Enabled(ctx, q480)
                setLowBufferMode(ctx, lowBuffer)
                setConcurrency(ctx, currentConcurrency)
                setTimeoutSeconds(ctx, currentTimeout)
                setMetadataSource(ctx, selectedMeta)
                setHindiOnly(ctx, hindiOnly)
                setDownloadOnly(ctx, downloadOnly)
                setShuffleHomeCategories(ctx, shuffleHome)
                safeToast(ctx, "Settings saved and applied")
                runCatching { dialog.dismiss() }
            }
        })
        root.addView(footer)

        runCatching {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

