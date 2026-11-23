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
import android.graphics.Color
import android.graphics.Typeface
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
import java.util.zip.ZipInputStream

object MovieFoxSettingsDialog {

    const val LOG_TAG = "MovieFoxDiag"
    private const val PREFS_NAME = "moviefox_ultra_max_v30"
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
        val runtimeBadge: String = "⚡ MF"
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

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var cachedCs3Repos: MutableList<Cs3RepoConfig>? = null
    private val cachedCs3Servers = CopyOnWriteArrayList<ServerItem>()
    private val diagLogs = CopyOnWriteArrayList<DiagLogEntry>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()
    @Volatile
    private var cs3Loaded = false

    fun bindContext(ctx: Context?) {
        if (ctx != null) appContext = ctx.applicationContext
    }

    fun currentContext(): Context? = appContext

    val EXTRACTORS = listOf(
        ExtractorItem("xcloud", "XCloud HLS & Direct", "xcloud.tattoo • stream.xcloud1.lol • workers.dev", "HLS"),
        ExtractorItem("hubcloud", "HubCloud & GamerXYT", "hubcloud.pw/dad/club/one • gamerxyt.com", "10Gbps"),
        ExtractorItem("gdflix", "GDFlix & GDLink", "gdflix.* • gdlink.* • new15.gdflix.*", "R2/DL"),
        ExtractorItem("pixeldrain", "Pixeldrain (.com / .dev)", "pixeldrain.com • pixeldrain.dev", "FAST"),
        ExtractorItem("gdrive", "Google Drive Direct", "drive.google.com • usercontent.google.com", "GDRIVE"),
        ExtractorItem("streamtape", "StreamTape Mirrors", "streamtape.com/to/net/xyz • shavetape • strcloud", "MP4"),
        ExtractorItem("voe", "Voe Mirrors", "voe.sx • voe.to • voe.co", "HLS/MP4"),
        ExtractorItem("buzz_hb_go", "BuzzServer / HBPlay / GoFlix", "fuckingfast.net • hbplay.pages.dev • goflix.sbs", "MIRROR"),
        ExtractorItem("basejwp", "BaseJWP / Nitrox / NxStream", "itsnitrox.tech • nxstreamhub • vibuxer • vidprime", "WORKER"),
        ExtractorItem("jwp_embeds", "Lulu / Vidara / MegaCloud / Wish / Moon", "luluvid • vidara • megacloud • streamwish • filemoon", "EMBED")
    )

    val BASE_SERVERS = listOf(
        ServerItem("sflix", "Sflix", "HLS/MP4", "https://sflix.film/", "#34D399", false, false, "Series", "⚡ MF"),
        ServerItem("play4u", "Play4u", "HI/M3U8", "https://play4u.org/", "#FB923C", false, true, "Movies", "⚡ MF"),
        ServerItem("prmovies", "Prmovies", "HI/M3U8", "https://prmovies.coupons/", "#F59E0B", false, true, "Movies", "⚡ MF"),
        ServerItem("hindidrama", "HindiDrama", "K-DRA", "https://hindidrama.net/", "#EC4899", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("themoviesflix", "TheMoviesFlix", "4K/1080p", "https://one.1moviesflix.lol/", "#22D3EE", false, true, "Movies", "⚡ MF"),
        ServerItem("dramaverse", "DramaVerse", "K-DRAMA", "https://mydverse.com/", "#A855F7", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("katdrama", "KatDrama", "ASIAN", "https://katdrama.org/", "#38BDF8", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("animeworld", "AnimeWorld", "ANIME", "https://watchanimeworld.top/", "#818CF8", false, true, "AsianDrama", "⚡ MF"),
        ServerItem("vegamovies", "VegaMovies", "DUAL/HI", "https://vegamovies.ps/", "#FACC15", false, true, "Movies", "⚡ MF"),
        ServerItem("cinestream", "CineStream Hub", "ALL/MULTI", "https://api.speedracelight.com/", "#10B981", false, true, "Movies", "⚡ CS"),
        ServerItem("videasy", "Videasy", "HLS/MP4", "https://api.speedracelight.com/", "#38BDF8", false, true, "Movies", "⚡ CS"),
        ServerItem("vidzee", "Vidzee", "MULTI/HLS", "https://player.vidzee.wtf/", "#C084FC", false, true, "Movies", "⚡ CS"),
        ServerItem("hexa", "Hexa", "HLS/1080p", "https://theemoviedb.hexa.su/", "#22D3EE", false, true, "Movies", "⚡ CS"),
        ServerItem("moviebox", "MovieBox", "MULTI/HD", "https://h5-api.aoneroom.com/", "#FB7185", false, true, "Series", "⚡ CS")
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
        return cachedCs3Servers.filter { it.tag.equals("NUVIO", true) || it.tag.equals("MANIFEST", true) }
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
    }

    fun getServerHealth(key: String): ServerHealthInfo? = serverHealthMap[key.lowercase()]

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
        val tMs = (appContext?.let { getTimeoutSeconds(it) } ?: 12).coerceIn(5, 20) * 1000
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
            if (!ok) appendDiagLog("ERROR", srv.key, "Probe $status | ${srv.probeUrl}", ms)
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
            info
        }
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
        radius: Float = 20f,
        strokeWidth: Int = 2
    ): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#141822"))
        cornerRadius = radius
        setStroke(strokeWidth, safeColor(strokeHex, "#252E42"))
    }

    private fun pillBg(bgHex: String, radius: Float = 14f): GradientDrawable = GradientDrawable().apply {
        setColor(safeColor(bgHex, "#1C2230"))
        cornerRadius = radius
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

    fun saveCookieForDomain(ctx: Context, domainOrUrl: String, cookie: String) {
        val host = runCatching {
            val raw = if (domainOrUrl.startsWith("http")) domainOrUrl else "https://$domainOrUrl"
            URL(raw).host.lowercase().removePrefix("www.")
        }.getOrDefault(domainOrUrl.lowercase())
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
        val host = runCatching {
            val raw = if (urlOrDomain.startsWith("http")) urlOrDomain else "https://$urlOrDomain"
            URL(raw).host.lowercase().removePrefix("www.")
        }.getOrDefault(urlOrDomain.lowercase())
        return map[host] ?: map.entries.firstOrNull { host.endsWith(it.key) || it.key.endsWith(host) }?.value
    }

    // =========================================================================
    // Advance CF WebView Solver — Single Server OR Sequential All-Servers Queue
    // =========================================================================
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
        var dialogRef: Dialog? = null
        var webViewRef: WebView? = null
        var idx = 0
        var savedCount = 0

        val titleTv = TextView(ctx).apply {
            textSize = 14.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        }
        val statusTv = TextView(ctx).apply {
            textSize = 10.5f
            setTextColor(safeColor("#93C5FD"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        }

        fun currentTarget(): ServerItem = targets[idx.coerceIn(0, targets.lastIndex)]

        fun tryCaptureCookie(url: String, forceSave: Boolean = false): Boolean {
            return runCatching {
                val cm = CookieManager.getInstance()
                cm.flush()
                val cookie = cm.getCookie(url).orEmpty()
                val hasClearance = cookie.contains("cf_clearance", true) ||
                    cookie.contains("__cf_bm", true) ||
                    cookie.contains("PHPSESSID", true)
                if (hasClearance || (forceSave && cookie.isNotBlank())) {
                    saveCookieForDomain(ctx, url, cookie)
                    appendDiagLog("INFO", currentTarget().key, "CF cookie saved (${cookie.length} chars)")
                    true
                } else {
                    false
                }
            }.getOrDefault(false)
        }

        fun closeAll() {
            if (!closed.compareAndSet(false, true)) return
            handler.removeCallbacksAndMessages(null)
            runCatching { webViewRef?.stopLoading(); webViewRef?.destroy() }
            webViewRef = null
            runCatching { dialogRef?.dismiss() }
            dialogRef = null
            syncCookiesFromWebView(ctx)
            if (savedCount > 0) safeToast(ctx, "CF Solved & Saved ($savedCount/${targets.size})")
            onCompleted?.invoke()
        }

        fun loadCurrentTarget() {
            if (closed.get()) return
            if (idx >= targets.size) {
                closeAll()
                return
            }
            val srv = currentTarget()
            titleTv.text = "🛡️ CF Bypass (${idx + 1}/${targets.size}) • ${srv.name}"
            statusTv.text = "Loading ${srv.probeUrl}..."
            webViewRef?.stopLoading()
            webViewRef?.loadUrl(srv.probeUrl)
        }

        fun advanceNext(markSaved: Boolean) {
            if (closed.get()) return
            if (markSaved) savedCount++
            idx++
            if (idx >= targets.size) {
                closeAll()
            } else {
                loadCurrentTarget()
            }
        }

        fun clickWebViewCenter() {
            val wv = webViewRef ?: return
            runCatching {
                val cx = (wv.width / 2f).coerceAtLeast(10f)
                val cy = (wv.height / 2f).coerceAtLeast(10f)
                val now = SystemClock.uptimeMillis()
                val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, cx, cy, 0)
                val up = MotionEvent.obtain(now, now + 90L, MotionEvent.ACTION_UP, cx, cy, 0)
                wv.dispatchTouchEvent(down)
                wv.dispatchTouchEvent(up)
                down.recycle()
                up.recycle()
                wv.evaluateJavascript(
                    """
                    (function(){
                        var btns = document.querySelectorAll('input[type=checkbox], button, [role=checkbox], .cb-i');
                        for (var i=0; i<btns.length; i++) {
                            try { btns[i].click(); } catch(e) {}
                        }
                    })();
                    """.trimIndent(),
                    null
                )
            }
        }

        val pollRunnable = object : Runnable {
            override fun run() {
                if (closed.get()) return
                val srv = currentTarget()
                if (tryCaptureCookie(srv.probeUrl, forceSave = false)) {
                    statusTv.text = "✓ Solved ${srv.name}! Moving next..."
                    handler.postDelayed({ advanceNext(true) }, 450L)
                }
                handler.postDelayed(this, 1400L)
            }
        }

        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0A0F1A", "#2563EB", 24f, 2)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
        }
        container.addView(titleTv)
        container.addView(statusTv)

        val wvHeight = (ctx.resources.displayMetrics.heightPixels * 0.54f).toInt().coerceAtLeast(dp(ctx, 290))
        val wv = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, wvHeight).apply {
                bottomMargin = dp(ctx, 8)
            }
            settings.apply {
                javaScriptEnabled = true
                domStorageEnabled = true
                databaseEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                userAgentString = CF_BYPASS_UA
                mediaPlaybackRequiresUserGesture = false
            }
            webChromeClient = WebChromeClient()
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean = false
                override fun onPageFinished(view: WebView?, url: String?) {
                    if (closed.get()) return
                    val srv = currentTarget()
                    statusTv.text = "Loaded ${srv.name} • Tap CF Click or Save & Next"
                    if (tryCaptureCookie(url ?: srv.probeUrl, forceSave = false)) {
                        statusTv.text = "✓ Auto-saved ${srv.name}!"
                        handler.postDelayed({ advanceNext(true) }, 400L)
                    }
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
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#059669", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1f).apply { marginEnd = dp(ctx, 4) }
            setOnClickListener {
                clickWebViewCenter()
                handler.postDelayed({
                    val srv = currentTarget()
                    if (tryCaptureCookie(srv.probeUrl, forceSave = true)) {
                        advanceNext(true)
                    } else {
                        statusTv.text = "Clicked! Complete challenge & tap Save"
                    }
                }, 800L)
            }
        })
        btnRow.addView(Button(ctx).apply {
            text = if (targets.size > 1) "✓ Save & Next" else "✓ Save"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#2563EB", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 1.1f).apply { marginEnd = dp(ctx, 4) }
            setOnClickListener {
                val srv = currentTarget()
                val ok = tryCaptureCookie(srv.probeUrl, forceSave = true)
                advanceNext(ok)
            }
        })
        if (targets.size > 1) {
            btnRow.addView(Button(ctx).apply {
                text = "Skip ➔"
                textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#475569", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.75f).apply { marginEnd = dp(ctx, 4) }
                setOnClickListener { advanceNext(false) }
            })
        }
        btnRow.addView(Button(ctx).apply {
            text = "Close"
            textSize = 10f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#334155", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 38), 0.75f)
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
            handler.postDelayed(pollRunnable, 1500L)
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

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_TIMEOUT_SEC, 18)
    fun setTimeoutSeconds(ctx: Context, sec: Int) = prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(4, 45)).apply()

    fun isShuffleHomeCategories(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHUFFLE_HOME, false)
    fun setShuffleHomeCategories(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHUFFLE_HOME, enabled).apply()

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
                    val badge = obj.optString("runtimeBadge", if (tag == "NUVIO") "⚡ NUV" else if (tag == "SKY") "⚡ SKY" else "⚡ CS3")
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
                            runtimeBadge = badge
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
                    .put("runtimeBadge", srv.runtimeBadge)
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
        if (loaded.none { it.id == "hungrysturdy_repo" }) {
            loaded.add(0, Cs3RepoConfig("hungrysturdy_repo", "HungrySturdy Builds Repo", HUNGRYSTURDY_REPO_URL, "CS3", true, 0))
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

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 10000): ByteArray? = try {
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
        val badgeLabel = when (repoType.uppercase()) {
            "NUVIO" -> "⚡ NUV"
            "SKYSTREAM" -> "⚡ SKY"
            else -> "⚡ CS3"
        }

        runCatching {
            val entries = mutableListOf<JSONObject>()
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
            } else if (body.startsWith("{")) {
                val root = JSONObject(body)

                // 1. Nuvio / Stremio Manifest detection
                if (root.has("resources") || root.has("catalogs") || (root.has("id") && root.has("version") && root.has("name") && !root.has("pluginLists"))) {
                    val mName = root.optString("name").trim().ifBlank { "Nuvio Addon" }
                    val baseAddonUrl = defaultUrl.substringBefore("/manifest.json").trimEnd('/')
                    val slug = mName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
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
                            runtimeBadge = "⚡ NUV"
                        )
                    )
                    return nestedPluginLists
                }

                // 2. CloudStream / SkyStream repo.json -> pluginLists
                root.optJSONArray("pluginLists")?.let { pLists ->
                    for (i in 0 until pLists.length()) {
                        val u = pLists.optString(i).trim()
                        if (u.startsWith("http")) nestedPluginLists.add(u)
                    }
                }

                // 3. Direct arrays inside root object
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
                        runtimeBadge = badgeLabel
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
            val platformTv: TextView,
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
                    background = cardBg("#121621", "#232C40", 18f)
                    setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
                }
                val avatarTv = TextView(ctx).apply {
                    textSize = 13f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD)
                    layoutParams = LinearLayout.LayoutParams(dp(ctx, 32), dp(ctx, 32)).apply { marginEnd = dp(ctx, 9) }
                }
                val col = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                }
                val topLine = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                }
                val nameTv = TextView(ctx).apply {
                    textSize = 13f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                    setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                }
                val platformTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 5) }
                }
                val tagTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 4) }
                }
                val statusTv = TextView(ctx).apply {
                    textSize = 8f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 4) }
                }
                topLine.addView(nameTv); topLine.addView(platformTv); topLine.addView(tagTv); topLine.addView(statusTv)

                val urlTv = TextView(ctx).apply {
                    textSize = 9.5f; isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
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
                holder = VH(card, avatarTv, nameTv, platformTv, tagTv, statusTv, urlTv, upBtn, downBtn, sw)
                card.tag = holder
                rowView = card
            } else {
                rowView = convertView
                holder = convertView.tag as VH
            }

            val srv = items[position]
            holder.avatarTv.text = srv.name.take(1).uppercase()
            holder.avatarTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.avatarTv.background = pillBg("#192236", 10f)
            holder.nameTv.text = srv.name
            holder.platformTv.text = srv.runtimeBadge
            holder.platformTv.setTextColor(safeColor("#38BDF8"))
            holder.platformTv.background = pillBg("#0B2239", 8f)
            holder.tagTv.text = srv.tag
            holder.tagTv.setTextColor(safeColor(srv.accentHex, "#38BDF8"))
            holder.tagTv.background = pillBg("#192236", 8f)

            val health = getServerHealth(srv.key)
            if (health != null) {
                holder.statusTv.visibility = View.VISIBLE
                holder.statusTv.text = if (health.latencyMs > 0) "${health.status} • ${health.latencyMs}ms" else health.status
                val (fg, bg) = if (health.isHealthy) "#34D399" to "#0D2B22" else "#F87171" to "#2D1216"
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

    private fun buildServerHealthCard(
        ctx: Context,
        srv: ServerItem,
        h: ServerHealthInfo?,
        onCfSolved: () -> Unit
    ): LinearLayout {
        val isHealthy = h?.isHealthy == true
        val statusText = h?.status ?: "READY"
        val borderHex = when {
            h == null -> "#232C40"
            isHealthy -> "#22C55E"
            statusText.contains("TIMEOUT", true) -> "#F59E0B"
            else -> "#EF4444"
        }
        val badgeBg = when {
            h == null -> "#192236"
            isHealthy -> "#0D2B22"
            statusText.contains("TIMEOUT", true) -> "#2E2009"
            else -> "#2D1216"
        }
        val badgeFg = when {
            h == null -> "#94A3B8"
            isHealthy -> "#34D399"
            statusText.contains("TIMEOUT", true) -> "#FBBF24"
            else -> "#F87171"
        }
        val hasCookie = getCookieForDomain(ctx, srv.probeUrl) != null

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg("#121621", borderHex, 15f, 2)
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }

            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 8), dp(ctx, 8)).apply { marginEnd = dp(ctx, 8) }
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(safeColor(if (h == null) "#475569" else borderHex))
                }
            })

            val infoCol = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            val titleRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            titleRow.addView(TextView(ctx).apply {
                text = "${srv.name} ${srv.runtimeBadge}"
                textSize = 12f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            titleRow.addView(TextView(ctx).apply {
                text = if (h != null && h.latencyMs > 0) "$statusText • ${h.latencyMs}ms" else statusText
                textSize = 8f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(badgeFg))
                background = pillBg(badgeBg, 8f)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 6) }
            })
            infoCol.addView(titleRow)
            infoCol.addView(TextView(ctx).apply {
                text = h?.detail ?: srv.probeUrl
                textSize = 9.5f
                maxLines = 2
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(safeColor("#94A3B8"))
                setPadding(0, dp(ctx, 2), dp(ctx, 4), 0)
            })
            addView(infoCol)

            addView(TextView(ctx).apply {
                text = if (hasCookie) "CF ✓" else "CF"
                textSize = 8.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(if (hasCookie) "#34D399" else "#38BDF8"))
                background = pillBg(if (hasCookie) "#0D2B22" else "#0D2338", 10f)
                setPadding(dp(ctx, 7), dp(ctx, 5), dp(ctx, 7), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener {
                    openCfBypassWindow(ctx, srv.name, srv.probeUrl, onCfSolved)
                }
            })

            addView(TextView(ctx).apply {
                text = "COPY"
                textSize = 8.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#38BDF8"))
                background = pillBg("#0D2338", 10f)
                setPadding(dp(ctx, 8), dp(ctx, 5), dp(ctx, 8), dp(ctx, 5))
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
        var selectedPlayer = getPreferredPlayer(ctx)
        var selectedSflixHost = getSflixHost(ctx)
        var useWebViewCf = isUseWebViewCf(ctx)
        var currentQuery = ""
        var selectedCategory = "All"
        var selectedRepoType = "AUTO"
        var selectedLogFilter = "ALL"
        var currentConcurrency = getConcurrency(ctx)
        var currentTimeout = getTimeoutSeconds(ctx)
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
            background = cardBg("#07090F", "#1E273B", 32f, 3)
            setPadding(dp(ctx, 13), dp(ctx, 13), dp(ctx, 13), dp(ctx, 13))
        }

        // ── Header ───────────────────────────────────────────────────────────
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
        }
        fun applyHeaderTheme() {
            header.background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = 22f
                setStroke(2, safeColor(activeTheme.primaryHex))
            }
        }
        applyHeaderTheme()

        val titleRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        titleRow.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX"
            textSize = 16f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        titleRow.addView(TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            textSize = 9f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#6EE7B7"))
            background = pillBg("#064E3B", 10f)
            setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
        })
        header.addView(titleRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                .apply { topMargin = dp(ctx, 9) }
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
            val valTv = TextView(ctx).apply { text = "-"; textSize = 12.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex)) }
            val lblTv = TextView(ctx).apply { text = label; textSize = 8f; isSingleLine = true; setTextColor(safeColor("#94A3B8")) }
            box.addView(valTv); box.addView(lblTv)
            return box to valTv
        }
        val (srvChip, srvValTv) = statChip("SERVERS", "#38BDF8", 6)
        val (extChip, extValTv) = statChip("EXTRACTORS", "#C084FC", 6)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)
        statsRow.addView(srvChip); statsRow.addView(extChip); statsRow.addView(spdChip)
        header.addView(statsRow)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 9) })

        fun refreshStats() {
            val srvOn = SERVERS.count { isServerEnabled(ctx, it.key) }
            srvValTv.text = "$srvOn/${SERVERS.size}"
            extValTv.text = "${EXTRACTORS.count { isExtractorEnabled(ctx, it.key) }}/${EXTRACTORS.size}"
            spdValTv.text = "${currentConcurrency}x • ${getPreferredQuality(ctx)}"
        }
        refreshStats()

        // ── Tab Bar ──────────────────────────────────────────────────────────
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 16f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("Providers", "Extractors", "Streaming", "Repos & Manifest", "Health & Logs")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title; textSize = 11f; isAllCaps = false; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 11), 0, dp(ctx, 11), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 33))
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) })

        // ── Providers Filter Controls ────────────────────────────────────────
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search servers..."
            setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 12f; isSingleLine = true
            background = cardBg("#10141E", "#253046", 14f)
            setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 35), 1f).apply { marginEnd = dp(ctx, 6) }
        }
        val allOnBtn = Button(ctx).apply {
            text = "ALL ON"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); background = pillBg(activeTheme.primaryHex, 13f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 35)).apply { marginEnd = dp(ctx, 5) }
        }
        val allOffBtn = Button(ctx).apply {
            text = "ALL OFF"; textSize = 9.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE); background = pillBg("#334155", 13f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 35))
        }
        actionRow.addView(searchBox); actionRow.addView(allOnBtn); actionRow.addView(allOffBtn)
        root.addView(actionRow)

        val chipRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val categories = listOf("All", "Movies", "Series", "AsianDrama", "Hindi", "Custom")
        val chipViews = mutableListOf<TextView>()
        lateinit var applyServerFilter: () -> Unit

        fun refreshCategoryChips() {
            chipViews.forEachIndexed { idx, tv ->
                val active = categories[idx] == selectedCategory
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = pillBg(if (active) activeTheme.primaryHex else "#141926", 11f)
            }
        }
        categories.forEach { cat ->
            val tv = TextView(ctx).apply {
                text = cat; textSize = 10f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 5) }
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

        val contentHeight = dp(ctx, 315)
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
            dividerHeight = dp(ctx, 6)
            overScrollMode = View.OVER_SCROLL_NEVER
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        applyServerFilter = {
            val orderMap = getServerOrder(ctx).withIndex().associate { it.value to it.index }
            val sorted = SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val filtered = sorted.filter { item ->
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
                catOk && qOk
            }
            serverAdapter.submitList(filtered)
            refreshStats()
        }
        applyServerFilter()

        allOnBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", true) }
            ed.apply(); applyServerFilter()
        }
        allOffBtn.setOnClickListener {
            val ed = prefs(ctx).edit()
            SERVERS.forEach { ed.putBoolean("$KEY_SRV_PREFIX${it.key.lowercase()}", false) }
            ed.apply(); applyServerFilter()
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 68))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // ── Tab 2: Extractors ────────────────────────────────────────────────
        EXTRACTORS.forEach { ext ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 16f)
                setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = "${ext.name} [${ext.tag}]"; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = ext.hosts; textSize = 9.5f; setTextColor(safeColor("#94A3B8")) })
            val sw = Switch(ctx).apply {
                isChecked = isExtractorEnabled(ctx, ext.key)
                setOnCheckedChangeListener { _, checked -> setExtractorEnabled(ctx, ext.key, checked); refreshStats() }
            }
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col); card.addView(sw)
            extractorPane.addView(card)
        }

        // ── Tab 3: Streaming ─────────────────────────────────────────────────
        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
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
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#8B93A7")) })
            val sw = Switch(ctx).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col); card.addView(sw)
            parent.addView(card)
        }

        fun makeStepperCard(parent: LinearLayout, title: String, desc: String, initial: Int, step: Int, minVal: Int, maxVal: Int, suffix: String, onChange: (Int) -> Unit) {
            var cur = initial
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 18f)
                setPadding(dp(ctx, 12), dp(ctx, 9), dp(ctx, 12), dp(ctx, 9))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply { text = title; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#8B93A7")) })
            val valTv = TextView(ctx).apply {
                text = "$cur$suffix"; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            }
            val minusBtn = TextView(ctx).apply {
                text = "−"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#1E293B", 10f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                setOnClickListener {
                    if (cur - step >= minVal) {
                        cur -= step; valTv.text = "$cur$suffix"; onChange(cur)
                    }
                }
            }
            val plusBtn = TextView(ctx).apply {
                text = "+"; textSize = 15f; gravity = Gravity.CENTER; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg(activeTheme.primaryHex, 10f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 30), dp(ctx, 30))
                setOnClickListener {
                    if (cur + step <= maxVal) {
                        cur += step; valTv.text = "$cur$suffix"; onChange(cur)
                    }
                }
            }
            card.addView(col); card.addView(minusBtn); card.addView(valTv); card.addView(plusBtn)
            parent.addView(card)
        }

        makeToggleCard(streamPane, "4K + 1440p", "Ultra HD & QHD streams", q4k) { q4k = it; setQuality4kEnabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "1080p FHD", "Full HD streams", q1080) { q1080 = it; setQuality1080Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "720p HD & Web", "Standard HD & WebRip", q720) { q720 = it; setQuality720Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "480p & Below", "Data saver streams", q480) { q480 = it; setQuality480Enabled(ctx, it); refreshStats() }
        makeToggleCard(streamPane, "Hindi Providers Only 🇮🇳", "Hide English-only providers", hindiOnly) { hindiOnly = it; setHindiOnly(ctx, it); applyServerFilter() }
        makeToggleCard(streamPane, "Download Only Links", "Prefer direct MP4/MKV links", downloadOnly) { downloadOnly = it; setDownloadOnly(ctx, it) }
        makeToggleCard(streamPane, "Zero-Buffer Mode", "Prioritize fast HLS/MP4 streams first", lowBuffer) { lowBuffer = it; setLowBufferMode(ctx, it) }
        makeToggleCard(streamPane, "Shuffle Home", "Shuffle home categories", shuffleHome) { shuffleHome = it; setShuffleHomeCategories(ctx, it) }

        makeStepperCard(streamPane, "Parallel Workers", "Concurrent server fetch threads", currentConcurrency, 2, 4, 40, "x") {
            currentConcurrency = it; setConcurrency(ctx, it); refreshStats()
        }
        makeStepperCard(streamPane, "Request Timeout", "Per-server connection timeout", currentTimeout, 2, 4, 45, "s") {
            currentTimeout = it; setTimeoutSeconds(ctx, it)
        }

        val themeCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 18f)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
        }
        themeCard.addView(TextView(ctx).apply { text = "🎨 UI Colour Theme"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val themeScroll = HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false }
        val themeRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; setPadding(0, dp(ctx, 7), 0, 0) }
        UI_THEMES.forEach { pal ->
            themeRow.addView(TextView(ctx).apply {
                text = pal.name; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg(pal.primaryHex, 11f)
                setPadding(dp(ctx, 10), dp(ctx, 6), dp(ctx, 10), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    activeTheme = pal
                    setSelectedTheme(ctx, pal.id)
                    applyHeaderTheme()
                    allOnBtn.background = pillBg(pal.primaryHex, 13f)
                    refreshCategoryChips()
                }
            })
        }
        themeScroll.addView(themeRow)
        themeCard.addView(themeScroll)
        streamPane.addView(themeCard)

        val hostCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 18f)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
        }
        hostCard.addView(TextView(ctx).apply { text = "🌐 Sflix Mirror Host"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
        val rg = RadioGroup(ctx)
        SFLIX_HOSTS.forEach { h ->
            rg.addView(RadioButton(ctx).apply {
                text = h; textSize = 11f; setTextColor(safeColor("#CBD5E1"))
                isChecked = selectedSflixHost.equals(h, true)
                setOnCheckedChangeListener { _, checked -> if (checked) selectedSflixHost = h }
            })
        }
        hostCard.addView(rg)
        streamPane.addView(hostCard)

        // ── Tab 4: Repos & Manifest (CS3 / SkyStream / Nuvio Manifest URL) ──
        val addRepoCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0C1322", "#2563EB", 18f, 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        addRepoCard.addView(TextView(ctx).apply {
            text = "🔗 Add CS3 / SkyStream / Nuvio Manifest URL"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        addRepoCard.addView(TextView(ctx).apply {
            text = "Paste repo.json, plugins.json, .cs3, SkyStream repo, or Nuvio/Stremio manifest.json link"
            textSize = 9.5f; setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 2), 0, dp(ctx, 6))
        })

        val repoNameInput = EditText(ctx).apply {
            hint = "Repo / Addon Name (optional)"
            setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 11.5f; isSingleLine = true
            background = cardBg("#101726", "#2A3650", 12f)
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 36)).apply { bottomMargin = dp(ctx, 6) }
        }
        val repoUrlInput = EditText(ctx).apply {
            hint = "https://.../repo.json or .../manifest.json"
            setHintTextColor(safeColor("#64748B")); setTextColor(Color.WHITE); textSize = 11.5f; isSingleLine = true
            background = cardBg("#101726", "#2A3650", 12f)
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 36)).apply { bottomMargin = dp(ctx, 6) }
        }

        val repoTypes = listOf("AUTO", "CS3", "SKYSTREAM", "NUVIO")
        val repoTypeChips = mutableListOf<TextView>()
        val repoTypeRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        fun refreshRepoTypeChips() {
            repoTypeChips.forEachIndexed { i, tv ->
                val active = repoTypes[i] == selectedRepoType
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = pillBg(if (active) activeTheme.primaryHex else "#161E31", 10f)
            }
        }
        repoTypes.forEach { rt ->
            val tv = TextView(ctx).apply {
                text = rt; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 10), dp(ctx, 5), dp(ctx, 10), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 5) }
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
            text = "+ ADD & FETCH"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#059669", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.3f).apply { marginEnd = dp(ctx, 5) }
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
            text = "FETCH ALL"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#0284C7", 12f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f)
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

        renderRepoCards = {
            cs3RepoHolder.removeAllViews()
            cs3RepoList.forEach { repo ->
                val typeColor = when (repo.repoType.uppercase()) {
                    "NUVIO" -> "#10B981"
                    "SKYSTREAM" -> "#38BDF8"
                    else -> "#A855F7"
                }
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 18f)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
                }
                val head = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                head.addView(TextView(ctx).apply {
                    text = repo.name; textSize = 12.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })
                head.addView(TextView(ctx).apply {
                    text = "${repo.repoType} • ${repo.serverCount}"
                    textSize = 8.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    background = pillBg(typeColor, 8f)
                    setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                })
                card.addView(head)
                card.addView(TextView(ctx).apply {
                    text = repo.repoUrl; textSize = 9.5f; setTextColor(safeColor("#94A3B8"))
                    setPadding(0, dp(ctx, 3), 0, dp(ctx, 6))
                })

                val rowBtns = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
                rowBtns.addView(Button(ctx).apply {
                    text = "FETCH SERVERS"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                    background = pillBg("#0284C7", 12f)
                    layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 1f).apply { marginEnd = dp(ctx, 5) }
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
                if (repo.id != "hungrysturdy_repo") {
                    rowBtns.addView(Button(ctx).apply {
                        text = "DELETE"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor("#FCA5A5"))
                        background = cardBg("#2B1114", "#7F1D1D", 12f, 1)
                        layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
                        setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
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

        // ── Tab 5: Health & Logs (CF Solve All + Structured Diagnostics) ─────
        val cfBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#0A1617", "#1E3A3A", 18f, 2)
            setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val cfHeader = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        cfHeader.addView(TextView(ctx).apply {
            text = "🛡️ Cloudflare Bypass"
            textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        lateinit var refreshHealthPane: () -> Unit

        cfHeader.addView(Button(ctx).apply {
            text = "⚡ Solve All"; textSize = 9.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#059669", 10f)
            setPadding(dp(ctx, 9), 0, dp(ctx, 9), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 30)).apply { marginEnd = dp(ctx, 4) }
            setOnClickListener {
                val cfTargets = SERVERS.filter { isServerEnabled(ctx, it.key) && getCookieForDomain(ctx, it.probeUrl) == null }
                    .ifEmpty { SERVERS.filter { isServerEnabled(ctx, it.key) } }
                openCfBypassQueue(ctx, cfTargets) {
                    refreshHealthPane()
                    applyServerFilter()
                }
            }
        })
        cfHeader.addView(Button(ctx).apply {
            text = "✓ Sync"; textSize = 9.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#2563EB", 10f)
            setPadding(dp(ctx, 9), 0, dp(ctx, 9), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 30))
            setOnClickListener {
                val synced = syncCookiesFromWebView(ctx)
                refreshHealthPane()
                safeToast(ctx, "Synced CF cookies ($synced servers)")
            }
        })
        cfBox.addView(cfHeader)

        cfBox.addView(TextView(ctx).apply {
            text = "Solve All runs sequential WebView solver across servers & saves cookies automatically."
            textSize = 9.5f; setTextColor(safeColor("#94A3B8"))
            setPadding(0, dp(ctx, 3), 0, dp(ctx, 5))
        })

        val wvRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 5) }
        }
        wvRow.addView(TextView(ctx).apply {
            text = "Use WebView for CF challenges"
            textSize = 11.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })
        wvRow.addView(Switch(ctx).apply {
            isChecked = useWebViewCf
            setOnCheckedChangeListener { _, checked -> useWebViewCf = checked; setUseWebViewCf(ctx, checked) }
        })
        cfBox.addView(wvRow)

        val cfStatusBanner = TextView(ctx).apply {
            textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); gravity = Gravity.CENTER
            setPadding(dp(ctx, 10), dp(ctx, 7), dp(ctx, 10), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 5) }
        }
        fun refreshCfBanner() {
            val totalSrvs = SERVERS.size.coerceAtLeast(1)
            val solvedSrvs = SERVERS.count { getCookieForDomain(ctx, it.probeUrl) != null }
            if (solvedSrvs >= totalSrvs) {
                cfStatusBanner.text = "✓ CF Cookies Saved: ALL ($solvedSrvs/$totalSrvs Servers Solved)"
                cfStatusBanner.setTextColor(safeColor("#6EE7B7"))
                cfStatusBanner.background = cardBg("#08241C", "#10B981", 12f, 1)
            } else if (solvedSrvs > 0) {
                cfStatusBanner.text = "✓ CF Cookies Saved ($solvedSrvs/$totalSrvs) — Tap to Solve Remaining"
                cfStatusBanner.setTextColor(safeColor("#FDE68A"))
                cfStatusBanner.background = cardBg("#211A0A", "#D97706", 12f, 1)
            } else {
                cfStatusBanner.text = "⚡ Tap Here to Solve CF Cookies for All Servers"
                cfStatusBanner.setTextColor(safeColor("#93C5FD"))
                cfStatusBanner.background = cardBg("#0B1B2E", "#2563EB", 12f, 1)
            }
        }
        cfStatusBanner.setOnClickListener {
            val remaining = SERVERS.filter { getCookieForDomain(ctx, it.probeUrl) == null }.ifEmpty { SERVERS }
            openCfBypassQueue(ctx, remaining) { refreshHealthPane() }
        }
        cfBox.addView(cfStatusBanner)

        cfBox.addView(Button(ctx).apply {
            text = "Clear CF cookies"; textSize = 10.5f; isAllCaps = false; setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#FCA5A5"))
            background = cardBg("#2B1114", "#7F1D1D", 12f, 1)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 34))
            setOnClickListener {
                clearAllCfCookies(ctx)
                refreshHealthPane()
                safeToast(ctx, "CF cookies cleared")
            }
        })

        // Live Diagnostics Metric Strip
        val metricsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        fun metricPill(label: String, colorHex: String, endDp: Int): Pair<LinearLayout, TextView> {
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
                background = cardBg("#0E1320", "#232D42", 12f, 1)
                setPadding(dp(ctx, 6), dp(ctx, 5), dp(ctx, 6), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                    if (endDp > 0) marginEnd = dp(ctx, endDp)
                }
            }
            val vTv = TextView(ctx).apply { text = "0"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex)) }
            val lTv = TextView(ctx).apply { text = label; textSize = 7.5f; setTextColor(safeColor("#94A3B8")) }
            box.addView(vTv); box.addView(lTv)
            return box to vTv
        }
        val (mLiveBox, mLiveTv) = metricPill("LIVE", "#34D399", 5)
        val (mToBox, mToTv) = metricPill("TIMEOUT", "#FBBF24", 5)
        val (mParseBox, mParseTv) = metricPill("PARSE ERR", "#F87171", 5)
        val (mAvgBox, mAvgTv) = metricPill("AVG MS", "#38BDF8", 0)
        metricsRow.addView(mLiveBox); metricsRow.addView(mToBox); metricsRow.addView(mParseBox); metricsRow.addView(mAvgBox)

        // Log Filter Chips
        val logFilterNames = listOf("ALL", "SEARCH", "LOADLINK", "PARSING", "TIMEOUT", "ERROR")
        val logFilterChips = mutableListOf<TextView>()
        val logFilterRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
        }
        val healthCardsHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val logTv = TextView(ctx).apply {
            textSize = 9.5f; setTextColor(safeColor("#E2E8F0"))
            background = cardBg("#0A0E17", "#26324A", 14f, 2)
            setPadding(dp(ctx, 10), dp(ctx, 9), dp(ctx, 10), dp(ctx, 9))
        }
        fun refreshLogChips() {
            logFilterChips.forEachIndexed { idx, tv ->
                val active = logFilterNames[idx] == selectedLogFilter
                tv.setTextColor(if (active) Color.WHITE else safeColor("#94A3B8"))
                tv.background = pillBg(if (active) activeTheme.primaryHex else "#141926", 10f)
            }
        }
        fun renderFilteredLogs() {
            val logs = getDiagnosticLogs(selectedLogFilter)
            logTv.text = if (logs.isEmpty()) {
                "No [$selectedLogFilter] logs yet • ADB tag: $LOG_TAG"
            } else {
                logs.asReversed().take(80).joinToString("\n\n")
            }
        }
        logFilterNames.forEach { cat ->
            val tv = TextView(ctx).apply {
                text = cat; textSize = 9f; setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 9), dp(ctx, 5), dp(ctx, 9), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(ctx, 5) }
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

        healthPane.addView(cfBox)
        healthPane.addView(metricsRow)
        healthPane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            addView(Button(ctx).apply {
                text = "TEST ALL (${SERVERS.size})"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#059669", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 1.25f).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener {
                    dialogScope.launch {
                        supervisorScope { SERVERS.map { srv -> async { probeServerLive(srv) } }.awaitAll() }
                        refreshHealthPane()
                        applyServerFilter()
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "COPY LOGS"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#0284C7", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 1f).apply { marginEnd = dp(ctx, 5) }
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
                text = "CLEAR"; textSize = 9.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#334155", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 34), 0.75f)
                setOnClickListener {
                    clearDiagnosticLogs()
                    serverHealthMap.clear()
                    refreshHealthPane()
                    applyServerFilter()
                }
            })
        })
        healthPane.addView(healthCardsHolder)
        healthPane.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(logFilterRow) })
        healthPane.addView(logTv)
        refreshHealthPane()

        val scrollPanes = listOf(extractorPane, streamPane, cs3Pane, healthPane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            if (idx == 0) {
                serversListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                chipScroll.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
            } else {
                serversListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                chipScroll.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollPanes.forEachIndexed { i, p -> p.visibility = if (i == idx - 1) View.VISIBLE else View.GONE }
                if (idx == 4) refreshHealthPane()
            }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg(activeTheme.primaryHex, 13f)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 9) }
        }
        footer.addView(Button(ctx).apply {
            text = "RESET"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 15f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 40), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener {
                prefs(ctx).edit().clear().apply()
                cachedCs3Repos = null
                cachedCs3Servers.clear()
                serverHealthMap.clear()
                clearDiagnosticLogs()
                applyServerFilter()
                runCatching { dialog.dismiss() }
            }
        })
        footer.addView(Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 11.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 15f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 40), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveCs3Repos(ctx, cs3RepoList)
                setSflixHost(ctx, selectedSflixHost)
                setSelectedTheme(ctx, activeTheme.id)
                setPreferredPlayer(ctx, selectedPlayer)
                setQuality4kEnabled(ctx, q4k)
                setQuality1080Enabled(ctx, q1080)
                setQuality720Enabled(ctx, q720)
                setQuality480Enabled(ctx, q480)
                setLowBufferMode(ctx, lowBuffer)
                setConcurrency(ctx, currentConcurrency)
                setTimeoutSeconds(ctx, currentTimeout)
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

