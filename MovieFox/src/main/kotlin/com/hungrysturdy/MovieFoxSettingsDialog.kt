package com.hungrysturdy

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Dialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.content.res.ColorStateList
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
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

    private const val PREFS_NAME = "moviefox_ultra_max_v14"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_SRV_ORDER = "mf_srv_order_csv"
    private const val KEY_CF_COOKIE_PREFIX = "mf_cf_cookie_"
    private const val KEY_AUDIO_PREFIX = "mf_audio_"
    private const val KEY_NUVIO_MANIFESTS = "mf_nuvio_manifests_json"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_QUALITY = "mf_preferred_quality"
    private const val KEY_LOW_BUFFER = "mf_low_buffer_mode"
    private const val KEY_DOWNLOAD_ONLY = "mf_download_only_mode"
    private const val KEY_FAST_FETCH = "mf_fast_fetch_mode"
    private const val KEY_CONCURRENCY = "mf_concurrency"
    private const val KEY_TIMEOUT_SEC = "mf_timeout_sec"

    const val CF_BYPASS_UA =
        "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val BG_DARK = Color.parseColor("#0D0F14")
    private val BG_CARD = Color.parseColor("#13161E")
    private val ACCENT_START = Color.parseColor("#6C63FF")
    private val ACCENT_END = Color.parseColor("#A855F7")
    private val TEXT_PRIMARY = Color.parseColor("#F0F2FF")
    private val TEXT_SECONDARY = Color.parseColor("#7B82A0")
    private val SWITCH_ON = Color.parseColor("#6C63FF")
    private val SWITCH_OFF = Color.parseColor("#2A2D3E")
    private val DIVIDER_COLOR = Color.parseColor("#1F2235")
    private val DANGER_COLOR = Color.parseColor("#FF4E6A")

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
        val isCustom: Boolean = false
    )

    data class BypassSite(val domain: String, val displayName: String)

    data class AudioOption(
        val key: String,
        val title: String,
        val subtitle: String,
        val defaultOn: Boolean = true
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

    val BASE_SERVERS = listOf(
        ServerItem("purstream", "Purstream", "API", "https://moviefox.pro/api/stream/purstream", "#38BDF8"),
        ServerItem("castle", "Castle", "API", "https://moviefox.pro/api/stream/castle", "#818CF8"),
        ServerItem("watchout", "Watchout", "API", "https://moviefox.pro/api/stream/watchout", "#2DD4BF"),
        ServerItem("cinetv_api", "CineTV API", "WEB/API", "https://cinetv.app/", "#F43F5E"),
        ServerItem("cinetv_hls", "CineTV HLS", "WORKER", "https://silent-frost-f2dbv.nxstreamhub.workers.dev/hls/", "#FB7185"),
        ServerItem("vegamovies", "VegaMovies", "FSL/PXL", "https://vegamovies.lease/", "#EC4899"),
        ServerItem("sflix", "Sflix", "SITE", "https://sflix.film/", "#F97316"),
        ServerItem("prmovies", "Prmovies", "SITE", "https://prmovies.coupons/", "#F59E0B"),
        ServerItem("play4u", "Play4u", "HLS", "https://play4u.org/", "#FB7185"),
        ServerItem("nuvio", "Nuvio Addon", "MANIFEST", "https://nuvio.app/", "#A855F7"),
        ServerItem("moviebox", "MovieBox", "BFF", "https://netnaija.film/", "#34D399"),
        ServerItem("netnaija", "NetNaija", "SITE", "https://www.thenetnaija.net/", "#FACC15")
    )

    private val CF_BYPASS_SITES = listOf(
        BypassSite("moviefox.pro", "MovieFox"),
        BypassSite("cinetv.app", "CineTV"),
        BypassSite("vegamovies.lease", "VegaMovies"),
        BypassSite("prmovies.coupons", "Prmovies"),
        BypassSite("play4u.org", "Play4u"),
        BypassSite("sflix.film", "Sflix"),
        BypassSite("www.thenetnaija.net", "NetNaija")
    )

    val SERVERS: List<ServerItem>
        get() = BASE_SERVERS + cachedCs3Servers.toList()

    val AUDIO_TRACKS = listOf(
        AudioOption("hindi", "Hindi", "Primary • Hindi audio / dub", true),
        AudioOption("english", "English", "Primary • English audio", true),
        AudioOption("tamil", "Tamil", "Primary • Tamil audio / dub", true),
        AudioOption("telugu", "Telugu", "Telugu audio / dub", false),
        AudioOption("korean", "Korean", "Korean audio / dub", false),
        AudioOption("japanese", "Japanese", "Japanese audio / dub", false),
        AudioOption("punjabi", "Punjabi", "Punjabi audio / dub", false),
        AudioOption("spanish", "Spanish", "Spanish audio / dub", false),
        AudioOption("french", "French", "French audio / dub", false),
        AudioOption("arabic", "Arabic", "Arabic audio / dub", false),
        AudioOption("bengali", "Bengali", "Bengali audio / dub", false)
    )

    val QUALITIES = listOf("AUTO", "2160p", "1080p", "720p", "480p")

    private val AVATAR_PALETTE = listOf(
        "#38BDF8", "#A855F7", "#F43F5E", "#34D399",
        "#FB923C", "#818CF8", "#22D3EE", "#EC4899", "#FACC15"
    )

    private fun isContextAlive(ctx: Context?): Boolean {
        if (ctx == null) return false
        if (ctx is Activity && (ctx.isFinishing || ctx.isDestroyed)) return false
        return true
    }

    private fun safeToast(ctx: Context?, msg: String) {
        if (!isContextAlive(ctx)) return
        runCatching { Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show() }
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

    private fun normalizeHost(urlOrDomain: String): String {
        val trimmed = urlOrDomain.trim()
        val host = if (trimmed.contains("://")) {
            runCatching { Uri.parse(trimmed).host }.getOrNull().orEmpty()
        } else {
            trimmed.substringBefore('/')
        }
        return host.lowercase().removePrefix("www.").trim()
    }

    fun saveCookieForDomain(ctx: Context, urlOrDomain: String, cookies: String) {
        val h = normalizeHost(urlOrDomain)
        if (h.isNotBlank()) {
            prefs(ctx).edit().putString("$KEY_CF_COOKIE_PREFIX$h", cookies.trim()).apply()
        }
    }

    fun getCookieForDomain(ctx: Context?, urlOrDomain: String): String? {
        if (ctx == null) return null
        val h = normalizeHost(urlOrDomain)
        if (h.isBlank()) return null
        return prefs(ctx).getString("$KEY_CF_COOKIE_PREFIX$h", null)?.takeIf { it.isNotBlank() }
    }

    fun hasSavedCookieForDomain(ctx: Context, urlOrDomain: String): Boolean =
        !getCookieForDomain(ctx, urlOrDomain).isNullOrBlank()

    fun removeCookieForDomain(ctx: Context, urlOrDomain: String) {
        val h = normalizeHost(urlOrDomain)
        if (h.isNotBlank()) {
            prefs(ctx).edit().remove("$KEY_CF_COOKIE_PREFIX$h").apply()
        }
    }

    fun getServerOrder(ctx: Context): List<String> {
        prefs(ctx)
        val allKeys = SERVERS.map { it.key }
        val saved = prefs(ctx).getString(KEY_SRV_ORDER, null)
            ?.split(",")?.filter { it.isNotBlank() }
            ?: return allKeys
        val validSaved = saved.filter { it in allKeys }
        return validSaved + (allKeys - validSaved.toSet())
    }

    fun saveServerOrder(ctx: Context, order: List<String>) {
        prefs(ctx).edit().putString(KEY_SRV_ORDER, order.joinToString(",")).apply()
    }

    fun getOrderedEnabledKeys(ctx: Context): List<String> =
        getServerOrder(ctx).filter { isServerEnabled(ctx, it) }

    fun isServerEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled).apply()
    }

    fun reportServerResult(ctx: Context?, key: String, success: Boolean, latencyMs: Long = 0L) {}
    fun getServerFailCount(ctx: Context, key: String): Int = 0
    fun getServerLastMs(ctx: Context, key: String): Long = -1L
    fun resetAllHealthStats(ctx: Context) {}

    fun isAudioEnabled(ctx: Context, key: String): Boolean {
        val def = AUDIO_TRACKS.find { it.key == key }?.defaultOn ?: false
        return prefs(ctx).getBoolean("$KEY_AUDIO_PREFIX$key", def)
    }

    fun setAudioEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_AUDIO_PREFIX$key", enabled).apply()
    }

    fun getPreferredQuality(ctx: Context): String = prefs(ctx).getString(KEY_QUALITY, "AUTO") ?: "AUTO"
    fun setPreferredQuality(ctx: Context, quality: String) = prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()

    fun isLowBufferMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)
    fun setLowBufferMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()

    fun isDownloadOnly(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_DOWNLOAD_ONLY, false)
    fun setDownloadOnly(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_DOWNLOAD_ONLY, enabled).apply()

    fun isFastFetchMode(ctx: Context): Boolean = prefs(ctx).getBoolean(KEY_FAST_FETCH, true)
    fun setFastFetchMode(ctx: Context, enabled: Boolean) = prefs(ctx).edit().putBoolean(KEY_FAST_FETCH, enabled).apply()

    fun isAutoDisableErrorServers(ctx: Context): Boolean = false
    fun setAutoDisableErrorServers(ctx: Context, enabled: Boolean) {}
    fun isStreamOnly(ctx: Context): Boolean = isLowBufferMode(ctx)
    fun getParallelServers(ctx: Context): Int = getConcurrency(ctx)

    fun getConcurrency(ctx: Context): Int = prefs(ctx).getInt(KEY_CONCURRENCY, 16)
    fun setConcurrency(ctx: Context, value: Int) = prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 30)).apply()

    fun getTimeoutSeconds(ctx: Context): Int = prefs(ctx).getInt(KEY_TIMEOUT_SEC, 8)
    fun setTimeoutSeconds(ctx: Context, sec: Int) = prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(4, 20)).apply()

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
                val key = obj.optString("key", "").trim()
                val name = obj.optString("name", "").trim()
                val probeUrl = obj.optString("probeUrl", "").trim()
                if (key.isNotBlank() && name.isNotBlank() && probeUrl.isNotBlank()) {
                    out.add(
                        ServerItem(
                            key = key,
                            name = name,
                            tag = obj.optString("tag", "CS3").ifBlank { "CS3" },
                            probeUrl = probeUrl,
                            accentHex = obj.optString("accentHex", "#38BDF8").ifBlank { "#38BDF8" },
                            isCustom = true
                        )
                    )
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
            arr.put(
                JSONObject().apply {
                    put("key", srv.key); put("name", srv.name)
                    put("tag", srv.tag); put("probeUrl", srv.probeUrl); put("accentHex", srv.accentHex)
                }
            )
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
                val url = obj.optString("repoUrl", "").trim()
                if (url.isNotBlank()) {
                    list.add(
                        Cs3RepoConfig(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            name = obj.optString("name", deriveManifestLabel(url)),
                            repoUrl = url,
                            enabled = obj.optBoolean("enabled", true),
                            serverCount = obj.optInt("serverCount", 0)
                        )
                    )
                }
            }
        }
        cachedCs3Repos = list
        return list
    }

    fun saveCs3Repos(ctx: Context, list: List<Cs3RepoConfig>) {
        cachedCs3Repos = list.toMutableList()
        val arr = JSONArray()
        list.forEach { repo ->
            arr.put(
                JSONObject().apply {
                    put("id", repo.id); put("name", repo.name)
                    put("repoUrl", repo.repoUrl); put("enabled", repo.enabled); put("serverCount", repo.serverCount)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_CS3_REPOS, arr.toString()).apply()
    }

    fun getEnabledCs3Servers(ctx: Context): List<ServerItem> {
        prefs(ctx)
        return cachedCs3Servers.filter { isServerEnabled(ctx, it.key) }
    }

    fun getNuvioManifests(ctx: Context): MutableList<NuvioManifestConfig> {
        cachedManifests?.let { return it }
        val raw = prefs(ctx).getString(KEY_NUVIO_MANIFESTS, "[]") ?: "[]"
        val list = mutableListOf<NuvioManifestConfig>()
        runCatching {
            val arr = JSONArray(raw)
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                val url = obj.optString("manifestUrl", "").trim()
                if (url.isNotBlank()) {
                    list.add(
                        NuvioManifestConfig(
                            id = obj.optString("id", System.currentTimeMillis().toString()),
                            label = obj.optString("label", deriveManifestLabel(url)),
                            manifestUrl = url,
                            enabled = obj.optBoolean("enabled", true)
                        )
                    )
                }
            }
        }
        cachedManifests = list
        return list
    }

    fun saveNuvioManifests(ctx: Context, list: List<NuvioManifestConfig>) {
        cachedManifests = list.toMutableList()
        val arr = JSONArray()
        list.forEach { item ->
            arr.put(
                JSONObject().apply {
                    put("id", item.id); put("label", item.label)
                    put("manifestUrl", item.manifestUrl); put("enabled", item.enabled)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_NUVIO_MANIFESTS, arr.toString()).apply()
    }

    fun getCustomSites(ctx: Context): List<CustomExternalSite> =
        getNuvioManifests(ctx).map { m ->
            CustomExternalSite(id = m.id, name = m.label, baseUrl = m.manifestUrl, enabled = m.enabled)
        }

    fun deriveManifestLabel(rawUrl: String): String = try {
        val normalized = normalizeNuvioManifestUrl(rawUrl)
        val host = URI(normalized).host.orEmpty().removePrefix("www.")
        host.replaceFirstChar { it.uppercase() }.ifBlank { "Addon" }
    } catch (_: Exception) {
        "Addon"
    }

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 7000): ByteArray? = runCatching {
        val fixedUrl = toRawGithubUrl(urlStr)
        val conn = (URL(fixedUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = timeoutMs
            readTimeout = timeoutMs
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", CF_BYPASS_UA)
            setRequestProperty("Accept", "application/json, application/octet-stream, text/plain, */*")
        }
        val code = conn.responseCode
        val bytes = if (code in 200..299) conn.inputStream.use { it.readBytes() } else null
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
                val rawName = pObj.optString("name", "").trim()
                val internalName = pObj.optString("internalName", "").trim()
                val finalName = rawName.ifBlank { internalName }
                if (finalName.isBlank()) return@forEachIndexed
                val url = pObj.optString("url", "")
                    .ifBlank { pObj.optString("mainUrl", "") }
                    .ifBlank { pObj.optString("pluginUrl", "") }
                    .ifBlank { pObj.optString("downloadUrl", "") }
                    .ifBlank { defaultUrl }.trim()
                val slug = (internalName.ifBlank { finalName }).lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                out.add(
                    ServerItem(
                        key = "cs3_${idTag}_${slug.ifBlank { "srv$idx" }}",
                        name = finalName,
                        tag = "CS3",
                        probeUrl = toRawGithubUrl(url),
                        accentHex = AVATAR_PALETTE[idx % AVATAR_PALETTE.size],
                        isCustom = true
                    )
                )
            }
        }
    }

    private suspend fun fetchServersFromCs3Repo(repo: Cs3RepoConfig, timeoutMs: Int = 7000): List<ServerItem> =
        withContext(Dispatchers.IO) {
            val out = mutableListOf<ServerItem>()
            val rawUrl = toRawGithubUrl(repo.repoUrl)
            val bytes = readHttpBytes(rawUrl, timeoutMs) ?: return@withContext emptyList()
            if (bytes.isEmpty()) return@withContext emptyList()
            val idTag = repo.id.takeLast(6)

            if (rawUrl.substringBefore("?").endsWith(".cs3", ignoreCase = true)) {
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
                                        pluginName = mObj.optString("name", "").trim()
                                            .ifBlank { mObj.optString("internalName", "").trim() }
                                    }
                                    lower.endsWith(".json") -> parseJsonServerEntries(zis.bufferedReader().readText().trim(), idTag, rawUrl, out)
                                    lower.endsWith(".dex") -> {
                                        val rawDex = String(zis.readBytes(), Charsets.ISO_8859_1)
                                        val seen = mutableSetOf<String>()
                                        var idx = 0
                                        Regex("""L[a-zA-Z0-9_/]+/([A-Z][a-zA-Z0-9]{2,28})(?:Provider|Plugin|Source);""")
                                            .findAll(rawDex).forEach { m ->
                                                val sName = m.groupValues[1]
                                                if (sName !in setOf("Main", "Base", "Abstract", "Custom", "Cloudstream", "Stream") && seen.add(sName.lowercase())) {
                                                    out.add(ServerItem("cs3_${idTag}_${sName.lowercase()}", sName, "CS3", rawUrl, AVATAR_PALETTE[idx % AVATAR_PALETTE.size], true))
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
                    val slug = fName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
                    out.add(ServerItem("cs3_${idTag}_${slug.ifBlank { "plugin" }}", fName, "CS3", rawUrl, "#34D399", true))
                }
                return@withContext out.distinctBy { it.key }
            }

            val body = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
            if (body.isBlank()) return@withContext emptyList()
            parseJsonServerEntries(body, idTag, rawUrl, out)

            runCatching {
                if (body.startsWith("{")) {
                    val rootJson = JSONObject(body)
                    val listUrls = mutableListOf<String>()
                    rootJson.optJSONArray("pluginLists")?.let { arr ->
                        for (i in 0 until arr.length()) arr.optString(i).trim().takeIf { it.isNotBlank() }?.let { listUrls.add(it) }
                    }
                    rootJson.optString("manifestList", "").trim().takeIf { it.isNotBlank() }?.let { listUrls.add(it) }
                    listUrls.distinct().forEach { u ->
                        val resolved = if (u.startsWith("http")) toRawGithubUrl(u) else "${rawUrl.substringBeforeLast('/')}/${u.trimStart('/')}"
                        readHttpBytes(resolved, timeoutMs)?.let { b ->
                            parseJsonServerEntries(String(b, Charsets.UTF_8).trim(), idTag, rawUrl, out)
                        }
                    }
                }
            }
            out.distinctBy { it.key }
        }

    private fun Int.dp(ctx: Context): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, toFloat(), ctx.resources.displayMetrics).toInt()

    private fun Float.dp(ctx: Context): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, this, ctx.resources.displayMetrics)

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply { cornerRadius = radius; setColor(color) }

    private fun stateDrawable() = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), GradientDrawable().apply { setColor(Color.parseColor("#2A2D45")) })
        addState(intArrayOf(), GradientDrawable().apply { setColor(Color.TRANSPARENT) })
    }

    private fun inputBg(ctx: Context) = GradientDrawable().apply {
        cornerRadius = 10f.dp(ctx); setColor(Color.parseColor("#0D1117")); setStroke(1, Color.parseColor("#2E2850"))
    }

    private fun divider(ctx: Context) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            .also { it.setMargins(20.dp(ctx), 0, 20.dp(ctx), 0) }
        setBackgroundColor(DIVIDER_COLOR)
    }

    private fun hSpacer(ctx: Context, w: Int) = View(ctx).apply { layoutParams = LinearLayout.LayoutParams(w.dp(ctx), 1) }

    private fun accentBar(ctx: Context, top: Int, bottom: Int) = View(ctx).apply {
        layoutParams = LinearLayout.LayoutParams(3.dp(ctx), 18.dp(ctx)).also { it.marginEnd = 12.dp(ctx) }
        background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom)).apply { cornerRadius = 99f }
    }

    private fun pillBtn(ctx: Context, label: String, textColor: Int, bgColor: Int, borderColor: Int, onClick: () -> Unit) =
        TextView(ctx).apply {
            text = label; textSize = 11f; setTypeface(null, Typeface.BOLD); setTextColor(textColor)
            setPadding(12.dp(ctx), 6.dp(ctx), 12.dp(ctx), 6.dp(ctx))
            background = GradientDrawable().apply { cornerRadius = 99f; setColor(bgColor); setStroke(1, borderColor) }
            isClickable = true; isFocusable = true
            setOnClickListener {
                animate().scaleX(0.9f).scaleY(0.9f).setDuration(60).withEndAction {
                    animate().scaleX(1f).scaleY(1f).setDuration(90).start()
                }.start()
                onClick()
            }
        }

    private fun styledSwitch(ctx: Context, checked: Boolean, trackOn: Int = SWITCH_ON) = Switch(ctx).apply {
        isChecked = checked; isClickable = false; isFocusable = false
        thumbTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(Color.WHITE, Color.parseColor("#9099B8"))
        )
        trackTintList = ColorStateList(
            arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
            intArrayOf(trackOn, SWITCH_OFF)
        )
    }

    private fun cardContainer(ctx: Context) = LinearLayout(ctx).apply {
        orientation = LinearLayout.VERTICAL
        val m = 16.dp(ctx)
        layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            .also { it.setMargins(m, 0, m, m) }
        background = roundRect(BG_CARD, 16f.dp(ctx))
        elevation = 4f
    }

    private fun animateExpand(view: View, expand: Boolean, onDone: (() -> Unit)? = null) {
        if (expand) {
            view.visibility = View.VISIBLE; view.alpha = 0f
            view.animate().alpha(1f).setDuration(200).start()
        } else {
            view.animate().alpha(0f).setDuration(150).withEndAction {
                view.visibility = View.GONE; view.alpha = 1f; onDone?.invoke()
            }.start()
        }
    }

    private fun buildCollapsibleCard(
        ctx: Context,
        title: String,
        accentA: Int,
        accentB: Int,
        startExpanded: Boolean = false,
        summaryProvider: (() -> String)? = null,
        block: LinearLayout.() -> Unit
    ): View {
        val card = cardContainer(ctx)
        var expanded = startExpanded
        val content = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 8.dp(ctx))
            visibility = if (expanded) View.VISIBLE else View.GONE
        }
        content.block()

        val summaryTv = TextView(ctx).apply {
            text = summaryProvider?.invoke().orEmpty()
            textSize = 11f; setTextColor(Color.parseColor("#5A5E7A"))
            setPadding(0, 0, 8.dp(ctx), 0)
        }
        val chevron = TextView(ctx).apply { text = if (expanded) "▲" else "▼"; textSize = 11f; setTextColor(TEXT_SECONDARY) }

        card.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(20.dp(ctx), 16.dp(ctx), 16.dp(ctx), 16.dp(ctx))
            isClickable = true; isFocusable = true; background = stateDrawable()
            addView(accentBar(ctx, accentA, accentB))
            addView(TextView(ctx).apply {
                text = title; textSize = 12f; setTypeface(null, Typeface.BOLD)
                setTextColor(TEXT_SECONDARY); letterSpacing = 0.08f
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(summaryTv); addView(chevron)
            setOnClickListener {
                expanded = !expanded
                chevron.text = if (expanded) "▲" else "▼"
                summaryTv.text = summaryProvider?.invoke().orEmpty()
                animateExpand(content, expanded) { summaryTv.text = summaryProvider?.invoke().orEmpty() }
            }
        })
        card.addView(content)
        card.alpha = 0f; card.translationY = 18f
        card.animate().alpha(1f).translationY(0f).setDuration(260).setInterpolator(DecelerateInterpolator()).start()
        return card
    }

    @SuppressLint("SetJavaScriptEnabled")
    fun showCloudflareBypassWebView(ctx: Context, startUrl: String, onCookiesChanged: () -> Unit = {}) {
        if (!isContextAlive(ctx)) return
        val mainHandler = Handler(Looper.getMainLooper())
        val targetUrl = if (startUrl.startsWith("http")) startUrl else "https://$startUrl"
        var currentUrl = targetUrl
        var dialogRef: Dialog? = null

        val statusText = TextView(ctx).apply {
            text = targetUrl; textSize = 10f; setTextColor(TEXT_SECONDARY); isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val progressBar = ProgressBar(ctx, null, android.R.attr.progressBarStyleHorizontal).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 3.dp(ctx))
            progressTintList = ColorStateList.valueOf(Color.parseColor("#22C55E"))
            max = 100; progress = 15
        }
        val cookieText = TextView(ctx).apply {
            text = "Cookies: waiting..."
            textSize = 11f; setTextColor(TEXT_SECONDARY); maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(16.dp(ctx), 4.dp(ctx), 16.dp(ctx), 6.dp(ctx))
        }

        fun refreshCookieView(u: String) {
            val c = CookieManager.getInstance().getCookie(u).orEmpty()
            mainHandler.post { cookieText.text = if (c.isBlank()) "Cookies: <none>" else "Cookies: $c" }
        }

        val webView = WebView(ctx).apply {
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f)
            setBackgroundColor(Color.WHITE)
            settings.apply {
                javaScriptEnabled = true; domStorageEnabled = true
                mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
                useWideViewPort = true; loadWithOverviewMode = true
                userAgentString = CF_BYPASS_UA
            }
            CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView, newProgress: Int) {
                    progressBar.progress = newProgress
                    progressBar.visibility = if (newProgress < 100) View.VISIBLE else View.GONE
                }
            }
            webViewClient = object : WebViewClient() {
                override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                    currentUrl = url; mainHandler.post { statusText.text = url }
                }
                override fun onPageFinished(view: WebView, url: String) {
                    currentUrl = url; mainHandler.post { statusText.text = url }
                    refreshCookieView(url)
                }
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
            }
        }

        val titleBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(16.dp(ctx), 12.dp(ctx), 10.dp(ctx), 12.dp(ctx))
            background = ColorDrawable(BG_CARD)
            addView(accentBar(ctx, Color.parseColor("#22C55E"), Color.parseColor("#16A34A")))
            addView(TextView(ctx).apply {
                text = "🌐 Cloudflare Bypass"; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })
            addView(pillBtn(ctx, "💾 Save Cookies", Color.parseColor("#10B981"), Color.parseColor("#0B1120"), Color.parseColor("#059669")) {
                val cookies = CookieManager.getInstance().getCookie(currentUrl)
                if (!cookies.isNullOrBlank()) {
                    saveCookieForDomain(ctx, currentUrl, cookies)
                    saveCookieForDomain(ctx, targetUrl, cookies)
                    CookieManager.getInstance().flush()
                    onCookiesChanged()
                    safeToast(ctx, "✓ Cookies saved!")
                    runCatching { dialogRef?.dismiss() }
                } else {
                    safeToast(ctx, "Page poora load hone dein pehle")
                }
            })
            addView(hSpacer(ctx, 8))
            addView(pillBtn(ctx, "✕", TEXT_SECONDARY, BG_CARD, Color.parseColor("#2A2D3E")) {
                runCatching { dialogRef?.dismiss() }
            })
        }

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL; background = ColorDrawable(BG_DARK)
            addView(titleBar); addView(progressBar); addView(webView); addView(cookieText)
            addView(LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16.dp(ctx), 6.dp(ctx), 16.dp(ctx), 8.dp(ctx))
                background = ColorDrawable(BG_CARD); addView(statusText)
            })
        }

        dialogRef = Dialog(ctx, android.R.style.Theme_Black_NoTitleBar_Fullscreen).apply {
            requestWindowFeature(Window.FEATURE_NO_TITLE); setContentView(root); setCancelable(true)
            setOnDismissListener {
                runCatching { webView.stopLoading(); webView.destroy() }
                onCookiesChanged()
            }
            window?.setLayout(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT)
            show()
        }
        webView.loadUrl(targetUrl)
    }

    fun show(ctx: Context) {
        if (!isContextAlive(ctx)) return
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val pendingBool = mutableMapOf<String, Boolean>()
        var pendingConcurrency = getConcurrency(ctx)
        var pendingTimeout = getTimeoutSeconds(ctx)
        var pendingQuality = getPreferredQuality(ctx)
        var pendingLowBuffer = isLowBufferMode(ctx)
        var pendingDownloadOnly = isDownloadOnly(ctx)

        val order = getServerOrder(ctx).toMutableList()
        val cs3RepoList = getCs3Repos(ctx).toMutableList()
        val nuvioList = getNuvioManifests(ctx).toMutableList()
        val clipboard = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager

        val scroll = ScrollView(ctx).apply {
            isScrollbarFadingEnabled = true
            background = ColorDrawable(BG_DARK)
            isFocusable = false
            descendantFocusability = ScrollView.FOCUS_AFTER_DESCENDANTS
        }

        val layout = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 24.dp(ctx))
            background = ColorDrawable(BG_DARK)
        }

        // Hero Banner
        layout.addView(LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp(ctx), 26.dp(ctx), 24.dp(ctx), 20.dp(ctx))
            background = GradientDrawable(GradientDrawable.Orientation.TL_BR, intArrayOf(Color.parseColor("#1A1730"), BG_DARK))
            addView(View(ctx).apply {
                layoutParams = LinearLayout.LayoutParams(48.dp(ctx), 4.dp(ctx)).also { it.bottomMargin = 14.dp(ctx) }
                background = GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT, intArrayOf(ACCENT_START, ACCENT_END)).apply { cornerRadius = 99f }
            })
            addView(TextView(ctx).apply {
                text = "MovieFox Settings"; textSize = 22f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY)
            })
            addView(TextView(ctx).apply {
                text = "Configure servers, CineTV, Cloudflare cookies & addons"
                textSize = 12.5f; setTextColor(TEXT_SECONDARY); setPadding(0, 4.dp(ctx), 0, 0)
            })
        })

        // 1. Scraping Settings Card
        layout.addView(buildCollapsibleCard(ctx, "⚙️  Scraping Settings", Color.parseColor("#06B6D4"), Color.parseColor("#0891B2")) {
            fun addToggle(label: String, sub: String, initial: Boolean, onChange: (Boolean) -> Unit) {
                val sw = styledSwitch(ctx, initial)
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(20.dp(ctx), 14.dp(ctx), 16.dp(ctx), 14.dp(ctx))
                    isClickable = true; isFocusable = true; background = stateDrawable()
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(ctx).apply { text = label; textSize = 14.5f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                    col.addView(TextView(ctx).apply { text = sub; textSize = 11.5f; setTextColor(TEXT_SECONDARY); setPadding(0, 2.dp(ctx), 0, 0) })
                    addView(col); addView(sw)
                    setOnClickListener { sw.isChecked = !sw.isChecked; onChange(sw.isChecked) }
                })
            }

            fun addStepper(label: String, sub: String, initial: Int, minV: Int, maxV: Int, suffix: String, onChange: (Int) -> Unit) {
                var v = initial
                val valTv = TextView(ctx).apply {
                    text = "$v$suffix"; textSize = 14.5f; setTypeface(null, Typeface.BOLD); setTextColor(ACCENT_START)
                    gravity = Gravity.CENTER; minWidth = 38.dp(ctx)
                }
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(20.dp(ctx), 14.dp(ctx), 16.dp(ctx), 14.dp(ctx))
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(ctx).apply { text = label; textSize = 14.5f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                    col.addView(TextView(ctx).apply { text = sub; textSize = 11.5f; setTextColor(TEXT_SECONDARY); setPadding(0, 2.dp(ctx), 0, 0) })
                    addView(col)
                    addView(pillBtn(ctx, " − ", TEXT_PRIMARY, Color.parseColor("#1A1E28"), Color.parseColor("#2E2850")) {
                        if (v > minV) { v--; valTv.text = "$v$suffix"; onChange(v) }
                    })
                    addView(valTv)
                    addView(pillBtn(ctx, " + ", TEXT_PRIMARY, Color.parseColor("#1A1E28"), Color.parseColor("#2E2850")) {
                        if (v < maxV) { v++; valTv.text = "$v$suffix"; onChange(v) }
                    })
                })
            }

            addToggle("Zero-Buffer Mode", "Highlight fast HLS/DASH streams first", pendingLowBuffer) { pendingLowBuffer = it }
            addView(divider(ctx))
            addToggle("Download Only Links", "Hide HLS streams, show direct MP4/MKV only", pendingDownloadOnly) { pendingDownloadOnly = it }
            addView(divider(ctx))
            addStepper("Scraping Concurrency", "Parallel servers running at once", pendingConcurrency, 2, 30, "x") { pendingConcurrency = it }
            addView(divider(ctx))
            addStepper("Server Timeout", "Wait time per server (8s–12s recommended)", pendingTimeout, 4, 20, "s") { pendingTimeout = it }
        })

        // 2. Cloudflare Bypass Card
        layout.addView(buildCollapsibleCard(ctx, "🌐  Cloudflare Bypass", Color.parseColor("#22C55E"), Color.parseColor("#16A34A")) {
            setPadding(16.dp(ctx), 4.dp(ctx), 16.dp(ctx), 12.dp(ctx))
            addView(TextView(ctx).apply {
                text = "Jis site par Cloudflare block ho, uske aage Open tap karein aur Save Cookies dabayein:"
                textSize = 11.5f; setTextColor(TEXT_SECONDARY); setPadding(4.dp(ctx), 0, 4.dp(ctx), 10.dp(ctx))
            })

            CF_BYPASS_SITES.forEachIndexed { idx, site ->
                if (idx > 0) addView(divider(ctx))
                lateinit var statusTv: TextView
                lateinit var btnRow: LinearLayout

                fun refreshRow() {
                    val saved = hasSavedCookieForDomain(ctx, site.domain)
                    statusTv.text = if (saved) "✓ Saved" else "Not Saved"
                    statusTv.setTextColor(if (saved) Color.parseColor("#10B981") else Color.parseColor("#6B7280"))
                    btnRow.removeAllViews()
                    btnRow.addView(pillBtn(ctx, "🌐 Open", Color.parseColor("#60A5FA"), Color.parseColor("#0B1120"), Color.parseColor("#1D4ED8")) {
                        showCloudflareBypassWebView(ctx, "https://${site.domain}") { refreshRow() }
                    })
                    if (saved) {
                        btnRow.addView(hSpacer(ctx, 8))
                        btnRow.addView(pillBtn(ctx, "✕ Remove", DANGER_COLOR, Color.parseColor("#1A0A0D"), Color.parseColor("#3A1520")) {
                            removeCookieForDomain(ctx, site.domain)
                            refreshRow()
                            safeToast(ctx, "Cookie removed for ${site.displayName}")
                        })
                    }
                }

                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(4.dp(ctx), 10.dp(ctx), 4.dp(ctx), 10.dp(ctx))
                    val left = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    left.addView(TextView(ctx).apply { text = site.displayName; textSize = 13.5f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                    statusTv = TextView(ctx).apply { textSize = 11f }
                    left.addView(statusTv)
                    btnRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                    addView(left); addView(btnRow)
                    refreshRow()
                })
            }
        })

        // 3. Servers & Priority Card
        lateinit var rebuildProviders: () -> Unit
        val providerRowsHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        var providerQuery = ""

        fun isSrvOnNow(key: String): Boolean = pendingBool[key] ?: isServerEnabled(ctx, key)

        fun showMoveDialog(label: String, currentPos: Int, total: Int, onMove: (Int) -> Unit) {
            val inp = EditText(ctx).apply {
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                setText("$currentPos"); hint = "1 - $total"; selectAll()
                setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                setPadding(14.dp(ctx), 10.dp(ctx), 14.dp(ctx), 10.dp(ctx)); background = inputBg(ctx)
            }
            val box = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL; setPadding(22.dp(ctx), 16.dp(ctx), 22.dp(ctx), 8.dp(ctx))
                addView(TextView(ctx).apply { text = "Move \"$label\" to position:"; textSize = 13f; setTextColor(TEXT_SECONDARY); setPadding(0, 0, 0, 8.dp(ctx)) })
                addView(inp)
            }
            val d = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
                .setView(box)
                .setPositiveButton("Move") { _, _ ->
                    val t = inp.text.toString().toIntOrNull()
                    if (t != null && t in 1..total) onMove(t - 1) else safeToast(ctx, "Enter 1 - $total")
                }
                .setNegativeButton("Cancel", null).create()
            d.window?.setBackgroundDrawable(roundRect(BG_DARK, 16f.dp(ctx))); d.show()
        }

        rebuildProviders = {
            val allMap = SERVERS.associateBy { it.key }
            val currentKeys = order.filter { it in allMap }.toMutableList()
            SERVERS.map { it.key }.forEach { if (it !in currentKeys) currentKeys.add(it) }
            order.clear(); order.addAll(currentKeys)

            providerRowsHolder.removeAllViews()
            val filtered = if (providerQuery.isBlank()) order else order.filter { k ->
                val s = allMap[k]
                s != null && (s.name.contains(providerQuery, true) || s.tag.contains(providerQuery, true))
            }

            filtered.forEachIndexed { dIdx, key ->
                val srv = allMap[key] ?: return@forEachIndexed
                val actualIdx = order.indexOf(key)
                if (dIdx > 0) providerRowsHolder.addView(divider(ctx))

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(16.dp(ctx), 10.dp(ctx), 12.dp(ctx), 10.dp(ctx))
                }

                row.addView(TextView(ctx).apply {
                    text = "${actualIdx + 1}"; textSize = 11f; setTypeface(null, Typeface.BOLD)
                    setTextColor(ACCENT_START); gravity = Gravity.CENTER; minWidth = 28.dp(ctx)
                    setPadding(4.dp(ctx), 4.dp(ctx), 4.dp(ctx), 4.dp(ctx))
                    background = GradientDrawable().apply {
                        cornerRadius = 6f.dp(ctx); setColor(Color.parseColor("#1A1730")); setStroke(1, Color.parseColor("#2E2850"))
                    }
                    isClickable = true
                    setOnClickListener {
                        showMoveDialog(srv.name, actualIdx + 1, order.size) { target ->
                            val item = order.removeAt(actualIdx)
                            order.add(target.coerceIn(0, order.size), item)
                            rebuildProviders()
                        }
                    }
                })
                row.addView(hSpacer(ctx, 10))

                val nameCol = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                nameCol.addView(TextView(ctx).apply {
                    text = srv.name; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY)
                    isSingleLine = true; ellipsize = TextUtils.TruncateAt.END
                })
                nameCol.addView(TextView(ctx).apply {
                    text = if (srv.isCustom) "CS3 Plugin" else srv.tag
                    textSize = 10f; setTextColor(TEXT_SECONDARY)
                })
                row.addView(nameCol)

                fun arrow(sym: String, enabled: Boolean, action: () -> Unit) = TextView(ctx).apply {
                    text = sym; textSize = 16f; gravity = Gravity.CENTER
                    setTextColor(if (enabled) ACCENT_START else Color.parseColor("#252840"))
                    setPadding(8.dp(ctx), 5.dp(ctx), 8.dp(ctx), 5.dp(ctx))
                    if (enabled) setOnClickListener { action() }
                }
                val searching = providerQuery.isNotBlank()
                row.addView(arrow("↑", !searching && actualIdx > 0) {
                    order.add(actualIdx - 1, order.removeAt(actualIdx)); rebuildProviders()
                })
                row.addView(arrow("↓", !searching && actualIdx < order.lastIndex) {
                    order.add(actualIdx + 1, order.removeAt(actualIdx)); rebuildProviders()
                })

                val sw = styledSwitch(ctx, isSrvOnNow(key))
                row.addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    isClickable = true; background = stateDrawable(); addView(sw)
                    setOnClickListener { sw.isChecked = !sw.isChecked; pendingBool[key] = sw.isChecked }
                })

                providerRowsHolder.addView(row)
            }
        }

        layout.addView(buildCollapsibleCard(
            ctx, "🎬  Servers & Priority", ACCENT_START, ACCENT_END, startExpanded = true,
            summaryProvider = { "${order.count { isSrvOnNow(it) }} / ${order.size} on" }
        ) {
            val pillRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                setPadding(16.dp(ctx), 6.dp(ctx), 16.dp(ctx), 6.dp(ctx))
                addView(pillBtn(ctx, "✓ All", Color.parseColor("#4ADE80"), Color.parseColor("#0A1A0F"), Color.parseColor("#1A3A1F")) {
                    order.forEach { pendingBool[it] = true }; rebuildProviders(); safeToast(ctx, "All servers enabled")
                })
                addView(hSpacer(ctx, 8))
                addView(pillBtn(ctx, "✕ None", DANGER_COLOR, Color.parseColor("#1A0A0D"), Color.parseColor("#3A1520")) {
                    order.forEach { pendingBool[it] = false }; rebuildProviders(); safeToast(ctx, "All servers disabled")
                })
                addView(hSpacer(ctx, 8))
                addView(pillBtn(ctx, "↺ Reset", ACCENT_START, Color.parseColor("#1A1730"), Color.parseColor("#2E2850")) {
                    order.clear(); order.addAll(SERVERS.map { it.key }); rebuildProviders()
                })
            }
            addView(pillRow)
            addView(EditText(ctx).apply {
                hint = "Search servers..."; textSize = 13f; setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                isSingleLine = true; setPadding(14.dp(ctx), 9.dp(ctx), 14.dp(ctx), 9.dp(ctx)); background = inputBg(ctx)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.setMargins(16.dp(ctx), 4.dp(ctx), 16.dp(ctx), 6.dp(ctx)) }
                addTextChangedListener(object : TextWatcher {
                    override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                    override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {
                        providerQuery = s?.toString()?.trim().orEmpty(); rebuildProviders()
                    }
                    override fun afterTextChanged(s: Editable?) {}
                })
            })
            rebuildProviders()
            addView(providerRowsHolder)
        })

        // 4. CS3 Repositories / .cs3 Card
        layout.addView(buildCollapsibleCard(
            ctx, "📦  CS3 Repositories / .cs3", Color.parseColor("#38BDF8"), Color.parseColor("#0284C7"),
            summaryProvider = { "${cachedCs3Servers.size} loaded" }
        ) {
            setPadding(16.dp(ctx), 6.dp(ctx), 16.dp(ctx), 12.dp(ctx))
            val nameInput = EditText(ctx).apply {
                hint = "Repo Name (optional)"; textSize = 12.5f; setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                isSingleLine = true; setPadding(12.dp(ctx), 9.dp(ctx), 12.dp(ctx), 9.dp(ctx)); background = inputBg(ctx)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = 8.dp(ctx) }
            }
            val urlInput = EditText(ctx).apply {
                hint = "https://.../repo.json or Plugin.cs3"; textSize = 12.5f; setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                isSingleLine = true; setPadding(12.dp(ctx), 9.dp(ctx), 12.dp(ctx), 9.dp(ctx)); background = inputBg(ctx)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 8.dp(ctx) }
            }
            val urlRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = 8.dp(ctx) }
                addView(urlInput)
                addView(pillBtn(ctx, "📋 Paste", Color.parseColor("#BAE6FD"), Color.parseColor("#0D2338"), Color.parseColor("#0284C7")) {
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) { urlInput.setText(clip); urlInput.setSelection(clip.length) }
                })
            }

            val repoListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            lateinit var renderRepos: () -> Unit

            fun fetchRepo(repo: Cs3RepoConfig) {
                safeToast(ctx, "Fetching CS3 servers...")
                dialogScope.launch {
                    val fetched = fetchServersFromCs3Repo(repo)
                    val prefix = "cs3_${repo.id.takeLast(6)}_"
                    val updated = (cachedCs3Servers.filterNot { it.key.startsWith(prefix) } + fetched).distinctBy { it.key }
                    saveCs3Servers(ctx, updated)
                    repo.serverCount = fetched.size
                    saveCs3Repos(ctx, cs3RepoList)
                    if (isContextAlive(ctx)) {
                        renderRepos()
                        rebuildProviders()
                        safeToast(ctx, "${fetched.size} CS3 servers loaded!")
                    }
                }
            }

            renderRepos = {
                repoListHolder.removeAllViews()
                cs3RepoList.forEachIndexed { idx, repo ->
                    if (idx > 0) repoListHolder.addView(divider(ctx))
                    repoListHolder.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(4.dp(ctx), 10.dp(ctx), 4.dp(ctx), 10.dp(ctx))
                        val info = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        info.addView(TextView(ctx).apply { text = "${repo.name} (${repo.serverCount})"; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                        info.addView(TextView(ctx).apply { text = repo.repoUrl; textSize = 10f; setTextColor(TEXT_SECONDARY); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                        addView(info)
                        addView(pillBtn(ctx, "Fetch", Color.parseColor("#38BDF8"), Color.parseColor("#0D2338"), Color.parseColor("#0284C7")) { fetchRepo(repo) })
                        addView(hSpacer(ctx, 6))
                        addView(pillBtn(ctx, "✕", DANGER_COLOR, Color.parseColor("#1A0A0D"), Color.parseColor("#3A1520")) {
                            val removed = cs3RepoList.removeAt(idx)
                            val prefix = "cs3_${removed.id.takeLast(6)}_"
                            saveCs3Servers(ctx, cachedCs3Servers.filterNot { it.key.startsWith(prefix) })
                            saveCs3Repos(ctx, cs3RepoList)
                            renderRepos(); rebuildProviders()
                        })
                    })
                }
            }

            addView(nameInput); addView(urlRow)
            addView(pillBtn(ctx, "+ Add & Fetch CS3 Servers", Color.WHITE, Color.parseColor("#0284C7"), Color.parseColor("#38BDF8")) {
                var u = urlInput.text.toString().trim()
                if (u.isBlank()) { safeToast(ctx, "URL dalein"); return@pillBtn }
                if (!u.startsWith("http")) u = "https://$u"
                val n = nameInput.text.toString().trim().ifEmpty { u.substringBefore("?").substringAfterLast("/").removeSuffix(".cs3").ifBlank { deriveManifestLabel(u) } }
                val newRepo = Cs3RepoConfig(System.currentTimeMillis().toString(), n, u, true, 0)
                cs3RepoList.add(newRepo); saveCs3Repos(ctx, cs3RepoList)
                nameInput.setText(""); urlInput.setText(""); renderRepos(); fetchRepo(newRepo)
            })
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 8.dp(ctx)) })
            renderRepos()
            addView(repoListHolder)
        })

        // 5. Nuvio / Stremio Addons Card
        layout.addView(buildCollapsibleCard(
            ctx, "🔌  Nuvio / Stremio Addons", Color.parseColor("#22D3EE"), Color.parseColor("#0891B2"),
            summaryProvider = { if (nuvioList.isEmpty()) "" else "${nuvioList.size} addon(s)" }
        ) {
            setPadding(16.dp(ctx), 6.dp(ctx), 16.dp(ctx), 12.dp(ctx))
            val lblIn = EditText(ctx).apply {
                hint = "Addon Name (e.g. Torrentio / Nuvio)"; textSize = 12.5f; setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                isSingleLine = true; setPadding(12.dp(ctx), 9.dp(ctx), 12.dp(ctx), 9.dp(ctx)); background = inputBg(ctx)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = 8.dp(ctx) }
            }
            val urlIn = EditText(ctx).apply {
                hint = "https://.../manifest.json"; textSize = 12.5f; setTextColor(TEXT_PRIMARY); setHintTextColor(TEXT_SECONDARY)
                isSingleLine = true; setPadding(12.dp(ctx), 9.dp(ctx), 12.dp(ctx), 9.dp(ctx)); background = inputBg(ctx)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).also { it.marginEnd = 8.dp(ctx) }
            }
            val rowIn = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                    .also { it.bottomMargin = 8.dp(ctx) }
                addView(urlIn)
                addView(pillBtn(ctx, "📋 Paste", Color.parseColor("#E9D5FF"), Color.parseColor("#241738"), Color.parseColor("#9333EA")) {
                    val clip = clipboard?.primaryClip?.getItemAt(0)?.coerceToText(ctx)?.toString()?.trim().orEmpty()
                    if (clip.isNotBlank()) { urlIn.setText(clip); urlIn.setSelection(clip.length) }
                })
            }

            val addonHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
            lateinit var renderAddons: () -> Unit
            renderAddons = {
                addonHolder.removeAllViews()
                nuvioList.forEachIndexed { idx, item ->
                    if (idx > 0) addonHolder.addView(divider(ctx))
                    addonHolder.addView(LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                        setPadding(4.dp(ctx), 10.dp(ctx), 4.dp(ctx), 10.dp(ctx))
                        val col = LinearLayout(ctx).apply {
                            orientation = LinearLayout.VERTICAL
                            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                        }
                        col.addView(TextView(ctx).apply { text = item.label; textSize = 13f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                        col.addView(TextView(ctx).apply { text = item.manifestUrl; textSize = 10f; setTextColor(TEXT_SECONDARY); isSingleLine = true; ellipsize = TextUtils.TruncateAt.END })
                        addView(col)
                        addView(pillBtn(ctx, "✕", DANGER_COLOR, Color.parseColor("#1A0A0D"), Color.parseColor("#3A1520")) {
                            nuvioList.removeAt(idx); saveNuvioManifests(ctx, nuvioList); renderAddons()
                        })
                    })
                }
            }

            addView(lblIn); addView(rowIn)
            addView(pillBtn(ctx, "+ Add Addon", Color.parseColor("#22D3EE"), Color.parseColor("#0A1820"), Color.parseColor("#1A3040")) {
                val raw = urlIn.text.toString().trim()
                if (raw.isBlank()) { safeToast(ctx, "Manifest URL dalein"); return@pillBtn }
                val norm = normalizeNuvioManifestUrl(raw)
                val name = lblIn.text.toString().trim().ifEmpty { deriveManifestLabel(norm) }
                nuvioList.add(NuvioManifestConfig(System.currentTimeMillis().toString(), name, norm, true))
                saveNuvioManifests(ctx, nuvioList)
                lblIn.setText(""); urlIn.setText(""); renderAddons(); safeToast(ctx, "Addon saved!")
            })
            addView(View(ctx).apply { layoutParams = LinearLayout.LayoutParams(1, 8.dp(ctx)) })
            renderAddons()
            addView(addonHolder)
        })

        // 6. Audio & Quality Card
        layout.addView(buildCollapsibleCard(ctx, "🔊  Audio & Quality", Color.parseColor("#F59E0B"), Color.parseColor("#D97706")) {
            val qRow = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL; setPadding(16.dp(ctx), 8.dp(ctx), 16.dp(ctx), 10.dp(ctx))
            }
            val qChips = mutableMapOf<String, TextView>()
            fun refreshQ() {
                qChips.forEach { (q, tv) ->
                    val on = q == pendingQuality
                    tv.setTextColor(if (on) Color.WHITE else TEXT_SECONDARY)
                    tv.background = GradientDrawable().apply {
                        cornerRadius = 99f
                        setColor(if (on) ACCENT_START else Color.parseColor("#0D1117"))
                        setStroke(1, if (on) ACCENT_START else Color.parseColor("#2E2850"))
                    }
                }
            }
            QUALITIES.forEach { q ->
                val tv = TextView(ctx).apply {
                    text = q; textSize = 11.5f; setTypeface(null, Typeface.BOLD)
                    setPadding(12.dp(ctx), 6.dp(ctx), 12.dp(ctx), 6.dp(ctx))
                    layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
                        .also { it.marginEnd = 6.dp(ctx) }
                    setOnClickListener { pendingQuality = q; refreshQ() }
                }
                qChips[q] = tv; qRow.addView(tv)
            }
            refreshQ()
            addView(HorizontalScrollView(ctx).apply { isHorizontalScrollBarEnabled = false; addView(qRow) })

            AUDIO_TRACKS.forEach { aud ->
                addView(divider(ctx))
                val sw = styledSwitch(ctx, isAudioEnabled(ctx, aud.key))
                addView(LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setPadding(20.dp(ctx), 11.dp(ctx), 16.dp(ctx), 11.dp(ctx))
                    isClickable = true; background = stateDrawable()
                    val col = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                    }
                    col.addView(TextView(ctx).apply { text = aud.title; textSize = 14f; setTypeface(null, Typeface.BOLD); setTextColor(TEXT_PRIMARY) })
                    col.addView(TextView(ctx).apply { text = aud.subtitle; textSize = 11f; setTextColor(TEXT_SECONDARY) })
                    addView(col); addView(sw)
                    setOnClickListener { sw.isChecked = !sw.isChecked; setAudioEnabled(ctx, aud.key, sw.isChecked) }
                })
            }
        })

        scroll.addView(layout)

        val dialog = AlertDialog.Builder(ctx, android.R.style.Theme_Material_Dialog)
            .setView(scroll)
            .setPositiveButton("Save") { _, _ ->
                pendingBool.forEach { (k, v) -> setServerEnabled(ctx, k, v) }
                saveServerOrder(ctx, order)
                setConcurrency(ctx, pendingConcurrency)
                setTimeoutSeconds(ctx, pendingTimeout)
                setPreferredQuality(ctx, pendingQuality)
                setLowBufferMode(ctx, pendingLowBuffer)
                setDownloadOnly(ctx, pendingDownloadOnly)
                saveCs3Repos(ctx, cs3RepoList)
                saveNuvioManifests(ctx, nuvioList)
                safeToast(ctx, "✓ Settings saved!")
            }
            .setNegativeButton("Cancel", null)
            .create()

        dialog.setOnDismissListener { runCatching { dialogScope.cancel() } }
        dialog.window?.setBackgroundDrawable(roundRect(BG_DARK, 20f.dp(ctx)))
        dialog.show()
        dialog.window?.setLayout(
            (ctx.resources.displayMetrics.widthPixels * 0.95).toInt(),
            WindowManager.LayoutParams.WRAP_CONTENT
        )
        dialog.getButton(AlertDialog.BUTTON_POSITIVE)?.apply { setTextColor(ACCENT_START); isAllCaps = false }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE)?.apply { setTextColor(TEXT_SECONDARY); isAllCaps = false }
    }
}
