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
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.ZipInputStream

object MovieFoxSettingsDialog {

    private const val LOG_TAG = "MovieFoxDiag"
    private const val PREFS_NAME = "moviefox_ultra_max_v18"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_EXT_PREFIX = "mf_ext_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_NUVIO_MANIFESTS = "mf_nuvio_manifests_json"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_COLLECTION_JSON = "mf_personal_collection_json"
    private const val KEY_THEME = "mf_ui_theme"
    private const val KEY_API_HOST = "mf_netnaija_api_host"
    private const val KEY_PLAYER = "mf_preferred_player"
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

    const val PERMANENT_STREMIO_MANIFEST =
        "https://apps.soluserv.es/stremio_catalog_plus/c.8e2ce47b1df7502fd7c8f45140d5eb89691d641a/manifest.json"

    const val SKY_UNIVERSE_REPO_URL =
        "https://raw.githubusercontent.com/akashdh11/sky-universe/refs/heads/main/mega_repo.json"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

    val NETNAIJA_API_HOSTS = listOf(
        "api6.aoneroom.com",
        "api5.aoneroom.com",
        "api4.aoneroom.com",
        "api4sg.aoneroom.com",
        "api3.aoneroom.com"
    )

    val PLAYER_OPTIONS = listOf(
        "Default (ExoPlayer)",
        "MX Player",
        "MX Player Pro",
        "VLC Player"
    )

    data class ThemePalette(
        val id: String,
        val name: String,
        val primaryHex: String,
        val gradStart: String,
        val gradMid: String,
        val gradEnd: String
    )

    val UI_THEMES = listOf(
        ThemePalette("purple", "Neon Purple", "#7C3AED", "#0B192C", "#1E153D", "#08272E"),
        ThemePalette("blue", "Cyber Blue", "#2563EB", "#09182D", "#0E294B", "#082538"),
        ThemePalette("emerald", "Emerald", "#059669", "#071F1B", "#0C2D24", "#08242B"),
        ThemePalette("crimson", "Crimson", "#E11D48", "#230B14", "#33101F", "#1B0E24"),
        ThemePalette("amber", "Gold Amber", "#D97706", "#211608", "#2E1C0A", "#1A1423")
    )

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

    data class ExtractorItem(
        val key: String,
        val name: String,
        val hosts: String,
        val tag: String
    )

    data class PersonalCollectionItem(
        val id: String,
        val title: String,
        val type: String,
        val note: String = ""
    )

    data class ServerHealthInfo(
        val key: String,
        val status: String,
        val httpCode: Int = 0,
        val latencyMs: Long = -1L,
        val detail: String = "",
        val updatedAt: String = ""
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
        var enabled: Boolean = true,
        val isPermanent: Boolean = false
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

    private var cachedPrefs: SharedPreferences? = null
    private var cachedManifests: MutableList<NuvioManifestConfig>? = null
    private var cachedCs3Repos: MutableList<Cs3RepoConfig>? = null
    private val cachedCs3Servers = CopyOnWriteArrayList<ServerItem>()
    private val errorLogRing = CopyOnWriteArrayList<String>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()
    private var cs3Loaded = false

    val VEGA_ALLOWED_HOSTS = listOf("pixeldrain", "fsl2", "fsl", "gdrive", "gdflix", "hubcloud")

    val EXTRACTORS = listOf(
        ExtractorItem("hubcloud", "HubCloud", "hubcloud.*", "DIRECT"),
        ExtractorItem("vcloud", "VCloud", "vcloud.*", "DIRECT"),
        ExtractorItem("gdflix", "GDFlix", "gdflix.*", "GDRIVE"),
        ExtractorItem("gdlink", "GDLink", "gdlink.*", "GDRIVE"),
        ExtractorItem("pixeldrain", "Pixeldrain / Dev", "pixeldrain.com / .dev", "FAST"),
        ExtractorItem("fastdlserver", "FastDL / FSL / FSL2", "fastdlserver.*", "FSL"),
        ExtractorItem("hubdrive", "Hubdrive", "hubdrive.*", "DRIVE"),
        ExtractorItem("driveleech", "Driveleech / Driveseed", "driveleech.* / driveseed.*", "DRIVE"),
        ExtractorItem("gofile", "Gofile", "gofile.io", "DIRECT"),
        ExtractorItem("voe", "Voe", "voe.sx", "HLS"),
        ExtractorItem("streamtape", "StreamTape", "streamtape.com / stape.*", "MP4"),
        ExtractorItem("nxstreamhub", "NxStreamHub", "nxstreamhub.workers.dev", "HLS"),
        ExtractorItem("itsnitrox", "Nitrox", "itsnitrox.tech", "HLS"),
        ExtractorItem("vibuxer", "Vibuxer", "vibuxer.com", "HLS"),
        ExtractorItem("gokuhd", "GokuHD / NexDrive", "nexdrive.*", "MULTI"),
        ExtractorItem("wootly", "Wootly / Videostr / MegaPlay", "wootly / videostr / megaplay", "EMBED")
    )

    val BASE_SERVERS = listOf(
        ServerItem("moviefox", "MovieFox Main", "HOME/TMDB", "https://moviefox.pro/", "#7C3AED", false, true, "Movies"),
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
        ServerItem("nuvio", "Nuvio / Stremio", "MANIFEST", PERMANENT_STREMIO_MANIFEST, "#A855F7", false, true, "Series"),
        ServerItem("moviebox", "MovieBox / NetNaija API", "BFF", "https://api3.aoneroom.com/wefeed-h5api-bff", "#34D399", false, true, "Movies"),
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
        "Soluserv Catalog Plus" to PERMANENT_STREMIO_MANIFEST,
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

    fun isJwtExpired(token: String?): Boolean {
        if (token.isNullOrBlank()) return true
        return try {
            val payload = token.split(".").getOrNull(1) ?: return true
            val decoded = String(Base64.decode(payload, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP))
            val exp = JSONObject(decoded).optLong("exp", 0L)
            exp <= (System.currentTimeMillis() / 1000L)
        } catch (_: Exception) {
            true
        }
    }

    fun reportServerStatus(key: String, status: String, httpCode: Int = 0, latencyMs: Long = -1L, detail: String = "") {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        serverHealthMap[key.lowercase()] = ServerHealthInfo(key.lowercase(), status, httpCode, latencyMs, detail, ts)
    }

    fun getServerHealth(key: String): ServerHealthInfo? = serverHealthMap[key.lowercase()]

    fun logSiteError(source: String, url: String, error: Throwable?, extra: String = "") {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val errMsg = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val line = "[$ts] [$source] ${if (extra.isNotBlank()) "$extra | " else ""}$url -> $errMsg".trim()
        Log.e(LOG_TAG, line, error)
        errorLogRing.add(line)
        while (errorLogRing.size > 150) errorLogRing.removeAt(0)

        val status = when {
            extra.contains("401") || extra.contains("token", true) || extra.contains("expired", true) -> "TOKEN EXPIRED"
            extra.contains("403") || extra.contains("503") || extra.contains("CF", true) -> "CF BLOCKED"
            error?.message?.contains("timeout", true) == true -> "TIMEOUT"
            else -> "ERROR"
        }
        reportServerStatus(source, status, 0, -1L, extra.ifBlank { errMsg })
    }

    fun getDiagnosticLogs(): List<String> = errorLogRing.toList()
    fun clearDiagnosticLogs() = errorLogRing.clear()

    private suspend fun probeServerLive(srv: ServerItem): ServerHealthInfo = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val conn = (URL(srv.probeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 5000
                readTimeout = 5000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            conn.disconnect()
            val status = when (code) {
                in 200..399 -> "LIVE"
                401 -> "TOKEN EXPIRED"
                403, 503 -> "CF BLOCKED"
                else -> "HTTP $code"
            }
            val info = ServerHealthInfo(
                srv.key, status, code, ms, srv.probeUrl,
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            )
            serverHealthMap[srv.key.lowercase()] = info
            info
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val info = ServerHealthInfo(
                srv.key, "DOWN", 0, ms, e.message ?: "Connection failed",
                SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            )
            serverHealthMap[srv.key.lowercase()] = info
            info
        }
    }

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

    fun getSelectedTheme(ctx: Context): ThemePalette {
        val id = prefs(ctx).getString(KEY_THEME, "purple") ?: "purple"
        return UI_THEMES.firstOrNull { it.id == id } ?: UI_THEMES.first()
    }
    fun setSelectedTheme(ctx: Context, id: String) = prefs(ctx).edit().putString(KEY_THEME, id).apply()

    fun getSelectedApiHost(ctx: Context): String =
        prefs(ctx).getString(KEY_API_HOST, "api3.aoneroom.com") ?: "api3.aoneroom.com"
    fun setSelectedApiHost(ctx: Context, host: String) = prefs(ctx).edit().putString(KEY_API_HOST, host).apply()

    fun getPreferredPlayer(ctx: Context): String =
        prefs(ctx).getString(KEY_PLAYER, PLAYER_OPTIONS.first()) ?: PLAYER_OPTIONS.first()
    fun setPreferredPlayer(ctx: Context, player: String) = prefs(ctx).edit().putString(KEY_PLAYER, player).apply()

    fun isExtractorEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_EXT_PREFIX${key.lowercase()}", true)
    fun setExtractorEnabled(ctx: Context, key: String, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("$KEY_EXT_PREFIX${key.lowercase()}", enabled).apply()

    fun getPersonalCollection(ctx: Context): MutableList<PersonalCollectionItem> {
        val raw = prefs(ctx).getString(KEY_COLLECTION_JSON, "[]") ?: "[]"
        val out = mutableListOf<PersonalCollectionItem>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val t = o.optString("title").trim()
                if (t.isNotBlank()) {
                    out.add(PersonalCollectionItem(o.optString("id", "$i"), t, o.optString("type", "Movie"), o.optString("note", "")))
                }
            }
        }
        return out
    }

    fun savePersonalCollection(ctx: Context, list: List<PersonalCollectionItem>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().put("id", it.id).put("title", it.title).put("type", it.type).put("note", it.note))
        }
        prefs(ctx).edit().putString(KEY_COLLECTION_JSON, arr.toString()).apply()
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

    fun reportServerResult(ctx: Context?, key: String, success: Boolean, latencyMs: Long = 0L) {
        reportServerStatus(key, if (success) "LIVE" else "ERROR", if (success) 200 else 0, latencyMs)
    }

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
        val raw = prefs(ctx).getString(KEY_CS3_REPOS, null)
        val list = mutableListOf<Cs3RepoConfig>()
        if (!raw.isNullOrBlank()) {
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
        }
        if (list.none { it.repoUrl.equals(SKY_UNIVERSE_REPO_URL, ignoreCase = true) }) {
            list.add(0, Cs3RepoConfig("sky_universe_mega", "Sky Universe Mega Repo", SKY_UNIVERSE_REPO_URL, true, 0))
        }
        cachedCs3Repos = list
        saveCs3Repos(ctx, list)
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
        val list = mutableListOf<NuvioManifestConfig>()
        val raw = prefs(ctx).getString(KEY_NUVIO_MANIFESTS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val url = obj.optString("manifestUrl").trim()
                    if (url.isNotBlank()) {
                        val isPerm = url.equals(PERMANENT_STREMIO_MANIFEST, ignoreCase = true) || obj.optBoolean("isPermanent", false)
                        list.add(
                            NuvioManifestConfig(
                                id = obj.optString("id", System.currentTimeMillis().toString()),
                                label = if (isPerm) "Soluserv Catalog Plus" else obj.optString("label", deriveManifestLabel(url)),
                                manifestUrl = url,
                                enabled = if (isPerm) true else obj.optBoolean("enabled", true),
                                isPermanent = isPerm
                            )
                        )
                    }
                }
            }
        }
        if (list.none { it.manifestUrl.equals(PERMANENT_STREMIO_MANIFEST, ignoreCase = true) }) {
            list.add(0, NuvioManifestConfig("perm_soluserv_catalog", "Soluserv Catalog Plus", PERMANENT_STREMIO_MANIFEST, true, true))
        }
        PRESET_CATALOGS.forEachIndexed { idx, (pName, pUrl) ->
            if (list.none { it.manifestUrl.equals(pUrl, ignoreCase = true) }) {
                list.add(NuvioManifestConfig("preset_${idx + 1}", pName, pUrl, true, pUrl == PERMANENT_STREMIO_MANIFEST))
            }
        }
        cachedManifests = list
        saveNuvioManifests(ctx, list)
        return list
    }

    fun saveNuvioManifests(ctx: Context, list: List<NuvioManifestConfig>) {
        val ensured = list.toMutableList()
        if (ensured.none { it.manifestUrl.equals(PERMANENT_STREMIO_MANIFEST, ignoreCase = true) }) {
            ensured.add(0, NuvioManifestConfig("perm_soluserv_catalog", "Soluserv Catalog Plus", PERMANENT_STREMIO_MANIFEST, true, true))
        }
        ensured.forEach {
            if (it.manifestUrl.equals(PERMANENT_STREMIO_MANIFEST, ignoreCase = true)) it.enabled = true
        }
        cachedManifests = ensured
        val arr = JSONArray()
        ensured.forEach { m ->
            arr.put(JSONObject().put("id", m.id).put("label", m.label).put("manifestUrl", m.manifestUrl).put("enabled", m.enabled).put("isPermanent", m.isPermanent))
        }
        prefs(ctx).edit().putString(KEY_NUVIO_MANIFESTS, arr.toString()).apply()
    }

    fun getCustomSites(ctx: Context): List<CustomExternalSite> =
        getNuvioManifests(ctx).map { CustomExternalSite(it.id, it.label, it.manifestUrl, it.enabled) }

    fun deriveManifestLabel(rawUrl: String): String = try {
        if (rawUrl.contains("soluserv.es", true)) "Soluserv Catalog Plus"
        else {
            val host = URI(normalizeNuvioManifestUrl(rawUrl)).host.orEmpty().removePrefix("www.")
            host.replaceFirstChar { it.uppercase() }.ifBlank { "Catalog Addon" }
        }
    } catch (_: Exception) {
        "Catalog Addon"
    }

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 7000): ByteArray? = try {
        val conn = (URL(toRawGithubUrl(urlStr)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CF_BYPASS_UA)
            setRequestProperty("Accept", "application/json, application/octet-stream, text/plain, */*")
        }
        val bytes = if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else {
            logSiteError("CS3Repo", urlStr, null, "HTTP ${conn.responseCode}")
            null
        }
        conn.disconnect()
        bytes
    } catch (e: Exception) {
        logSiteError("CS3Repo", urlStr, e, "readHttpBytes failed")
        null
    }

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
            val statusTv: TextView,
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
                val statusTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 5) }
                }
                topLine.addView(nameTv); topLine.addView(tagTv); topLine.addView(statusTv)

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

                holder = VH(card, avatarTv, nameTv, tagTv, statusTv, urlTv, upBtn, downBtn, sw)
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

            val health = getServerHealth(srv.key)
            if (health != null) {
                holder.statusTv.visibility = View.VISIBLE
                holder.statusTv.text = if (health.latencyMs > 0) "${health.status} ${health.latencyMs}ms" else health.status
                val (fg, bg) = when {
                    health.status == "LIVE" -> "#34D399" to "#0D2B22"
                    health.status.contains("TOKEN") -> "#FBBF24" to "#2D210B"
                    else -> "#F87171" to "#2D1216"
                }
                holder.statusTv.setTextColor(safeColor(fg))
                holder.statusTv.background = pillBg(bg, 8f)
            } else {
                holder.statusTv.visibility = View.GONE
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
        val collectionList = getPersonalCollection(ctx)
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        var activeTheme = getSelectedTheme(ctx)
        var selectedApiHost = getSelectedApiHost(ctx)
        var selectedPlayer = getPreferredPlayer(ctx)
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
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        fun applyHeaderTheme() {
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = 24f
                setStroke(2, safeColor(activeTheme.primaryHex))
            }
        }
        applyHeaderTheme()

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
            setTextColor(safeColor("#C084FC")); background = cardBg("#1A1333", activeTheme.primaryHex, 14f, 2)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 28), dp(ctx, 28))
            setOnClickListener {
                val recentErrs = getDiagnosticLogs().takeLast(6).joinToString("\n").ifBlank { "All systems healthy." }
                AlertDialog.Builder(ctx)
                    .setTitle("MovieFox Ultra Max")
                    .setMessage("• API Host: $selectedApiHost\n• Player: $selectedPlayer\n• Theme: ${activeTheme.name}\n• Stremio Addon: Soluserv Catalog Plus\n\nRecent Logs:\n$recentErrs")
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
        val tabTitles = listOf("Providers", "Extractors", "Streaming", "Collection", "Catalogs", "Home Page", "CS3 Repos", "Health & Logs")
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
            text = "ALL ON"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); background = pillBg(activeTheme.primaryHex, 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)).apply { marginEnd = dp(ctx, 5) }
        }
        val allOffBtn = Button(ctx).apply {
            text = "ALL OFF"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
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
                        tv.background = if (k == selectedServerCat) pillBg(activeTheme.primaryHex, 16f) else cardBg("#10141E", "#232C40", 16f)
                        tv.setTextColor(if (k == selectedServerCat) Color.WHITE else safeColor("#94A3B8"))
                    }
                    applyServerFilter()
                }
            }
            chip.background = if (catName == "All") pillBg(activeTheme.primaryHex, 16f) else cardBg("#10141E", "#232C40", 16f)
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
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val collectionPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val catalogPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val homePane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // --- Extractors Pane ---
        extractorPane.addView(TextView(ctx).apply {
            text = "Extractor.kt Host Resolvers (${EXTRACTORS.size} hosts)"
            textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8"))
            setPadding(dp(ctx, 4), 0, dp(ctx, 4), dp(ctx, 8))
        })
        EXTRACTORS.forEach { ext ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 18f)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = "${ext.name}  [${ext.tag}]"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = ext.hosts; textSize = 10f; setTextColor(safeColor("#94A3B8")) })
            val sw = Switch(ctx).apply {
                isChecked = isExtractorEnabled(ctx, ext.key)
                setOnCheckedChangeListener { _, checked -> setExtractorEnabled(ctx, ext.key, checked) }
            }
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col); card.addView(sw)
            extractorPane.addView(card)
        }

        // --- Streaming Pane ---
        val themeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        themeCard.addView(TextView(ctx).apply { text = "🎨 UI Colour Theme"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val themeBtns = mutableMapOf<String, TextView>()
        UI_THEMES.forEach { tp ->
            val tv = TextView(ctx).apply {
                text = tp.name; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    activeTheme = tp
                    setSelectedTheme(ctx, tp.id)
                    applyHeaderTheme()
                    themeBtns.forEach { (id, v) ->
                        val p = UI_THEMES.first { it.id == id }
                        v.background = if (id == activeTheme.id) pillBg(p.primaryHex, 14f) else cardBg("#0B0E16", "#232C40", 14f)
                        v.setTextColor(if (id == activeTheme.id) Color.WHITE else safeColor("#94A3B8"))
                    }
                    safeToast(ctx, "Theme: ${tp.name}")
                }
            }
            tv.background = if (tp.id == activeTheme.id) pillBg(tp.primaryHex, 14f) else cardBg("#0B0E16", "#232C40", 14f)
            tv.setTextColor(if (tp.id == activeTheme.id) Color.WHITE else safeColor("#94A3B8"))
            themeBtns[tp.id] = tv; themeRow.addView(tv)
        }
        themeCard.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(themeRow) })
        streamPane.addView(themeCard)

        val apiCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        apiCard.addView(TextView(ctx).apply { text = "🌐 NetNaija / MovieBox API Host"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val apiGroup = RadioGroup(ctx).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(ctx, 6), 0, 0) }
        NETNAIJA_API_HOSTS.forEach { host ->
            val rb = RadioButton(ctx).apply {
                text = host; setTextColor(Color.WHITE); textSize = 12f
                isChecked = host.equals(selectedApiHost, true)
                setOnClickListener { selectedApiHost = host; setSelectedApiHost(ctx, host) }
            }
            apiGroup.addView(rb)
        }
        apiCard.addView(apiGroup)
        streamPane.addView(apiCard)

        val playerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        playerCard.addView(TextView(ctx).apply { text = "▶ Preferred External Player"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val playerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val playerBtns = mutableMapOf<String, TextView>()
        PLAYER_OPTIONS.forEach { pl ->
            val tv = TextView(ctx).apply {
                text = pl; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    selectedPlayer = pl; setPreferredPlayer(ctx, pl)
                    playerBtns.forEach { (k, v) ->
                        v.background = if (k == selectedPlayer) pillBg(activeTheme.primaryHex, 14f) else cardBg("#0B0E16", "#232C40", 14f)
                        v.setTextColor(if (k == selectedPlayer) Color.WHITE else safeColor("#94A3B8"))
                    }
                }
            }
            tv.background = if (pl == selectedPlayer) pillBg(activeTheme.primaryHex, 14f) else cardBg("#0B0E16", "#232C40", 14f)
            tv.setTextColor(if (pl == selectedPlayer) Color.WHITE else safeColor("#94A3B8"))
            playerBtns[pl] = tv; playerRow.addView(tv)
        }
        playerCard.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(playerRow) })
        streamPane.addView(playerCard)

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

        makeToggleCard(streamPane, "4K + 1440p", "Ultra HD & QHD streams", q4k) { q4k = it; setQuality4kEnabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "1080p FHD", "Full HD streams", q1080) { q1080 = it; setQuality1080Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "720p HD & Web", "Standard HD & WebRip", q720) { q720 = it; setQuality720Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "480p & Below", "Data saver streams", q480) { q480 = it; setQuality480Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "Hindi Providers Only 🇮🇳", "Hide English-only providers", hindiOnly) {
            hindiOnly = it; setHindiOnly(ctx, it); applyServerFilter()
        }
        makeToggleCard(streamPane, "Download Only Links", "Prefer direct MP4/MKV links", downloadOnly) {
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
        makeStepper("Concurrent Threads", "Parallel source requests", currentConcurrency, 2, 50, 1, "x") {
            currentConcurrency = it; setConcurrency(ctx, it); refreshStats()
        }
        makeStepper("Server Timeout", "Wait limit per source", currentTimeout, 4, 20, 1, "s") {
            currentTimeout = it; setTimeoutSeconds(ctx, it)
        }

        // --- Personal Collection Pane ---
        val colAddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#131826", "#314161", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        colAddBox.addView(TextView(ctx).apply { text = "⭐ Personal Collection (Movies & Series)"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#FBBF24")) })
        val colTitleInput = EditText(ctx).apply {
            hint = "Movie or Series title..."; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
        }
        val colHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderCollection: () -> Unit
        renderCollection = {
            colHolder.removeAllViews()
            if (collectionList.isEmpty()) {
                colHolder.addView(TextView(ctx).apply {
                    text = "Your Personal Collection is empty. Add titles above."
                    textSize = 11.5f; setTextColor(safeColor("#64748B")); gravity = Gravity.CENTER; setPadding(0, dp(ctx, 14), 0, dp(ctx, 14))
                })
            } else {
                collectionList.forEachIndexed { idx, item ->
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        background = cardBg("#121621", "#232C40", 18f)
                        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
                    }
                    card.addView(TextView(ctx).apply {
                        text = "${item.title}  (${item.type})"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })
                    card.addView(TextView(ctx).apply {
                        text = "REMOVE"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#F87171"))
                        background = pillBg("#2A1519", 12f); setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        setOnClickListener { collectionList.removeAt(idx); savePersonalCollection(ctx, collectionList); renderCollection() }
                    })
                    colHolder.addView(card)
                }
            }
        }
        val colBtnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        colBtnRow.addView(Button(ctx).apply {
            text = "+ ADD MOVIE"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 14f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 6) }
            setOnClickListener {
                val t = colTitleInput.text.toString().trim()
                if (t.isNotBlank()) {
                    collectionList.add(0, PersonalCollectionItem(System.currentTimeMillis().toString(), t, "Movie"))
                    savePersonalCollection(ctx, collectionList); colTitleInput.setText(""); renderCollection()
                }
            }
        })
        colBtnRow.addView(Button(ctx).apply {
            text = "+ ADD SERIES"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#0284C7", 14f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
            setOnClickListener {
                val t = colTitleInput.text.toString().trim()
                if (t.isNotBlank()) {
                    collectionList.add(0, PersonalCollectionItem(System.currentTimeMillis().toString(), t, "Series"))
                    savePersonalCollection(ctx, collectionList); colTitleInput.setText(""); renderCollection()
                }
            }
        })
        colAddBox.addView(colTitleInput); colAddBox.addView(colBtnRow)
        collectionPane.addView(colAddBox); collectionPane.addView(colHolder)
        renderCollection()

        // --- Catalogs Pane ---
        val metaCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        metaCard.addView(TextView(ctx).apply { text = "Active Metadata Source"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val metaRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val metaChips = mutableMapOf<String, TextView>()
        fun refreshMetaChips() {
            metaChips.forEach { (m, tv) ->
                tv.background = if (m == selectedMeta) pillBg(activeTheme.primaryHex, 14f) else cardBg("#0B0E16", "#232C40", 14f)
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
        refreshMetaChips(); metaCard.addView(metaRow); catalogPane.addView(metaCard)

        val nuvioAddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#131826", "#314161", 22f)
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
                        nuvioList.add(NuvioManifestConfig(System.currentTimeMillis().toString(), pName, pUrl, true, pUrl == PERMANENT_STREMIO_MANIFEST))
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
            nuvioList.forEachIndexed { idx, item ->
                val isPerm = item.isPermanent || item.manifestUrl.equals(PERMANENT_STREMIO_MANIFEST, true)
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; background = cardBg("#121621", if (isPerm) activeTheme.primaryHex else "#232C40", 20f)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
                }
                val row = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(TextView(ctx).apply {
                    text = if (isPerm) "${item.label} • PERMANENT" else item.label
                    textSize = 13f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                if (!isPerm) {
                    row.addView(TextView(ctx).apply {
                        text = "DELETE"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#F87171"))
                        background = pillBg("#2A1519", 12f); setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 8) }
                        setOnClickListener { nuvioList.removeAt(idx); saveNuvioManifests(ctx, nuvioList); renderNuvioList(); refreshStats() }
                    })
                }
                row.addView(Switch(ctx).apply {
                    isChecked = if (isPerm) true else item.enabled; isEnabled = !isPerm
                    setOnCheckedChangeListener { _, c -> if (!isPerm) { item.enabled = c; saveNuvioManifests(ctx, nuvioList); refreshStats() } }
                })
                card.addView(row)
                card.addView(TextView(ctx).apply {
                    text = item.manifestUrl; textSize = 10f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#94A3B8")); setPadding(0, dp(ctx, 4), 0, 0)
                })
                nuvioListHolder.addView(card)
            }
        }
        nuvioAddBox.addView(labelInput); nuvioAddBox.addView(manifestRow)
        nuvioAddBox.addView(Button(ctx).apply {
            text = "+ ADD MANIFEST.JSON URL"; textSize = 11f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38))
            setOnClickListener {
                val raw = manifestUrlInput.text.toString().trim()
                if (raw.isBlank()) { safeToast(ctx, "Manifest URL dalein"); return@setOnClickListener }
                val norm = normalizeNuvioManifestUrl(raw)
                val lbl = labelInput.text.toString().trim().ifEmpty { deriveManifestLabel(norm) }
                nuvioList.add(NuvioManifestConfig(System.currentTimeMillis().toString(), lbl, norm, true, norm.equals(PERMANENT_STREMIO_MANIFEST, true)))
                saveNuvioManifests(ctx, nuvioList); labelInput.setText(""); manifestUrlInput.setText("")
                renderNuvioList(); refreshStats(); safeToast(ctx, "$lbl added!")
            }
        })
        catalogPane.addView(nuvioAddBox); catalogPane.addView(nuvioListHolder); renderNuvioList()

        // --- Home Page Pane ---
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
                    if (idx > 0) setOnClickListener { homeCats[idx] = homeCats[idx - 1].also { homeCats[idx - 1] = homeCats[idx] }; saveHomeCategories(ctx, homeCats); renderHomeCats() }
                })
                card.addView(TextView(ctx).apply {
                    text = "↓"; textSize = 15f; setTextColor(safeColor(if (idx < homeCats.lastIndex) "#38BDF8" else "#334155"))
                    setPadding(dp(ctx, 7), dp(ctx, 4), dp(ctx, 7), dp(ctx, 4))
                    if (idx < homeCats.lastIndex) setOnClickListener { homeCats[idx] = homeCats[idx + 1].also { homeCats[idx + 1] = homeCats[idx] }; saveHomeCategories(ctx, homeCats); renderHomeCats() }
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
        renderHomeCats(); homePane.addView(homeCatHolder)

        homePane.addView(Button(ctx).apply {
            text = "+ Add a new category"; textSize = 11f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 14f)
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

        // --- CS3 Repos Pane ---
        val cs3AddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#131826", "#2563EB", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) }
        }
        cs3AddBox.addView(TextView(ctx).apply { text = "+ Add SkyStream / CS3 Repo & Fetch Servers"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#38BDF8")) })
        val cs3NameInput = EditText(ctx).apply {
            hint = "Repo Name (optional)"; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f); setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 8); bottomMargin = dp(ctx, 8) }
        }
        val cs3UrlInput = EditText(ctx).apply {
            hint = SKY_UNIVERSE_REPO_URL; setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
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
            safeToast(ctx, "Fetching SkyStream / CS3 servers...")
            dialogScope.launch {
                val fetched = fetchServersFromCs3Repo(repo)
                val prefix = "cs3_${repo.id.takeLast(6)}_"
                saveCs3Servers(ctx, (cachedCs3Servers.filterNot { it.key.startsWith(prefix) } + fetched).distinctBy { it.key })
                repo.serverCount = fetched.size; saveCs3Repos(ctx, cs3RepoList)
                if (isContextAlive(ctx)) { renderCs3Repos(); applyServerFilter(); safeToast(ctx, "${fetched.size} servers loaded!") }
            }
        }
        renderCs3Repos = {
            cs3RepoListHolder.removeAllViews()
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
                        val rem = cs3RepoList.removeAt(idx)
                        saveCs3Servers(ctx, cachedCs3Servers.filterNot { it.key.startsWith("cs3_${rem.id.takeLast(6)}_") })
                        saveCs3Repos(ctx, cs3RepoList); renderCs3Repos(); applyServerFilter()
                    }
                })
                card.addView(row)
                card.addView(TextView(ctx).apply { text = repo.repoUrl; textSize = 10f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END; setTextColor(safeColor("#94A3B8")); setPadding(0, dp(ctx, 4), 0, 0) })
                cs3RepoListHolder.addView(card)
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
                cs3RepoList.add(newRepo); saveCs3Repos(ctx, cs3RepoList); cs3NameInput.setText(""); cs3UrlInput.setText(""); renderCs3Repos(); fetchAndApplyCs3(newRepo)
            }
        })
        cs3Pane.addView(cs3AddBox); cs3Pane.addView(cs3RepoListHolder); renderCs3Repos()

        // --- Health, Token Status & Logcat Pane ---
        val healthSummaryTv = TextView(ctx).apply {
            textSize = 11f; setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#0A0E17", "#26324A", 16f, 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val logTv = TextView(ctx).apply {
            textSize = 10f; setTextColor(safeColor("#E2E8F0"))
            background = cardBg("#0A0E17", "#26324A", 16f, 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        fun refreshHealthPane() {
            val healthLines = SERVERS.mapNotNull { srv ->
                val h = getServerHealth(srv.key) ?: return@mapNotNull null
                "• ${srv.name}: ${h.status} (${h.latencyMs}ms) — ${h.detail}"
            }
            healthSummaryTv.text = if (healthLines.isEmpty()) "Tap 'TEST ALL SERVERS' to check Domain, URL, HTTP & Token status." else healthLines.joinToString("\n")
            val logs = getDiagnosticLogs()
            logTv.text = if (logs.isEmpty()) "No error logs yet (ADB tag: $LOG_TAG)" else logs.asReversed().joinToString("\n\n")
        }

        healthPane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(Button(ctx).apply {
                text = "TEST ALL SERVERS"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#059669", 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    safeToast(ctx, "Testing all servers & API hosts...")
                    dialogScope.launch {
                        supervisorScope {
                            SERVERS.map { srv -> async { probeServerLive(srv) } }.awaitAll()
                        }
                        if (isContextAlive(ctx)) {
                            refreshHealthPane()
                            applyServerFilter()
                            safeToast(ctx, "Health check complete!")
                        }
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "CLEAR LOGS"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#334155", 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
                setOnClickListener { clearDiagnosticLogs(); serverHealthMap.clear(); refreshHealthPane(); applyServerFilter() }
            })
        })
        healthPane.addView(healthSummaryTv)
        healthPane.addView(logTv)
        refreshHealthPane()

        val scrollPanes = listOf(extractorPane, streamPane, collectionPane, catalogPane, homePane, cs3Pane, healthPane)
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
                if (idx == 7) refreshHealthPane()
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg(activeTheme.primaryHex, 15f)
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
                cachedManifests = null; cachedCs3Repos = null
                getNuvioManifests(ctx); getCs3Repos(ctx)
                applyServerFilter(); safeToast(ctx, "Settings reset (Stremio & SkyStream kept)")
                runCatching { dialog.dismiss() }
            }
        })
        footer.addView(Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveNuvioManifests(ctx, nuvioList)
                saveCs3Repos(ctx, cs3RepoList)
                saveHomeCategories(ctx, homeCats)
                savePersonalCollection(ctx, collectionList)
                setSelectedTheme(ctx, activeTheme.id)
                setSelectedApiHost(ctx, selectedApiHost)
                setPreferredPlayer(ctx, selectedPlayer)
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
                safeToast(ctx, "Settings saved & applied")
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

