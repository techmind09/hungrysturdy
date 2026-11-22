package com.hungrysturdy

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.lagradost.api.Log
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.extractors.DoodLaExtractor
import com.lagradost.cloudstream3.extractors.GDMirrorbot
import com.lagradost.cloudstream3.extractors.VidHidePro
import com.lagradost.cloudstream3.extractors.VidStack
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

// ── In-App Cloudflare Bypass (WebView Cookie + Interceptor, No External Browser) ──

object MovieFoxInAppCfBypass {

    private const val PREFS_CF = "moviefox_inapp_cf_cookies"
    private const val KEY_WEBVIEW_CF = "use_webview_cf_bypass"

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()
    private val cookieCache = ConcurrentHashMap<String, String>()

    fun isWebViewCfEnabled(ctx: Context? = CloudStreamApp.context): Boolean {
        val c = ctx ?: return true
        return runCatching {
            c.applicationContext.getSharedPreferences(PREFS_CF, Context.MODE_PRIVATE)
                .getBoolean(KEY_WEBVIEW_CF, true)
        }.getOrDefault(true)
    }

    fun setWebViewCfEnabled(ctx: Context?, enabled: Boolean) {
        val c = ctx ?: return
        runCatching {
            c.applicationContext.getSharedPreferences(PREFS_CF, Context.MODE_PRIVATE)
                .edit().putBoolean(KEY_WEBVIEW_CF, enabled).apply()
        }
    }

    fun hostOf(url: String): String = runCatching {
        URI(url).host?.lowercase().orEmpty()
    }.getOrDefault("")

    fun saveCookie(ctx: Context?, url: String, cookie: String) {
        val h = hostOf(url)
        if (h.isBlank() || cookie.isBlank()) return
        cookieCache[h] = cookie
        ctx?.let { c ->
            runCatching {
                c.applicationContext.getSharedPreferences(PREFS_CF, Context.MODE_PRIVATE)
                    .edit().putString("ck_$h", cookie).apply()
            }
        }
    }

    fun getCookie(url: String, ctx: Context? = CloudStreamApp.context): String? {
        val h = hostOf(url)
        if (h.isBlank()) return null
        cookieCache[h]?.takeIf { it.isNotBlank() }?.let { return it }
        val fromPrefs = ctx?.let { c ->
            runCatching {
                c.applicationContext.getSharedPreferences(PREFS_CF, Context.MODE_PRIVATE)
                    .getString("ck_$h", null)
            }.getOrNull()
        }?.takeIf { it.isNotBlank() }
        if (fromPrefs != null) {
            cookieCache[h] = fromPrefs
            return fromPrefs
        }
        val fromWebView = runCatching { CookieManager.getInstance().getCookie(url) }.getOrNull()?.takeIf { it.isNotBlank() }
        if (fromWebView != null) {
            cookieCache[h] = fromWebView
            return fromWebView
        }
        return null
    }

    fun clearCookies(ctx: Context? = CloudStreamApp.context) {
        cookieCache.clear()
        cfKillerMap.values.forEach { runCatching { it.savedCookies.clear() } }
        ctx?.let { c ->
            runCatching {
                c.applicationContext.getSharedPreferences(PREFS_CF, Context.MODE_PRIVATE).edit().clear().apply()
                CookieManager.getInstance().removeAllCookies(null)
                CookieManager.getInstance().flush()
            }
        }
    }

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(hostOf(url).ifBlank { url }) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(hostOf(url).ifBlank { url }) { CloudflareKiller() }

    fun injectHeaders(url: String, headers: Map<String, String>): Map<String, String> {
        val out = headers.toMutableMap()
        if (out.keys.none { it.equals("User-Agent", true) }) {
            out["User-Agent"] = MovieFoxSettingsDialog.CF_BYPASS_UA
        }
        val ck = getCookie(url)
        if (!ck.isNullOrBlank()) {
            val existing = out["Cookie"].orEmpty()
            out["Cookie"] = if (existing.isBlank()) ck else "$existing; $ck"
        }
        return out
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun solveViaInAppWebView(url: String, headers: Map<String, String>, timeoutMs: Long = 16_000L): String? {
        if (!isWebViewCfEnabled()) return null
        val act = (CloudStreamApp.context as? Activity) ?: return null
        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val finished = AtomicBoolean(false)
                val mainHandler = Handler(Looper.getMainLooper())
                var webViewRef: WebView? = null

                fun complete(result: String?) {
                    if (!finished.compareAndSet(false, true)) return
                    mainHandler.post {
                        runCatching {
                            webViewRef?.stopLoading()
                            webViewRef?.destroy()
                        }
                    }
                    if (cont.isActive) cont.resume(result)
                }

                mainHandler.post {
                    if (act.isFinishing || act.isDestroyed) {
                        complete(null)
                        return@post
                    }
                    runCatching {
                        val cm = CookieManager.getInstance()
                        cm.setAcceptCookie(true)
                        val wv = WebView(act).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.userAgentString = headers["User-Agent"] ?: MovieFoxSettingsDialog.CF_BYPASS_UA
                            cm.setAcceptThirdPartyCookies(this, true)
                            webViewClient = object : WebViewClient() {
                                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                                    val target = request?.url?.toString().orEmpty()
                                    if (target.startsWith("http")) view?.loadUrl(target)
                                    return true
                                }

                                override fun onPageFinished(view: WebView?, loadedUrl: String?) {
                                    val targetUrl = loadedUrl ?: url
                                    val ck = runCatching { cm.getCookie(targetUrl) }.getOrNull().orEmpty()
                                    if (ck.isNotBlank()) saveCookie(act, targetUrl, ck)
                                    view?.evaluateJavascript(
                                        "(function(){ return document.documentElement ? document.documentElement.outerHTML : ''; })()"
                                    ) { rawHtml ->
                                        val html = runCatching {
                                            if (rawHtml != null && rawHtml.startsWith("\"") && rawHtml.endsWith("\"")) {
                                                JSONObject("{\"h\":$rawHtml}").getString("h")
                                            } else rawHtml.orEmpty()
                                        }.getOrDefault("")
                                        val low = html.lowercase()
                                        val stillChallenge = low.contains("just a moment") ||
                                            low.contains("checking your browser") ||
                                            low.contains("performing security verification")
                                        if (!stillChallenge && html.length > 200) {
                                            complete(html)
                                        }
                                    }
                                }
                            }
                            loadUrl(url, headers)
                        }
                        mainHandler.postDelayed({ complete(null) }, timeoutMs - 500L)
                    }.onFailure {
                        complete(null)
                    }
                }
            }
        }
    }

    suspend fun get(
        url: String,
        headers: Map<String, String> = emptyMap(),
        referer: String? = null,
        timeoutSec: Long = 15L,
        allowRedirects: Boolean = true
    ): String {
        val base = headers.toMutableMap().apply {
            if (!referer.isNullOrBlank()) putIfAbsent("Referer", referer)
        }
        val reqHeaders = injectHeaders(url, base)
        val first = runCatching {
            app.get(url, headers = reqHeaders, allowRedirects = allowRedirects, timeout = timeoutSec)
        }.getOrNull() ?: return ""

        val firstText = runCatching { first.text }.getOrDefault("")
        val isBlocked = first.code in listOf(403, 503) ||
            firstText.contains("Just a moment", true) ||
            firstText.contains("Checking your browser", true)
        if (!isBlocked) return firstText

        runCatching { first.okhttpResponse.close() }
        return mutexFor(url).withLock {
            val killer = killerFor(url)
            val killedRes = runCatching {
                app.get(url, headers = reqHeaders, interceptor = killer, allowRedirects = allowRedirects, timeout = 20L)
            }.getOrNull()
            val killedText = runCatching { killedRes?.text }.getOrNull().orEmpty()
            val stillBlocked = killedRes == null ||
                killedRes.code in listOf(403, 503) ||
                killedText.contains("Just a moment", true) ||
                killedText.contains("Checking your browser", true)
            killedRes?.let { runCatching { it.okhttpResponse.close() } }
            if (!stillBlocked && killedText.isNotBlank()) return@withLock killedText

            solveViaInAppWebView(url, reqHeaders).orEmpty()
        }
    }

    suspend fun post(
        url: String,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        json: Any? = null,
        referer: String? = null,
        timeoutSec: Long = 15L
    ): String {
        val base = headers.toMutableMap().apply {
            if (!referer.isNullOrBlank()) putIfAbsent("Referer", referer)
        }
        val reqHeaders = injectHeaders(url, base)
        val first = runCatching {
            app.post(url, headers = reqHeaders, data = data, json = json, timeout = timeoutSec)
        }.getOrNull() ?: return ""
        if (first.code !in listOf(403, 503)) return first.text
        runCatching { first.okhttpResponse.close() }
        return mutexFor(url).withLock {
            val killer = killerFor(url)
            val retry = runCatching {
                app.post(url, headers = reqHeaders, data = data, json = json, interceptor = killer, timeout = 20L)
            }.getOrNull()
            val body = runCatching { retry?.text }.getOrNull().orEmpty()
            retry?.let { runCatching { it.okhttpResponse.close() } }
            body
        }
    }
}

// ── 2. Unified CineStream Extractors & Source Invokers (Single File) ──────────────

object MovieFoxCineStreamExtractors {

    private const val TAG = "MovieFoxCSExt"

    private fun cap(s: String): String = s.replaceFirstChar { it.uppercase() }
    private fun enc(s: String): String = URLEncoder.encode(s, "UTF-8")
    private fun slug(s: String?): String? =
        s?.filter { it.isWhitespace() || it.isLetterOrDigit() }?.trim()?.replace("\\s+".toRegex(), "-")?.lowercase()

    private fun qualityOf(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val low = str.lowercase()
        return when {
            low.contains("8k") -> 4320
            low.contains("4k") || low.contains("uhd") -> 2160
            low.contains("2k") -> 1440
            else -> Qualities.Unknown.value
        }
    }

    private fun formatSize(bytes: Long): String = when {
        bytes <= 0L -> ""
        bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
        else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
    }

    private suspend fun emitSub(lang: String?, url: String?, subtitleCallback: (SubtitleFile) -> Unit) {
        val u = url?.trim().orEmpty()
        if (!u.startsWith("http")) return
        subtitleCallback(newSubtitleFile(lang?.trim().orEmpty().ifBlank { "English" }, u))
    }

    private suspend fun <A, B> Iterable<A>.csAmap(concurrency: Int = 7, block: suspend (A) -> B?): List<B> =
        supervisorScope {
            val gate = Semaphore(concurrency)
            map { item ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            block(item)
                        } catch (e: CancellationException) {
                            if (!isActive) throw e
                            null
                        } catch (e: Exception) {
                            Log.d(TAG, "csAmap item error: ${e.message}")
                            null
                        }
                    }
                }
            }.awaitAll().filterNotNull()
        }

    private suspend fun runParallelTasks(concurrency: Int = 7, vararg tasks: suspend () -> Unit) =
        supervisorScope {
            val gate = Semaphore(concurrency)
            tasks.map { task ->
                async(Dispatchers.IO) {
                    gate.withPermit {
                        try {
                            task()
                        } catch (e: CancellationException) {
                            if (!isActive) throw e
                        } catch (e: Exception) {
                            Log.d(TAG, "Task error: ${e.message}")
                        }
                    }
                }
            }.awaitAll()
        }

    suspend fun invokeStremioStreams(
        sourceName: String,
        api: String,
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (imdbId.isNullOrBlank()) return
        val url = "$api/stream/${if (season == null) "movie/$imdbId" else "series/$imdbId:$season:$episode"}.json"
        val raw = MovieFoxInAppCfBypass.get(url, timeoutSec = 20L)
        val arr = runCatching { JSONObject(raw).optJSONArray("streams") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val sUrl = obj.optString("url").trim()
            if (!sUrl.startsWith("http") || sUrl.contains("github.com") || sUrl.contains("googleusercontent")) continue
            val name = obj.optString("name").trim().ifBlank { sourceName }
            val title = obj.optString("title").lines().firstOrNull()?.trim().orEmpty()
            val label = if (title.isBlank()) "[$sourceName] $name" else "[$sourceName] $name • $title"
            val isM3u8 = sUrl.contains(".m3u8", true) || listOf("hls", "m3u8", "vixsrc").any { label.contains(it, true) }
            callback(
                newExtractorLink(sourceName, label, sUrl, if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = qualityOf("$name $title")
                    this.headers = MovieFoxInAppCfBypass.injectHeaders(sUrl, mapOf("Referer" to "$api/"))
                }
            )
            obj.optJSONArray("subtitles")?.let { subs ->
                for (k in 0 until subs.length()) {
                    val sub = subs.optJSONObject(k) ?: continue
                    emitSub(sub.optString("lang"), sub.optString("url"), subtitleCallback)
                }
            }
        }
    }

    suspend fun invokeStremioTorrents(
        sourceName: String,
        api: String,
        id: String?,
        season: Int?,
        episode: Int?,
        callback: (ExtractorLink) -> Unit
    ) {
        if (id.isNullOrBlank()) return
        val url = when {
            season == null -> "$api/stream/movie/$id.json"
            id.contains("kitsu", true) -> "$api/stream/series/$id:$episode.json"
            else -> "$api/stream/series/$id:$season:$episode.json"
        }
        val raw = MovieFoxInAppCfBypass.get(url, timeoutSec = 20L)
        val arr = runCatching { JSONObject(raw).optJSONArray("streams") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i) ?: continue
            val hash = obj.optString("infoHash").trim()
            if (hash.isBlank()) continue
            val title = obj.optString("title").ifBlank { obj.optString("name") }.trim()
            val seeders = Regex("""[👤👥]\s*(\d+)""").find(title)?.groupValues?.get(1)?.toIntOrNull() ?: 0
            if (seeders < 25) continue
            val idx = obj.optInt("fileIdx", 0)
            val magnet = "magnet:?xt=urn:btih:$hash&dn=${enc(title.ifBlank { sourceName })}&index=$idx"
            callback(
                newExtractorLink("$sourceName🧲", "$sourceName 🧲 | 👤 $seeders • $title", magnet, ExtractorLinkType.MAGNET) {
                    this.quality = qualityOf(title)
                }
            )
        }
    }

    suspend fun invokeStremioSubtitles(
        imdbId: String?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        if (imdbId.isNullOrBlank()) return
        val endpoints = listOf(
            "https://opensubtitles.stremio.homes/en|hi|de|ar|tr|es|ta|te|ru|ko/ai-translated=true|from=all|auto-adjustment=true",
            """https://subsense.nepiraw.com/n0tcjfba-{"languages":["en","hi","ta","es","ar"],"maxSubtitles":10}"""
        )
        endpoints.csAmap { base ->
            val u = if (season != null) "$base/subtitles/series/$imdbId:$season:$episode.json" else "$base/subtitles/movie/$imdbId.json"
            val raw = MovieFoxInAppCfBypass.get(u, timeoutSec = 10L)
            val arr = runCatching { JSONObject(raw).optJSONArray("subtitles") }.getOrNull() ?: return@csAmap
            for (i in 0 until arr.length()) {
                val sub = arr.optJSONObject(i) ?: continue
                emitSub(sub.optString("lang").ifBlank { sub.optString("lang_code") }, sub.optString("url"), subtitleCallback)
            }
        }
    }

    suspend fun invokeVideasy(
        title: String?,
        tmdbId: Int?,
        imdbId: String?,
        year: Int?,
        season: Int?,
        episode: Int?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (title.isNullOrBlank() || tmdbId == null) return
        val headers = mapOf(
            "Accept" to "*/*",
            "Origin" to "https://player.videasy.to",
            "Referer" to "https://player.videasy.to/"
        )
        val seed = runCatching {
            JSONObject(MovieFoxInAppCfBypass.get("${CineStreamConfig.VIDEASY_API}/seed?mediaId=$tmdbId", headers, timeoutSec = 8L)).optString("seed")
        }.getOrDefault("")
        if (seed.isBlank()) return

        val encTitle = enc(enc(title))
        listOf("myflixerzupcloud", "downloader2", "m4uhd", "hdmovie", "cdn", "superflix", "lamovie", "jett", "tejo", "neon2", "ym").csAmap { srv ->
            val u = if (season == null) {
                "${CineStreamConfig.VIDEASY_API}/$srv/sources-with-title?title=$encTitle&mediaType=movie&year=$year&tmdbId=$tmdbId&imdbId=$imdbId&enc=2&seed=$seed"
            } else {
                "${CineStreamConfig.VIDEASY_API}/$srv/sources-with-title?title=$encTitle&mediaType=tv&year=$year&tmdbId=$tmdbId&episodeId=$episode&seasonId=$season&imdbId=$imdbId&enc=2&seed=$seed"
            }
            val encData = MovieFoxInAppCfBypass.get(u, headers, timeoutSec = 10L)
            if (encData.isBlank()) return@csAmap
            val decText = MovieFoxInAppCfBypass.post("${CineStreamConfig.MULTI_DECRYPT_API}/dec-videasy", json = mapOf("text" to encData, "id" to tmdbId, "seed" to seed), timeoutSec = 10L)
            val result = runCatching { JSONObject(decText).getJSONObject("result") }.getOrNull() ?: return@csAmap
            val sources = result.optJSONArray("sources") ?: JSONArray()
            for (i in 0 until sources.length()) {
                val obj = sources.optJSONObject(i) ?: continue
                val q = obj.optString("quality", "HD")
                val sUrl = obj.optString("url")
                if (sUrl.isBlank()) continue
                val t = if (sUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else if (sUrl.contains(".mp4") || sUrl.contains(".mkv")) ExtractorLinkType.VIDEO else INFER_TYPE
                callback(newExtractorLink("Videasy[${cap(srv)}]", "Videasy[${cap(srv)}] $q", sUrl, t) {
                    this.quality = qualityOf(q)
                    this.headers = headers
                })
            }
            val subs = result.optJSONArray("subtitles") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                emitSub(sub.optString("language"), sub.optString("url"), subtitleCallback)
            }
        }
    }

    suspend fun invokeHexa(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val url = if (season == null) "${CineStreamConfig.HEXA_API}/api/tmdb/movie/$tmdbId/images"
        else "${CineStreamConfig.HEXA_API}/api/tmdb/tv/$tmdbId/season/$season/episode/$episode/images"
        val key = ByteArray(32).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        val token = runCatching {
            JSONObject(MovieFoxInAppCfBypass.get("${CineStreamConfig.MULTI_DECRYPT_API}/enc-hexa", timeoutSec = 8L)).getJSONObject("result").getString("token")
        }.getOrDefault("")
        if (token.isBlank()) return
        val headers = mapOf(
            "Accept" to "text/plain",
            "X-Api-Key" to key,
            "X-Fingerprint-Lite" to "e9136c41504646444",
            "Referer" to "https://hexa.su/",
            "X-Cap-Token" to token
        )
        val encData = MovieFoxInAppCfBypass.get(url, headers, timeoutSec = 10L)
        val decText = MovieFoxInAppCfBypass.post(
            "${CineStreamConfig.MULTI_DECRYPT_API}/dec-hexa",
            json = mapOf("text" to encData, "key" to key),
            headers = mapOf("Content-Type" to "application/json"),
            timeoutSec = 10L
        )
        val arr = runCatching { JSONObject(decText).getJSONObject("result").getJSONArray("sources") }.getOrNull() ?: return
        for (i in 0 until arr.length()) {
            val src = arr.optJSONObject(i) ?: continue
            val srv = cap(src.optString("server", "Hexa"))
            val m3u8 = src.optString("url")
            if (m3u8.isNotBlank()) generateM3u8("Hexa $srv", m3u8, "https://hexa.su/").forEach(callback)
        }
    }

    suspend fun invokeVidzee(id: Int?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (id == null) return
        val secret = base64Decode("QTdrUDl4TTJRdjhMcjROejFIdDZZYzNCdzVKZjBEc1U=")
        listOf(0, 1, 2, 4, 5, 6, 7).csAmap { sr ->
            val apiUrl = if (season == null) "${CineStreamConfig.VIDZEE_API}/api/server?id=$id&sr=$sr"
            else "${CineStreamConfig.VIDZEE_API}/api/server?id=$id&sr=$sr&ss=$season&ep=$episode"
            val raw = MovieFoxInAppCfBypass.get(apiUrl, timeoutSec = 10L)
            val json = runCatching { JSONObject(raw) }.getOrNull() ?: return@csAmap
            val globalHeaders = mutableMapOf<String, String>()
            json.optJSONObject("headers")?.let { hObj -> hObj.keys().forEach { k -> globalHeaders[k] = hObj.getString(k) } }
            val urls = json.optJSONArray("url") ?: JSONArray()
            for (i in 0 until urls.length()) {
                val obj = urls.optJSONObject(i) ?: continue
                val dec = csDecryptVidzee(obj.optString("link"), secret) ?: continue
                if (!dec.startsWith("https:")) continue
                val name = obj.optString("name", "")
                val type = obj.optString("type", "hls")
                val lang = obj.optString("lang", "Unknown")
                callback(newExtractorLink("VidZee", "VidZee $name ($lang)", dec, if (type.equals("hls", true)) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = globalHeaders["referer"] ?: "${CineStreamConfig.VIDZEE_API}/"
                    this.headers = globalHeaders
                    this.quality = Qualities.P1080.value
                })
            }
            val subs = json.optJSONArray("tracks") ?: JSONArray()
            for (i in 0 until subs.length()) {
                val sub = subs.optJSONObject(i) ?: continue
                emitSub(sub.optString("lang", "Unknown"), sub.optString("url"), subtitleCallback)
            }
        }
    }

    suspend fun invokeVidrock(tmdbId: Int?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        if (tmdbId == null) return
        val type = if (season == null) "movie" else "tv"
        val query = if (type == "movie") "$tmdbId" else "${tmdbId}_${season}_${episode}"
        val headers = mapOf("Origin" to CineStreamConfig.VIDROCK_API, "Referer" to "${CineStreamConfig.VIDROCK_API}/")
        val raw = MovieFoxInAppCfBypass.get("${CineStreamConfig.VIDROCK_API}/api/$type/$query/", headers, timeoutSec = 10L)
        val obj = runCatching { JSONObject(raw) }.getOrNull() ?: return
        obj.keys().forEach { srv ->
            val encStr = obj.optJSONObject(srv)?.optString("url").orEmpty()
            if (encStr.isBlank() || encStr == "error" || encStr == "null") return@forEach
            val dec = csDecryptVidrock(encStr) ?: return@forEach
            callback(newExtractorLink("Vidrock[$srv]", "Vidrock[$srv]", dec, if (dec.contains("m3u8")) ExtractorLinkType.M3U8 else INFER_TYPE) {
                this.headers = headers
            })
        }
    }

    suspend fun invokeKisskh(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        if (title.isNullOrBlank()) return
        val type = if (season == null) "2" else "1"
        val searchRaw = MovieFoxInAppCfBypass.get("${CineStreamConfig.KISSKH_API}/api/DramaList/Search?q=${enc(title)}&type=$type", referer = "${CineStreamConfig.KISSKH_API}/", timeoutSec = 10L)
        val list = runCatching { tryParseJson<ArrayList<CsKisskhResults>>(searchRaw) }.getOrNull() ?: return
        val match = list.firstOrNull { it.title.equals(title, true) } ?: list.firstOrNull() ?: return
        val id = match.id ?: return
        val detailRaw = MovieFoxInAppCfBypass.get("${CineStreamConfig.KISSKH_API}/api/DramaList/Drama/$id?isq=false", referer = "${CineStreamConfig.KISSKH_API}/", timeoutSec = 10L)
        val detail = runCatching { tryParseJson<CsKisskhDetail>(detailRaw) }.getOrNull() ?: return
        val epId = if (season == null) detail.episodes?.firstOrNull()?.id else detail.episodes?.firstOrNull { it.number == episode }?.id ?: return
        val vidKey = runCatching {
            JSONObject(MovieFoxInAppCfBypass.get("${CineStreamConfig.MULTI_DECRYPT_API}/enc-kisskh?text=$epId&type=vid", referer = CineStreamConfig.KISSKH_API, timeoutSec = 8L)).optString("result")
        }.getOrDefault("")
        if (vidKey.isBlank()) return
        val srcRaw = MovieFoxInAppCfBypass.get("${CineStreamConfig.KISSKH_API}/api/DramaList/Episode/$epId.png?err=false&ts=&time=&kkey=$vidKey", referer = CineStreamConfig.KISSKH_API, timeoutSec = 10L)
        runCatching { tryParseJson<CsKisskhSources>(srcRaw) }.getOrNull()?.let { s ->
            listOf(s.video, s.thirdParty).csAmap { rawLink ->
                val safeLink = rawLink ?: return@csAmap null
                val full = if (safeLink.startsWith("http")) safeLink else "${CineStreamConfig.KISSKH_API}/${safeLink.trimStart('/')}"
                if (full.contains(".m3u8") || full.contains(".mp4")) {
                    callback(newExtractorLink("Kisskh", "Kisskh", full, INFER_TYPE) {
                        this.referer = CineStreamConfig.KISSKH_API
                        this.quality = Qualities.P720.value
                        this.headers = mapOf("Origin" to CineStreamConfig.KISSKH_API)
                    })
                } else {
                    csLoadExtractor(full.substringBefore("?"), "${CineStreamConfig.KISSKH_API}/", subtitleCallback, callback)
                }
            }
        }
        val subKey = runCatching {
            JSONObject(MovieFoxInAppCfBypass.get("${CineStreamConfig.MULTI_DECRYPT_API}/enc-kisskh?text=$epId&type=sub", timeoutSec = 8L)).optString("result")
        }.getOrDefault("")
        if (subKey.isNotBlank()) {
            val subRaw = MovieFoxInAppCfBypass.get("${CineStreamConfig.KISSKH_API}/api/Sub/$epId?kkey=$subKey", referer = CineStreamConfig.KISSKH_API, timeoutSec = 8L)
            runCatching { tryParseJson<List<CsKisskhSubtitle>>(subRaw) }.getOrNull()?.forEach { sub ->
                emitSub(sub.label, sub.src, subtitleCallback)
            }
        }
    }

    suspend fun invokeAnimesalt(title: String?, season: Int?, episode: Int?, callback: (ExtractorLink) -> Unit) {
        val s = slug(title) ?: return
        val headers = mapOf("Referer" to "${CineStreamConfig.ANIMESALT_API}/")
        val url = if (season == null) "${CineStreamConfig.ANIMESALT_API}/movies/$s/" else "${CineStreamConfig.ANIMESALT_API}/episode/$s-${season}x${episode}/"
        val html = MovieFoxInAppCfBypass.get(url, headers, timeoutSec = 12L)
        val m = Regex("""src="(https://as-cdn\d+\.top/video/([a-f0-9]+))\"""").find(html) ?: return
        val playerUrl = m.groupValues[1]
        val hash = m.groupValues[2]
        val cdn = playerUrl.substringBefore("/video/")
        val postRaw = MovieFoxInAppCfBypass.post(
            "$cdn/player/index.php?data=$hash&do=getVideo",
            data = mapOf("hash" to hash, "r" to "${CineStreamConfig.ANIMESALT_API}/"),
            headers = mapOf("Referer" to "${CineStreamConfig.ANIMESALT_API}/", "Origin" to cdn, "X-Requested-With" to "XMLHttpRequest"),
            timeoutSec = 15L
        )
        val obj = runCatching { JSONObject(postRaw) }.getOrNull() ?: return
        val m3u8 = obj.optString("videoSource").ifBlank { obj.optString("securedLink") }
        if (m3u8.startsWith("http")) {
            callback(newExtractorLink("AnimeSalt[Multi]", "AnimeSalt[Multi]", m3u8, ExtractorLinkType.M3U8) {
                this.headers = headers
                this.quality = Qualities.P1080.value
            })
        }
    }

    suspend fun invokeToonstream(title: String?, season: Int?, episode: Int?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val api = CineStreamConfig.dynamicApi("toonstream", "https://toonstream.one")
        val s = slug(title) ?: return
        val url = if (season == null) "$api/movies/$s/" else "$api/episode/$s-${season}x${episode}/"
        val html = MovieFoxInAppCfBypass.get(url, referer = api, timeoutSec = 12L)
        val doc = Jsoup.parse(html, api)
        doc.select("div.video > iframe").csAmap { el ->
            val src = el.attr("data-src").ifBlank { el.attr("src") }
            if (src.isBlank()) return@csAmap
            val fullSrc = if (src.startsWith("http")) src else "$api/${src.trimStart('/')}"
            val innerDoc = Jsoup.parse(MovieFoxInAppCfBypass.get(fullSrc, timeoutSec = 12L), fullSrc)
            innerDoc.select("div.Video > iframe, iframe").csAmap { ifr ->
                val emb = ifr.attr("src").ifBlank { ifr.attr("data-src") }
                if (emb.isNotBlank()) {
                    val fullEmb = if (emb.startsWith("http")) emb else "$api/${emb.trimStart('/')}"
                    csLoadExtractor(fullEmb, "$api/", subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun csLoadExtractor(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        loadExtractor(url, referer, subtitleCallback) { link ->
            callback(link)
        }
    }

    suspend fun runStandardSources(res: CsAllLoadLinksData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        CineStreamConfig.init()
        runParallelTasks(
            concurrency = 7,
            { invokeStremioTorrents("Torrentio", CineStreamConfig.TORRENTIO_API, res.imdbId, res.season, res.episode, callback) },
            { invokeStremioTorrents("TorrentsDB", CineStreamConfig.TORRENTSDB_API, res.imdbId, res.season, res.episode, callback) },
            { invokeStremioSubtitles(res.imdbId, res.season, res.episode, subtitleCallback) },
            { invokeVideasy(res.title, res.tmdbId, res.imdbId, res.year, res.season, res.episode, subtitleCallback, callback) },
            { invokeHexa(res.tmdbId, res.season, res.episode, callback) },
            { invokeVidzee(res.tmdbId, res.season, res.episode, subtitleCallback, callback) },
            { invokeVidrock(res.tmdbId, res.season, res.episode, callback) },
            { if (res.isAsian) invokeKisskh(res.title, res.season, res.episode, subtitleCallback, callback) },
            { if (res.isAnime || res.isCartoon) invokeAnimesalt(res.title, res.season, res.episode, callback) },
            { if (res.isAnime || res.isCartoon) invokeToonstream(res.title, res.season, res.episode, subtitleCallback, callback) }
        )
    }

    suspend fun runAnimeSources(res: CsAllLoadLinksData, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        CineStreamConfig.init()
        runParallelTasks(
            concurrency = 7,
            { invokeStremioTorrents("Torrentio", CineStreamConfig.TORRENTIO_API, "kitsu:${res.kitsuId}", res.season, res.episode, callback) },
            { invokeStremioTorrents("TorrentsDB", CineStreamConfig.TORRENTSDB_API, "kitsu:${res.kitsuId}", res.season, res.episode, callback) },
            { invokeStremioSubtitles(res.imdbId, res.imdbSeason, res.imdbEpisode, subtitleCallback) },
            { invokeAnimesalt(res.imdbTitle ?: res.title, res.imdbSeason, res.imdbEpisode, callback) },
            { invokeToonstream(res.imdbTitle ?: res.title, res.imdbSeason, res.imdbEpisode, subtitleCallback, callback) }
        )
    }
}

// ── ExtractorApi Implementations (Single File) ────────────────────────────────────

class CsStreameeeeee : CsVideostr() {
    override var name = "Streameeeeee"
    override var mainUrl = "https://streameeeeee.site"
}

open class CsVideostr : ExtractorApi() {
    override val name = "Videostr"
    override val mainUrl = "https://videostr.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base = CineStreamConfig.dynamicApi("videostr", mainUrl)
        val headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest", "Referer" to "$base/")
        val html = MovieFoxInAppCfBypass.get(url, headers, timeoutSec = 15L)
        if (html.isBlank()) return
        val fileId = Jsoup.parse(html).selectFirst("[id$=\"-player\"]")?.attr("data-id").orEmpty()
        if (fileId.isEmpty()) return
        val triple = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""", RegexOption.DOT_MATCHES_ALL).find(html)
        val single = Regex("""\b[a-zA-Z0-9]{48}\b""").find(html)
        val nonce = if (triple != null) triple.groupValues[1] + triple.groupValues[2] + triple.groupValues[3] else single?.value ?: return
        val json = MovieFoxInAppCfBypass.get("$base/embed-1/v3/e-1/getSources?id=$fileId&_k=$nonce", headers, timeoutSec = 12L)
        val m3u8 = runCatching { JSONObject(json).optJSONArray("sources")?.optJSONObject(0)?.optString("file") }.getOrNull().orEmpty()
        if (m3u8.startsWith("http")) generateM3u8(name, m3u8, base, headers = headers).forEach(callback)
    }
}

class CsGofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val apiBase = "https://api.gofile.io"
    private val secret = "12af056dacea0b"
    private val lang = "en-GB"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val ua = MovieFoxSettingsDialog.CF_BYPASS_UA
        val accRaw = MovieFoxInAppCfBypass.post("$apiBase/accounts", headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl))
        val token = runCatching { JSONObject(accRaw).getJSONObject("data").getString("token") }.getOrDefault("")
        if (token.isBlank()) return
        val slot = System.currentTimeMillis() / 1000 / 14400
        val wt = sha256("$ua::$lang::$token::$slot::$secret")
        val contRaw = MovieFoxInAppCfBypass.get(
            "$apiBase/contents/$id?page=1&pageSize=100&sortField=name&sortDirection=1",
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua, "Authorization" to "Bearer $token", "X-BL" to lang, "X-Website-Token" to wt)
        )
        val children = runCatching { JSONObject(contRaw).getJSONObject("data").optJSONObject("children") }.getOrNull() ?: return
        val names = children.names() ?: return
        for (i in 0 until names.length()) {
            val f = children.optJSONObject(names.optString(i)) ?: continue
            if (f.optString("type") != "file") continue
            val link = f.optString("link").trim()
            if (!link.startsWith("http")) continue
            val fName = f.optString("name")
            val sz = f.optLong("size", 0L)
            callback(newExtractorLink("Gofile", "[Gofile] $fName [${formatSize(sz)}]", link, ExtractorLinkType.VIDEO) {
                this.quality = qualityOf(fName)
                this.headers = mapOf("Cookie" to "accountToken=$token")
            })
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class CsWootly : ExtractorApi() {
    override var name = "Wootly"
    override var mainUrl = "https://www.wootly.ch"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = MovieFoxInAppCfBypass.get(url, referer = referer)
        val iframe = Jsoup.parse(html).selectFirst("iframe")?.attr("src") ?: return
        val iHtml = MovieFoxInAppCfBypass.post(iframe, data = mapOf("qdfx" to "1"), referer = url)
        val vd = Regex("""var\s+vd\s*=\s*["']([^"']+)["']""").find(iHtml)?.groupValues?.get(1) ?: return
        val tk = Regex("""tk\s*=\s*["']([^"']+)["']""").find(iHtml)?.groupValues?.get(1) ?: return
        val streamUrl = MovieFoxInAppCfBypass.get("https://web.wootly.ch/grabm?t=$tk&id=$vd", referer = iframe).trim()
        if (streamUrl.startsWith("http")) {
            callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) { this.referer = referer ?: "" })
        }
    }
}

class CsVibePlayer : ExtractorApi() {
    override val name = "VibePlayer"
    override val mainUrl = "https://vibeplayer.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = MovieFoxInAppCfBypass.get(url, referer = referer)
        val videoUrl = Regex("""const\s+src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
    }
}

class CsVivibebe : CsVibePlayer() { override var mainUrl = "https://vivibebe.site" }
class CsBibiemb : CsVibePlayer() { override var mainUrl = "https://bibiemb.xyz" }
class CsPlaymogo : DoodLaExtractor() { override var mainUrl = "https://playmogo.com" }
class CsOtakuvid : VidHidePro() { override var mainUrl = "https://otakuvid.online" }
class CsOtakuhg : VidHidePro() { override var mainUrl = "https://otakuhg.site" }
class CsMinochinos : VidHidePro() { override var mainUrl = "https://minochinos.com" }
class CsHdStream4u : VidHidePro() { override var mainUrl = "https://hdstream4u.com" }
class CsHubstream : VidStack() { override var mainUrl = "https://hubstream.art" }
class CsIqsmartgames : GDMirrorbot() { override var name = "Iqsmartgames"; override var mainUrl = "https://streams.iqsmartgames.com"; override var requiresReferer = true }

class CsVidwish : CsMegaPlay() { override val name = "Vidwish"; override val mainUrl = "https://vidwish.live" }
class CsVidtube : CsMegaPlay() { override val name = "Vidtube"; override val mainUrl = "https://vidtube.site" }

open class CsMegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = MovieFoxInAppCfBypass.get(url, referer = referer ?: "https://anikototv.to/")
        val el = Jsoup.parse(html).selectFirst("#megaplay-player")
        val streamId = el?.attr("data-id") ?: el?.attr("data-realid") ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: return
        val type = if (url.contains("/dub", true)) "dub" else "sub"
        val jsonText = MovieFoxInAppCfBypass.get("$mainUrl/stream/getSources?id=$streamId&type=$type", referer = url)
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        val m3u8 = root.optJSONObject("sources")?.optString("file").orEmpty()
        val h = mapOf("Accept" to "*/*", "Origin" to mainUrl, "Referer" to "$mainUrl/")
        if (m3u8.isNotBlank()) {
            val gen = generateM3u8(name, m3u8, mainUrl, headers = h)
            if (gen.isNotEmpty()) gen.forEach(callback) else callback(newExtractorLink(name, name, m3u8, ExtractorLinkType.M3U8) {
                this.referer = "$mainUrl/"
                this.headers = h
            })
        }
        root.optJSONArray("tracks")?.let { tracks ->
            for (i in 0 until tracks.length()) {
                val t = tracks.optJSONObject(i) ?: continue
                val kind = t.optString("kind")
                if (kind == "captions" || kind == "subtitles") {
                    emitSub(t.optString("label", "Unknown"), t.optString("file"), subtitleCallback)
                }
            }
        }
    }
}

open class CsAsianload : ExtractorApi() {
    override val name = "Asianload"
    override val mainUrl = "https://asianload.cfd"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = MovieFoxInAppCfBypass.get(url, referer = referer)
        val script = Jsoup.parse(html).selectFirst("div#player + script")?.html() ?: return
        val unpacked = JsUnpacker(script).unpack() ?: return
        Regex("""window\.atob\("([^"]+)"\)""").findAll(unpacked).forEach { m ->
            val decoded = runCatching { base64Decode(m.groupValues[1]) }.getOrNull().orEmpty()
            if (decoded.contains(".mp4")) {
                callback(newExtractorLink("AsianLoad (mp4)", "AsianLoad", decoded, ExtractorLinkType.VIDEO) { this.referer = url })
            } else if (decoded.contains(".m3u8")) {
                callback(newExtractorLink("AsianLoad (m3u8)", "AsianLoad", decoded, ExtractorLinkType.M3U8) { this.referer = url })
            }
        }
    }
}

class CsEmbedload : CsAsianload() { override val name = "Embedload"; override val mainUrl = "https://embedload.cfd" }

class CsFlixCloud : ExtractorApi() {
    override val name = "FlixCloud"
    override val mainUrl = "https://flixcloud.cc"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = MovieFoxInAppCfBypass.get(url, referer = "$mainUrl/")
        val script = Jsoup.parse(html).selectFirst("script:containsData(video_id)")?.data() ?: return
        val start = script.indexOf("data:{")
        if (start == -1) return
        val from = script.indexOf('{', start)
        var depth = 0
        var end = -1
        for (i in from until script.length) {
            when (script[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) { end = i; break }
                }
            }
        }
        if (end == -1) return
        val rawObj = script.substring(from, end + 1).replace(Regex("""([{,]\s*)([A-Za-z0-9_]+)(\s*:)"""), "$1\"$2\"$3")
        val data = runCatching { JSONObject(rawObj) }.getOrNull() ?: return
        data.optJSONArray("subtitles")?.let { subs ->
            for (i in 0 until subs.length()) {
                val s = subs.optJSONObject(i) ?: continue
                emitSub(s.optString("language"), s.optString("url"), subtitleCallback)
            }
        }
        data.remove("subtitles")
        val resText = MovieFoxInAppCfBypass.post(
            "${CineStreamConfig.MULTI_DECRYPT_API}/dec-flixcloud?type=token",
            json = mapOf("data" to data),
            headers = mapOf("Content-Type" to "application/json"),
            timeoutSec = 10L
        )
        val resJson = runCatching { JSONObject(resText) }.getOrNull() ?: return
        val resolved = runCatching { tryParseJson<CsResolvedReAnime>(resText)?.result }.getOrNull() ?: return
        val tokenText = MovieFoxInAppCfBypass.get("$mainUrl/api/m3u8/${resolved.token}", referer = "$mainUrl/", timeoutSec = 10L)
        val ctxObj = resJson.optJSONObject("result")?.optJSONObject("context") ?: return
        val streamRespObj = runCatching { JSONObject(tokenText) }.getOrNull() ?: return
        val decBody = JSONObject().put("data", JSONObject().put("context", ctxObj).put("stream_response", streamRespObj)).toString()
        val decText = MovieFoxInAppCfBypass.post(
            "${CineStreamConfig.MULTI_DECRYPT_API}/dec-flixcloud?type=stream",
            json = decBody.toRequestBody("application/json".toMediaType()),
            timeoutSec = 10L
        )
        val dec = runCatching { tryParseJson<CsReAnimeStream>(decText)?.result }.getOrNull() ?: return
        val parseManifest = "${CineStreamConfig.MULTI_DECRYPT_API}/parse-flixcloud?url=${enc(dec.stream)}&w_payload=${enc(dec.context.wPayload)}"
        callback(newExtractorLink(name, name, parseManifest, ExtractorLinkType.M3U8) {
            this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
            this.quality = Qualities.P1080.value
        })
    }
}

class CsPpzjYoutube : ExtractorApi() {
    override val name = "PpzjYoutube"
    override val mainUrl = "https://if9.ppzj-youtube.cfd"
    override val requiresReferer = true
    private val apiUrl = "https://api-play-270325.ppzj-youtube.cfd/api/tp1rd/playiframe"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val domain = URI(url).let { "${it.scheme}://${it.host}" }
            val headers = mapOf("Referer" to domain, "X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded")
            val html = MovieFoxInAppCfBypass.get(url, headers)
            val matches = Regex("""const\s*id(?:User|file)_enc\s*=\s*"([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            if (matches.size < 2) return@runCatching
            val fileId = decryptHexAES(matches[0], "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn")
            val userId = decryptHexAES(matches[1], "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O")
            val payload = """{"idfile":"$fileId","iduser":"$userId","domain_play":"${referer ?: "https://m4uhd.vip"}","platform":"Linux armv81","hlsSupport":true}"""
            val encPayload = encryptHexAES(payload, "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ")
            val sig = md5(encPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h")
            val resText = MovieFoxInAppCfBypass.post(apiUrl, data = mapOf("data" to "$encPayload|$sig"), headers = headers)
            val encVid = runCatching { JSONObject(resText).optString("data").substringBefore("|") }.getOrDefault("")
            if (encVid.isBlank()) return@runCatching
            val videoUrl = decryptHexAES(encVid, "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL")
            generateM3u8(name, videoUrl, domain, Qualities.P1080.value).forEach(callback)
        }
    }

    private fun encryptHexAES(plaintext: String, password: String): String {
        val salt = ByteArray(8).also { SecureRandom().nextBytes(it) }
        val (key, iv) = deriveKeyIv(password.toByteArray(), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        return ("Salted__".toByteArray() + salt + cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))).joinToString("") { "%02x".format(it) }
    }

    private fun decryptHexAES(hex: String, password: String): String {
        val bytes = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val salt = bytes.copyOfRange(8, 16)
        val ct = bytes.copyOfRange(16, bytes.size)
        val (key, iv) = deriveKeyIv(password.toByteArray(), salt)
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding").apply { init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv)) }
        return String(cipher.doFinal(ct), Charsets.UTF_8)
    }

    private fun deriveKeyIv(password: ByteArray, salt: ByteArray): Pair<ByteArray, ByteArray> {
        val md5 = MessageDigest.getInstance("MD5")
        val buf = ByteArray(48)
        var prev = ByteArray(0)
        var gen = 0
        while (gen < 48) {
            md5.reset()
            md5.update(prev)
            md5.update(password)
            md5.update(salt)
            prev = md5.digest()
            System.arraycopy(prev, 0, buf, gen, prev.size)
            gen += prev.size
        }
        return buf.copyOfRange(0, 32) to buf.copyOfRange(32, 48)
    }

    private fun md5(input: String): String =
        MessageDigest.getInstance("MD5").digest(input.toByteArray()).joinToString("") { "%02x".format(it) }
}
