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
import java.util.zip.ZipInputStream

object MovieFoxSettingsDialog {

    private const val LOG_TAG = "MovieFoxDiag"
    private const val PREFS_NAME = "moviefox_ultra_max_v22"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_EXT_PREFIX = "mf_ext_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_THEME = "mf_ui_theme"
    private const val KEY_PLAYER = "mf_preferred_player"
    private const val KEY_QUALITY = "mf_preferred_quality"
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

    const val SKY_UNIVERSE_REPO_URL =
        "https://raw.githubusercontent.com/akashdh11/sky-universe/refs/heads/main/mega_repo.json"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/154.0.0.0 Mobile Safari/537.36"

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
        val problemStage: String = "",
        val updatedAt: String = ""
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

    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var cachedPrefs: SharedPreferences? = null
    @Volatile
    private var cachedCs3Repos: MutableList<Cs3RepoConfig>? = null
    private val cachedCs3Servers = CopyOnWriteArrayList<ServerItem>()
    private val errorLogRing = CopyOnWriteArrayList<String>()
    private val serverHealthMap = ConcurrentHashMap<String, ServerHealthInfo>()
    @Volatile
    private var cs3Loaded = false

    fun bindContext(ctx: Context?) {
        if (ctx != null) appContext = ctx.applicationContext
    }

    val VEGA_ALLOWED_HOSTS = listOf(
        "pixeldrain.dev", "pixeldrain", "fsl2", "fsl", "gdrive", "gdflix", "hubcloud",
        "nexdrive", "vcloud", "vegadrive", "fastdl", "filebee", "filepress",
        "fpgo", "gofile", "gpdl", ".r2.dev", ".r2.cloudflarestorage.com", "buzzserver", "10gbps"
    )

    val EXTRACTORS = listOf(
        ExtractorItem("pixeldrain", "Pixeldrain Dev", "pixeldrain.dev / pixeldrain.com", "FAST"),
        ExtractorItem("fastdlserver", "FastDL / FSL / FSL2", "fastdl.* / fastdlserver.* / hubcdn.wiki", "FSL"),
        ExtractorItem("hubcloud", "HubCloud", "hubcloud.* / .workers.dev", "DIRECT"),
        ExtractorItem("vcloud", "VCloud", "vcloud.* / mcloud.*", "DIRECT"),
        ExtractorItem("gdflix", "GDFlix", "gdflix.* / gdlink.* / gdtot.*", "GDRIVE"),
        ExtractorItem("hubdrive", "Hubdrive / Hblinks / Linksmod / Howblogs", "hubdrive.* / hblinks.* / linksmod.* / howblogs.*", "DRIVE"),
        ExtractorItem("driveleech", "Driveleech / Driveseed / FilePress", "driveleech.* / driveseed.* / filebee.* / filepress / fpgo", "DRIVE"),
        ExtractorItem("gofile", "Gofile", "gofile.io / api.gofile.io", "DIRECT"),
        ExtractorItem("voe", "Voe", "voe.sx", "HLS"),
        ExtractorItem("streamtape", "StreamTape", "streamtape.com / stape.* / strtape", "MP4"),
        ExtractorItem("nxstreamhub", "NxStreamHub", "nxstreamhub.workers.dev", "HLS"),
        ExtractorItem("itsnitrox", "Nitrox", "itsnitrox.tech", "HLS"),
        ExtractorItem("vibuxer", "Vibuxer", "vibuxer.com", "HLS"),
        ExtractorItem("gokuhd", "NexDrive / VegaDrive", "nexdrive.* / vegadrive.*", "MULTI"),
        ExtractorItem("wootly", "Wootly / Videostr / MegaPlay / VibePlayer / Modiplay", "wootly.* / videostr.* / streameeeeee.* / megaplay.* / vidwish.* / vidtube.* / vibeplayer.* / vivibebe.* / bibiemb.* / modiplay.* / iqsmartgames.*", "EMBED")
    )

    val BASE_SERVERS = listOf(
        ServerItem("pixeldrain_dev", "Pixeldrain Dev", "PIXELDRAIN", "https://pixeldrain.dev/", "#38BDF8", false, true, "Movies", "⚡ FAST"),
        ServerItem("fsl_fastdl", "FSL / FSL2 / FastDL", "FSL•FASTDL", "https://hubcdn.wiki/", "#34D399", false, true, "Movies", "⚡ FSL"),
        ServerItem("hubcloud_vcloud", "HubCloud / VCloud", "CLOUD•CDN", "https://gofile.io/", "#A855F7", false, true, "Movies", "⚡ CDN"),
        ServerItem("gdflix_drive", "GDFlix / Drive / FilePress", "GDRIVE•FILEBEE", "https://pixeldrain.dev/", "#F59E0B", false, true, "Movies", "⚡ DRIVE"),
        ServerItem("gofile", "Gofile", "GOFILE", "https://gofile.io/", "#22D3EE", false, true, "Movies", "⚡ CLOUD"),
        ServerItem("voe_streamtape", "Voe / StreamTape", "VOE•STAPE", "https://voe.sx/", "#818CF8", false, true, "Movies", "⚡ HOST"),
        ServerItem("nxstreamhub_nitrox", "NxStreamHub / Nitrox", "HLS•WORKER", "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls", "#F43F5E", false, true, "Movies", "⚡ HLS"),
        ServerItem("wootly_embeds", "Wootly / Embeds / Speedostream", "EMBED•STREAMS", "https://wootly.ch/", "#FB923C", false, true, "Movies", "⚡ EMBED"),
        ServerItem("prmovies", "Prmovies", "HI/M3U8", "https://prmovies.kids/", "#F59E0B", false, true, "Hindi", "⚡ SKY")
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
        detail: String = "",
        problemStage: String = ""
    ) {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        serverHealthMap[key.lowercase()] = ServerHealthInfo(
            key = key.lowercase(),
            status = status,
            isHealthy = isHealthy,
            httpCode = httpCode,
            latencyMs = latencyMs,
            detail = detail,
            problemStage = problemStage.ifBlank { if (isHealthy) "OK" else "ERROR" },
            updatedAt = ts
        )
    }

    fun getServerHealth(key: String): ServerHealthInfo? = serverHealthMap[key.lowercase()]

    fun isServerWorking(key: String): Boolean = true

    fun logSiteError(source: String, url: String, error: Throwable?, extra: String = "") {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())
        val errMsg = error?.let { "${it.javaClass.simpleName}: ${it.message.orEmpty()}" }.orEmpty()
        val line = "[$ts] [$source] ${if (extra.isNotBlank()) "$extra | " else ""}$url -> $errMsg".trim()
        Log.e(LOG_TAG, line, error)
        errorLogRing.add(line)
        while (errorLogRing.size > 150) errorLogRing.removeAt(0)
        reportServerStatus(source, "ERROR", false, 0, -1L, extra.ifBlank { errMsg }, "LOADLINKS")
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
                problemStage = if (ok) "OK" else "HTTP",
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
                problemStage = "NETWORK",
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

    fun getSelectedApiHost(ctx: Context): String = "h5-api.aoneroom.com"

    fun getPreferredPlayer(ctx: Context): String =
        prefs(ctx).getString(KEY_PLAYER, PLAYER_OPTIONS.first()) ?: PLAYER_OPTIONS.first()
    fun setPreferredPlayer(ctx: Context, player: String) = prefs(ctx).edit().putString(KEY_PLAYER, player).apply()

    fun isExtractorEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_EXT_PREFIX${key.lowercase()}", true)
    fun setExtractorEnabled(ctx: Context, key: String, enabled: Boolean) =
        prefs(ctx).edit().putBoolean("$KEY_EXT_PREFIX${key.lowercase()}", enabled).apply()

    fun getCookieForDomain(ctx: Context?, urlOrDomain: String): String? = null

    fun isVegaAllowedHost(linkNameOrUrl: String): Boolean {
        val lower = linkNameOrUrl.lowercase()
        return VEGA_ALLOWED_HOSTS.any { lower.contains(it) }
    }

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

    fun setPreferredQuality(ctx: Context, quality: String) = prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()

    fun isLowBufferMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)
    fun setLowBufferMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()

    fun isDownloadOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DOWNLOAD_ONLY, false)
    fun setDownloadOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_DOWNLOAD_ONLY, enabled).apply()

    fun isStreamOnly(ctx: Context): Boolean = isLowBufferMode(ctx)
    fun getParallelServers(ctx: Context): Int = getConcurrency(ctx)

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
        getServerOrder(ctx).filter { isServerEnabled(ctx, it) && isServerWorking(it) }

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
                    out.add(
                        ServerItem(
                            key = key,
                            name = name,
                            tag = obj.optString("tag", "CS3").ifBlank { "CS3" },
                            probeUrl = probeUrl,
                            accentHex = obj.optString("accentHex", "#38BDF8"),
                            isCustom = true,
                            isHindi = true,
                            category = "Movies",
                            runtimeBadge = "⚡ SKY"
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
            arr.put(JSONObject().put("key", srv.key).put("name", srv.name).put("tag", srv.tag).put("probeUrl", srv.probeUrl).put("accentHex", srv.accentHex))
        }
        prefs(ctx).edit().putString(KEY_CS3_SERVERS, arr.toString()).apply()
    }

    fun getCs3Repos(ctx: Context): MutableList<Cs3RepoConfig> {
        prefs(ctx)
        cachedCs3Repos?.let { return it }
        val list = mutableListOf(Cs3RepoConfig("sky_universe_mega", "Sky Universe Mega Repo", SKY_UNIVERSE_REPO_URL, true, 0))
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
        return cachedCs3Servers.filter { isServerEnabled(ctx, it.key) && isServerWorking(it.key) }
    }

    fun getNuvioManifests(ctx: Context): List<NuvioManifestConfig> = emptyList()

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 8000): ByteArray? = try {
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
    } catch (_: Exception) {
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
                out.add(ServerItem("cs3_${idTag}_${slug.ifBlank { "srv$idx" }}", finalName, "CS3", toRawGithubUrl(url), AVATAR_PALETTE[idx % AVATAR_PALETTE.size], true, true, "Movies", "⚡ SKY"))
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
            runCatching {
                ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                    var entry = zis.nextEntry
                    while (entry != null) {
                        if (!entry.isDirectory && entry.name.endsWith(".json", true)) {
                            parseJsonServerEntries(zis.bufferedReader().readText().trim(), idTag, rawUrl, out)
                        }
                        entry = zis.nextEntry
                    }
                }
            }
            return@withContext out.distinctBy { "${it.name.lowercase()}|${it.probeUrl.lowercase()}" }
        }

        val body = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
        if (body.isNotBlank()) parseJsonServerEntries(body, idTag, rawUrl, out)
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
                val platformTv = TextView(ctx).apply {
                    textSize = 8.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD)
                    setPadding(dp(ctx, 5), dp(ctx, 2), dp(ctx, 5), dp(ctx, 2))
                    layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
                        .apply { marginStart = dp(ctx, 5) }
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
                topLine.addView(nameTv); topLine.addView(platformTv); topLine.addView(tagTv); topLine.addView(statusTv)

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
            holder.avatarTv.background = pillBg("#192236", 12f)
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
                holder.statusTv.text = if (health.latencyMs > 0) "${health.status} ${health.latencyMs}ms" else health.status
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

    private fun buildServerHealthCard(ctx: Context, srv: ServerItem, h: ServerHealthInfo?): LinearLayout {
        val isHealthy = h?.isHealthy == true
        val statusText = h?.status ?: "READY"
        val borderHex = when {
            h == null -> "#232C40"
            isHealthy -> "#22C55E"
            else -> "#EF4444"
        }
        val badgeBg = when {
            h == null -> "#192236"
            isHealthy -> "#0D2B22"
            else -> "#2D1216"
        }
        val badgeFg = when {
            h == null -> "#94A3B8"
            isHealthy -> "#34D399"
            else -> "#F87171"
        }

        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = cardBg("#121621", borderHex, 16f, 2)
            setPadding(dp(ctx, 10), dp(ctx, 8), dp(ctx, 10), dp(ctx, 8))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 6) }

            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 9), dp(ctx, 9)).apply { marginEnd = dp(ctx, 8) }
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
                textSize = 12.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            titleRow.addView(TextView(ctx).apply {
                text = if (h != null && h.latencyMs > 0) "$statusText • ${h.latencyMs}ms" else statusText
                textSize = 8.5f
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

    fun show(ctx: Context) {
        if (!isContextAlive(ctx)) return
        bindContext(ctx)
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val cs3RepoList = getCs3Repos(ctx).toMutableList()
        var activeTheme = getSelectedTheme(ctx)
        var selectedPlayer = getPreferredPlayer(ctx)
        var currentQuery = ""
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

        header.addView(TextView(ctx).apply {
            text = "MOVIEFOX SELECTED SERVERS"
            textSize = 16.5f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
        })

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
            val valTv = TextView(ctx).apply { text = "-"; textSize = 13f; isSingleLine = true; setTypeface(typeface, Typeface.BOLD); setTextColor(safeColor(colorHex)) }
            val lblTv = TextView(ctx).apply { text = label; textSize = 8.5f; isSingleLine = true; setTextColor(safeColor("#94A3B8")) }
            box.addView(valTv); box.addView(lblTv)
            return box to valTv
        }
        val (srvChip, srvValTv) = statChip("SERVERS", "#38BDF8", 6)
        val (extChip, extValTv) = statChip("EXTRACTORS", "#C084FC", 6)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)
        statsRow.addView(srvChip); statsRow.addView(extChip); statsRow.addView(spdChip)
        header.addView(statsRow)
        root.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 10) })

        fun refreshStats() {
            val srvOn = SERVERS.count { isServerEnabled(ctx, it.key) }
            srvValTv.text = "$srvOn/${SERVERS.size}"
            extValTv.text = "${EXTRACTORS.count { isExtractorEnabled(ctx, it.key) }}/${EXTRACTORS.size}"
            spdValTv.text = "${currentConcurrency}x • ${getPreferredQuality(ctx)}"
        }
        refreshStats()

        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 18f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }
        val tabTitles = listOf("Providers", "Extractors", "Streaming", "CS3 Repos", "Health & Logs")
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

        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
        }
        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search servers..."
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

        val contentHeight = dp(ctx, 335)
        lateinit var applyServerFilter: () -> Unit
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
            val sorted = SERVERS.sortedBy { orderMap[it.key] ?: Int.MAX_VALUE }
            val filtered = if (currentQuery.isEmpty()) sorted else sorted.filter {
                it.name.lowercase().contains(currentQuery) || it.tag.lowercase().contains(currentQuery)
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight + dp(ctx, 36))
        }
        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val extractorPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val streamPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val healthPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

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
                setOnCheckedChangeListener { _, checked -> setExtractorEnabled(ctx, ext.key, checked); refreshStats() }
            }
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col); card.addView(sw)
            extractorPane.addView(card)
        }

        fun makeToggleCard(parent: LinearLayout, title: String, desc: String, initial: Boolean, onChange: (Boolean) -> Unit) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
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
        makeToggleCard(streamPane, "Hindi Providers Only 🇮🇳", "Hide English-only providers", hindiOnly) { hindiOnly = it; setHindiOnly(ctx, it); applyServerFilter() }
        makeToggleCard(streamPane, "Download Only Links", "Prefer direct MP4/MKV links", downloadOnly) { downloadOnly = it; setDownloadOnly(ctx, it) }
        makeToggleCard(streamPane, "Zero-Buffer Mode", "Prioritize fast HLS/MP4 streams first", lowBuffer) { lowBuffer = it; setLowBufferMode(ctx, it) }
        makeToggleCard(streamPane, "Shuffle Home", "Shuffle home categories", shuffleHome) { shuffleHome = it; setShuffleHomeCategories(ctx, it) }

        val cs3RepoHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        cs3RepoList.forEach { repo ->
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 7) }
            }
            card.addView(TextView(ctx).apply { text = repo.name; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE) })
            card.addView(TextView(ctx).apply { text = repo.repoUrl; textSize = 10f; setTextColor(safeColor("#94A3B8")) })
            val fetchBtn = Button(ctx).apply {
                text = "FETCH REPO SERVERS"; textSize = 10.5f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#0284C7", 14f)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 36)).apply { topMargin = dp(ctx, 6) }
                setOnClickListener {
                    dialogScope.launch {
                        val fetched = fetchServersFromCs3Repo(repo)
                        saveCs3Servers(ctx, fetched)
                        repo.serverCount = fetched.size
                        saveCs3Repos(ctx, cs3RepoList)
                        applyServerFilter()
                        safeToast(ctx, "${fetched.size} servers loaded!")
                    }
                }
            }
            card.addView(fetchBtn)
            cs3RepoHolder.addView(card)
        }
        cs3Pane.addView(cs3RepoHolder)

        val healthCardsHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val logTv = TextView(ctx).apply {
            textSize = 10f; setTextColor(safeColor("#E2E8F0"))
            background = cardBg("#0A0E17", "#26324A", 16f, 2)
            setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
        }
        fun refreshHealthPane() {
            healthCardsHolder.removeAllViews()
            SERVERS.forEach { srv ->
                healthCardsHolder.addView(buildServerHealthCard(ctx, srv, getServerHealth(srv.key)))
            }
            val logs = getDiagnosticLogs()
            logTv.text = if (logs.isEmpty()) "No error logs yet (ADB tag: $LOG_TAG)" else logs.asReversed().joinToString("\n\n")
        }

        healthPane.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(ctx, 8) }
            addView(Button(ctx).apply {
                text = "TEST ALL (${SERVERS.size})"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#059669", 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1.2f).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener {
                    dialogScope.launch {
                        supervisorScope { SERVERS.map { srv -> async { probeServerLive(srv) } }.awaitAll() }
                        refreshHealthPane()
                        applyServerFilter()
                    }
                }
            })
            addView(Button(ctx).apply {
                text = "COPY ALL"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#0284C7", 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 5) }
                setOnClickListener {
                    copyToClipboard(ctx, "Diagnostic Logs", getDiagnosticLogs().joinToString("\n"))
                }
            })
            addView(Button(ctx).apply {
                text = "CLEAR"; textSize = 10f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
                background = pillBg("#334155", 14f)
                layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 0.8f)
                setOnClickListener {
                    clearDiagnosticLogs()
                    serverHealthMap.clear()
                    refreshHealthPane()
                    applyServerFilter()
                }
            })
        })
        healthPane.addView(healthCardsHolder)
        healthPane.addView(logTv)
        refreshHealthPane()

        val scrollPanes = listOf(extractorPane, streamPane, cs3Pane, healthPane)
        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        fun selectTab(idx: Int) {
            if (idx == 0) {
                serversListView.visibility = View.VISIBLE
                actionRow.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
            } else {
                serversListView.visibility = View.GONE
                actionRow.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                scrollPanes.forEachIndexed { i, p -> p.visibility = if (i == idx - 1) View.VISIBLE else View.GONE }
                if (idx == 4) refreshHealthPane()
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
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(ctx, 10) }
        }
        footer.addView(Button(ctx).apply {
            text = "RESET"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener {
                prefs(ctx).edit().clear().apply()
                serverHealthMap.clear()
                applyServerFilter()
                runCatching { dialog.dismiss() }
            }
        })
        footer.addView(Button(ctx).apply {
            text = "SAVE & APPLY"; textSize = 12f; setTypeface(typeface, Typeface.BOLD); setTextColor(Color.WHITE)
            background = pillBg(activeTheme.primaryHex, 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveCs3Repos(ctx, cs3RepoList)
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

