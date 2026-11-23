package com.hungrysturdy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
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
import java.net.HttpURLConnection
import java.net.URL
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

object MovieFoxSettingsDialog {

    const val LOG_TAG = "MovieFoxDiag"
    private const val PREFS_NAME = "moviefox_ultra_max_v22"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_EXT_PREFIX = "mf_ext_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_CF_COOKIES = "mf_cf_cookies_json"
    private const val KEY_USE_WEBVIEW_CF = "mf_use_webview_cf"
    private const val KEY_SFLIX_HOST = "mf_sflix_host"
    private const val KEY_THEME = "mf_ui_theme"
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

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

    val SFLIX_HOSTS = listOf(
        "https://sflix.film",
        "https://h5-api.aoneroom.com",
        "https://movie-box.co",
        "https://123moviesfree.club"
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
        val runtimeBadge: String = "⚡ SKY"
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

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    private val errorLogRing = CopyOnWriteArrayList<String>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()

    fun bindContext(ctx: Context?) {
        if (ctx != null) appContext = ctx.applicationContext
    }

    fun currentContext(): Context? = appContext

    val EXTRACTORS = listOf(
        ExtractorItem("basejwp", "BaseJWP Extractor", "JWP / Unpacker / B64 / m3u8 / mp4", "HLS/MP4"),
        ExtractorItem("gdrive", "GDrive Extractor", "drive.google.com • usercontent.google.com", "GDRIVE"),
        ExtractorItem("streamtape", "StreamTape Mirrors", "streamtape.com/to/net/xyz • shavetape • strcloud", "MP4"),
        ExtractorItem("voe", "Voe Mirrors", "voe.sx • voe.to • voe.co", "HLS/MP4"),
        ExtractorItem("pixeldrain", "Pixeldrain", "pixeldrain.com • pixeldrain.dev", "FAST"),
        ExtractorItem("hubcloud", "KatHubCloud & GamerXYT", "hubcloud.pw/dad/club/one • gamerxyt.com", "10Gbps"),
        ExtractorItem("gdflix", "KatGDFlix Mirrors", "gdflix.* • gdlink.* • new15.gdflix.*", "R2/DL"),
        ExtractorItem("buzz_hb_go", "BuzzServer / HBPlay / GoFlix", "fuckingfast.net • hbplay.pages.dev • goflix.sbs", "MIRROR"),
        ExtractorItem("jwp_hosts", "VidPrime / Nitrox / NxStream / Vibuxer", "vidprime.top • itsnitrox.tech • nxstreamhub • vibuxer", "WORKER"),
        ExtractorItem("jwp_embeds", "Lulu / Vidara / Rabbit / MegaCloud / Wish / Moon", "luluvid • vidara • megacloud • streamwish • filemoon • voe", "EMBED")
    )

    val BASE_SERVERS = listOf(
        ServerItem("prmovies", "Prmovies", "HI/M3U8", "https://prmovies.coupons/", "#F59E0B", false, true, "Movies", "⚡ SKY"),
        ServerItem("hindidrama", "HindiDrama", "K-DRA", "https://hindidrama.net/", "#EC4899", false, true, "AsianDrama", "⚡ SKY"),
        ServerItem("sflix", "Sflix", "HLS/MP4", "https://sflix.film/", "#34D399", false, false, "Series", "⚡ SKY")
    )

    val SERVERS: List<ServerItem>
        get() = BASE_SERVERS

    fun copyToClipboard(ctx: Context?, label: String, text: String) {
        if (ctx == null || text.isBlank()) return
        runCatching {
            val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            cb?.setPrimaryClip(ClipData.newPlainText(label, text))
            safeToast(ctx, "Copied: $label")
        }
    }

    fun reportServerStatus(
        key: String,
        status: String,
        isHealthy: Boolean,
        httpCode: Int = 0,
        latencyMs: Long = -1L,
        detail: String = ""
    ) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        serverHealthMap[key.lowercase()] = ServerHealthInfo(
            key = key.lowercase(),
            status = status,
            isHealthy = isHealthy,
            httpCode = httpCode,
            latencyMs = latencyMs,
            detail = detail,
            updatedAt = ts
        )
    }

    fun getServerHealth(key: String): ServerHealthInfo? = serverHealthMap[key.lowercase()]

    fun logSiteError(source: String, url: String, error: Throwable?, extra: String = "") {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val errMsg = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val line = "[$ts] [$source] ${if (extra.isNotBlank()) "$extra | " else ""}$url -> $errMsg".trim()
        Log.e(LOG_TAG, line, error)
        errorLogRing.add(line)
        while (errorLogRing.size > 150) errorLogRing.removeAt(0)
        reportServerStatus(source, "ERROR", false, 0, -1L, extra.ifBlank { errMsg })
    }

    fun getDiagnosticLogs(): List<String> = errorLogRing.toList()
    fun clearDiagnosticLogs() = errorLogRing.clear()

    private suspend fun probeServerLive(srv: ServerItem): ServerHealthInfo = withContext(Dispatchers.IO) {
        val start = System.currentTimeMillis()
        try {
            val conn = (URL(srv.probeUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8000
                readTimeout = 8000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", CF_BYPASS_UA)
            }
            val code = conn.responseCode
            val ms = System.currentTimeMillis() - start
            runCatching { conn.inputStream?.close() }
            runCatching { conn.errorStream?.close() }
            conn.disconnect()
            val ok = code in 200..399 || code == 405
            val info = ServerHealthInfo(
                key = srv.key,
                status = if (ok) "LIVE" else "HTTP $code",
                isHealthy = ok,
                httpCode = code,
                latencyMs = ms,
                detail = srv.probeUrl,
                updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
            )
            serverHealthMap[srv.key.lowercase()] = info
            info
        } catch (e: Exception) {
            val ms = System.currentTimeMillis() - start
            val info = ServerHealthInfo(
                key = srv.key,
                status = "DOWN",
                isHealthy = false,
                httpCode = 0,
                latencyMs = ms,
                detail = "${e.message ?: "Timeout/Network"} • ${srv.probeUrl}",
                updatedAt = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
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

    private fun cardBg(bgHex: String = "#141822", strokeHex: String = "#252E42", radius: Float = 22f, strokeWidth: Int = 2): GradientDrawable =
        GradientDrawable().apply {
            setColor(safeColor(bgHex, "#141822"))
            cornerRadius = radius
            setStroke(strokeWidth, safeColor(strokeHex, "#252E42"))
        }

    private fun pillBg(bgHex: String, radius: Float = 16f): GradientDrawable =
        GradientDrawable().apply {
            setColor(safeColor(bgHex, "#1C2230"))
            cornerRadius = radius
        }

    private fun prefs(ctx: Context): SharedPreferences {
        bindContext(ctx)
        return cachedPrefs ?: ctx.applicationContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .also { cachedPrefs = it }
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

    fun getSflixHost(ctx: Context): String =
        prefs(ctx).getString(KEY_SFLIX_HOST, SFLIX_HOSTS.first()) ?: SFLIX_HOSTS.first()
    fun setSflixHost(ctx: Context, host: String) =
        prefs(ctx).edit().putString(KEY_SFLIX_HOST, host).apply()

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

    fun getConcurrency(ctx: Context): Int = prefs(ctx).getInt(KEY_CONCURRENCY, 15)
    fun setConcurrency(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 50)).apply()

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_TIMEOUT_SEC, 12)
    fun setTimeoutSeconds(ctx: Context, sec: Int) = prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(4, 30)).apply()

    fun isShuffleHomeCategories(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_SHUFFLE_HOME, false)
    fun setShuffleHomeCategories(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_SHUFFLE_HOME, enabled).apply()

    fun isServerEnabled(ctx: Context, key: String): Boolean {
        val enabled = prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)
        if (!enabled) return false
        if (isHindiOnly(ctx)) {
            val item = SERVERS.firstOrNull { it.key.equals(key, true) }
            if (item != null && !item.isHindi) return false
        }
        return true
    }

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled).apply()
    }

    fun getServerOrder(ctx: Context): List<String> {
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

    fun show(ctx: Context) {
        if (!isContextAlive(ctx)) return
        bindContext(ctx)
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        var activeTheme = getSelectedTheme(ctx)
        var selectedSflixHost = getSflixHost(ctx)
        var useWebViewCf = isUseWebViewCf(ctx)
        var currentQuery = ""
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
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(safeColor(activeTheme.gradStart), safeColor(activeTheme.gradMid), safeColor(activeTheme.gradEnd))
            ).apply {
                cornerRadius = 24f
                setStroke(2, safeColor(activeTheme.primaryHex))
            }
        }

        header.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX ⚡"
            textSize = 16.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })

        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 18f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("Providers", "Extractors", "Streaming", "Health & Logs")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title; textSize = 11.5f; isAllCaps = false; isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        root.addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(tabBar) }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) })

        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 360))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val providersPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        fun rebuildProvidersPane() {
            providersPane.removeAllViews()
            val orderMap = getServerOrder(ctx).withIndex().associate { it.value to it.index }
            SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
                .filter { currentQuery.isBlank() || it.name.contains(currentQuery, true) }
                .forEach { srv ->
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
                    col.addView(TextView(ctx).apply { text = "${srv.name}  [${srv.tag}]"; textSize = 13.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
                    col.addView(TextView(ctx).apply { text = srv.probeUrl; textSize = 10f; setTextColor(safeColor("#94A3B8")) })
                    val sw = Switch(ctx).apply {
                        isChecked = isServerEnabled(ctx, srv.key)
                        setOnCheckedChangeListener { _, checked -> setServerEnabled(ctx, srv.key, checked) }
                    }
                    card.addView(col); card.addView(sw)
                    providersPane.addView(card)
                }
        }
        rebuildProvidersPane()

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
            col.addView(TextView(ctx).apply { text = "${ext.name} [${ext.tag}]"; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            col.addView(TextView(ctx).apply { text = ext.hosts; textSize = 10f; setTextColor(safeColor("#94A3B8")) })
            val sw = Switch(ctx).apply {
                isChecked = isExtractorEnabled(ctx, ext.key)
                setOnCheckedChangeListener { _, checked -> setExtractorEnabled(ctx, ext.key, checked) }
            }
            card.addView(col); card.addView(sw)
            extractorPane.addView(card)
        }

        fun makeToggle(title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
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
            col.addView(TextView(ctx).apply { text = desc; textSize = 10f; setTextColor(safeColor("#94A3B8")) })
            val sw = Switch(ctx).apply {
                isChecked = initial
                setOnCheckedChangeListener { _, checked -> onChange(checked) }
            }
            card.addView(col); card.addView(sw)
            streamPane.addView(card)
        }

        makeToggle("4K + 1440p", "Ultra HD streams", q4k) { q4k = it; setQuality4kEnabled(ctx, it) }
        makeToggle("1080p FHD", "Full HD streams", q1080) { q1080 = it; setQuality1080Enabled(ctx, it) }
        makeToggle("720p HD", "Standard HD streams", q720) { q720 = it; setQuality720Enabled(ctx, it) }
        makeToggle("480p & Below", "Data saver streams", q480) { q480 = it; setQuality480Enabled(ctx, it) }
        makeToggle("Hindi Providers Only 🇮🇳", "Hide English-only servers", hindiOnly) { hindiOnly = it; setHindiOnly(ctx, it); rebuildProvidersPane() }
        makeToggle("Download Only Links", "Prefer direct MP4/MKV links", downloadOnly) { downloadOnly = it; setDownloadOnly(ctx, it) }
        makeToggle("Zero-Buffer Mode", "Prioritize fast HLS/MP4 links", lowBuffer) { lowBuffer = it; setLowBufferMode(ctx, it) }
        makeToggle("Shuffle Home", "Shuffle home categories", shuffleHome) { shuffleHome = it; setShuffleHomeCategories(ctx, it) }

        val healthListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val logTv = TextView(ctx).apply {
            textSize = 10f; setTextColor(safeColor("#E2E8F0"))
            background = cardBg("#0A0E17", "#26324A", 14f, 1)
            setPadding(dp(ctx, 10), dp(ctx, 10), dp(ctx, 10), dp(ctx, 10))
        }
        fun refreshHealth() {
            healthListHolder.removeAllViews()
            SERVERS.forEach { srv ->
                val h = getServerHealth(srv.key)
                val status = h?.let { "${it.status} • ${it.latencyMs}ms" } ?: "READY"
                healthListHolder.addView(TextView(ctx).apply {
                    text = "${srv.name}: $status (${srv.probeUrl})"
                    textSize = 11.5f; setTextColor(Color.WHITE)
                    background = cardBg("#121621", if (h?.isHealthy == true) "#10B981" else "#232C40", 14f, 1)
                    setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 6) }
                })
            }
            val logs = getDiagnosticLogs()
            logTv.text = if (logs.isEmpty()) "No error logs yet (ADB tag: $LOG_TAG)" else logs.asReversed().joinToString("\n")
        }

        healthPane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(Button(ctx).apply {
                text = "TEST ALL (${SERVERS.size})"; textSize = 10f; setTextColor(Color.WHITE)
                background = pillBg("#059669", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener {
                    dialogScope.launch {
                        supervisorScope { SERVERS.map { async { probeServerLive(it) } }.awaitAll() }
                        refreshHealth()
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "COPY ALL"; textSize = 10f; setTextColor(Color.WHITE)
                background = pillBg("#0284C7", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener { copyToClipboard(ctx, "Logs", getDiagnosticLogs().joinToString("\n")) }
            })
            addView(Button(ctx).apply {
                text = "CLEAR"; textSize = 10f; setTextColor(Color.WHITE)
                background = pillBg("#334155", 12f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 0.8f)
                setOnClickListener { clearDiagnosticLogs(); serverHealthMap.clear(); refreshHealth() }
            })
        })
        healthPane.addView(healthListHolder)
        healthPane.addView(logTv)
        refreshHealth()

        val panes = listOf(providersPane, extractorPane, streamPane, healthPane)
        panes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            panes.forEachIndexed { i, p -> p.visibility = if (i == idx) View.VISIBLE else View.GONE }
            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg(activeTheme.primaryHex, 14f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(safeColor("#8B93A7"))
                }
            }
            if (idx == 3) refreshHealth()
        }
        tabButtons.forEachIndexed { i, btn -> btn.setOnClickListener { selectTab(i) } }
        selectTab(0)

        root.addView(scrollView)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10) }
        }
        footer.addView(Button(ctx).apply {
            text = "RESET"; textSize = 12f; setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 14f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener { prefs(ctx).edit().clear().apply(); runCatching { dialog.dismiss() } }
        })
        footer.addView(Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 12f; setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 14f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                setSflixHost(ctx, selectedSflixHost)
                setUseWebViewCf(ctx, useWebViewCf)
                safeToast(ctx, "Saved & Applied")
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
