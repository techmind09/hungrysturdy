package com.hungrysturdy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.ContextWrapper
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
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
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.zip.ZipInputStream

object MovieFoxSettingsDialog {

    const val LOG_TAG = "MovieFoxDiag"
    private const val PREFS_NAME = "moviefox_ultra_max_v36"
    private const val CF_VERIFIED_MARKER = "__CF_VERIFIED__"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_EXT_PREFIX = "mf_ext_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_CF_COOKIES = "mf_cf_cookies_json"
    private const val KEY_USE_WEBVIEW_CF = "mf_use_webview_cf"
    private const val KEY_SFLIX_HOST = "mf_sflix_host"
    private const val KEY_THEME = "mf_ui_theme"
    private const val KEY_PLAYER = "mf_preferred_player"
    private const val KEY_Q_4K = "mf_q_4k_1440p"
    private const val KEY_Q_1080 = "mf_q_1080p"
    private const val KEY_Q_720 = "mf_q_720p"
    private const val KEY_Q_480 = "mf_q_480p"
    private const val KEY_HINDI_ONLY = "mf_hindi_providers_only"
    private const val KEY_LOW_BUFFER = "mf_low_buffer_mode"
    private const val KEY_DOWNLOAD_ONLY = "mf_download_only_mode"
    private const val KEY_AUTO_OFF_DEAD = "mf_auto_off_dead_servers"
    private const val KEY_BANNER_THUMB_URL = "mf_custom_banner_thumb_url"
    private const val KEY_CONCURRENCY = "mf_concurrency"
    private const val KEY_TIMEOUT_SEC = "mf_timeout_sec"
    private const val KEY_SHUFFLE_HOME = "mf_shuffle_home_categories"

    const val HUNGRYSTURDY_REPO_URL =
        "https://raw.githubusercontent.com/techmind09/hungrysturdy/refs/heads/builds/repo.json"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    val PLAYER_OPTIONS = listOf(
        "Default (ExoPlayer)",
        "MX Player",
        "MX Player Pro",
        "VLC Player"
    )

    val SFLIX_HOSTS = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com"
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
        val category: String = "Movies",
        val runtimeBadge: String = "⚡ MF",
        val thumbnailUrl: String = ""
    )

    data class ExtractorItem(
        val key: String,
        val name: String,
        val hosts: String,
        val tag: String
    )

    data class ServerHealthInfo(
        val key: String,
        val status: String,
        val isHealthy: Boolean,
        val httpCode: Int = 0,
        val latencyMs: Long = -1L,
        val detail: String = "",
        val updatedAt: String = ""
    )

    data class Cs3RepoConfig(
        val id: String,
        val name: String,
        val repoUrl: String,
        val repoType: String = "CS3", // CS3, SKYSTREAM, NUVIO
        var enabled: Boolean = true,
        var serverCount: Int = 0
    )

    data class DiagLogEntry(
        val timestamp: String,
        val category: String, // SEARCH, LOADLINK, PARSING, TIMEOUT, ERROR, INFO
        val source: String,
        val message: String,
        val latencyMs: Long = -1L
    ) {
        fun formatLine(): String {
            val msPart = if (latencyMs >= 0L) " [${latencyMs}ms]" else ""
            return "[$timestamp] [$category] [$source]$msPart $message"
        }
    }

    private val PERMANENT_REPOS = listOf(
        Cs3RepoConfig("hungrysturdy_repo", "HungrySturdy Builds Repo", HUNGRYSTURDY_REPO_URL, "CS3", true, 0),
        Cs3RepoConfig("phisher98_repo", "Phisher98 Extensions", "https://raw.githubusercontent.com/phisher98/cloudstream-extensions-phisher/refs/heads/builds/repo.json", "CS3", true, 0),
        Cs3RepoConfig("eclipsia_repo", "Eclipsia Repo", "https://cs.eclipsia.dpdns.org/repo.json", "CS3", true, 0),
        Cs3RepoConfig("desi_repo", "Desi Repo", "https://raw.githubusercontent.com/Faisal0786/Desi/main/repo.json", "CS3", true, 0),
        Cs3RepoConfig("plaviohd_repo", "PlavioHD Repo", "https://raw.githubusercontent.com/kotlinsyd/plaviohd/builds/repo.json", "CS3", true, 0)
    )

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var cachedCs3Repos: MutableList<Cs3RepoConfig>? = null
    private val cachedCs3Servers = CopyOnWriteArrayList<ServerItem>()
    private val diagLogs = CopyOnWriteArrayList<DiagLogEntry>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()
    private val bitmapCache = ConcurrentHashMap<String, Bitmap>()
    @Volatile
    private var cs3Loaded = false
    private val autoSyncStarted = AtomicBoolean(false)

    fun bindContext(ctx: Context?) {
        if (ctx != null) {
            appContext = ctx.applicationContext
            if (autoSyncStarted.compareAndSet(false, true)) {
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    runCatching {
                        val c = ctx.applicationContext
                        val repos = getCs3Repos(c)
                        val fetched = mutableListOf<ServerItem>()
                        supervisorScope {
                            repos.filter { it.enabled }.map { r ->
                                async {
                                    val list = fetchServersFromCs3Repo(r)
                                    r.serverCount = list.size
                                    list
                                }
                            }.awaitAll().forEach { fetched.addAll(it) }
                        }
                        if (fetched.isNotEmpty()) {
                            saveCs3Servers(c, fetched)
                            saveCs3Repos(c, repos)
                        }
                    }
                }
            }
        }
    }

    fun currentContext(): Context? = appContext

    val EXTRACTORS = listOf(
        ExtractorItem("xcloud", "XCloud HLS & Direct", "xcloud.tattoo • stream.xcloud1.lol • workers.dev", "HLS"),
        ExtractorItem("hubcloud", "HubCloud & VCloud & GamerXYT", "hubcloud.pw/dad/club/one • vcloud.* • gamerxyt.com", "10Gbps"),
        ExtractorItem("gdflix", "GDFlix & GDLink & FastDL", "gdflix.* • gdlink.* • fastdlserver.*", "R2/DL"),
        ExtractorItem("pixeldrain", "Pixeldrain (.com / .dev)", "pixeldrain.com • pixeldrain.dev", "FAST"),
        ExtractorItem("gdrive", "Google Drive Direct", "drive.google.com • usercontent.google.com", "GDRIVE"),
        ExtractorItem("streamtape", "StreamTape Mirrors", "streamtape.com/to/net/xyz • shavetape • strcloud", "MP4"),
        ExtractorItem("voe", "Voe Mirrors", "voe.sx • voe.to • voe.co", "HLS/MP4"),
        ExtractorItem("buzz_hb_go", "BuzzServer / HBPlay / GoFlix", "fuckingfast.net • hbplay.pages.dev • goflix.sbs", "MIRROR"),
        ExtractorItem("basejwp", "BaseJWP / Nitrox / NxStream", "itsnitrox.tech • nxstreamhub • vibuxer • vidprime", "WORKER"),
        ExtractorItem("jwp_embeds", "Lulu / Vidara / MegaCloud / Wish / Moon", "luluvid • vidara • megacloud • streamwish • filemoon", "EMBED")
    )

    val BASE_SERVERS = listOf(
        ServerItem("mf_1", "MovieFox 1", "MANIFEST", "https://cncverse.dpdns.org/u/p_ij06hqyj40r4/manifest.json", "#38BDF8", false, true, "Movies", "⚡ MF"),
        ServerItem("mf_2", "MovieFox 2", "MANIFEST", "https://pengu.uk/%7B%22auth_token%22%3A%22LIuypj1fVzaEnmUrpUmn7_RyN-Vad4fzSP3p5NRukRs%22%7D/manifest.json", "#A855F7", false, true, "Movies", "⚡ MF"),
        ServerItem("mf_3", "MovieFox 3", "MANIFEST", "https://hdhub.thevolecitor.qzz.io/eyJ0b3Jib3giOiJ1bnNldCIsInF1YWxpdGllcyI6IjIxNjBwLDEwODBwLDcyMHAsNDgwcCIsInNvcnQiOiJkZXNjIiwiY29udGVudCI6ImxhdGluLGdlcm1hbixwb3J0dWd1ZXNlLGZyZW5jaCxpdGFsaWFuLGFzaWFuIiwiY2F0YWxvZ3MiOiJmZWF0dXJlZCxwb3B1bGFyIn0/manifest.json", "#10B981", false, true, "Movies", "⚡ MF"),
        ServerItem("mf_4", "MovieFox 4", "MANIFEST", "https://free.flixnest.app/eNqNUctuwjAQ_BefQXKcQKX8SoVWJt40W9Z25EciqPrvNa9CgUNPlnd2RrMzXwIwRnSJNAPThIBT-UXQzH4GwzEF1DaCwzT7sBNtChkXAvqACLEb0GrRCiXVWq6VWp7Zy544YViOPh6fqRYLcZPqdMIPHwijaN83f5BBO4cMZ_oTTK7jbBC0yZxE22uOxQo6vWWEZjeYIW-vBi9To4n31ify7hG5yj7MX8bxn527sAwF7NID6WSvyb_TFxoQRx_S5W5yMZUWgEwJuCeQrN7qdaMkKSWd_FxV9dzEapa-3le1rGqWq5LznVoii9D7YHU6dtQMz_DBOyzYGPxEBgOw7zSXtTGgpWzP3tIEts4QfQ7dqbRbJ2Lz_QPhgdTs/manifest.json", "#F59E0B", false, true, "Movies", "⚡ MF"),
        ServerItem("sflix", "Sflix", "HLS/MP4", "https://sflix.film/", "#34D399", false, false, "Series", "⚡ MF"),
        ServerItem("play4u", "Play4u", "HI/M3U8", "https://play4u.org/", "#FB923C", false, true, "Movies", "⚡ MF"),
        ServerItem("prmovies", "Prmovies", "HI/M3U8", "https://prmovies.coupons/", "#F59E0B", false, true, "Movies", "⚡ MF"),
        ServerItem("hindidrama", "HindiDrama", "K-DRA", "https://hindidrama.net/", "#EC4899", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("themoviesflix", "TheMoviesFlix", "4K/1080p", "https://themoviesflix.eat/", "#22D3EE", false, true, "Movies", "⚡ MF"),
        ServerItem("dramaverse", "DramaVerse", "K-DRAMA", "https://mydverse.com/", "#A855F7", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("katdrama", "KatDrama", "ASIAN", "https://katdrama.org/", "#38BDF8", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("animeworld", "AnimeWorld", "ANIME", "https://animesalt.cx/", "#818CF8", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("vegamovies", "VegaMovies", "FSL/PXL", "https://vegamovies11.in/", "#FACC15", false, true, "Movies", "⚡ MF"),
        ServerItem("bollyflix", "BollyFlix", "GDFlix/HI", "https://new1.bollyflix.tours/", "#F43F5E", false, true, "Movies", "⚡ MF"),
        ServerItem("cinestream", "CineStream Hub", "ALL/MULTI", "https://new16.hdhub4u.cl/", "#10B981", false, true, "Movies", "⚡ MF"),
        ServerItem("videasy", "Videasy", "HLS/MP4", "https://player.videasy.to/", "#38BDF8", false, true, "Movies", "⚡ MF"),
        ServerItem("vidzee", "Vidzee", "MULTI/HLS", "https://player.vidzee.wtf/", "#C084FC", false, true, "Movies", "⚡ MF"),
        ServerItem("hexa", "Hexa", "HLS/1080p", "https://hexa.su/", "#22D3EE", false, true, "Movies", "⚡ MF"),
        ServerItem("moviebox", "MovieBox", "MULTI/HD", "https://moviebox01.com/", "#FB7185", false, true, "Series", "⚡ MF")
    )

    private val AVATAR_PALETTE = listOf(
        "#38BDF8", "#A855F7", "#F43F5E", "#34D399",
        "#FB923C", "#818CF8", "#22D3EE", "#EC4899", "#FACC15"
    )

    val SERVERS: List<ServerItem>
        get() {
            appContext?.let { ctx ->
                if (!cs3Loaded) prefs(ctx)
            }
            return BASE_SERVERS + cachedCs3Servers.toList()
        }

    fun getNuvioManifestServers(ctx: Context?): List<ServerItem> {
        val c = ctx ?: appContext
        if (c != null && !cs3Loaded) prefs(c)
        val builtInManifests = BASE_SERVERS.filter { it.tag.equals("MANIFEST", true) }
        val customManifests = cachedCs3Servers.filter { it.tag.equals("NUVIO", true) || it.tag.equals("MANIFEST", true) }
        return builtInManifests + customManifests
    }

    fun copyToClipboard(ctx: Context?, label: String, text: String) {
        if (ctx == null || text.isBlank()) return
        runCatching {
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText(label, text))
            safeToast(ctx, "Copied: $label")
        }
    }

    private fun nowTs(): String = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())

    private fun appendDiagLog(category: String, source: String, message: String, latencyMs: Long = -1L) {
        val entry = DiagLogEntry(nowTs(), category.uppercase(), source, message.trim(), latencyMs)
        diagLogs.add(entry)
        while (diagLogs.size > 250) diagLogs.removeAt(0)
        Log.d(LOG_TAG, entry.formatLine())
    }

    fun logSearch(source: String, query: String, resultCount: Int, latencyMs: Long = -1L, detail: String = "") {
        val extra = if (detail.isNotBlank()) " | $detail" else ""
        appendDiagLog("SEARCH", source, "title=\"$query\" -> hits=$resultCount$extra", latencyMs)
    }

    fun logLoadLink(source: String, title: String, linksFound: Int, latencyMs: Long = -1L, detail: String = "") {
        val extra = if (detail.isNotBlank()) " | $detail" else ""
        appendDiagLog("LOADLINK", source, "title=\"$title\" -> links=$linksFound$extra", latencyMs)
    }

    fun logParsing(source: String, url: String, stage: String, error: Throwable? = null, detail: String = "") {
        val err = error?.let { " -> ${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val extra = if (detail.isNotBlank()) " | $detail" else ""
        appendDiagLog("PARSING", source, "[$stage] $url$extra$err")
    }

    fun logTimeout(source: String, url: String, timeoutSec: Long, stage: String = "HTTP") {
        appendDiagLog("TIMEOUT", source, "[$stage] timeout(${timeoutSec}s) | $url", timeoutSec * 1000L)
        reportServerStatus(source, "TIMEOUT", false, 0, timeoutSec * 1000L, "Timeout ${timeoutSec}s • $url")
    }

    fun reportServerStatus(
        key: String,
        status: String,
        isHealthy: Boolean,
        httpCode: Int = 0,
        latencyMs: Long = -1L,
        detail: String = ""
    ) {
        serverHealthMap[key.lowercase()] = ServerHealthInfo(
            key = key.lowercase(),
            status = status,
            isHealthy = isHealthy,
            httpCode = httpCode,
            latencyMs = latencyMs,
            detail = detail,
            updatedAt = nowTs()
        )
        val ctx = appContext
        if (!isHealthy && ctx != null && isAutoOffDeadServers(ctx)) {
            setServerEnabled(ctx, key, false)
        }
    }

    fun getServerHealth(key: String): ServerHealthInfo? = serverHealthMap[key.lowercase()]

    fun isServerDead(info: ServerHealthInfo?): Boolean {
        if (info == null) return false
        if (info.isHealthy) return false
        val st = info.status.uppercase(Locale.getDefault())
        return st == "DOWN" ||
            st == "TIMEOUT" ||
            st == "ERROR" ||
            st.startsWith("HTTP ") ||
            st.startsWith("CF ")
    }

    fun disableDeadServers(ctx: Context): Int {
        var disabledCount = 0
        val ed = prefs(ctx).edit()
        SERVERS.forEach { srv ->
            val info = getServerHealth(srv.key)
            val currentlyOn = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", true)
            if (isServerDead(info) && currentlyOn) {
                ed.putBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", false)
                disabledCount++
            }
        }
        ed.apply()
        if (disabledCount > 0) {
            appendDiagLog("INFO", "AutoOff", "Turned OFF $disabledCount non-working servers")
        }
        return disabledCount
    }

    fun logSiteError(source: String, url: String, error: Throwable?, extra: String = "") {
        val errClass = error?.javaClass?.simpleName.orEmpty()
        val errText = error?.message.orEmpty()
        val combined = "$source $extra $errClass $errText $url".lowercase()
        val category = when {
            combined.contains("timeout") || combined.contains("interruptedio") || combined.contains("sockettimeout") -> "TIMEOUT"
            combined.contains("json") || combined.contains("parse") || combined.contains("jsoup") || combined.contains("jackson") -> "PARSING"
            combined.contains("?s=") || combined.contains("search") -> "SEARCH"
            combined.contains("loadlinks") || combined.contains("extract") || combined.contains("play") -> "LOADLINK"
            else -> "ERROR"
        }
        val errMsg = error?.let { "$errClass: $errText" }.orEmpty()
        val msg = "${if (extra.isNotBlank()) "$extra | " else ""}$url${if (errMsg.isNotBlank()) " -> $errMsg" else ""}"
        Log.e(LOG_TAG, "[$category] [$source] $msg", error)
        appendDiagLog(category, source, msg)
        val statusLabel = if (category == "TIMEOUT") "TIMEOUT" else "ERROR"
        reportServerStatus(source, statusLabel, false, 0, -1L, extra.ifBlank { errMsg })
    }

    fun getDiagnosticLogs(categoryFilter: String = "ALL"): List<String> {
        val list = if (categoryFilter.equals("ALL", true)) {
            diagLogs.toList()
        } else {
            diagLogs.filter { it.category.equals(categoryFilter, true) }
        }
        return list.map { it.formatLine() }
    }

    fun clearDiagnosticLogs() = diagLogs.clear()

    private suspend fun probeServerLive(srv: ServerItem): ServerHealthInfo = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        val tMs = (appContext?.let { getTimeoutSeconds(it) } ?: 25).coerceIn(10, 18) * 1000
        try {
            val conn = (URL(srv.probeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = tMs
                readTimeout = tMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
                getCookieForDomain(appContext, srv.probeUrl)?.let { setRequestProperty("Cookie", it) }
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            runCatching { conn.inputStream?.close() }
            runCatching { conn.errorStream?.close() }
            conn.disconnect()
            val ok = code in 200..399 || code == 405
            val status = when {
                ok -> "LIVE"
                code == 403 || code == 503 -> "CF $code"
                else -> "HTTP $code"
            }
            val info = ServerHealthInfo(
                key = srv.key,
                status = status,
                isHealthy = ok,
                httpCode = code,
                latencyMs = ms,
                detail = srv.probeUrl,
                updatedAt = nowTs()
            )
            serverHealthMap[srv.key.lowercase()] = info
            if (!ok) {
                appendDiagLog("ERROR", srv.key, "Probe $status | ${srv.probeUrl}", ms)
                appContext?.let { if (isAutoOffDeadServers(it)) setServerEnabled(it, srv.key, false) }
            }
            info
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val isTimeout = e.message?.contains("timeout", true) == true ||
                e.javaClass.simpleName.contains("Timeout", true) ||
                e.javaClass.simpleName.contains("InterruptedIO", true)
            val status = if (isTimeout) "TIMEOUT" else "DOWN"
            val info = ServerHealthInfo(
                key = srv.key,
                status = status,
                isHealthy = false,
                httpCode = 0,
                latencyMs = ms,
                detail = "${e.message ?: "Network error"} • ${srv.probeUrl}",
                updatedAt = nowTs()
            )
            serverHealthMap[srv.key.lowercase()] = info
            appendDiagLog(if (isTimeout) "TIMEOUT" else "ERROR", srv.key, "Probe $status: ${e.message ?: "Network"} | ${srv.probeUrl}", ms)
            appContext?.let { if (isAutoOffDeadServers(it)) setServerEnabled(it, srv.key, false) }
            info
        }
    }

    private suspend fun loadBitmapFromUrl(urlStr: String): Bitmap? = withContext(Dispatchers.IO) {
        if (urlStr.isBlank()) return@withContext null
        bitmapCache[urlStr]?.let { return@withContext it }
        runCatching {
            val conn = (URL(toRawGithubUrl(urlStr)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.use { inp ->
                    val bmp = BitmapFactory.decodeStream(inp)
                    if (bmp != null) {
                        bitmapCache[urlStr] = bmp
                    }
                    bmp
                }
            } else null
        }.getOrNull()
    }

    private fun resolveActivityContext(rawCtx: Context): Context {
        if (rawCtx is Activity) return rawCtx
        var base = (rawCtx as? ContextWrapper)?.baseContext
        while (base != null) {
            if (base is Activity) return base
            base = (base as? ContextWrapper)?.baseContext
        }
        val fgActivity = runCatching {
            val atClass = Class.forName("android.app.ActivityThread")
            val thread = atClass.getMethod("currentActivityThread").invoke(null)
            val field = atClass.getDeclaredField("mActivities").apply { isAccessible = true }
            val activities = field.get(thread) as? Map<*, *>
            activities?.values?.firstNotNullOfOrNull { record ->
                val rClass = record?.javaClass
                val paused = rClass?.getDeclaredField("paused")?.apply { isAccessible = true }
                    ?.getBoolean(record) ?: true
                if (!paused) {
                    rClass?.getDeclaredField("activity")?.apply { isAccessible = true }
                        ?.get(record) as? Activity
                } else null
            }
        }.getOrNull()
        return fgActivity ?: rawCtx
    }

    private fun isContextAlive(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return false
        return true
    }

    // =========================================================================
    // Ultra Glass & Neon Design System Helpers
    // =========================================================================
    private fun safeColor(hex: String, fallback: String = "#94A3B8"): Int =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.parseColor(fallback) }

    private fun alphaColor(hex: String, alpha: Int): Int {
        val base = safeColor(hex)
        return Color.argb(alpha.coerceIn(0, 255), Color.red(base), Color.green(base), Color.blue(base))
    }

    private fun lightenColor(hex: String, factor: Float = 0.22f): Int {
        val c = safeColor(hex)
        val r = (Color.red(c) + (255 - Color.red(c)) * factor).toInt().coerceIn(0, 255)
        val g = (Color.green(c) + (255 - Color.green(c)) * factor).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) + (255 - Color.blue(c)) * factor).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun darkenColor(hex: String, factor: Float = 0.22f): Int {
        val c = safeColor(hex)
        val r = (Color.red(c) * (1f - factor)).toInt().coerceIn(0, 255)
        val g = (Color.green(c) * (1f - factor)).toInt().coerceIn(0, 255)
        val b = (Color.blue(c) * (1f - factor)).toInt().coerceIn(0, 255)
        return Color.rgb(r, g, b)
    }

    private fun safeToast(ctx: Context?, msg: String) {
        if (!isContextAlive(ctx)) return
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
    }

    private fun dp(ctx: Context, px: Int): Int =
        (px * ctx.resources.displayMetrics.density).toInt()

    private fun dpf(ctx: Context, px: Float): Float =
        px * ctx.resources.displayMetrics.density

    private fun cardBg(
        bgHex: String = "#111624",
        strokeHex: String = "#232D45",
        radius: Float = 20f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#111624"))
        cornerRadius = radius
        setStroke(strokeWidth, safeColor(strokeHex, "#232D45"))
    }

    private fun glassCardBg(
        topHex: String = "#151C2E",
        bottomHex: String = "#0E1320",
        strokeColor: Int = safeColor("#26334D"),
        radius: Float = 20f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable(
        GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(safeColor(topHex), safeColor(bottomHex))
    ).apply {
        cornerRadius = radius
        setStroke(strokeWidth, strokeColor)
    }

    private fun pillBg(bgHex: String, radius: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#1C2230"))
        cornerRadius = radius
    }

    private fun tintBadgeBg(accentHex: String, radius: Float = 10f, bgAlpha: Int = 36, strokeAlpha: Int = 95): GradientDrawable =
        GradientDrawable().apply {
            setColor(alphaColor(accentHex, bgAlpha))
            cornerRadius = radius
            setStroke(2, alphaColor(accentHex, strokeAlpha))
        }

    private fun neonGradBtn(primaryHex: String, radius: Float = 16f): GradientDrawable =
        GradientDrawable(
            GradientDrawable.Orientation.LEFT_RIGHT,
            intArrayOf(lightenColor(primaryHex, 0.14f), safeColor(primaryHex), darkenColor(primaryHex, 0.14f))
        ).apply {
            cornerRadius = radius
            setStroke(2, alphaColor("#FFFFFF", 42))
        }

    @SuppressLint("ClickableViewAccessibility")
    private fun attachPressAnim(view: View) {
        view.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> v.animate().scaleX(0.95f).scaleY(0.95f).setDuration(85).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.animate().scaleX(1f).scaleY(1f).setDuration(120).start()
            }
            false
        }
    }

    private fun monogramOf(name: String): String {
        val parts = name.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        return when {
            parts.size >= 2 -> "${parts[0].first()}${parts[1].first()}".uppercase()
            name.length >= 2 -> name.take(2).uppercase()
            else -> "MF"
        }
    }

    private class NeonSwitch(
        ctx: Context,
        initialChecked: Boolean,
        private var accentHex: String
    ) : FrameLayout(ctx) {

        private val trackW = dp(ctx, 44)
        private val trackH = dp(ctx, 24)
        private val thumbSize = dp(ctx, 18)
        private val thumbPad = dp(ctx, 3)
        private val travelX = (trackW - thumbSize - thumbPad * 2).toFloat()

        private val thumbView: View
        var isChecked: Boolean = initialChecked
            private set
        var onCheckedChange: ((Boolean) -> Unit)? = null

        init {
            layoutParams = LinearLayout.LayoutParams(trackW, trackH)
            isClickable = true
            isFocusable = true

            thumbView = View(ctx).apply {
                layoutParams = LayoutParams(thumbSize, thumbSize, Gravity.START or Gravity.CENTER_VERTICAL).apply {
                    marginStart = thumbPad
                }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(Color.WHITE)
                }
                elevation = dpf(ctx, 2f)
            }
            addView(thumbView)
            applyVisualState(animated = false)
            setOnClickListener { toggle() }
        }

        fun updateAccent(newHex: String) {
            accentHex = newHex
            applyVisualState(animated = false)
        }

        fun setCheckedSilent(checked: Boolean) {
            isChecked = checked
            applyVisualState(animated = false)
        }

        fun setChecked(checked: Boolean, animate: Boolean = true) {
            if (isChecked == checked) return
            isChecked = checked
            applyVisualState(animated = animate)
            onCheckedChange?.invoke(isChecked)
        }

        fun toggle() = setChecked(!isChecked, animate = true)

        private fun applyVisualState(animated: Boolean) {
            background = GradientDrawable().apply {
                cornerRadius = trackH / 2f
                if (isChecked) {
                    setColor(safeColor(accentHex, "#7C3AED"))
                    setStroke(2, alphaColor("#FFFFFF", 55))
                } else {
                    setColor(safeColor("#1B2335"))
                    setStroke(2, safeColor("#2E3B56"))
                }
            }
            val targetX = if (isChecked) travelX else 0f
            thumbView.animate().cancel()
            if (animated) {
                thumbView.animate().translationX(targetX).setDuration(150).start()
            } else {
                thumbView.translationX = targetX
            }
            thumbView.alpha = if (isChecked) 1f else 0.78f
        }
    }

    private fun makeSectionLabel(ctx: Context, textStr: String, accentHex: String): LinearLayout {
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 6)
            }
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 3), dp(ctx, 13)).apply { marginEnd = dp(ctx, 7) }
                background = pillBg(accentHex, 4f)
            })
            addView(TextView(ctx).apply {
                text = textStr.uppercase()
                textSize = 9.5f
                letterSpacing = 0.08f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#94A3B8"))
            })
        }
    }

    private fun prefs(ctx: Context): SharedPreferences {
        bindContext(ctx)
        return cachedPrefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also {
                cachedPrefs = it
                if (!cs3Loaded) {
                    cs3Loaded = true
                    cachedCs3Servers.clear()
                    cachedCs3Servers.addAll(loadCs3ServersInternal(it))
                }
            }
    }

    fun getSelectedTheme(ctx: Context): ThemePalette {
        val id = prefs(ctx).getString(KEY_THEME, "purple") ?: "purple"
        return UI_THEMES.firstOrNull { it.id == id } ?: UI_THEMES.first()
    }
    fun setSelectedTheme(ctx: Context, id: String) = prefs(ctx).edit().putString(KEY_THEME, id).apply()

    fun isUseWebViewCf(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_USE_WEBVIEW_CF, true)
    fun setUseWebViewCf(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_USE_WEBVIEW_CF, enabled).apply()

    fun isAutoOffDeadServers(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_AUTO_OFF_DEAD, false)
    fun setAutoOffDeadServers(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_AUTO_OFF_DEAD, enabled).apply()

    fun getCustomBannerThumbnailUrl(ctx: Context): String = prefs(ctx).getString(KEY_BANNER_THUMB_URL, "") ?: ""
    fun setCustomBannerThumbnailUrl(ctx: Context, url: String) = prefs(ctx).edit().putString(KEY_BANNER_THUMB_URL, url.trim()).apply()

    private fun extractHostKey(domainOrUrl: String): String = runCatching {
        val raw = if (domainOrUrl.startsWith("http")) domainOrUrl else "https://$domainOrUrl"
        URL(raw).host.lowercase().removePrefix("www.")
    }.getOrDefault(domainOrUrl.lowercase())

    fun saveCookieForDomain(ctx: Context, domainOrUrl: String, cookie: String) {
        val host = extractHostKey(domainOrUrl)
        val map = getSavedCookiesMap(ctx).toMutableMap()
        if (cookie.isBlank()) map.remove(host) else map[host] = cookie
        prefs(ctx).edit().putString(KEY_CF_COOKIES, JSONObject(map as Map<*, *>).toString()).apply()
    }

    fun getSavedCookiesMap(ctx: Context?): Map<String, String> {
        val c = ctx ?: appContext ?: return emptyMap()
        val raw = prefs(c).getString(KEY_CF_COOKIES, "{}") ?: "{}"
        val out = linkedMapOf<String, String>()
        runCatching {
            val obj = JSONObject(raw)
            obj.keys().forEach { k ->
                val v = obj.optString(k)
                if (v.isNotBlank()) out[k] = v
            }
        }
        return out
    }

    fun isDomainCfVerified(ctx: Context?, urlOrDomain: String): Boolean {
        val map = getSavedCookiesMap(ctx)
        if (map.isEmpty()) return false
        val host = extractHostKey(urlOrDomain)
        return map.containsKey(host) || map.keys.any { host.endsWith(it) || it.endsWith(host) }
    }

    fun syncCookiesFromWebView(ctx: Context): Int {
        var count = 0
        runCatching {
            val cm = CookieManager.getInstance()
            cm.flush()
            SERVERS.forEach { srv ->
                val cookie = cm.getCookie(srv.probeUrl)
                if (!cookie.isNullOrBlank()) {
                    saveCookieForDomain(ctx, srv.probeUrl, cookie)
                    count++
                }
            }
        }
        return count
    }

    fun clearAllCfCookies(ctx: Context) {
        prefs(ctx).edit().remove(KEY_CF_COOKIES).apply()
        runCatching {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        }
    }

    fun getCookieForDomain(ctx: Context?, urlOrDomain: String): String? {
        val map = getSavedCookiesMap(ctx)
        if (map.isEmpty()) return null
        val host = extractHostKey(urlOrDomain)
        val raw = map[host] ?: map.entries.firstOrNull { host.endsWith(it.key) || it.key.endsWith(host) }?.value
        return raw?.takeIf { it.isNotBlank() && it != CF_VERIFIED_MARKER }
    }

    fun openCfBypassWindow(
        rawCtx: Context,
        targetName: String,
        targetUrl: String,
        onCompleted: (() -> Unit)? = null
    ) {
        val single = ServerItem(
            key = targetName.lowercase(),
            name = targetName,
            tag = "CF",
            probeUrl = targetUrl,
            accentHex = "#38BDF8"
        )
        openCfBypassQueue(rawCtx, listOf(single), onCompleted)
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun openCfBypassQueue(
        rawCtx: Context,
        targets: List<ServerItem>,
        onCompleted: (() -> Unit)? = null
    ) {
        val ctx = resolveActivityContext(rawCtx)
        if (!isContextAlive(ctx) || targets.isEmpty()) return
        bindContext(ctx)

        val handler = Handler(Looper.getMainLooper())
        val closed = AtomicBoolean(false)
        val stepToken = AtomicInteger(0)
        var dialogRef: Dialog? = null
        var webViewRef: WebView? = null
        var idx = 0
        var savedCount = 0
        var challengeDetected = false

        val titleTv = TextView(ctx).apply {
            textSize = 14f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val statusTv = TextView(ctx).apply {
            textSize = 10.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(safeColor("#93C5FD"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 10
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 4)).apply {
                bottomMargin = dp(ctx, 8)
            }
        }

        fun currentTarget(): ServerItem = targets[idx.coerceIn(0, targets.lastIndex)]

        fun saveDomainState(url: String, requireClearance: Boolean): Boolean {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val srv = currentTarget()
                val cookie = cm.getCookie(url).orEmpty().ifBlank { cm.getCookie(srv.probeUrl).orEmpty() }
                val hasClearance = cookie.contains("cf_clearance", true) || cookie.contains("__cf_bm", true)
                when {
                    hasClearance -> {
                        saveCookieForDomain(ctx, srv.probeUrl, cookie)
                        if (url.startsWith("http") && extractHostKey(url) != extractHostKey(srv.probeUrl)) {
                            saveCookieForDomain(ctx, url, cookie)
                        }
                        appendDiagLog("INFO", srv.key, "CF clearance saved (${cookie.length} chars)")
                        true
                    }
                    !requireClearance -> {
                        val toStore = cookie.ifBlank { CF_VERIFIED_MARKER }
                        saveCookieForDomain(ctx, srv.probeUrl, toStore)
                        appendDiagLog("INFO", srv.key, if (cookie.isNotBlank()) "Cookie saved (${cookie.length} chars)" else "Verified (no CF challenge)")
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
        }

        fun closeAll() {
            if (!closed.compareAndSet(false, true)) return
            stepToken.incrementAndGet()
            handler.removeCallbacksAndMessages(null)
            runCatching { webViewRef?.stopLoading(); webViewRef?.destroy() }
            webViewRef = null
            runCatching { dialogRef?.dismiss() }
            dialogRef = null
            syncCookiesFromWebView(ctx)
            if (savedCount > 0) safeToast(ctx, "Verified & Saved ($savedCount/${targets.size})")
            onCompleted?.invoke()
        }

        fun loadCurrentTarget() {
            if (closed.get()) return
            if (idx >= targets.size) {
                closeAll()
                return
            }
            val token = stepToken.incrementAndGet()
            challengeDetected = false
            val srv = currentTarget()
            titleTv.text = "🛡️ CF Solver (${idx + 1}/${targets.size}) • ${srv.name}"
            statusTv.text = "Loading ${srv.probeUrl}..."
            statusTv.setTextColor(safeColor("#93C5FD"))
            progressBar.visibility = View.VISIBLE
            progressBar.progress = 15
            webViewRef?.stopLoading()
            webViewRef?.loadUrl(srv.probeUrl)

            handler.postDelayed({
                if (!closed.get() && stepToken.get() == token) {
                    statusTv.text = "Slow load • Solve challenge or tap Skip / Save"
                    statusTv.setTextColor(safeColor("#FDE68A"))
                }
            }, 10_000L)
        }

        fun advanceNext(expectedToken: Int, markSaved: Boolean) {
            if (closed.get()) return
            if (!stepToken.compareAndSet(expectedToken, expectedToken + 1)) return
            if (markSaved) savedCount++
            idx++
            if (idx >= targets.size) {
                closeAll()
            } else {
                loadCurrentTarget()
            }
        }

        fun inspectPageState(view: WebView?, url: String?, expectedToken: Int) {
            if (closed.get() || stepToken.get() != expectedToken || view == null) return
            val srv = currentTarget()
            if (saveDomainState(url ?: srv.probeUrl, requireClearance = true)) {
                statusTv.text = "✓ CF Cleared for ${srv.name}!"
                statusTv.setTextColor(safeColor("#6EE7B7"))
                handler.postDelayed({ advanceNext(expectedToken, true) }, 400L)
                return
            }

            view.evaluateJavascript(
                """
                (function(){
                    var t = (document.title || '').toLowerCase();
                    var b = (document.body ? document.body.innerText : '').toLowerCase().slice(0, 800);
                    var hasTurnstile = !!document.querySelector('iframe[src*="challenges.cloudflare.com"], #challenge-stage, #cf-challenge-running, .cf-turnstile, #turnstile-wrapper');
                    var isCfText = t.indexOf('just a moment') !== -1 || t.indexOf('attention required') !== -1 ||
                        t.indexOf('security check') !== -1 || b.indexOf('verify you are human') !== -1 ||
                        b.indexOf('checking your browser') !== -1 || (b.indexOf('cloudflare') !== -1 && b.indexOf('ray id') !== -1);
                    if (hasTurnstile || isCfText) return 'CF';
                    if (b.trim().length > 25 || document.images.length > 0) return 'OK';
                    return 'WAIT';
                })();
                """.trimIndent()
            ) { rawRes ->
                if (closed.get() || stepToken.get() != expectedToken) return@evaluateJavascript
                val state = rawRes?.replace("\"", "")?.trim().orEmpty()
                when (state) {
                    "CF" -> {
                        challengeDetected = true
                        statusTv.text = "🛡️ CF Challenge active — tap Verify box or ⚡ CF Click"
                        statusTv.setTextColor(safeColor("#FBBF24"))
                    }
                    "OK" -> {
                        challengeDetected = false
                        saveDomainState(url ?: srv.probeUrl, requireClearance = false)
                        statusTv.text = "✓ ${srv.name} ready! Moving next..."
                        statusTv.setTextColor(safeColor("#6EE7B7"))
                        handler.postDelayed({ advanceNext(expectedToken, true) }, 450L)
                    }
                    else -> {
                        statusTv.text = "Waiting for ${srv.name}..."
                    }
                }
            }
        }

        fun clickCfChallengeOnly() {
            val wv = webViewRef ?: return
            val token = stepToken.get()
            runCatching {
                wv.evaluateJavascript(
                    """
                    (function(){
                        var cf = document.querySelector('#challenge-stage input[type=checkbox], .cf-turnstile input, .cb-i, #turnstile-wrapper input');
                        if (cf) { cf.click(); return 'CLICKED'; }
                        var ifr = document.querySelector('iframe[src*="challenges.cloudflare.com"]');
                        if (ifr) {
                            var r = ifr.getBoundingClientRect();
                            return 'IFRAME:' + Math.round(r.left + 32) + ':' + Math.round(r.top + r.height / 2);
                        }
                        return 'NONE';
                    })();
                    """.trimIndent()
                ) { res ->
                    if (closed.get() || stepToken.get() != token) return@evaluateJavascript
                    val clean = res?.replace("\"", "")?.trim().orEmpty()
                    if (clean.startsWith("IFRAME:")) {
                        val parts = clean.split(":")
                        val density = ctx.resources.displayMetrics.density
                        val x = (parts.getOrNull(1)?.toFloatOrNull() ?: 80f) * density
                        val y = (parts.getOrNull(2)?.toFloatOrNull() ?: 160f) * density
                        val now = SystemClock.uptimeMillis()
                        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
                        val up = MotionEvent.obtain(now, now + 80L, MotionEvent.ACTION_UP, x, y, 0)
                        wv.dispatchTouchEvent(down)
                        wv.dispatchTouchEvent(up)
                        down.recycle()
                        up.recycle()
                        statusTv.text = "Tapped CF Turnstile • Waiting for clearance..."
                    } else if (clean == "CLICKED") {
                        statusTv.text = "Clicked CF checkbox • Waiting..."
                    } else if (!challengeDetected) {
                        val srv = currentTarget()
                        saveDomainState(wv.url ?: srv.probeUrl, requireClearance = false)
                        statusTv.text = "✓ No CF challenge on ${srv.name} — Saved!"
                        handler.postDelayed({ advanceNext(token, true) }, 350L)
                    } else {
                        statusTv.text = "Tap the Cloudflare box manually, then wait"
                    }
                }
            }
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (closed.get()) return
                val token = stepToken.get()
                val srv = currentTarget()
                if (saveDomainState(webViewRef?.url ?: srv.probeUrl, requireClearance = true)) {
                    statusTv.text = "✓ CF Cleared for ${srv.name}!"
                    statusTv.setTextColor(safeColor("#6EE7B7"))
                    handler.postDelayed({ advanceNext(token, true) }, 350L)
                }
                handler.postDelayed(this, 1600L)
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1120", "#070B14", alphaColor("#38BDF8", 110), dpf(ctx, 20f), 2)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }
        container.addView(titleTv)
        container.addView(statusTv)
        container.addView(progressBar)

        val wvHeight = (ctx.resources.displayMetrics.heightPixels * 0.56f).toInt().coerceAtLeast(dp(ctx, 300))
        val wv = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wvHeight).apply {
                bottomMargin = dp(ctx, 10)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = CF_BYPASS_UA
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    progressBar.progress = newProgress
                    if (newProgress >= 100) progressBar.visibility = View.INVISIBLE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    val token = stepToken.get()
                    handler.postDelayed({ inspectPageState(view, url, token) }, 700L)
                }
            }
        }
        webViewRef = wv
        container.addView(wv)

        val btnRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        btnRow.addView(Button(ctx).apply {
            text = "⚡ CF Click"
            textSize = 10f; isAllCaps = false; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
            setOnClickListener { clickCfChallengeOnly() }
        })
        btnRow.addView(Button(ctx).apply {
            text = if (targets.size > 1) "✓ Save & Next" else "✓ Save"
            textSize = 10f; isAllCaps = false; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = neonGradBtn("#2563EB", dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.15f).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
            setOnClickListener {
                val token = stepToken.get()
                val srv = currentTarget()
                val ok = saveDomainState(wv.url ?: srv.probeUrl, requireClearance = false)
                advanceNext(token, ok)
            }
        })
        if (targets.size > 1) {
            btnRow.addView(Button(ctx).apply {
                text = "Skip"
                textSize = 10f; isAllCaps = false; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = cardBg("#1E293B", "#334155", dpf(ctx, 10f), 1)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.7f).apply { marginEnd = dp(ctx, 5) }
                attachPressAnim(this)
                setOnClickListener { advanceNext(stepToken.get(), false) }
            })
        }
        btnRow.addView(Button(ctx).apply {
            text = "Close"
            textSize = 10f; isAllCaps = false; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#141C2B", "#2A3650", dpf(ctx, 10f), 1)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.7f)
            attachPressAnim(this)
            setOnClickListener { closeAll() }
        })
        container.addView(btnRow)

        val dlg = AlertDialog.Builder(ctx).setView(container).setCancelable(false).create()
        dialogRef = dlg
        dlg.setOnDismissListener { if (!closed.get()) closeAll() }

        runCatching {
            CookieManager.getInstance().setAcceptCookie(true)
            CookieManager.getInstance().setAcceptThirdPartyCookies(wv, true)
            dlg.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
            dlg.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.95).toInt()
            dlg.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
            loadCurrentTarget()
            handler.postDelayed(pollRunnable, 1600L)
        }
    }

    fun getSflixHost(ctx: Context): String =
        prefs(ctx).getString(KEY_SFLIX_HOST, SFLIX_HOSTS.first()) ?: SFLIX_HOSTS.first()
    fun setSflixHost(ctx: Context, host: String) =
        prefs(ctx).edit().putString(KEY_SFLIX_HOST, host).apply()

    fun getPreferredPlayer(ctx: Context): String =
        prefs(ctx).getString(KEY_PLAYER, PLAYER_OPTIONS.first()) ?: PLAYER_OPTIONS.first()
    fun setPreferredPlayer(ctx: Context, player: String) = prefs(ctx).edit().putString(KEY_PLAYER, player).apply()

    fun isExtractorEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_EXT_PREFIX${key.lowercase()}", true)
    fun setExtractorEnabled(ctx: Context, key: String, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("$KEY_EXT_PREFIX${key.lowercase()}", enabled).apply()

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

    fun getPreferredQuality(ctx: Context): String = when {
        isQuality4kEnabled(ctx) -> "4K"
        isQuality1080Enabled(ctx) -> "1080p"
        isQuality720Enabled(ctx) -> "720p"
        isQuality480Enabled(ctx) -> "480p"
        else -> "AUTO"
    }

    fun isLowBufferMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)
    fun setLowBufferMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()

    fun isDownloadOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DOWNLOAD_ONLY, false)
    fun setDownloadOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_DOWNLOAD_ONLY, enabled).apply()

    fun getConcurrency(ctx: Context): Int = prefs(ctx).getInt(KEY_CONCURRENCY, 24)
    fun setConcurrency(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 50)).apply()

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_TIMEOUT_SEC, 25)
    fun setTimeoutSeconds(ctx: Context, sec: Int) = prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(10, 60)).apply()

    fun isShuffleHomeCategories(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHUFFLE_HOME, false)
    fun setShuffleHomeCategories(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHUFFLE_HOME, enabled).apply()

    fun isServerEnabled(ctx: Context, key: String): Boolean {
        val enabled = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)
        if (!enabled) return false
        val langPref = MovieFoxProvider.getPreferredLanguage(ctx)
        if (langPref == "HI" || isHindiOnly(ctx)) {
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

    // =========================================================================
    // CS3 / SkyStream / Nuvio Manifest Repo Storage & Parser
    // =========================================================================
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

    private fun detectRepoType(url: String, explicit: String): String {
        if (!explicit.equals("AUTO", true)) return explicit.uppercase()
        val low = url.lowercase()
        return when {
            low.contains("manifest.json") || low.contains("nuvio") || low.contains("stremio") -> "NUVIO"
            low.contains("skystream") || low.contains("sky") -> "SKYSTREAM"
            else -> "CS3"
        }
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
                    val tag = obj.optString("tag", "CS3").ifBlank { "CS3" }
                    out.add(
                        ServerItem(
                            key = key,
                            name = name,
                            tag = tag,
                            probeUrl = probeUrl,
                            accentHex = obj.optString("accentHex", "#38BDF8"),
                            isCustom = true,
                            isHindi = true,
                            category = "Movies",
                            runtimeBadge = "⚡ MF",
                            thumbnailUrl = obj.optString("thumbnailUrl", "")
                        )
                    )
                }
            }
        }
        return out.distinctBy { "${it.name.lowercase()}|${it.probeUrl.lowercase()}" }
    }

    private fun saveCs3Servers(ctx: Context, list: List<ServerItem>) {
        val deduped = list.distinctBy { "${it.name.lowercase()}|${it.probeUrl.lowercase()}" }
        cachedCs3Servers.clear()
        cachedCs3Servers.addAll(deduped)
        val arr = JSONArray()
        deduped.forEach { srv ->
            arr.put(
                JSONObject()
                    .put("key", srv.key)
                    .put("name", srv.name)
                    .put("tag", srv.tag)
                    .put("probeUrl", srv.probeUrl)
                    .put("accentHex", srv.accentHex)
                    .put("runtimeBadge", "⚡ MF")
                    .put("thumbnailUrl", srv.thumbnailUrl)
            )
        }
        prefs(ctx).edit().putString(KEY_CS3_SERVERS, arr.toString()).apply()
    }

    fun getCs3Repos(ctx: Context): MutableList<Cs3RepoConfig> {
        val p = prefs(ctx)
        cachedCs3Repos?.let { return it }
        val loaded = mutableListOf<Cs3RepoConfig>()
        val raw = p.getString(KEY_CS3_REPOS, null)
        if (!raw.isNullOrBlank()) {
            runCatching {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    val id = obj.optString("id").trim()
                    val name = obj.optString("name").trim()
                    val url = obj.optString("repoUrl").trim()
                    if (id.isNotBlank() && url.isNotBlank()) {
                        loaded.add(
                            Cs3RepoConfig(
                                id = id,
                                name = name.ifBlank { url.substringAfter("://").substringBefore("/") },
                                repoUrl = url,
                                repoType = obj.optString("repoType", "CS3").ifBlank { "CS3" },
                                enabled = obj.optBoolean("enabled", true),
                                serverCount = obj.optInt("serverCount", 0)
                            )
                        )
                    }
                }
            }
        }
        PERMANENT_REPOS.asReversed().forEach { perm ->
            if (loaded.none { it.id == perm.id }) {
                loaded.add(0, perm.copy())
            }
        }
        cachedCs3Repos = loaded
        return loaded
    }

    fun saveCs3Repos(ctx: Context, list: List<Cs3RepoConfig>) {
        cachedCs3Repos = list.toMutableList()
        val arr = JSONArray()
        list.forEach { r ->
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("name", r.name)
                    .put("repoUrl", r.repoUrl)
                    .put("repoType", r.repoType)
                    .put("enabled", r.enabled)
                    .put("serverCount", r.serverCount)
            )
        }
        prefs(ctx).edit().putString(KEY_CS3_REPOS, arr.toString()).apply()
    }

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 15000): ByteArray? = try {
        val conn = (URL(toRawGithubUrl(urlStr)).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CF_BYPASS_UA)
        }
        val bytes = if (conn.responseCode in 200..299) conn.inputStream.use { it.readBytes() } else null
        runCatching { conn.errorStream?.close() }
        conn.disconnect()
        bytes
    } catch (e: Exception) {
        logParsing("RepoFetch", urlStr, "HTTP", e)
        null
    }

    private fun parseJsonServerEntries(
        body: String,
        idTag: String,
        defaultUrl: String,
        repoType: String,
        out: MutableList<ServerItem>
    ): List<String> {
        val nestedPluginLists = mutableListOf<String>()
        val tagLabel = when (repoType.uppercase()) {
            "NUVIO" -> "NUVIO"
            "SKYSTREAM" -> "SKY"
            else -> "CS3"
        }

        runCatching {
            val entries = mutableListOf<JSONObject>()
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
            } else if (body.startsWith("{")) {
                val root = JSONObject(body)

                if (root.has("resources") || root.has("catalogs") || (root.has("id") && root.has("version") && root.has("name") && !root.has("pluginLists"))) {
                    val mName = root.optString("name").trim().ifBlank { "Nuvio Addon" }
                    val baseAddonUrl = defaultUrl.substringBefore("/manifest.json").trimEnd('/')
                    val slug = mName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    val thumb = root.optString("logo").ifBlank { root.optString("background") }.trim()
                    out.add(
                        ServerItem(
                            key = "nuv_${idTag}_${slug.ifBlank { "addon" }}",
                            name = mName,
                            tag = "NUVIO",
                            probeUrl = baseAddonUrl.ifBlank { defaultUrl },
                            accentHex = "#10B981",
                            isCustom = true,
                            isHindi = true,
                            category = "Movies",
                            runtimeBadge = "⚡ MF",
                            thumbnailUrl = thumb
                        )
                    )
                    return nestedPluginLists
                }

                root.optJSONArray("pluginLists")?.let { pLists ->
                    for (i in 0 until pLists.length()) {
                        val u = pLists.optString(i).trim()
                        if (u.startsWith("http")) nestedPluginLists.add(u)
                    }
                }

                listOf("plugins", "items", "providers", "servers", "sources", "addons").forEach { key ->
                    root.optJSONArray(key)?.let { arr ->
                        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
                    }
                }
            }

            entries.forEachIndexed { idx, pObj ->
                val finalName = pObj.optString("name").trim().ifBlank { pObj.optString("internalName").trim() }
                if (finalName.isBlank()) return@forEachIndexed
                val url = pObj.optString("url")
                    .ifBlank { pObj.optString("mainUrl") }
                    .ifBlank { pObj.optString("pluginUrl") }
                    .ifBlank { pObj.optString("manifestUrl") }
                    .ifBlank { defaultUrl }.trim()
                val thumb = pObj.optString("iconUrl")
                    .ifBlank { pObj.optString("image") }
                    .ifBlank { pObj.optString("poster") }
                    .ifBlank { pObj.optString("logo") }.trim()
                val slug = finalName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                out.add(
                    ServerItem(
                        key = "ext_${idTag}_${slug.ifBlank { "srv$idx" }}",
                        name = finalName,
                        tag = tagLabel,
                        probeUrl = toRawGithubUrl(url),
                        accentHex = AVATAR_PALETTE[idx % AVATAR_PALETTE.size],
                        isCustom = true,
                        isHindi = true,
                        category = "Movies",
                        runtimeBadge = "⚡ MF",
                        thumbnailUrl = thumb
                    )
                )
            }
        }.onFailure {
            logParsing("RepoParser", defaultUrl, "JSON", it)
        }
        return nestedPluginLists
    }

    private suspend fun fetchServersFromCs3Repo(repo: Cs3RepoConfig): List<ServerItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ServerItem>()
        val rawUrl = toRawGithubUrl(repo.repoUrl)
        val bytes = readHttpBytes(rawUrl) ?: return@withContext emptyList()
        if (bytes.isEmpty()) return@withContext emptyList()
        val idTag = repo.id.takeLast(6)

        if (rawUrl.substringBefore("?").endsWith(".cs3", true)) {
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".json", true)) {
                            parseJsonServerEntries(zis.bufferedReader().readText().trim(), idTag, rawUrl, repo.repoType, out)
                        }
                        entry = zis.nextEntry
                    }
                }
            }.onFailure { logParsing("CS3Zip", rawUrl, "ZIP", it) }
            return@withContext out.distinctBy { "${it.name.lowercase()}|${it.probeUrl.lowercase()}" }
        }

        val body = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
        if (body.isNotBlank()) {
            val nestedLists = parseJsonServerEntries(body, idTag, rawUrl, repo.repoType, out)
            nestedLists.take(3).forEach { listUrl ->
                val listBytes = readHttpBytes(listUrl) ?: return@forEach
                val listBody = runCatching { String(listBytes, Charsets.UTF_8).trim() }.getOrDefault("")
                if (listBody.isNotBlank()) {
                    parseJsonServerEntries(listBody, idTag, listUrl, repo.repoType, out)
                }
            }
        }
        out.distinctBy { "${it.name.lowercase()}|${it.probeUrl.lowercase()}" }
    }

    private class ServerListAdapter(
        private val ctx: Context,
        private val getAccentHex: () -> String,
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
            val statusDot: View,
            val nameTv: TextView,
            val platformTv: TextView,
            val tagTv: TextView,
            val statusTv: TextView,
            val urlTv: TextView,
            val upBtn: TextView,
            val downBtn: TextView,
            val sw: NeonSwitch
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
                    background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                    setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
                }

                val avatarWrap = FrameLayout(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply { marginEnd = dp(ctx, 10) }
                }
                val avatarTv = TextView(ctx).apply {
                    textSize = 11.5f
                    gravity = Gravity.CENTER
                    setTypeface(typeface, Typeface.BOLD)
                    layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
                }
                val statusDot = View(ctx).apply {
                    layoutParams = FrameLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9), Gravity.END or Gravity.BOTTOM)
                }
                avatarWrap.addView(avatarTv)
                avatarWrap.addView(statusDot)

                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                        marginEnd = dp(ctx, 6)
                    }
                }
                val nameTv = TextView(ctx).apply {
                    textSize = 13f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                }
                val badgeLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        topMargin = dp(ctx, 3)
                    }
                }
                val platformTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                }
                val tagTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 4) }
                }
                val statusTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 1), dp(ctx, 5), dp(ctx, 1))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 4) }
                }
                badgeLine.addView(platformTv)
                badgeLine.addView(tagTv)
                badgeLine.addView(statusTv)

                val urlTv = TextView(ctx).apply {
                    textSize = 9.2f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#6B7894"))
                    setPadding(0, dp(ctx, 2), 0, 0)
                }
                col.addView(nameTv)
                col.addView(badgeLine)
                col.addView(urlTv)

                val orderCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    gravity = Gravity.CENTER
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(ctx, 8)
                    }
                }
                val upBtn = TextView(ctx).apply {
                    text = "▲"
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 18)).apply { bottomMargin = dp(ctx, 3) }
                    attachPressAnim(this)
                }
                val downBtn = TextView(ctx).apply {
                    text = "▼"
                    textSize = 8.5f
                    gravity = Gravity.CENTER
                    includeFontPadding = false
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 24), dp(ctx, 18))
                    attachPressAnim(this)
                }
                orderCol.addView(upBtn)
                orderCol.addView(downBtn)

                val sw = NeonSwitch(ctx, true, getAccentHex())

                card.addView(avatarWrap)
                card.addView(col)
                card.addView(orderCol)
                card.addView(sw)

                holder = VH(card, avatarTv, statusDot, nameTv, platformTv, tagTv, statusTv, urlTv, upBtn, downBtn, sw)
                card.tag = holder
                rowView = card
            } else {
                rowView = convertView
                holder = convertView.tag as VH
            }

            val srv = items[position]
            val isOn = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", true)
            val accent = getAccentHex()

            holder.root.background = glassCardBg(
                topHex = if (isOn) "#141C2F" else "#0E131F",
                bottomHex = if (isOn) "#0D1322" else "#0A0E17",
                strokeColor = if (isOn) alphaColor(srv.accentHex, 78) else safeColor("#1C2438"),
                radius = dpf(ctx, 15f),
                strokeWidth = 2
            )

            holder.avatarTv.text = monogramOf(srv.name)
            holder.avatarTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.avatarTv.background = tintBadgeBg(srv.accentHex, dpf(ctx, 10f), 42, 110)

            val health = getServerHealth(srv.key)
            val dotHex = when {
                health == null -> if (isOn) "#38BDF8" else "#475569"
                health.isHealthy -> "#22C55E"
                health.status.contains("TIMEOUT", true) -> "#F59E0B"
                else -> "#EF4444"
            }
            holder.statusDot.background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(safeColor(dotHex))
                setStroke(dp(ctx, 2), safeColor("#0E1320"))
            }

            holder.nameTv.text = srv.name
            holder.nameTv.alpha = if (isOn) 1f else 0.58f

            holder.platformTv.text = srv.runtimeBadge
            holder.platformTv.setTextColor(safeColor("#38BDF8"))
            holder.platformTv.background = tintBadgeBg("#38BDF8", dpf(ctx, 6f), 32, 70)

            holder.tagTv.text = srv.tag
            holder.tagTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.tagTv.background = tintBadgeBg(srv.accentHex, dpf(ctx, 6f), 32, 70)

            if (health != null) {
                holder.statusTv.visibility = View.VISIBLE
                holder.statusTv.text = if (health.latencyMs > 0) "${health.status} ${health.latencyMs}ms" else health.status
                val hHex = when {
                    health.isHealthy -> "#34D399"
                    health.status.contains("TIMEOUT", true) -> "#FBBF24"
                    else -> "#F87171"
                }
                holder.statusTv.setTextColor(safeColor(hHex))
                holder.statusTv.background = tintBadgeBg(hHex, dpf(ctx, 6f), 38, 95)
            } else {
                holder.statusTv.visibility = View.GONE
            }

            holder.urlTv.text = srv.probeUrl

            val canUp = position > 0
            val canDown = position < items.lastIndex
            holder.upBtn.setTextColor(safeColor(if (canUp) "#7DD3FC" else "#334155"))
            holder.upBtn.background = cardBg(if (canUp) "#162238" else "#0E1422", if (canUp) "#263B5E" else "#182033", dpf(ctx, 6f), 1)
            holder.downBtn.setTextColor(safeColor(if (canDown) "#7DD3FC" else "#334155"))
            holder.downBtn.background = cardBg(if (canDown) "#162238" else "#0E1422", if (canDown) "#263B5E" else "#182033", dpf(ctx, 6f), 1)

            holder.upBtn.setOnClickListener { if (canUp) onMoveUp(srv) }
            holder.downBtn.setOnClickListener { if (canDown) onMoveDown(srv) }

            holder.sw.onCheckedChange = null
            holder.sw.updateAccent(accent)
            holder.sw.setCheckedSilent(isOn)
            holder.sw.onCheckedChange = { checked ->
                setServerEnabled(ctx, srv.key, checked)
                onToggleChanged()
                notifyDataSetChanged()
            }
            holder.root.setOnClickListener { holder.sw.toggle() }
            return rowView
        }
    }

    private fun buildServerHealthCard(
        ctx: Context,
        srv: ServerItem,
        h: ServerHealthInfo?,
        onCfSolved: () -> Unit
    ): LinearLayout {
        val isHealthy = h?.isHealthy == true
        val statusText = h?.status ?: "READY"
        val accentHex = when {
            h == null -> "#475569"
            isHealthy -> "#22C55E"
            statusText.contains("TIMEOUT", true) -> "#F59E0B"
            else -> "#EF4444"
        }
        val badgeFg = when {
            h == null -> "#94A3B8"
            isHealthy -> "#34D399"
            statusText.contains("TIMEOUT", true) -> "#FBBF24"
            else -> "#F87171"
        }
        val isVerified = isDomainCfVerified(ctx, srv.probeUrl)

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = glassCardBg(
                topHex = "#131A2B",
                bottomHex = "#0C111E",
                strokeColor = alphaColor(accentHex, if (h == null) 80 else 135),
                radius = dpf(ctx, 14f),
                strokeWidth = 2
            )
            setPadding(dp(ctx, 8), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 7) }

            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 4), dp(ctx, 36)).apply { marginEnd = dp(ctx, 9) }
                background = pillBg(accentHex, dpf(ctx, 4f))
            })

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    marginEnd = dp(ctx, 6)
                }
            }
            infoCol.addView(TextView(ctx).apply {
                text = "${srv.name}  ⚡ MF"
                textSize = 12.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            val statusRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                    topMargin = dp(ctx, 3)
                }
            }
            statusRow.addView(TextView(ctx).apply {
                text = if (h != null && h.latencyMs > 0) "● $statusText • ${h.latencyMs}ms" else "● $statusText"
                textSize = 8.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(badgeFg))
                background = tintBadgeBg(accentHex, dpf(ctx, 7f), 38, 100)
                setPadding(dp(ctx, 7), dp(ctx, 2), dp(ctx, 7), dp(ctx, 2))
            })
            infoCol.addView(statusRow)

            infoCol.addView(TextView(ctx).apply {
                text = h?.detail ?: srv.probeUrl
                textSize = 9.2f
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(safeColor("#7D8AA6"))
                setPadding(0, dp(ctx, 3), 0, 0)
            })
            addView(infoCol)

            addView(TextView(ctx).apply {
                text = if (isVerified) "CF ✓" else "CF"
                textSize = 9.2f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(if (isVerified) "#34D399" else "#38BDF8"))
                background = tintBadgeBg(if (isVerified) "#10B981" else "#38BDF8", dpf(ctx, 9f), 38, 105)
                setPadding(dp(ctx, 9), dp(ctx, 7), dp(ctx, 9), dp(ctx, 7))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 5) }
                attachPressAnim(this)
                setOnClickListener {
                    openCfBypassWindow(ctx, srv.name, srv.probeUrl, onCfSolved)
                }
            })

            addView(TextView(ctx).apply {
                text = "COPY"
                textSize = 9.2f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#93C5FD"))
                background = tintBadgeBg("#2563EB", dpf(ctx, 9f), 34, 95)
                setPadding(dp(ctx, 9), dp(ctx, 7), dp(ctx, 9), dp(ctx, 7))
                attachPressAnim(this)
                setOnClickListener {
                    copyToClipboard(ctx, srv.name, "${srv.name}: $statusText -> ${h?.detail ?: srv.probeUrl}")
                }
            })
        }
    }

    fun show(rawCtx: Context) {
        val ctx = resolveActivityContext(rawCtx)
        if (!isContextAlive(ctx)) return
        bindContext(ctx)
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val cs3RepoList = getCs3Repos(ctx).toMutableList()
        var activeTheme = getSelectedTheme(ctx)
        var selectedLanguage = MovieFoxProvider.getPreferredLanguage(ctx)
        var selectedPlayer = getPreferredPlayer(ctx)
        var selectedSflixHost = getSflixHost(ctx)
        var useWebViewCf = isUseWebViewCf(ctx)
        var autoOffDead = isAutoOffDeadServers(ctx)
        var bannerThumbUrl = getCustomBannerThumbnailUrl(ctx)
        var currentQuery = ""
        var selectedCategory = "All"
        var selectedRepoType = "AUTO"
        var selectedLogFilter = "ALL"
        var currentConcurrency = getConcurrency(ctx)
        var currentTimeout = getTimeoutSeconds(ctx)
        var hindiOnly = isHindiOnly(ctx) || selectedLanguage == "HI"
        var downloadOnly = isDownloadOnly(ctx)
        var shuffleHome = isShuffleHomeCategories(ctx)
        var q4k = isQuality4kEnabled(ctx)
        var q1080 = isQuality1080Enabled(ctx)
        var q720 = isQuality720Enabled(ctx)
        var q480 = isQuality480Enabled(ctx)
        var lowBuffer = isLowBufferMode(ctx)
        var activeTabIdx = 0

        val themedSwitches = mutableListOf<NeonSwitch>()
        val themedPlusBtns = mutableListOf<TextView>()
        val themedSectionBars = mutableListOf<View>()

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 13), dp(ctx, 13), dp(ctx, 13), dp(ctx, 13))
        }

        // ── Hero Header + Unique Banner & Thumbnail Strip ────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        titleCol.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX"
            textSize = 16.5f
            letterSpacing = 0.04f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        titleCol.addView(TextView(ctx).apply {
            text = "PRO ENGINE • CLOUDSTREAM & NUVIO CORE"
            textSize = 8.2f
            letterSpacing = 0.09f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, 0)
        })
        titleRow.addView(titleCol)

        titleRow.addView(TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            textSize = 9f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#6EE7B7"))
            background = tintBadgeBg("#10B981", dpf(ctx, 10f), 46, 130)
            setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
        })
        header.addView(titleRow)

        val showcaseBanner = FrameLayout(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 92)).apply {
                topMargin = dp(ctx, 10)
            }
            background = cardBg("#0A101D", "#273756", dpf(ctx, 14f), 2)
            clipToOutline = true
        }
        val bannerImageView = ImageView(ctx).apply {
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            scaleType = ImageView.ScaleType.CENTER_CROP
            alpha = 0.42f
        }
        val bannerOverlay = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
            setPadding(dp(ctx, 11), dp(ctx, 8), dp(ctx, 11), dp(ctx, 8))
        }
        val bannerTopRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val heroThumbBox = TextView(ctx).apply {
            text = "MF"
            textSize = 14f
            gravity = Gravity.CENTER
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 42), dp(ctx, 42)).apply { marginEnd = dp(ctx, 10) }
        }
        val bannerTextCol = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        }
        val bannerTitleTv = TextView(ctx).apply {
            text = "UNIQUE SERVER SHOWCASE"
            textSize = 11.5f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val bannerSubTv = TextView(ctx).apply {
            text = "Lang: $selectedLanguage • Tap any server thumbnail to toggle"
            textSize = 9.2f
            isSingleLine = true
            ellipsize = TextUtils.TruncateAt.END
            setTextColor(safeColor("#CBD5E1"))
        }
        bannerTextCol.addView(bannerTitleTv)
        bannerTextCol.addView(bannerSubTv)
        bannerTopRow.addView(heroThumbBox)
        bannerTopRow.addView(bannerTextCol)

        val thumbStripScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                topMargin = dp(ctx, 6)
            }
        }
        val thumbStripRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
        }
        thumbStripScroll.addView(thumbStripRow)

        bannerOverlay.addView(bannerTopRow)
        bannerOverlay.addView(thumbStripScroll)
        showcaseBanner.addView(bannerImageView)
        showcaseBanner.addView(bannerOverlay)
        header.addView(showcaseBanner)

        lateinit var applyServerFilter: () -> Unit
        lateinit var refreshHealthPane: () -> Unit

        fun refreshBannerAndThumbnails() {
            val activeServers = SERVERS.filter { isServerEnabled(ctx, it.key) }
            val featured = activeServers.firstOrNull() ?: SERVERS.firstOrNull()
            if (featured != null) {
                heroThumbBox.text = monogramOf(featured.name)
                bannerTitleTv.text = "TOP ACTIVE: ${featured.name.uppercase()} • ${featured.tag}"
                bannerSubTv.text = "${activeServers.size}/${SERVERS.size} Live • Lang: $selectedLanguage • ${activeTheme.name}"
            }

            if (bannerThumbUrl.isNotBlank()) {
                dialogScope.launch {
                    val bmp = loadBitmapFromUrl(bannerThumbUrl)
                    if (bmp != null && isContextAlive(ctx)) {
                        bannerImageView.setImageBitmap(bmp)
                        heroThumbBox.background = BitmapDrawable(ctx.resources, bmp)
                        heroThumbBox.text = ""
                    }
                }
            } else {
                bannerImageView.setImageDrawable(null)
                heroThumbBox.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
            }

            thumbStripRow.removeAllViews()
            SERVERS.take(16).forEach { srv ->
                val isOn = isServerEnabled(ctx, srv.key)
                val h = getServerHealth(srv.key)
                val dotHex = when {
                    h == null -> if (isOn) srv.accentHex else "#475569"
                    h.isHealthy -> "#22C55E"
                    isServerDead(h) -> "#EF4444"
                    else -> "#F59E0B"
                }
                val chip = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    background = cardBg(
                        bgHex = if (isOn) "#141D31" else "#0C101A",
                        strokeHex = if (isOn) srv.accentHex else "#243049",
                        radius = dpf(ctx, 9f),
                        strokeWidth = 1
                    )
                    setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 8), dp(ctx, 3))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
                        marginEnd = dp(ctx, 5)
                    }
                    alpha = if (isOn) 1f else 0.55f
                    attachPressAnim(this)
                    setOnClickListener {
                        val nextState = !isServerEnabled(ctx, srv.key)
                        setServerEnabled(ctx, srv.key, nextState)
                        applyServerFilter()
                        refreshBannerAndThumbnails()
                        safeToast(ctx, "${srv.name}: ${if (nextState) "ON" else "OFF"}")
                    }
                }
                chip.addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 7), dp(ctx, 7)).apply { marginEnd = dp(ctx, 5) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(safeColor(dotHex))
                    }
                })
                chip.addView(TextView(ctx).apply {
                    text = "${monogramOf(srv.name)} • ${srv.name}"
                    textSize = 8.5f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                if (srv.thumbnailUrl.isNotBlank()) {
                    val miniIv = ImageView(ctx).apply {
                        layoutParams = LinearLayout.LayoutParams(dp(ctx, 16), dp(ctx, 16)).apply { marginStart = dp(ctx, 5) }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                    }
                    chip.addView(miniIv)
                    dialogScope.launch {
                        val bmp = loadBitmapFromUrl(srv.thumbnailUrl)
                        if (bmp != null && isContextAlive(ctx)) {
                            miniIv.setImageBitmap(bmp)
                        }
                    }
                }
                thumbStripRow.addView(chip)
            }
        }

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 11) }
        }
        fun statChip(label: String, colorHex: String, marginEndDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                background = glassCardBg("#0E1525", "#090D18", alphaColor(colorHex, 75), dpf(ctx, 12f), 2)
                setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (marginEndDp > 0) marginEnd = dp(ctx, marginEndDp)
                }
            }
            val valTv = TextView(ctx).apply {
                text = "-"
                textSize = 13f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(colorHex))
            }
            val lblTv = TextView(ctx).apply {
                text = label
                textSize = 7.8f
                letterSpacing = 0.08f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#8A98B5"))
                setPadding(0, dp(ctx, 1), 0, 0)
            }
            box.addView(valTv)
            box.addView(lblTv)
            return box to valTv
        }
        val (srvChip, srvValTv) = statChip("SERVERS", "#38BDF8", 7)
        val (extChip, extValTv) = statChip("EXTRACTORS", "#C084FC", 7)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)
        statsRow.addView(srvChip)
        statsRow.addView(extChip)
        statsRow.addView(spdChip)
        header.addView(statsRow)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })

        fun refreshStats() {
            val srvOn = SERVERS.count { isServerEnabled(ctx, it.key) }
            srvValTv.text = "$srvOn/${SERVERS.size}"
            extValTv.text = "${EXTRACTORS.count { isExtractorEnabled(ctx, it.key) }}/${EXTRACTORS.size}"
            spdValTv.text = "${currentConcurrency}x • $selectedLanguage"
            refreshBannerAndThumbnails()
        }
        refreshStats()

        // ── Sleek Segmented Tab Bar with Icons ───────────────────────────────
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0C111D", "#1E2940", dpf(ctx, 14f), 2)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("⚡ Providers", "🧩 Extractors", "🎛️ Streaming", "📦 Repos", "🩺 Health")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title
                textSize = 10.8f
                isAllCaps = false
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 35)).apply {
                    marginEnd = dp(ctx, 3)
                }
                attachPressAnim(this)
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(tabBar)
        }
        root.addView(tabScroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) })

        // ── Providers Filter & Search Controls ───────────────────────────────
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search servers, tags..."
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            background = cardBg("#0F1524", "#24314B", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val allOnBtn = Button(ctx).apply {
            text = "ALL ON"
            textSize = 9.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38)).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
        }
        val allOffBtn = Button(ctx).apply {
            text = "OFF"
            textSize = 9.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 12f), 2)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 38))
            attachPressAnim(this)
        }
        actionRow.addView(searchBox)
        actionRow.addView(allOnBtn)
        actionRow.addView(allOffBtn)
        root.addView(actionRow)

        // Language Filter Chips Synced with MovieFoxProvider
        val langFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
        }
        val langChips = linkedMapOf<String, TextView>()
        val langLabels = mapOf(
            "ALL" to "🌐 ALL LANG",
            "HI" to "🇮🇳 HINDI",
            "EN" to "🇺🇸 ENGLISH",
            "SOUTH" to "🎬 SOUTH",
            "ASIAN" to "🌏 ASIAN"
        )
        fun refreshLangChips() {
            MovieFoxProvider.SUPPORTED_LANGUAGE_FILTERS.forEach { code ->
                val tv = langChips[code] ?: return@forEach
                val active = code.equals(selectedLanguage, true)
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn("#0284C7", dpf(ctx, 9f))
                else cardBg("#0F1523", "#222E46", dpf(ctx, 9f), 1)
            }
        }
        MovieFoxProvider.SUPPORTED_LANGUAGE_FILTERS.forEach { code ->
            val tv = TextView(ctx).apply {
                text = langLabels[code] ?: code
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener {
                    selectedLanguage = code
                    MovieFoxProvider.setPreferredLanguage(ctx, code)
                    if (code == "HI") {
                        hindiOnly = true
                        setHindiOnly(ctx, true)
                    }
                    refreshLangChips()
                    applyServerFilter()
                    safeToast(ctx, "Language filter: $code")
                }
            }
            langChips[code] = tv
            langFilterRow.addView(tv)
        }
        refreshLangChips()
        val langScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(langFilterRow)
        }
        root.addView(langScroll)

        // Dedicated Quick Action Row to Turn OFF Non-Working / Dead Servers
        val deadServersActionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val offDeadBtn = Button(ctx).apply {
            text = "🛑 OFF NON-WORKING SERVERS"
            textSize = 9.5f
            isAllCaps = false
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#FCA5A5"))
            background = tintBadgeBg("#EF4444", dpf(ctx, 10f), 42, 120)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
        }
        val testAndOffDeadBtn = Button(ctx).apply {
            text = "⚡ SCAN & OFF DEAD"
            textSize = 9.5f
            isAllCaps = false
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#D97706", dpf(ctx, 10f))
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 1f)
            attachPressAnim(this)
        }
        deadServersActionRow.addView(offDeadBtn)
        deadServersActionRow.addView(testAndOffDeadBtn)
        root.addView(deadServersActionRow)

        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val categories = listOf("All", "Movies", "Series", "AsianDrama", "Hindi", "Custom")
        val chipViews = mutableListOf<TextView>()

        fun refreshCategoryChips() {
            chipViews.forEachIndexed { idx, tv ->
                val active = categories[idx] == selectedCategory
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
                else cardBg("#111726", "#202A40", dpf(ctx, 10f), 1)
            }
        }
        categories.forEach { cat ->
            val tv = TextView(ctx).apply {
                text = cat
                textSize = 10f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener {
                    selectedCategory = cat
                    refreshCategoryChips()
                    applyServerFilter()
                }
            }
            chipViews.add(tv)
            chipRow.addView(tv)
        }
        refreshCategoryChips()
        val chipScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            addView(chipRow)
        }
        root.addView(chipScroll)

        val screenH = ctx.resources.displayMetrics.heightPixels
        val contentHeight = (screenH * 0.37f).toInt().coerceIn(dp(ctx, 250), dp(ctx, 380))

        lateinit var serverAdapter: ServerListAdapter
        serverAdapter = ServerListAdapter(
            ctx = ctx,
            getAccentHex = { activeTheme.primaryHex },
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
            val sorted = SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val filtered = sorted.filter { item ->
                val langOk = when (selectedLanguage) {
                    "HI" -> item.isHindi || item.isCustom
                    "ASIAN" -> item.category.equals("AsianDrama", true) || item.isCustom
                    else -> true
                }
                val catOk = when (selectedCategory) {
                    "All" -> true
                    "Hindi" -> item.isHindi
                    "Custom" -> item.isCustom
                    else -> item.category.equals(selectedCategory, ignoreCase = true)
                }
                val qOk = currentQuery.isEmpty() ||
                    item.name.lowercase().contains(currentQuery) ||
                    item.tag.lowercase().contains(currentQuery) ||
                    item.probeUrl.lowercase().contains(currentQuery)
                langOk && catOk && qOk
            }
            serverAdapter.submitList(filtered)
            refreshStats()
        }
        applyServerFilter()

        offDeadBtn.setOnClickListener {
            val turnedOff = disableDeadServers(ctx)
            applyServerFilter()
            refreshHealthPane()
            if (turnedOff > 0) {
                safeToast(ctx, "$turnedOff non-working servers turned OFF")
            } else {
                safeToast(ctx, "No failed/dead servers found yet — tap SCAN & OFF DEAD")
            }
        }

        testAndOffDeadBtn.setOnClickListener {
            testAndOffDeadBtn.text = "SCANNING..."
            testAndOffDeadBtn.isEnabled = false
            dialogScope.launch {
                supervisorScope {
                    SERVERS.map { srv -> async { probeServerLive(srv) } }.awaitAll()
                }
                val turnedOff = disableDeadServers(ctx)
                testAndOffDeadBtn.text = "⚡ SCAN & OFF DEAD"
                testAndOffDeadBtn.isEnabled = true
                applyServerFilter()
                refreshHealthPane()
                safeToast(ctx, "Scan complete: $turnedOff dead servers turned OFF")
            }
        }

        allOnBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", true) }
            ed.apply()
            applyServerFilter()
        }
        allOffBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", false) }
            ed.apply()
            applyServerFilter()
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
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 130))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // ── Tab 2: Extractors ────────────────────────────────────────────────
        val extSec = makeSectionLabel(ctx, "Link Extractors & Mirrors", activeTheme.primaryHex)
        themedSectionBars.add(extSec.getChildAt(0))
        extractorPane.addView(extSec)

        EXTRACTORS.forEachIndexed { idx, ext ->
            val badgeAccent = AVATAR_PALETTE[idx % AVATAR_PALETTE.size]
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            val headRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            headRow.addView(TextView(ctx).apply {
                text = ext.name
                textSize = 12.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            })
            headRow.addView(TextView(ctx).apply {
                text = ext.tag
                textSize = 8f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(badgeAccent))
                background = tintBadgeBg(badgeAccent, dpf(ctx, 6f), 34, 85)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginStart = dp(ctx, 6) }
            })
            col.addView(headRow)
            col.addView(TextView(ctx).apply {
                text = ext.hosts
                textSize = 9.5f
                setTextColor(safeColor("#7D8AA6"))
                setPadding(0, dp(ctx, 3), 0, 0)
            })
            val sw = NeonSwitch(ctx, isExtractorEnabled(ctx, ext.key), activeTheme.primaryHex).apply {
                onCheckedChange = { checked -> setExtractorEnabled(ctx, ext.key, checked); refreshStats() }
            }
            themedSwitches.add(sw)
            card.setOnClickListener { sw.toggle() }
            card.addView(col)
            card.addView(sw)
            extractorPane.addView(card)
        }

        // ── Tab 3: Streaming (Grouped Pro Cards) ─────────────────────────────
        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#7D8AA6")); setPadding(0, dp(ctx, 2), 0, 0) })
            val sw = NeonSwitch(ctx, initial, activeTheme.primaryHex).apply {
                onCheckedChange = { checked -> onChange(checked) }
            }
            themedSwitches.add(sw)
            card.setOnClickListener { sw.toggle() }
            card.addView(col)
            card.addView(sw)
            parent.addView(card)
        }

        fun makeStepperCard(parent: LinearLayout, title: String, desc: String, initial: Int, step: Int, minVal: Int, maxVal: Int, suffix: String, onChange: (Int) -> Unit) {
            var cur = initial
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 8) }
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#7D8AA6")); setPadding(0, dp(ctx, 2), 0, 0) })
            val valTv = TextView(ctx).apply {
                text = "$cur$suffix"
                textSize = 12.5f
                gravity = Gravity.CENTER
                minWidth = dp(ctx, 40)
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }
            val minusBtn = TextView(ctx).apply {
                text = "−"
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = cardBg("#1A2338", "#2C3B59", dpf(ctx, 9f), 1)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                attachPressAnim(this)
                setOnClickListener {
                    if (cur - step >= minVal) {
                        cur -= step; valTv.text = "$cur$suffix"; onChange(cur)
                    }
                }
            }
            val plusBtn = TextView(ctx).apply {
                text = "+"
                textSize = 16f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f))
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                attachPressAnim(this)
                setOnClickListener {
                    if (cur + step <= maxVal) {
                        cur += step; valTv.text = "$cur$suffix"; onChange(cur)
                    }
                }
            }
            themedPlusBtns.add(plusBtn)
            card.addView(col)
            card.addView(minusBtn)
            card.addView(valTv)
            card.addView(plusBtn)
            parent.addView(card)
        }

        val qSec = makeSectionLabel(ctx, "🎞️ Video Quality Filters", activeTheme.primaryHex)
        themedSectionBars.add(qSec.getChildAt(0))
        streamPane.addView(qSec)
        makeToggleCard(streamPane, "4K + 1440p UHD", "Ultra HD & QHD streams", q4k) { q4k = it; setQuality4kEnabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "1080p Full HD", "Crisp FHD streams", q1080) { q1080 = it; setQuality1080Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "720p HD & Web", "Standard HD & WebRip", q720) { q720 = it; setQuality720Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "480p & Below", "Data saver streams", q480) { q480 = it; setQuality480Enabled(ctx, it); refreshStats() }

        val pSec = makeSectionLabel(ctx, "⚡ Playback, Language & Engine", activeTheme.primaryHex)
        themedSectionBars.add(pSec.getChildAt(0))
        streamPane.addView(pSec)
        makeToggleCard(streamPane, "Auto-Off Dead Servers", "Automatically switch OFF servers that fail or time out", autoOffDead) {
            autoOffDead = it
            setAutoOffDeadServers(ctx, it)
            if (it) {
                disableDeadServers(ctx)
                applyServerFilter()
            }
        }
        makeToggleCard(streamPane, "Hindi Providers Only 🇮🇳", "Syncs HI language filter & hides English-only providers", hindiOnly) {
            hindiOnly = it
            setHindiOnly(ctx, it)
            selectedLanguage = if (it) "HI" else "ALL"
            MovieFoxProvider.setPreferredLanguage(ctx, selectedLanguage)
            refreshLangChips()
            applyServerFilter()
        }
        makeToggleCard(streamPane, "Download Only Links", "Prefer direct MP4/MKV links", downloadOnly) { downloadOnly = it; setDownloadOnly(ctx, it) }
        makeToggleCard(streamPane, "Zero-Buffer Mode", "Prioritize fast HLS/MP4 streams first", lowBuffer) { lowBuffer = it; setLowBufferMode(ctx, it) }
        makeToggleCard(streamPane, "Shuffle Home", "Shuffle home categories", shuffleHome) { shuffleHome = it; setShuffleHomeCategories(ctx, it) }

        val tSec = makeSectionLabel(ctx, "🚀 Performance Tuning", activeTheme.primaryHex)
        themedSectionBars.add(tSec.getChildAt(0))
        streamPane.addView(tSec)
        makeStepperCard(streamPane, "Parallel Workers", "Concurrent server fetch threads", currentConcurrency, 2, 4, 40, "x") {
            currentConcurrency = it; setConcurrency(ctx, it); refreshStats()
        }
        makeStepperCard(streamPane, "Request Timeout", "Per-server connection timeout", currentTimeout, 5, 10, 60, "s") {
            currentTimeout = it; setTimeoutSeconds(ctx, it)
        }

        val aSec = makeSectionLabel(ctx, "🎨 Appearance & Network", activeTheme.primaryHex)
        themedSectionBars.add(aSec.getChildAt(0))
        streamPane.addView(aSec)

        val thumbConfigCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        thumbConfigCard.addView(TextView(ctx).apply {
            text = "🖼️ Banner & Thumbnail Image URL"
            textSize = 13f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        val thumbUrlInput = EditText(ctx).apply {
            hint = "https://.../banner-or-thumbnail.jpg (leave empty for dynamic)"
            setText(bannerThumbUrl)
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 11f
            isSingleLine = true
            background = cardBg("#0B111E", "#263654", dpf(ctx, 10f), 1)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 36)).apply {
                topMargin = dp(ctx, 6)
                bottomMargin = dp(ctx, 6)
            }
        }
        thumbConfigCard.addView(thumbUrlInput)
        thumbConfigCard.addView(Button(ctx).apply {
            text = "APPLY BANNER THUMBNAIL"
            textSize = 10f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 34))
            attachPressAnim(this)
            setOnClickListener {
                bannerThumbUrl = thumbUrlInput.text?.toString().orEmpty().trim()
                setCustomBannerThumbnailUrl(ctx, bannerThumbUrl)
                refreshBannerAndThumbnails()
                safeToast(ctx, "Banner thumbnail updated")
            }
        })
        streamPane.addView(thumbConfigCard)

        val themeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        themeCard.addView(TextView(ctx).apply { text = "UI Accent Theme"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val themeScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val themeChipMap = linkedMapOf<String, LinearLayout>()

        lateinit var applyThemeEverywhere: () -> Unit

        fun refreshThemeSwatches() {
            UI_THEMES.forEach { pal ->
                val chip = themeChipMap[pal.id] ?: return@forEach
                val isSel = pal.id == activeTheme.id
                chip.background = if (isSel) {
                    GradientDrawable().apply {
                        setColor(alphaColor(pal.primaryHex, 70))
                        cornerRadius = dpf(ctx, 10f)
                        setStroke(dp(ctx, 2), safeColor(pal.primaryHex))
                    }
                } else {
                    cardBg("#0F1523", "#222E46", dpf(ctx, 10f), 1)
                }
            }
        }

        UI_THEMES.forEach { pal ->
            val chip = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                addView(View(ctx).apply {
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 10), dp(ctx, 10)).apply { marginEnd = dp(ctx, 6) }
                    background = GradientDrawable().apply {
                        shape = GradientDrawable.OVAL
                        setColor(safeColor(pal.primaryHex))
                    }
                })
                addView(TextView(ctx).apply {
                    text = pal.name
                    textSize = 10.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                })
                setOnClickListener {
                    activeTheme = pal
                    setSelectedTheme(ctx, pal.id)
                    applyThemeEverywhere()
                    refreshThemeSwatches()
                    refreshBannerAndThumbnails()
                }
            }
            themeChipMap[pal.id] = chip
            themeRow.addView(chip)
        }
        refreshThemeSwatches()
        themeScroll.addView(themeRow)
        themeCard.addView(themeScroll)
        streamPane.addView(themeCard)

        val playerCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        playerCard.addView(TextView(ctx).apply { text = "🎬 Preferred Player"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val playerScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val playerRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 8), 0, 0) }
        val playerChips = mutableListOf<TextView>()
        fun refreshPlayerChips() {
            playerChips.forEachIndexed { idx, tv ->
                val active = PLAYER_OPTIONS[idx].equals(selectedPlayer, true)
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 10f))
                else cardBg("#0F1523", "#222E46", dpf(ctx, 10f), 1)
            }
        }
        PLAYER_OPTIONS.forEach { pl ->
            val tv = TextView(ctx).apply {
                text = pl
                textSize = 10.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), dp(ctx, 6), dp(ctx, 11), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener {
                    selectedPlayer = pl
                    setPreferredPlayer(ctx, pl)
                    refreshPlayerChips()
                }
            }
            playerChips.add(tv)
            playerRow.addView(tv)
        }
        refreshPlayerChips()
        playerScroll.addView(playerRow)
        playerCard.addView(playerScroll)
        streamPane.addView(playerCard)

        val hostCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#131A2B", "#0D121F", safeColor("#222D45"), dpf(ctx, 15f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        hostCard.addView(TextView(ctx).apply { text = "🌐 Sflix Mirror Host"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val rg = RadioGroup(ctx).apply { setPadding(0, dp(ctx, 4), 0, 0) }
        SFLIX_HOSTS.forEach { h ->
            rg.addView(RadioButton(ctx).apply {
                text = h
                textSize = 11.2f
                setTextColor(safeColor("#CBD5E1"))
                isChecked = selectedSflixHost.equals(h, true)
                setOnCheckedChangeListener { _, checked -> if (checked) selectedSflixHost = h }
            })
        }
        hostCard.addView(rg)
        streamPane.addView(hostCard)

        // ── Tab 4: Repos & Manifest ──────────────────────────────────────────
        val addRepoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0E1A30", "#0A1120", alphaColor("#38BDF8", 115), dpf(ctx, 16f), 2)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        addRepoCard.addView(TextView(ctx).apply {
            text = "🔗 Add CS3 / SkyStream / Nuvio Manifest"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })
        addRepoCard.addView(TextView(ctx).apply {
            text = "Paste repo.json, plugins.json, .cs3, SkyStream repo, or Nuvio/Stremio manifest.json link"
            textSize = 9.5f
            setTextColor(safeColor("#8A9AB8"))
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 8))
        })

        val repoNameInput = EditText(ctx).apply {
            hint = "Repo / Addon Name (optional)"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isSingleLine = true
            background = cardBg("#0B111E", "#263654", dpf(ctx, 11f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 7), dp(ctx, 11), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38)).apply { bottomMargin = dp(ctx, 7) }
        }
        val repoUrlInput = EditText(ctx).apply {
            hint = "https://.../repo.json or .../manifest.json"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 11.5f
            isSingleLine = true
            background = cardBg("#0B111E", "#263654", dpf(ctx, 11f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 7), dp(ctx, 11), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 38)).apply { bottomMargin = dp(ctx, 8) }
        }

        val repoTypes = listOf("AUTO", "CS3", "SKYSTREAM", "NUVIO")
        val repoTypeChips = mutableListOf<TextView>()
        val repoTypeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        fun refreshRepoTypeChips() {
            repoTypeChips.forEachIndexed { i, tv ->
                val active = repoTypes[i] == selectedRepoType
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f))
                else cardBg("#11192B", "#22304C", dpf(ctx, 9f), 1)
            }
        }
        repoTypes.forEach { rt ->
            val tv = TextView(ctx).apply {
                text = rt
                textSize = 9.5f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener { selectedRepoType = rt; refreshRepoTypeChips() }
            }
            repoTypeChips.add(tv)
            repoTypeRow.addView(tv)
        }
        refreshRepoTypeChips()

        val cs3RepoHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        lateinit var renderRepoCards: () -> Unit

        suspend fun syncAllReposAndSave(): Int {
            val allFetched = mutableListOf<ServerItem>()
            supervisorScope {
                cs3RepoList.filter { it.enabled }.map { r ->
                    async {
                        val list = fetchServersFromCs3Repo(r)
                        r.serverCount = list.size
                        list
                    }
                }.awaitAll().forEach { allFetched.addAll(it) }
            }
            saveCs3Servers(ctx, allFetched)
            saveCs3Repos(ctx, cs3RepoList)
            applyServerFilter()
            renderRepoCards()
            return allFetched.size
        }

        val addBtnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        addBtnRow.addView(Button(ctx).apply {
            text = "+ ADD & FETCH"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 11f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                val rawUrl = repoUrlInput.text?.toString().orEmpty().trim()
                if (rawUrl.isBlank() || !rawUrl.contains(".")) {
                    safeToast(ctx, "Valid URL enter karein")
                    return@setOnClickListener
                }
                val finalUrl = toRawGithubUrl(rawUrl)
                val finalType = detectRepoType(finalUrl, selectedRepoType)
                val customName = repoNameInput.text?.toString().orEmpty().trim().ifBlank {
                    val host = runCatching { URL(finalUrl).host.removePrefix("www.") }.getOrDefault("Custom Repo")
                    "$finalType • $host"
                }
                val newRepo = Cs3RepoConfig(
                    id = "repo_${System.currentTimeMillis()}",
                    name = customName,
                    repoUrl = finalUrl,
                    repoType = finalType,
                    enabled = true,
                    serverCount = 0
                )
                cs3RepoList.add(newRepo)
                saveCs3Repos(ctx, cs3RepoList)
                repoNameInput.setText("")
                repoUrlInput.setText("")
                renderRepoCards()
                dialogScope.launch {
                    val total = syncAllReposAndSave()
                    safeToast(ctx, "Added $finalType! Total custom servers: $total")
                }
            }
        })
        addBtnRow.addView(Button(ctx).apply {
            text = "FETCH ALL"
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#0284C7", dpf(ctx, 11f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f)
            attachPressAnim(this)
            setOnClickListener {
                dialogScope.launch {
                    val total = syncAllReposAndSave()
                    safeToast(ctx, "Synced ${cs3RepoList.size} repos ($total servers)")
                }
            }
        })

        addRepoCard.addView(repoNameInput)
        addRepoCard.addView(repoUrlInput)
        addRepoCard.addView(repoTypeRow)
        addRepoCard.addView(addBtnRow)

        val permanentIds = PERMANENT_REPOS.map { it.id }.toSet()

        renderRepoCards = {
            cs3RepoHolder.removeAllViews()
            cs3RepoList.forEach { repo ->
                val typeColor = when (repo.repoType.uppercase()) {
                    "NUVIO" -> "#10B981"
                    "SKYSTREAM" -> "#38BDF8"
                    else -> "#A855F7"
                }
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = glassCardBg("#131A2B", "#0D121F", alphaColor(typeColor, 75), dpf(ctx, 15f), 2)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
                }
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(TextView(ctx).apply {
                    text = repo.name
                    textSize = 12.8f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(ctx, 6) }
                })
                head.addView(TextView(ctx).apply {
                    text = "${repo.repoType} • ${repo.serverCount}"
                    textSize = 8.5f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(safeColor(typeColor))
                    background = tintBadgeBg(typeColor, dpf(ctx, 8f), 40, 110)
                    setPadding(dp(ctx, 7), dp(ctx, 3), dp(ctx, 7), dp(ctx, 3))
                })
                card.addView(head)
                card.addView(TextView(ctx).apply {
                    text = repo.repoUrl
                    textSize = 9.5f
                    maxLines = 2
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#7D8AA6"))
                    setPadding(0, dp(ctx, 4), 0, dp(ctx, 8))
                })

                val rowBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                rowBtns.addView(Button(ctx).apply {
                    text = "⟳ FETCH SERVERS"
                    textSize = 10f
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    background = neonGradBtn("#0284C7", dpf(ctx, 10f))
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 35), 1f).apply { marginEnd = dp(ctx, 6) }
                    attachPressAnim(this)
                    setOnClickListener {
                        dialogScope.launch {
                            val fetched = fetchServersFromCs3Repo(repo)
                            repo.serverCount = fetched.size
                            val existingOther = cachedCs3Servers.filterNot { it.key.contains(repo.id.takeLast(6)) }
                            saveCs3Servers(ctx, existingOther + fetched)
                            saveCs3Repos(ctx, cs3RepoList)
                            applyServerFilter()
                            renderRepoCards()
                            safeToast(ctx, "${repo.name}: ${fetched.size} loaded!")
                        }
                    }
                })
                if (repo.id !in permanentIds) {
                    rowBtns.addView(Button(ctx).apply {
                        text = "DELETE"
                        textSize = 10f
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(safeColor("#FCA5A5"))
                        background = tintBadgeBg("#EF4444", dpf(ctx, 10f), 42, 120)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 35))
                        setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                        attachPressAnim(this)
                        setOnClickListener {
                            cs3RepoList.remove(repo)
                            saveCs3Repos(ctx, cs3RepoList)
                            val remaining = cachedCs3Servers.filterNot { it.key.contains(repo.id.takeLast(6)) }
                            saveCs3Servers(ctx, remaining)
                            applyServerFilter()
                            renderRepoCards()
                            safeToast(ctx, "Removed ${repo.name}")
                        }
                    })
                }
                card.addView(rowBtns)
                cs3RepoHolder.addView(card)
            }
        }
        renderRepoCards()
        cs3Pane.addView(addRepoCard)
        cs3Pane.addView(cs3RepoHolder)

        // ── Tab 5: Health & Logs ─────────────────────────────────────────────
        val cfBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = glassCardBg("#0B1D1E", "#081216", alphaColor("#10B981", 105), dpf(ctx, 16f), 2)
            setPadding(dp(ctx, 12), dp(ctx, 11), dp(ctx, 12), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) }
        }
        val cfHeader = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cfHeader.addView(TextView(ctx).apply {
            text = "🛡️ Cloudflare Shield"
            textSize = 13.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        cfHeader.addView(Button(ctx).apply {
            text = "⚡ Solve All"
            textSize = 9.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 9f))
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32)).apply { marginEnd = dp(ctx, 5) }
            attachPressAnim(this)
            setOnClickListener {
                val cfTargets = SERVERS.filter { isServerEnabled(ctx, it.key) && !isDomainCfVerified(ctx, it.probeUrl) }
                    .ifEmpty { SERVERS.filter { isServerEnabled(ctx, it.key) } }
                openCfBypassQueue(ctx, cfTargets) {
                    refreshHealthPane()
                    applyServerFilter()
                }
            }
        })
        cfHeader.addView(Button(ctx).apply {
            text = "✓ Sync"
            textSize = 9.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#2563EB", dpf(ctx, 9f))
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 32))
            attachPressAnim(this)
            setOnClickListener {
                val synced = syncCookiesFromWebView(ctx)
                refreshHealthPane()
                safeToast(ctx, "Synced CF cookies ($synced servers)")
            }
        })
        cfBox.addView(cfHeader)

        cfBox.addView(TextView(ctx).apply {
            text = "Auto-verifies non-CF sites & pauses only when a real Cloudflare challenge appears."
            textSize = 9.5f
            setTextColor(safeColor("#8EA3B5"))
            setPadding(0, dp(ctx, 4), 0, dp(ctx, 7))
        })

        val wvRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        wvRow.addView(TextView(ctx).apply {
            text = "Use WebView for CF challenges"
            textSize = 11.8f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        val cfSwitch = NeonSwitch(ctx, useWebViewCf, activeTheme.primaryHex).apply {
            onCheckedChange = { checked -> useWebViewCf = checked; setUseWebViewCf(ctx, checked) }
        }
        themedSwitches.add(cfSwitch)
        wvRow.setOnClickListener { cfSwitch.toggle() }
        wvRow.addView(cfSwitch)
        cfBox.addView(wvRow)

        val cfStatusBanner = TextView(ctx).apply {
            textSize = 10.5f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            attachPressAnim(this)
        }
        fun refreshCfBanner() {
            val totalSrvs = SERVERS.size.coerceAtLeast(1)
            val solvedSrvs = SERVERS.count { isDomainCfVerified(ctx, it.probeUrl) }
            if (solvedSrvs >= totalSrvs) {
                cfStatusBanner.text = "✓ All Servers Verified ($solvedSrvs/$totalSrvs)"
                cfStatusBanner.setTextColor(safeColor("#6EE7B7"))
                cfStatusBanner.background = tintBadgeBg("#10B981", dpf(ctx, 10f), 42, 120)
            } else if (solvedSrvs > 0) {
                cfStatusBanner.text = "✓ Verified ($solvedSrvs/$totalSrvs) — Tap to Verify Remaining"
                cfStatusBanner.setTextColor(safeColor("#FDE68A"))
                cfStatusBanner.background = tintBadgeBg("#D97706", dpf(ctx, 10f), 42, 120)
            } else {
                cfStatusBanner.text = "⚡ Tap Here to Verify & Solve CF for All Servers"
                cfStatusBanner.setTextColor(safeColor("#93C5FD"))
                cfStatusBanner.background = tintBadgeBg("#2563EB", dpf(ctx, 10f), 42, 120)
            }
        }
        cfStatusBanner.setOnClickListener {
            val remaining = SERVERS.filter { !isDomainCfVerified(ctx, it.probeUrl) }.ifEmpty { SERVERS }
            openCfBypassQueue(ctx, remaining) { refreshHealthPane() }
        }
        cfBox.addView(cfStatusBanner)

        cfBox.addView(Button(ctx).apply {
            text = "Clear CF cookies"
            textSize = 10.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#FCA5A5"))
            background = tintBadgeBg("#EF4444", dpf(ctx, 10f), 36, 110)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 35))
            attachPressAnim(this)
            setOnClickListener {
                clearAllCfCookies(ctx)
                refreshHealthPane()
                safeToast(ctx, "CF cookies cleared")
            }
        })

        val metricsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        fun metricPill(label: String, colorHex: String, endDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                background = glassCardBg("#111829", "#0B101C", alphaColor(colorHex, 80), dpf(ctx, 11f), 2)
                setPadding(dp(ctx, 6), dp(ctx, 7), dp(ctx, 6), dp(ctx, 7))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (endDp > 0) marginEnd = dp(ctx, endDp)
                }
            }
            val vTv = TextView(ctx).apply { text = "0"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex)) }
            val lTv = TextView(ctx).apply { text = label; textSize = 7.5f; letterSpacing = 0.06f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#8A98B5")) }
            box.addView(vTv)
            box.addView(lTv)
            return box to vTv
        }
        val (mLiveBox, mLiveTv) = metricPill("LIVE", "#34D399", 5)
        val (mToBox, mToTv) = metricPill("TIMEOUT", "#FBBF24", 5)
        val (mParseBox, mParseTv) = metricPill("PARSE ERR", "#F87171", 5)
        val (mAvgBox, mAvgTv) = metricPill("AVG MS", "#38BDF8", 0)
        metricsRow.addView(mLiveBox)
        metricsRow.addView(mToBox)
        metricsRow.addView(mParseBox)
        metricsRow.addView(mAvgBox)

        val logFilterNames = listOf("ALL", "SEARCH", "LOADLINK", "PARSING", "TIMEOUT", "ERROR")
        val logFilterChips = mutableListOf<TextView>()
        val logFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val healthCardsHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val logTv = TextView(ctx).apply {
            textSize = 9.2f
            typeface = Typeface.MONOSPACE
            setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#060911", "#1E2A42", dpf(ctx, 13f), 2)
            setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
        }
        fun refreshLogChips() {
            logFilterChips.forEachIndexed { idx, tv ->
                val active = logFilterNames[idx] == selectedLogFilter
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = if (active) neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f))
                else cardBg("#101624", "#202A40", dpf(ctx, 9f), 1)
            }
        }
        fun renderFilteredLogs() {
            val logs = getDiagnosticLogs(selectedLogFilter)
            logTv.text = if (logs.isEmpty()) {
                "> No [$selectedLogFilter] logs yet • ADB tag: $LOG_TAG"
            } else {
                logs.asReversed().take(80).joinToString("\n\n")
            }
        }
        logFilterNames.forEach { cat ->
            val tv = TextView(ctx).apply {
                text = cat
                textSize = 9f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 5) }
                attachPressAnim(this)
                setOnClickListener {
                    selectedLogFilter = cat
                    refreshLogChips()
                    renderFilteredLogs()
                }
            }
            logFilterChips.add(tv)
            logFilterRow.addView(tv)
        }
        refreshLogChips()

        refreshHealthPane = {
            refreshCfBanner()
            refreshBannerAndThumbnails()
            val healthValues = serverHealthMap.values
            val liveCount = healthValues.count { it.isHealthy }
            val timeoutCount = diagLogs.count { it.category == "TIMEOUT" }
            val parseErrCount = diagLogs.count { it.category == "PARSING" }
            val validLatencies = healthValues.map { it.latencyMs }.filter { it > 0L }
            val avgMs = if (validLatencies.isNotEmpty()) validLatencies.average().toLong() else 0L

            mLiveTv.text = "$liveCount/${SERVERS.size}"
            mToTv.text = "$timeoutCount"
            mParseTv.text = "$parseErrCount"
            mAvgTv.text = if (avgMs > 0) "${avgMs}ms" else "-"

            healthCardsHolder.removeAllViews()
            SERVERS.forEach { srv ->
                healthCardsHolder.addView(buildServerHealthCard(ctx, srv, getServerHealth(srv.key)) {
                    refreshHealthPane()
                    applyServerFilter()
                })
            }
            renderFilteredLogs()
        }

        val testAllBtn = Button(ctx).apply {
            text = "⚡ TEST ALL (${SERVERS.size})"
            textSize = 9.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = neonGradBtn("#059669", dpf(ctx, 11f))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 6) }
            attachPressAnim(this)
            setOnClickListener {
                text = "TESTING..."
                isEnabled = false
                dialogScope.launch {
                    supervisorScope { SERVERS.map { srv -> async { probeServerLive(srv) } }.awaitAll() }
                    if (isAutoOffDeadServers(ctx)) {
                        disableDeadServers(ctx)
                    }
                    text = "⚡ TEST ALL (${SERVERS.size})"
                    isEnabled = true
                    refreshHealthPane()
                    applyServerFilter()
                }
            }
        }

        healthPane.addView(cfBox)
        healthPane.addView(metricsRow)
        healthPane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(testAllBtn)
            addView(Button(ctx).apply {
                text = "COPY LOGS"
                textSize = 9.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                background = neonGradBtn("#0284C7", dpf(ctx, 11f))
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 6) }
                attachPressAnim(this)
                setOnClickListener {
                    val report = buildString {
                        appendLine("=== MovieFox Diagnostics ($selectedLogFilter) ===")
                        SERVERS.forEach { srv ->
                            val h = getServerHealth(srv.key)
                            appendLine("${srv.name}: ${h?.status ?: "READY"} (${h?.latencyMs ?: -1}ms)")
                        }
                        appendLine()
                        append(getDiagnosticLogs(selectedLogFilter).joinToString("\n"))
                    }
                    copyToClipboard(ctx, "Diagnostics", report)
                }
            })
            addView(Button(ctx).apply {
                text = "CLEAR"
                textSize = 9.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#CBD5E1"))
                background = cardBg("#1A2234", "#2D3A54", dpf(ctx, 11f), 1)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 0.72f)
                attachPressAnim(this)
                setOnClickListener {
                    clearDiagnosticLogs()
                    serverHealthMap.clear()
                    refreshHealthPane()
                    applyServerFilter()
                }
            })
        })
        healthPane.addView(healthCardsHolder)

        val logSec = makeSectionLabel(ctx, "Live Console Output", activeTheme.primaryHex)
        themedSectionBars.add(logSec.getChildAt(0))
        healthPane.addView(logSec)
        healthPane.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(logFilterRow) })
        healthPane.addView(logTv)
        refreshHealthPane()

        val scrollPanes = listOf(extractorPane, streamPane, cs3Pane, healthPane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            activeTabIdx = idx
            if (idx == 0) {
                serversListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                langScroll.visibility = View.VISIBLE
                deadServersActionRow.visibility = View.VISIBLE
                chipScroll.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
                serversListView.alpha = 0f
                serversListView.translationY = dpf(ctx, 8f)
                serversListView.animate().alpha(1f).translationY(0f).setDuration(150).start()
            } else {
                serversListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                langScroll.visibility = View.GONE
                deadServersActionRow.visibility = View.GONE
                chipScroll.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollView.scrollTo(0, 0)
                scrollPanes.forEachIndexed { i, p ->
                    val showPane = (i == idx - 1)
                    p.visibility = if (showPane) View.VISIBLE else View.GONE
                    if (showPane) {
                        p.alpha = 0f
                        p.translationY = dpf(ctx, 8f)
                        p.animate().alpha(1f).translationY(0f).setDuration(150).start()
                    }
                }
                if (idx == 4) refreshHealthPane()
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 11f))
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(safeColor("#8B98B3"))
                }
            }
            tabScroll.post {
                val targetBtn = tabButtons[idx]
                val scrollX = (targetBtn.left - tabScroll.width / 2 + targetBtn.width / 2).coerceAtLeast(0)
                tabScroll.smoothScrollTo(scrollX, 0)
            }
        }
        tabButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectTab(i) } }

        root.addView(serversListView)
        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }

        root.addView(View(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 1)).apply {
                topMargin = dp(ctx, 10)
                bottomMargin = dp(ctx, 9)
            }
            setBackgroundColor(safeColor("#1C263B"))
        })

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        val resetBtn = Button(ctx).apply {
            text = "RESET"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#CBD5E1"))
            background = cardBg("#151C2C", "#2A3652", dpf(ctx, 13f), 2)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            attachPressAnim(this)
            setOnClickListener {
                prefs(ctx).edit().clear().apply()
                MovieFoxProvider.setPreferredLanguage(ctx, "ALL")
                selectedLanguage = "ALL"
                cachedCs3Repos = null
                cachedCs3Servers.clear()
                serverHealthMap.clear()
                clearDiagnosticLogs()
                refreshLangChips()
                applyServerFilter()
                runCatching { dialog.dismiss() }
            }
        }
        val saveApplyBtn = Button(ctx).apply {
            text = "SAVE & APPLY"
            textSize = 11.8f
            letterSpacing = 0.04f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.4f).apply { marginStart = dp(ctx, 4) }
            attachPressAnim(this)
            setOnClickListener {
                saveCs3Repos(ctx, cs3RepoList)
                setSflixHost(ctx, selectedSflixHost)
                setSelectedTheme(ctx, activeTheme.id)
                MovieFoxProvider.setPreferredLanguage(ctx, selectedLanguage)
                setPreferredPlayer(ctx, selectedPlayer)
                setQuality4kEnabled(ctx, q4k)
                setQuality1080Enabled(ctx, q1080)
                setQuality720Enabled(ctx, q720)
                setQuality480Enabled(ctx, q480)
                setLowBufferMode(ctx, lowBuffer)
                setAutoOffDeadServers(ctx, autoOffDead)
                setCustomBannerThumbnailUrl(ctx, thumbUrlInput.text?.toString().orEmpty().trim())
                setConcurrency(ctx, currentConcurrency)
                setTimeoutSeconds(ctx, currentTimeout)
                setHindiOnly(ctx, hindiOnly)
                setDownloadOnly(ctx, downloadOnly)
                setShuffleHomeCategories(ctx, shuffleHome)
                safeToast(ctx, "Settings saved & applied ($selectedLanguage)")
                runCatching { dialog.dismiss() }
            }
        }
        footer.addView(resetBtn)
        footer.addView(saveApplyBtn)
        root.addView(footer)

        applyThemeEverywhere = {
            root.background = glassCardBg("#090D18", "#05070E", alphaColor(activeTheme.primaryHex, 95), dpf(ctx, 24f), 3)
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = dpf(ctx, 18f)
                setStroke(2, alphaColor(activeTheme.primaryHex, 175))
            }
            allOnBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 12f))
            saveApplyBtn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 13f))
            themedSwitches.forEach { it.updateAccent(activeTheme.primaryHex) }
            themedPlusBtns.forEach { it.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 9f)) }
            themedSectionBars.forEach { it.background = pillBg(activeTheme.primaryHex, 4f) }
            refreshLangChips()
            refreshCategoryChips()
            refreshPlayerChips()
            refreshRepoTypeChips()
            refreshLogChips()
            tabButtons.forEachIndexed { i, btn ->
                if (i == activeTabIdx) {
                    btn.background = neonGradBtn(activeTheme.primaryHex, dpf(ctx, 11f))
                    btn.setTextColor(Color.WHITE)
                }
            }
            serverAdapter.notifyDataSetChanged()
        }

        applyThemeEverywhere()
        refreshBannerAndThumbnails()
        selectTab(0)

        runCatching {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.95).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}

