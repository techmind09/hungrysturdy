package com.hungrysturdy

import android.app.Activity
import android.app.AlertDialog
import android.content.ClipboardManager
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    private const val PREFS_NAME = "moviefox_ultra_max_v12"
    private const val KEY_SRV_PREFIX = "mf_srv_"
    private const val KEY_AUDIO_PREFIX = "mf_audio_"
    private const val KEY_NUVIO_MANIFESTS = "mf_nuvio_manifests_json"
    private const val KEY_CS3_REPOS = "mf_cs3_repos_json"
    private const val KEY_CS3_SERVERS = "mf_cs3_servers_json"
    private const val KEY_QUALITY = "mf_preferred_quality"
    private const val KEY_LOW_BUFFER = "mf_low_buffer_mode"
    private const val KEY_FAST_FETCH = "mf_fast_fetch_mode"
    private const val KEY_CONCURRENCY = "mf_concurrency"
    private const val KEY_TIMEOUT_SEC = "mf_timeout_sec"

    private const val DEFAULT_UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

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
        ServerItem("cinestream", "CineStream", "HUB", "https://moviefox.pro/", "#38BDF8"),
        ServerItem("4khdhub", "4KHDHub", "4K/HDR", "https://4khdhub.dad/", "#F43F5E"),
        ServerItem("hdhub4u", "HDHub4u", "SITE", "https://hdhub4u.tv/", "#FB923C"),
        ServerItem("moviesmod", "MoviesMod", "SITE", "https://moviesmod.ltd/", "#A855F7"),
        ServerItem("moviesdrive", "MoviesDrive", "DRIVE", "https://moviesdrive.cv/", "#22D3EE"),
        ServerItem("purstream", "Purstream", "API", "https://moviefox.pro/api/stream/purstream", "#38BDF8"),
        ServerItem("castle", "Castle", "API", "https://moviefox.pro/api/stream/castle", "#818CF8"),
        ServerItem("watchout", "Watchout", "API", "https://moviefox.pro/api/stream/watchout", "#2DD4BF"),
        ServerItem("vegamovies", "VegaMovies", "FSL/PXL", "https://vegamovies.lease/", "#EC4899"),
        ServerItem("sflix", "Sflix", "SITE", "https://sflix.film/", "#F97316"),
        ServerItem("prmovies", "Prmovies", "SITE", "https://prmovies.coupons/", "#F59E0B"),
        ServerItem("play4u", "Play4u", "HLS", "https://play4u.org/", "#FB7185"),
        ServerItem("nuvio", "Nuvio Addon", "MANIFEST", "https://nuvio.app/", "#A855F7"),
        ServerItem("moviebox", "MovieBox", "BFF", "https://netnaija.film/", "#34D399"),
        ServerItem("netnaija", "NetNaija", "SITE", "https://www.thenetnaija.net/", "#FACC15")
    )

    val SERVERS: List<ServerItem>
        get() = BASE_SERVERS + cachedCs3Servers.toList()

    val AUDIO_TRACKS = listOf(
        AudioOption("hindi", "Hindi", "Primary language • Hindi audio / dub", true),
        AudioOption("english", "English", "Primary language • English audio", true),
        AudioOption("tamil", "Tamil", "Primary language • Tamil audio / dub", true),
        AudioOption("telugu", "Telugu", "Other language • Telugu audio / dub", false),
        AudioOption("korean", "Korean", "Other language • Korean audio / dub", false),
        AudioOption("japanese", "Japanese", "Other language • Japanese audio / dub", false),
        AudioOption("punjabi", "Punjabi", "Other language • Punjabi audio / dub", false),
        AudioOption("spanish", "Spanish", "Other language • Spanish audio / dub", false),
        AudioOption("french", "French", "Other language • French audio / dub", false),
        AudioOption("arabic", "Arabic", "Other language • Arabic audio / dub", false),
        AudioOption("bengali", "Bengali", "Other language • Bengali audio / dub", false)
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

    private fun safeColor(hex: String, fallback: String = "#94A3B8"): Int =
        runCatching { Color.parseColor(hex) }.getOrElse { Color.parseColor(fallback) }

    private fun safeToast(ctx: Context?, msg: String, duration: Int = Toast.LENGTH_SHORT) {
        if (!isContextAlive(ctx)) return
        runCatching { Toast.makeText(ctx, msg, duration).show() }
    }

    private fun toRawGithubUrl(raw: String): String {
        val trimmed = raw.trim()
        val fixed = if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) {
            trimmed
        } else {
            "https://$trimmed"
        }
        return when {
            fixed.contains("github.com/") && fixed.contains("/blob/") ->
                fixed.replace("https://github.com/", "https://raw.githubusercontent.com/")
                    .replace("/blob/", "/")
            fixed.contains("github.com/") && fixed.contains("/raw/") ->
                fixed.replace("https://github.com/", "https://raw.githubusercontent.com/")
                    .replace("/raw/", "/")
            else -> fixed
        }
    }

    private fun normalizeNuvioManifestUrl(raw: String): String {
        var u = raw.trim()
        if (u.startsWith("stremio://", ignoreCase = true)) {
            u = "https://" + u.substringAfter("://")
        }
        if (!u.startsWith("http://") && !u.startsWith("https://")) {
            u = "https://$u"
        }
        val qIdx = u.indexOf('?')
        val pathPart = if (qIdx >= 0) u.substring(0, qIdx) else u
        val queryPart = if (qIdx >= 0) u.substring(qIdx) else ""
        val cleanPath = pathPart.trimEnd('/')
        val withManifest = if (cleanPath.endsWith("/manifest.json", ignoreCase = true)) {
            cleanPath
        } else {
            "$cleanPath/manifest.json"
        }
        return "$withManifest$queryPart"
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
                    put("key", srv.key)
                    put("name", srv.name)
                    put("tag", srv.tag)
                    put("probeUrl", srv.probeUrl)
                    put("accentHex", srv.accentHex)
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
                    put("id", repo.id)
                    put("name", repo.name)
                    put("repoUrl", repo.repoUrl)
                    put("enabled", repo.enabled)
                    put("serverCount", repo.serverCount)
                }
            )
        }
        prefs(ctx).edit().putString(KEY_CS3_REPOS, arr.toString()).apply()
    }

    fun getEnabledCs3Servers(ctx: Context): List<ServerItem> {
        prefs(ctx)
        return cachedCs3Servers.filter { isServerEnabled(ctx, it.key) }
    }

    private fun readHttpBytes(urlStr: String, timeoutMs: Int = 7000): ByteArray? {
        return runCatching {
            val fixedUrl = toRawGithubUrl(urlStr)
            val conn = (URL(fixedUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = timeoutMs
                readTimeout = timeoutMs
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", DEFAULT_UA)
                setRequestProperty("Accept", "application/json, application/octet-stream, text/plain, */*")
            }
            val code = conn.responseCode
            val bytes = if (code in 200..299) {
                conn.inputStream.use { it.readBytes() }
            } else {
                null
            }
            conn.disconnect()
            bytes
        }.getOrNull()
    }

    /**
     * Extracts both plugin manifest metadata AND all bundled providers/servers inside a .cs3 archive
     * (including multi-provider plugins like StreamHubOne.cs3).
     */
    private fun extractAllServersFromCs3Zip(
        bytes: ByteArray,
        repoId: String,
        fallbackUrl: String
    ): List<ServerItem> {
        val discovered = mutableListOf<ServerItem>()
        val idTag = repoId.takeLast(6)
        var pluginName = ""

        runCatching {
            ZipInputStream(ByteArrayInputStream(bytes)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val lower = entry.name.lowercase()
                        when {
                            lower.endsWith("manifest.json") -> {
                                val text = zis.bufferedReader().readText()
                                val mObj = JSONObject(text)
                                pluginName = mObj.optString("name", "").trim()
                                    .ifBlank { mObj.optString("internalName", "").trim() }
                            }
                            lower.endsWith(".json") -> {
                                val text = zis.bufferedReader().readText().trim()
                                parseJsonServerEntries(text, idTag, fallbackUrl, discovered)
                            }
                            lower.endsWith(".dex") -> {
                                val dexBytes = zis.readBytes()
                                extractProvidersFromDexBytes(dexBytes, idTag, fallbackUrl, discovered)
                            }
                        }
                    }
                    entry = zis.nextEntry
                }
            }
        }

        if (discovered.isEmpty()) {
            val finalName = pluginName.ifBlank {
                fallbackUrl.substringBefore("?").substringAfterLast("/").removeSuffix(".cs3")
            }.ifBlank { "CS3 Plugin" }
            val slug = finalName.lowercase().replace(Regex("[^a-z0-9]+"), "_").trim('_')
            discovered.add(
                ServerItem(
                    key = "cs3_${idTag}_${slug.ifBlank { "plugin" }}",
                    name = finalName,
                    tag = "CS3",
                    probeUrl = fallbackUrl,
                    accentHex = "#34D399",
                    isCustom = true
                )
            )
        }
        return discovered.distinctBy { it.key }
    }

    private fun parseJsonServerEntries(
        body: String,
        idTag: String,
        defaultUrl: String,
        out: MutableList<ServerItem>
    ) {
        runCatching {
            val entries = mutableListOf<JSONObject>()
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
            } else if (body.startsWith("{")) {
                val root = JSONObject(body)
                listOf("plugins", "items", "providers", "servers", "sources").forEach { key ->
                    val arr = root.optJSONArray(key)
                    if (arr != null) {
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
                    .ifBlank { defaultUrl }
                    .trim()
                val slug = (internalName.ifBlank { finalName })
                    .lowercase()
                    .replace(Regex("[^a-z0-9]+"), "_")
                    .trim('_')
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

    /**
     * Scans DEX constant pool / ASCII strings inside a multi-provider .cs3 plugin
     * for provider declarations (e.g., Provider/Extractor classes or embedded provider JSON).
     */
    private fun extractProvidersFromDexBytes(
        dexBytes: ByteArray,
        idTag: String,
        fallbackUrl: String,
        out: MutableList<ServerItem>
    ) {
        if (dexBytes.isEmpty()) return
        val rawText = String(dexBytes, Charsets.ISO_8859_1)

        // 1. Check embedded JSON arrays of providers inside DEX strings
        val jsonArrayRegex = Regex("""\[\s*\{\s*"name"\s*:\s*"[^"]{2,40}"[^]]{10,4000}\]""")
        jsonArrayRegex.findAll(rawText).forEach { match ->
            parseJsonServerEntries(match.value, idTag, fallbackUrl, out)
        }

        // 2. Check Cloudstream MainAPI / Provider class names in DEX descriptor table
        if (out.isEmpty()) {
            val classRegex = Regex("""L[a-zA-Z0-9_/]+/([A-Z][a-zA-Z0-9]{2,28})(?:Provider|Plugin|Source);""")
            val seen = mutableSetOf<String>()
            var idx = 0
            classRegex.findAll(rawText).forEach { m ->
                val simpleName = m.groupValues[1]
                if (simpleName !in setOf("Main", "Base", "Abstract", "Custom", "Cloudstream", "Stream") &&
                    seen.add(simpleName.lowercase())
                ) {
                    val slug = simpleName.lowercase().replace(Regex("[^a-z0-9]+"), "_")
                    out.add(
                        ServerItem(
                            key = "cs3_${idTag}_$slug",
                            name = simpleName,
                            tag = "CS3",
                            probeUrl = fallbackUrl,
                            accentHex = AVATAR_PALETTE[idx % AVATAR_PALETTE.size],
                            isCustom = true
                        )
                    )
                    idx++
                }
            }
        }
    }

    private suspend fun fetchServersFromCs3Repo(
        repo: Cs3RepoConfig,
        timeoutMs: Int = 7000
    ): List<ServerItem> = withContext(Dispatchers.IO) {
        val out = mutableListOf<ServerItem>()
        val rawUrl = toRawGithubUrl(repo.repoUrl)
        val bytes = readHttpBytes(rawUrl, timeoutMs) ?: return@withContext emptyList()
        if (bytes.isEmpty()) return@withContext emptyList()

        // Case 1: Direct .cs3 plugin file
        if (rawUrl.substringBefore("?").endsWith(".cs3", ignoreCase = true)) {
            return@withContext extractAllServersFromCs3Zip(bytes, repo.id, rawUrl)
        }

        val body = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
        if (body.isBlank()) return@withContext emptyList()

        val idTag = repo.id.takeLast(6)
        val entries = mutableListOf<JSONObject>()

        runCatching {
            if (body.startsWith("[")) {
                val arr = JSONArray(body)
                for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { entries.add(it) }
            } else if (body.startsWith("{")) {
                val rootJson = JSONObject(body)

                // Direct plugins array
                val pluginsArr = rootJson.optJSONArray("plugins")
                    ?: rootJson.optJSONArray("items")
                    ?: rootJson.optJSONArray("providers")
                if (pluginsArr != null) {
                    for (i in 0 until pluginsArr.length()) {
                        pluginsArr.optJSONObject(i)?.let { entries.add(it) }
                    }
                }

                // Cloudstream standard repo.json uses "pluginLists": ["https://.../plugins.json"]
                val listUrls = mutableListOf<String>()
                rootJson.optJSONArray("pluginLists")?.let { pLists ->
                    for (i in 0 until pLists.length()) {
                        pLists.optString(i, "").trim().takeIf { it.isNotBlank() }?.let { listUrls.add(it) }
                    }
                }
                rootJson.optString("manifestList", "").trim()
                    .takeIf { it.isNotBlank() }
                    ?.let { listUrls.add(it) }

                listUrls.distinct().forEach { rawListUrl ->
                    val resolvedListUrl = if (rawListUrl.startsWith("http")) {
                        toRawGithubUrl(rawListUrl)
                    } else {
                        val base = rawUrl.substringBeforeLast('/')
                        "$base/${rawListUrl.trimStart('/')}"
                    }
                    val listBytes = readHttpBytes(resolvedListUrl, timeoutMs)
                    if (listBytes != null && listBytes.isNotEmpty()) {
                        val listText = String(listBytes, Charsets.UTF_8).trim()
                        if (listText.startsWith("[")) {
                            val subArr = JSONArray(listText)
                            for (j in 0 until subArr.length()) {
                                subArr.optJSONObject(j)?.let { entries.add(it) }
                            }
                        } else if (listText.startsWith("{")) {
                            val subObj = JSONObject(listText)
                            val subPlugins = subObj.optJSONArray("plugins") ?: subObj.optJSONArray("items")
                            if (subPlugins != null) {
                                for (j in 0 until subPlugins.length()) {
                                    subPlugins.optJSONObject(j)?.let { entries.add(it) }
                                }
                            }
                        }
                    }
                }
            }
        }

        entries.forEachIndexed { idx, pObj ->
            val rawName = pObj.optString("name", "").trim()
            val internalName = pObj.optString("internalName", "").trim()
            val pluginUrl = pObj.optString("url", "")
                .ifBlank { pObj.optString("pluginUrl", "") }
                .ifBlank { pObj.optString("downloadUrl", "") }
                .ifBlank { pObj.optString("repositoryUrl", "") }
                .ifBlank { rawUrl }
                .trim()
            if (rawName.isBlank() && internalName.isBlank()) return@forEachIndexed
            val finalName = rawName.ifBlank { internalName }
            val resolvedProbe = toRawGithubUrl(pluginUrl)
            val slug = (internalName.ifBlank { finalName })
                .lowercase()
                .replace(Regex("[^a-z0-9]+"), "_")
                .trim('_')
            out.add(
                ServerItem(
                    key = "cs3_${idTag}_${slug.ifBlank { "srv$idx" }}",
                    name = finalName,
                    tag = "CS3",
                    probeUrl = resolvedProbe,
                    accentHex = AVATAR_PALETTE[idx % AVATAR_PALETTE.size],
                    isCustom = true
                )
            )
        }

        out.distinctBy { it.key }
    }

    private suspend fun verifyNuvioManifest(
        rawUrl: String,
        timeoutMs: Int = 5500
    ): Pair<String, String?> = withContext(Dispatchers.IO) {
        val normalized = normalizeNuvioManifestUrl(rawUrl)
        val bytes = readHttpBytes(normalized, timeoutMs) ?: return@withContext normalized to null
        val text = runCatching { String(bytes, Charsets.UTF_8).trim() }.getOrDefault("")
        val parsedName = runCatching {
            if (text.startsWith("{")) {
                JSONObject(text).optString("name", "").trim().takeIf { it.isNotBlank() }
            } else null
        }.getOrNull()
        normalized to parsedName
    }

    fun isServerEnabled(ctx: Context, key: String): Boolean =
        prefs(ctx).getBoolean("$KEY_SRV_PREFIX${key.lowercase()}", true)

    fun setServerEnabled(ctx: Context, key: String, enabled: Boolean) {
        prefs(ctx).edit().putBoolean("$KEY_SRV_PREFIX${key.lowercase()}", enabled).apply()
    }

    // Kept for backward compatibility with MovieFoxProvider callers; no longer flags UI errors
    fun reportServerResult(ctx: Context?, key: String, success: Boolean, latencyMs: Long = 0L) {
        // Error badges removed per user preference
    }

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
                    put("id", item.id)
                    put("label", item.label)
                    put("manifestUrl", item.manifestUrl)
                    put("enabled", item.enabled)
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
        host.replaceFirstChar { it.uppercase() }.ifBlank { "Nuvio Manifest" }
    } catch (_: Exception) {
        "Nuvio Manifest"
    }

    fun getPreferredQuality(ctx: Context): String =
        prefs(ctx).getString(KEY_QUALITY, "AUTO") ?: "AUTO"

    fun setPreferredQuality(ctx: Context, quality: String) {
        prefs(ctx).edit().putString(KEY_QUALITY, quality).apply()
    }

    fun isLowBufferMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_LOW_BUFFER, true)

    fun setLowBufferMode(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_LOW_BUFFER, enabled).apply()
    }

    fun isFastFetchMode(ctx: Context): Boolean =
        prefs(ctx).getBoolean(KEY_FAST_FETCH, true)

    fun setFastFetchMode(ctx: Context, enabled: Boolean) {
        prefs(ctx).edit().putBoolean(KEY_FAST_FETCH, enabled).apply()
    }

    fun isAutoDisableErrorServers(ctx: Context): Boolean = false
    fun setAutoDisableErrorServers(ctx: Context, enabled: Boolean) {}
    fun isStreamOnly(ctx: Context): Boolean = isLowBufferMode(ctx)
    fun isDownloadOnly(ctx: Context): Boolean = false
    fun getParallelServers(ctx: Context): Int = getConcurrency(ctx)

    fun getConcurrency(ctx: Context): Int =
        prefs(ctx).getInt(KEY_CONCURRENCY, 16)

    fun setConcurrency(ctx: Context, value: Int) {
        prefs(ctx).edit().putInt(KEY_CONCURRENCY, value.coerceIn(2, 24)).apply()
    }

    fun getTimeoutSeconds(ctx: Context): Int =
        prefs(ctx).getInt(KEY_TIMEOUT_SEC, 4)

    fun setTimeoutSeconds(ctx: Context, sec: Int) {
        prefs(ctx).edit().putInt(KEY_TIMEOUT_SEC, sec.coerceIn(3, 15)).apply()
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

    /**
     * Lightweight RecyclerView adapter so 200+ CS3 & built-in servers render smoothly with zero crash.
     */
    private class ServerListAdapter(
        private val ctx: Context,
        private val onToggleChanged: () -> Unit
    ) : RecyclerView.Adapter<ServerListAdapter.VH>() {

        private var items: List<ServerItem> = emptyList()

        fun submitList(newList: List<ServerItem>) {
            items = newList
            notifyDataSetChanged()
        }

        class VH(
            val root: LinearLayout,
            val avatarTv: TextView,
            val nameTv: TextView,
            val tagTv: TextView,
            val urlTv: TextView,
            val sw: Switch
        ) : RecyclerView.ViewHolder(root)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#111520", "#222B3F", 20f)
                setPadding(dp(ctx, 11), dp(ctx, 10), dp(ctx, 11), dp(ctx, 10))
                layoutParams = RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }

            val avatarTv = TextView(ctx).apply {
                textSize = 14f
                gravity = Gravity.CENTER
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 36), dp(ctx, 36)).apply {
                    marginEnd = dp(ctx, 10)
                }
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }

            val topLine = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }

            val nameTv = TextView(ctx).apply {
                textSize = 13.5f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            }

            val tagTv = TextView(ctx).apply {
                textSize = 8.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 6), dp(ctx, 2), dp(ctx, 6), dp(ctx, 2))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(ctx, 6) }
            }

            topLine.addView(nameTv)
            topLine.addView(tagTv)

            val urlTv = TextView(ctx).apply {
                textSize = 10f
                isSingleLine = true
                ellipsize = TextUtils.TruncateAt.END
                setTextColor(safeColor("#7C869C"))
                setPadding(0, dp(ctx, 2), dp(ctx, 6), 0)
            }

            col.addView(topLine)
            col.addView(urlTv)

            val sw = Switch(ctx)

            card.addView(avatarTv)
            card.addView(col)
            card.addView(sw)

            return VH(card, avatarTv, nameTv, tagTv, urlTv, sw)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: VH, position: Int) {
            val srv = items[position]
            val accent = safeColor(srv.accentHex, "#38BDF8")

            holder.avatarTv.text = srv.name.firstOrNull()?.uppercase() ?: "S"
            holder.avatarTv.background = GradientDrawable().apply {
                setColor(Color.argb(42, Color.red(accent), Color.green(accent), Color.blue(accent)))
                cornerRadius = 14f
                setStroke(1, accent)
            }
            holder.avatarTv.setTextColor(accent)

            holder.nameTv.text = srv.name
            holder.tagTv.text = srv.tag
            holder.tagTv.setTextColor(accent)
            holder.tagTv.background = pillBg("#192236", 8f)

            holder.urlTv.text = if (srv.isCustom) {
                "CS3 Provider • ${srv.probeUrl.substringAfter("://").substringBefore("/")}"
            } else {
                srv.probeUrl
            }

            // Detach previous listener before setting checked state to prevent recycled view triggers
            holder.sw.setOnCheckedChangeListener(null)
            holder.sw.isChecked = isServerEnabled(ctx, srv.key)
            holder.sw.setOnCheckedChangeListener { _, checked ->
                setServerEnabled(ctx, srv.key, checked)
                onToggleChanged()
            }

            holder.root.setOnClickListener {
                holder.sw.isChecked = !holder.sw.isChecked
            }
        }
    }

    fun show(ctx: Context) {
        if (!isContextAlive(ctx)) return
        prefs(ctx)

        val dialogScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        val nuvioList = getNuvioManifests(ctx).toMutableList()
        val cs3RepoList = getCs3Repos(ctx).toMutableList()
        val audioCards = mutableMapOf<String, LinearLayout>()

        var currentQuery = ""
        var currentFilter = "ALL" // ALL, BUILTIN, CS3, ACTIVE
        var selectedQuality = getPreferredQuality(ctx)
        var currentConcurrency = getConcurrency(ctx)

        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#07090F", "#1E273B", 34f, 3)
            setPadding(dp(ctx, 14), dp(ctx, 14), dp(ctx, 14), dp(ctx, 14))
        }

        // Top Header Card
        val header = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                intArrayOf(
                    safeColor("#0B192C"),
                    safeColor("#1E153D"),
                    safeColor("#08272E")
                )
            ).apply {
                cornerRadius = 24f
                setStroke(2, safeColor("#2E3D59"))
            }
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
        }

        val topRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        topRow.addView(TextView(ctx).apply {
            text = "MOVIEFOX ULTRA MAX"
            textSize = 16.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
        })

        topRow.addView(TextView(ctx).apply {
            text = "⚡ TURBO FAST"
            textSize = 9.5f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#34D399"))
            background = pillBg("#0D2B22", 14f)
            setPadding(dp(ctx, 9), dp(ctx, 4), dp(ctx, 9), dp(ctx, 4))
        })
        header.addView(topRow)

        val statsRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
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
                text = "-"
                textSize = 13f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor(colorHex))
            }
            val lblTv = TextView(ctx).apply {
                text = label
                textSize = 8.5f
                isSingleLine = true
                setTextColor(safeColor("#94A3B8"))
            }
            box.addView(valTv)
            box.addView(lblTv)
            return box to valTv
        }

        val (srvChip, srvValTv) = statChip("ACTIVE SERVERS", "#38BDF8", 6)
        val (cs3Chip, cs3ValTv) = statChip("CS3 PLUGINS", "#34D399", 6)
        val (spdChip, spdValTv) = statChip("SPEED", "#FBBF24", 0)

        statsRow.addView(srvChip)
        statsRow.addView(cs3Chip)
        statsRow.addView(spdChip)
        header.addView(statsRow)

        root.addView(
            header,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        )

        fun refreshStats() {
            val allSrvs = SERVERS
            val srvOn = allSrvs.count { isServerEnabled(ctx, it.key) }
            val cs3Count = cachedCs3Servers.size
            srvValTv.text = "$srvOn/${allSrvs.size}"
            cs3ValTv.text = "$cs3Count Loaded"
            spdValTv.text = "${currentConcurrency}x • $selectedQuality"
        }
        refreshStats()

        // Main Navigation Tabs
        val tabScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val tabBar = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            background = cardBg("#0F131C", "#212A3D", 18f)
            setPadding(dp(ctx, 4), dp(ctx, 4), dp(ctx, 4), dp(ctx, 4))
        }

        val tabTitles = listOf("Servers", "CS3 Repos", "Nuvio Manifest", "Audio & Quality", "Buffer & Speed")
        val tabButtons = tabTitles.map { title ->
            Button(ctx).apply {
                text = title
                textSize = 11.5f
                isAllCaps = false
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), 0, dp(ctx, 12), 0)
                layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 34))
            }
        }
        tabButtons.forEach { tabBar.addView(it) }
        tabScroll.addView(tabBar)
        root.addView(tabScroll)

        // Search & Quick Bulk Controls (Clean — No Error Buttons)
        val actionRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val searchBox = EditText(ctx).apply {
            hint = "🔍 Search servers or languages..."
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12f
            isSingleLine = true
            background = cardBg("#10141E", "#253046", 15f)
            setPadding(dp(ctx, 11), dp(ctx, 7), dp(ctx, 11), dp(ctx, 7))
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 36), 1f).apply { marginEnd = dp(ctx, 6) }
        }

        val enableAllBtn = Button(ctx).apply {
            text = "ALL ON"
            textSize = 10f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#059669", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36)).apply {
                marginEnd = dp(ctx, 5)
            }
        }

        val disableAllBtn = Button(ctx).apply {
            text = "ALL OFF"
            textSize = 10f
            isSingleLine = true
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#E2E8F0"))
            background = pillBg("#1E293B", 14f)
            setPadding(dp(ctx, 10), 0, dp(ctx, 10), 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(ctx, 36))
        }

        actionRow.addView(searchBox)
        actionRow.addView(enableAllBtn)
        actionRow.addView(disableAllBtn)
        root.addView(actionRow)

        val dialog = AlertDialog.Builder(ctx).setView(root).create()
        dialog.setOnDismissListener {
            runCatching { dialogScope.cancel() }
        }

        // Content Container (Servers uses RecyclerView directly; other tabs use ScrollView)
        val contentHeight = dp(ctx, 365)

        val serversContainer = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        // Category Filter Chips inside Servers Tab
        val filterScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }
        val filterRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val filterOptions = listOf(
            "ALL" to "All Servers",
            "BUILTIN" to "Built-in",
            "CS3" to "CS3 Repos",
            "ACTIVE" to "Active Only"
        )
        val filterChips = mutableMapOf<String, TextView>()

        lateinit var applyServerFilter: () -> Unit

        fun refreshFilterChips() {
            filterChips.forEach { (key, tv) ->
                if (key == currentFilter) {
                    tv.background = pillBg("#7C3AED", 14f)
                    tv.setTextColor(Color.WHITE)
                } else {
                    tv.background = cardBg("#0F131C", "#222B3F", 14f)
                    tv.setTextColor(safeColor("#94A3B8"))
                }
            }
        }

        filterOptions.forEach { (key, label) ->
            val chip = TextView(ctx).apply {
                text = label
                textSize = 11f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 12), dp(ctx, 5), dp(ctx, 12), dp(ctx, 5))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    currentFilter = key
                    refreshFilterChips()
                    applyServerFilter()
                }
            }
            filterChips[key] = chip
            filterRow.addView(chip)
        }
        refreshFilterChips()
        filterScroll.addView(filterRow)
        serversContainer.addView(filterScroll)

        val serverAdapter = ServerListAdapter(ctx) {
            refreshStats()
        }

        val serversRecycler = RecyclerView(ctx).apply {
            layoutManager = LinearLayoutManager(ctx)
            adapter = serverAdapter
            itemAnimator = null
            overScrollMode = View.OVER_SCROLL_NEVER
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        }
        serversContainer.addView(serversRecycler)

        applyServerFilter = {
            val filtered = SERVERS.filter { srv ->
                val matchesTab = when (currentFilter) {
                    "BUILTIN" -> !srv.isCustom
                    "CS3" -> srv.isCustom
                    "ACTIVE" -> isServerEnabled(ctx, srv.key)
                    else -> true
                }
                val matchesSearch = currentQuery.isEmpty() ||
                    srv.name.lowercase().contains(currentQuery) ||
                    srv.tag.lowercase().contains(currentQuery)
                matchesTab && matchesSearch
            }
            serverAdapter.submitList(filtered)
            refreshStats()
        }

        applyServerFilter()

        enableAllBtn.setOnClickListener {
            val editor = prefs(ctx).edit()
            SERVERS.forEach { srv ->
                editor.putBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", true)
            }
            editor.apply()
            applyServerFilter()
            safeToast(ctx, "Sabhi ${SERVERS.size} servers ON ho gaye!")
        }

        disableAllBtn.setOnClickListener {
            val editor = prefs(ctx).edit()
            SERVERS.forEach { srv ->
                editor.putBoolean("$KEY_SRV_PREFIX${srv.key.lowercase()}", false)
            }
            editor.apply()
            applyServerFilter()
            safeToast(ctx, "Sabhi servers OFF kar diye gaye!")
        }

        // ScrollView for remaining tabs
        val scrollView = ScrollView(ctx).apply {
            isVerticalScrollBarEnabled = false
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, contentHeight)
        }

        val wrapper = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val cs3Pane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val nuvioPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val audioPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val speedPane = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }

        // CS3 Repository Servers Tab
        val cs3AddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#131826", "#2563EB", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }

        cs3AddBox.addView(TextView(ctx).apply {
            text = "+ Add CS3 Repository / .cs3 & Fetch Servers"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#38BDF8"))
        })

        val cs3NameInput = EditText(ctx).apply {
            hint = "Repo Name (optional)"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 8)
            }
        }

        val cs3UrlRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val cs3UrlInput = EditText(ctx).apply {
            hint = "https://.../repo.json or StreamHubOne.cs3"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#323E57", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            }
        }

        val pasteCs3Btn = Button(ctx).apply {
            text = "PASTE"
            textSize = 10.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#BAE6FD"))
            background = cardBg("#0D2338", "#0284C7", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 40))
            setOnClickListener {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                if (clip.isNotBlank()) {
                    cs3UrlInput.setText(clip)
                    cs3UrlInput.setSelection(clip.length)
                } else {
                    safeToast(ctx, "Clipboard khali hai")
                }
            }
        }

        cs3UrlRow.addView(cs3UrlInput)
        cs3UrlRow.addView(pasteCs3Btn)

        val cs3RepoListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun syncCs3ServersIntoList(repos: List<Cs3RepoConfig>) {
            val activePrefixes = repos.filter { it.enabled }.map { "cs3_${it.id.takeLast(6)}_" }
            val actualCustom = cachedCs3Servers.filter { srv ->
                activePrefixes.any { prefix -> srv.key.startsWith(prefix) }
            }
            saveCs3Servers(ctx, actualCustom)
            applyServerFilter()
        }

        lateinit var renderCs3Repos: () -> Unit

        fun fetchAndApplyCs3Repo(repo: Cs3RepoConfig) {
            safeToast(ctx, "Fetching CS3 servers...")
            dialogScope.launch {
                val fetched = fetchServersFromCs3Repo(repo)
                val prefix = "cs3_${repo.id.takeLast(6)}_"
                val updatedCustom = (cachedCs3Servers.filterNot { it.key.startsWith(prefix) } + fetched)
                    .distinctBy { it.key }
                saveCs3Servers(ctx, updatedCustom)
                repo.serverCount = fetched.size
                saveCs3Repos(ctx, cs3RepoList)
                if (dialog.isShowing && isContextAlive(ctx)) {
                    renderCs3Repos()
                    applyServerFilter()
                    safeToast(ctx, "${fetched.size} servers loaded!")
                }
            }
        }

        renderCs3Repos = {
            cs3RepoListHolder.removeAllViews()
            if (cs3RepoList.isEmpty()) {
                cs3RepoListHolder.addView(TextView(ctx).apply {
                    text = "Koi CS3 repository add nahi hai."
                    textSize = 12f
                    setTextColor(safeColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                })
            } else {
                cs3RepoList.forEachIndexed { idx, repo ->
                    val card = LinearLayout(ctx).apply {
                        orientation = LinearLayout.VERTICAL
                        background = cardBg("#121621", "#232C40", 20f)
                        setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { bottomMargin = dp(ctx, 7) }
                    }

                    val row = LinearLayout(ctx).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER_VERTICAL
                    }

                    row.addView(TextView(ctx).apply {
                        text = "${repo.name} (${repo.serverCount})"
                        textSize = 13.5f
                        isSingleLine = true
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(Color.WHITE)
                        layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    })

                    val fetchBtn = TextView(ctx).apply {
                        text = "FETCH"
                        textSize = 9.5f
                        isSingleLine = true
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(safeColor("#38BDF8"))
                        background = pillBg("#0D2338", 12f)
                        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(ctx, 6) }
                        setOnClickListener { fetchAndApplyCs3Repo(repo) }
                    }

                    val delBtn = TextView(ctx).apply {
                        text = "DELETE"
                        textSize = 9.5f
                        isSingleLine = true
                        setTypeface(typeface, Typeface.BOLD)
                        setTextColor(safeColor("#F87171"))
                        background = pillBg("#2A1519", 12f)
                        setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                        layoutParams = LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        ).apply { marginEnd = dp(ctx, 8) }
                        setOnClickListener {
                            val removed = cs3RepoList.removeAt(idx)
                            val prefix = "cs3_${removed.id.takeLast(6)}_"
                            val remaining = cachedCs3Servers.filterNot { it.key.startsWith(prefix) }
                            saveCs3Servers(ctx, remaining)
                            saveCs3Repos(ctx, cs3RepoList)
                            renderCs3Repos()
                            applyServerFilter()
                        }
                    }

                    val sw = Switch(ctx).apply {
                        isChecked = repo.enabled
                        setOnCheckedChangeListener { _, checked ->
                            repo.enabled = checked
                            saveCs3Repos(ctx, cs3RepoList)
                            syncCs3ServersIntoList(cs3RepoList)
                        }
                    }

                    row.addView(fetchBtn)
                    row.addView(delBtn)
                    row.addView(sw)
                    card.addView(row)

                    card.addView(TextView(ctx).apply {
                        text = repo.repoUrl
                        textSize = 10.5f
                        isSingleLine = true
                        ellipsize = TextUtils.TruncateAt.END
                        setTextColor(safeColor("#94A3B8"))
                        setPadding(0, dp(ctx, 4), 0, 0)
                    })
                    cs3RepoListHolder.addView(card)
                }
            }
        }

        val addCs3RepoBtn = Button(ctx).apply {
            text = "ADD & FETCH CS3 SERVERS"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#0284C7", 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40))
            setOnClickListener {
                var rawUrl = cs3UrlInput.text.toString().trim()
                if (rawUrl.isBlank()) {
                    safeToast(ctx, "Pehle CS3 repo URL dalein")
                    return@setOnClickListener
                }
                if (!rawUrl.startsWith("http://") && !rawUrl.startsWith("https://") && !rawUrl.startsWith("github.com", true)) {
                    rawUrl = "https://$rawUrl"
                }
                val repoName = cs3NameInput.text.toString().trim().ifEmpty {
                    rawUrl.substringBefore("?").substringAfterLast("/").removeSuffix(".cs3").ifBlank { deriveManifestLabel(rawUrl) }
                }
                val newRepo = Cs3RepoConfig(
                    id = System.currentTimeMillis().toString(),
                    name = repoName,
                    repoUrl = rawUrl,
                    enabled = true,
                    serverCount = 0
                )
                cs3RepoList.add(newRepo)
                saveCs3Repos(ctx, cs3RepoList)
                cs3NameInput.setText("")
                cs3UrlInput.setText("")
                renderCs3Repos()
                fetchAndApplyCs3Repo(newRepo)
            }
        }

        cs3AddBox.addView(cs3NameInput)
        cs3AddBox.addView(cs3UrlRow)
        cs3AddBox.addView(addCs3RepoBtn)
        cs3Pane.addView(cs3AddBox)
        cs3Pane.addView(cs3RepoListHolder)
        renderCs3Repos()

        // Nuvio Manifest Tab
        val nuvioAddBox = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#131826", "#314161", 22f)
            setPadding(dp(ctx, 14), dp(ctx, 12), dp(ctx, 14), dp(ctx, 12))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 10) }
        }

        nuvioAddBox.addView(TextView(ctx).apply {
            text = "+ Add & Fetch Nuvio Manifest"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#C084FC"))
        })

        val labelInput = EditText(ctx).apply {
            hint = "Manifest Name (optional)"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#2B354B", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(ctx, 8)
                bottomMargin = dp(ctx, 8)
            }
        }

        val manifestInputRow = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        val manifestUrlInput = EditText(ctx).apply {
            hint = "https://.../manifest.json"
            setHintTextColor(safeColor("#64748B"))
            setTextColor(Color.WHITE)
            textSize = 12.5f
            isSingleLine = true
            background = cardBg("#080B12", "#323E57", 14f)
            setPadding(dp(ctx, 11), dp(ctx, 9), dp(ctx, 11), dp(ctx, 9))
            layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
                marginEnd = dp(ctx, 6)
            }
        }

        val pasteManifestBtn = Button(ctx).apply {
            text = "PASTE"
            textSize = 10.5f
            isAllCaps = false
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(safeColor("#E9D5FF"))
            background = cardBg("#241738", "#9333EA", 14f)
            layoutParams = LinearLayout.LayoutParams(dp(ctx, 64), dp(ctx, 40))
            setOnClickListener {
                val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                val clip = cb?.primaryClip?.getItemAt(0)?.text?.toString().orEmpty().trim()
                if (clip.isNotBlank()) {
                    manifestUrlInput.setText(clip)
                    manifestUrlInput.setSelection(clip.length)
                } else {
                    safeToast(ctx, "Clipboard khali hai")
                }
            }
        }

        manifestInputRow.addView(manifestUrlInput)
        manifestInputRow.addView(pasteManifestBtn)

        val nuvioListHolder = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }

        fun renderNuvioList() {
            nuvioListHolder.removeAllViews()
            if (nuvioList.isEmpty()) {
                nuvioListHolder.addView(TextView(ctx).apply {
                    text = "Koi Nuvio manifest add nahi hai."
                    textSize = 12f
                    setTextColor(safeColor("#64748B"))
                    gravity = Gravity.CENTER
                    setPadding(dp(ctx, 12), dp(ctx, 16), dp(ctx, 12), dp(ctx, 16))
                })
                return
            }

            nuvioList.forEachIndexed { idx, item ->
                val card = LinearLayout(ctx).apply {
                    orientation = LinearLayout.VERTICAL
                    background = cardBg("#121621", "#232C40", 20f)
                    setPadding(dp(ctx, 12), dp(ctx, 10), dp(ctx, 12), dp(ctx, 10))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { bottomMargin = dp(ctx, 7) }
                }

                val row = LinearLayout(ctx).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                }

                row.addView(TextView(ctx).apply {
                    text = item.label
                    textSize = 13.5f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                })

                val delBtn = TextView(ctx).apply {
                    text = "DELETE"
                    textSize = 9.5f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(safeColor("#F87171"))
                    background = pillBg("#2A1519", 12f)
                    setPadding(dp(ctx, 8), dp(ctx, 4), dp(ctx, 8), dp(ctx, 4))
                    layoutParams = LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT,
                        ViewGroup.LayoutParams.WRAP_CONTENT
                    ).apply { marginEnd = dp(ctx, 8) }
                    setOnClickListener {
                        nuvioList.removeAt(idx)
                        saveNuvioManifests(ctx, nuvioList)
                        refreshStats()
                        renderNuvioList()
                    }
                }

                val sw = Switch(ctx).apply {
                    isChecked = item.enabled
                    setOnCheckedChangeListener { _, checked ->
                        item.enabled = checked
                        saveNuvioManifests(ctx, nuvioList)
                        refreshStats()
                    }
                }

                row.addView(delBtn)
                row.addView(sw)
                card.addView(row)

                card.addView(TextView(ctx).apply {
                    text = item.manifestUrl
                    textSize = 10.5f
                    isSingleLine = true
                    ellipsize = TextUtils.TruncateAt.END
                    setTextColor(safeColor("#94A3B8"))
                    setPadding(0, dp(ctx, 4), 0, 0)
                })
                nuvioListHolder.addView(card)
            }
        }

        val addManifestBtn = Button(ctx).apply {
            text = "SAVE & FETCH NUVIO MANIFEST"
            textSize = 11.5f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#9333EA", 15f)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(ctx, 40))
            setOnClickListener {
                val rawUrl = manifestUrlInput.text.toString().trim()
                if (rawUrl.isBlank()) {
                    safeToast(ctx, "Pehle Nuvio manifest URL dalein")
                    return@setOnClickListener
                }
                val manualName = labelInput.text.toString().trim()
                val newItem = NuvioManifestConfig(
                    id = System.currentTimeMillis().toString(),
                    label = manualName.ifEmpty { deriveManifestLabel(rawUrl) },
                    manifestUrl = normalizeNuvioManifestUrl(rawUrl),
                    enabled = true
                )
                nuvioList.add(newItem)
                saveNuvioManifests(ctx, nuvioList)
                labelInput.setText("")
                manifestUrlInput.setText("")
                refreshStats()
                renderNuvioList()
                safeToast(ctx, "Fetching Nuvio manifest...")

                dialogScope.launch {
                    val (verifiedUrl, fetchedName) = verifyNuvioManifest(rawUrl)
                    newItem.manifestUrl = verifiedUrl
                    if (manualName.isEmpty() && !fetchedName.isNullOrBlank()) {
                        newItem.label = fetchedName
                    }
                    saveNuvioManifests(ctx, nuvioList)
                    if (dialog.isShowing && isContextAlive(ctx)) {
                        renderNuvioList()
                        refreshStats()
                        safeToast(ctx, "${newItem.label} ready!")
                    }
                }
            }
        }

        nuvioAddBox.addView(labelInput)
        nuvioAddBox.addView(manifestInputRow)
        nuvioAddBox.addView(addManifestBtn)
        nuvioPane.addView(nuvioAddBox)
        nuvioPane.addView(nuvioListHolder)
        renderNuvioList()

        // Audio & Quality Tab
        val qualityCard = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = cardBg("#121621", "#253046", 20f)
            setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(ctx, 8) }
        }

        qualityCard.addView(TextView(ctx).apply {
            text = "Preferred Quality"
            textSize = 14f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
        })

        val qScroll = HorizontalScrollView(ctx).apply {
            isHorizontalScrollBarEnabled = false
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 8) }
        }
        val qRow = LinearLayout(ctx).apply { orientation = LinearLayout.HORIZONTAL }
        val qChips = mutableMapOf<String, TextView>()

        fun refreshQualityChips() {
            qChips.forEach { (q, tv) ->
                if (q == selectedQuality) {
                    tv.background = pillBg("#2563EB", 14f)
                    tv.setTextColor(Color.WHITE)
                } else {
                    tv.background = cardBg("#0B0E16", "#232C40", 14f)
                    tv.setTextColor(safeColor("#94A3B8"))
                }
            }
        }

        QUALITIES.forEach { q ->
            val chip = TextView(ctx).apply {
                text = q
                textSize = 11.5f
                isSingleLine = true
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(ctx, 13), dp(ctx, 6), dp(ctx, 13), dp(ctx, 6))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(ctx, 6) }
                setOnClickListener {
                    selectedQuality = q
                    setPreferredQuality(ctx, q)
                    refreshQualityChips()
                    refreshStats()
                }
            }
            qChips[q] = chip
            qRow.addView(chip)
        }
        refreshQualityChips()
        qScroll.addView(qRow)
        qualityCard.addView(qScroll)
        audioPane.addView(qualityCard)

        var addedOtherHeader = false
        AUDIO_TRACKS.forEach { aud ->
            if (!aud.defaultOn && !addedOtherHeader) {
                addedOtherHeader = true
                audioPane.addView(TextView(ctx).apply {
                    text = "Other Languages"
                    textSize = 12f
                    isSingleLine = true
                    setTypeface(typeface, Typeface.BOLD)
                    setTextColor(safeColor("#94A3B8"))
                    setPadding(dp(ctx, 4), dp(ctx, 6), dp(ctx, 4), dp(ctx, 4))
                })
            }

            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 10), dp(ctx, 13), dp(ctx, 10))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }

            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = aud.title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = aud.subtitle
                textSize = 11f
                setTextColor(safeColor("#8B93A7"))
            })

            val sw = Switch(ctx).apply {
                isChecked = isAudioEnabled(ctx, aud.key)
                setOnCheckedChangeListener { _, checked ->
                    setAudioEnabled(ctx, aud.key, checked)
                }
            }
            audioCards[aud.key] = card

            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(sw)
            audioPane.addView(card)
        }

        // Buffer & Speed Tab
        val lowBufferSw = Switch(ctx).apply {
            isChecked = isLowBufferMode(ctx)
            setOnCheckedChangeListener { _, v -> setLowBufferMode(ctx, v) }
        }
        val fastFetchSw = Switch(ctx).apply {
            isChecked = isFastFetchMode(ctx)
            setOnCheckedChangeListener { _, v -> setFastFetchMode(ctx, v) }
        }

        fun makeToggleCard(title: String, desc: String, sw: Switch) {
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(safeColor("#8B93A7"))
            })
            card.setOnClickListener { sw.isChecked = !sw.isChecked }
            card.addView(col)
            card.addView(sw)
            speedPane.addView(card)
        }

        makeToggleCard("Zero-Buffer Mode", "Fast HLS/DASH streams pehle load hon", lowBufferSw)
        makeToggleCard("Turbo Fast Fetch", "Parallel fast timeout (4-5s max)", fastFetchSw)

        fun makeStepper(
            title: String,
            desc: String,
            initialVal: Int,
            minVal: Int,
            maxVal: Int,
            step: Int,
            suffix: String,
            onChange: (Int) -> Unit
        ) {
            var v = initialVal
            val card = LinearLayout(ctx).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = cardBg("#121621", "#232C40", 20f)
                setPadding(dp(ctx, 13), dp(ctx, 11), dp(ctx, 13), dp(ctx, 11))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = dp(ctx, 7) }
            }
            val col = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
            }
            col.addView(TextView(ctx).apply {
                text = title
                textSize = 14f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(Color.WHITE)
            })
            col.addView(TextView(ctx).apply {
                text = desc
                textSize = 11f
                setTextColor(safeColor("#8B93A7"))
            })

            val valTv = TextView(ctx).apply {
                text = "$v$suffix"
                textSize = 14.5f
                setTypeface(typeface, Typeface.BOLD)
                setTextColor(safeColor("#38BDF8"))
                setPadding(dp(ctx, 9), 0, dp(ctx, 9), 0)
            }

            val minusBtn = Button(ctx).apply {
                text = "−"
                setTextColor(Color.WHITE)
                background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener {
                    if (v - step >= minVal) {
                        v -= step
                        valTv.text = "$v$suffix"
                        onChange(v)
                    }
                }
            }
            val plusBtn = Button(ctx).apply {
                text = "+"
                setTextColor(Color.WHITE)
                background = pillBg("#1D263B", 14f)
                layoutParams = LinearLayout.LayoutParams(dp(ctx, 35), dp(ctx, 35))
                setOnClickListener {
                    if (v + step <= maxVal) {
                        v += step
                        valTv.text = "$v$suffix"
                        onChange(v)
                    }
                }
            }
            card.addView(col)
            card.addView(minusBtn)
            card.addView(valTv)
            card.addView(plusBtn)
            speedPane.addView(card)
        }

        makeStepper("Parallel Workers", "Concurrent endpoint queries", currentConcurrency, 2, 24, 2, "x") {
            currentConcurrency = it
            setConcurrency(ctx, it)
            refreshStats()
        }
        makeStepper("Fast Timeout", "Dead server skip wait time (4s best)", getTimeoutSeconds(ctx), 3, 15, 1, "s") {
            setTimeoutSeconds(ctx, it)
        }

        // Live Search Filter
        searchBox.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                currentQuery = s?.toString().orEmpty().trim().lowercase()
                applyServerFilter()
                AUDIO_TRACKS.forEach { aud ->
                    val match = currentQuery.isEmpty() ||
                        aud.title.lowercase().contains(currentQuery) ||
                        aud.subtitle.lowercase().contains(currentQuery)
                    audioCards[aud.key]?.visibility = if (match) View.VISIBLE else View.GONE
                }
            }
            override fun afterTextChanged(s: Editable?) {}
        })

        val scrollPanes = listOf(cs3Pane, nuvioPane, audioPane, speedPane)
        fun selectTab(idx: Int) {
            if (idx == 0) {
                serversContainer.visibility = View.VISIBLE
                scrollView.visibility = View.GONE
                enableAllBtn.visibility = View.VISIBLE
                disableAllBtn.visibility = View.VISIBLE
            } else {
                serversContainer.visibility = View.GONE
                scrollView.visibility = View.VISIBLE
                enableAllBtn.visibility = View.GONE
                disableAllBtn.visibility = View.GONE
                scrollPanes.forEachIndexed { i, pane ->
                    pane.visibility = if (i == idx - 1) View.VISIBLE else View.GONE
                }
            }

            tabButtons.forEachIndexed { i, btn ->
                if (i == idx) {
                    btn.background = pillBg("#2563EB", 15f)
                    btn.setTextColor(Color.WHITE)
                } else {
                    btn.setBackgroundColor(Color.TRANSPARENT)
                    btn.setTextColor(safeColor("#8B93A7"))
                }
            }
        }

        tabButtons.forEachIndexed { index, btn ->
            btn.setOnClickListener { selectTab(index) }
        }
        selectTab(0)

        scrollPanes.forEach { wrapper.addView(it) }
        scrollView.addView(wrapper)

        root.addView(serversContainer)
        root.addView(scrollView)

        val footer = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(ctx, 10) }
        }

        val closeBtn = Button(ctx).apply {
            text = "CLOSE"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#1C2333", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1f).apply { marginEnd = dp(ctx, 8) }
            setOnClickListener { runCatching { dialog.dismiss() } }
        }

        val saveBtn = Button(ctx).apply {
            text = "SAVE & APPLY"
            textSize = 12f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(Color.WHITE)
            background = pillBg("#2563EB", 16f)
            layoutParams = LinearLayout.LayoutParams(0, dp(ctx, 42), 1.3f).apply { marginStart = dp(ctx, 8) }
            setOnClickListener {
                saveNuvioManifests(ctx, nuvioList)
                saveCs3Repos(ctx, cs3RepoList)
                setPreferredQuality(ctx, selectedQuality)
                setConcurrency(ctx, currentConcurrency)
                safeToast(ctx, "Settings applied!")
                runCatching { dialog.dismiss() }
            }
        }

        footer.addView(closeBtn)
        footer.addView(saveBtn)
        root.addView(footer)

        runCatching {
            dialog.window?.setBackgroundDrawableResource(android.R.color.transparent)
            dialog.show()
            val width = (ctx.resources.displayMetrics.widthPixels * 0.94).toInt()
            dialog.window?.setLayout(width, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
    }
}
