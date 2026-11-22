package com.hungrysturdy

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Base64
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
import com.lagradost.cloudstream3.utils.M3u8Helper.Companion.generateM3u8
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import org.jsoup.Jsoup
import java.net.URI
import java.net.URLDecoder
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec
import kotlin.coroutines.resume

// ── In-App Cloudflare Bypass (WebView Cookie + Interceptor, No Browser) ──

internal object MovieFoxInAppCfBypass {

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
        val fromWebView = runCatching { CookieManager.getInstance().getCookie(url) }
            .getOrNull()?.takeIf { it.isNotBlank() }
        if (fromWebView != null) {
            cookieCache[h] = fromWebView
            return fromWebView
        }
        return null
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
    private suspend fun solveViaInAppWebView(
        url: String,
        headers: Map<String, String>,
        timeoutMs: Long = 16_000L
    ): String? {
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

private val mfDynamicUrlCache = ConcurrentHashMap<String, String>()

private fun mfBaseUrl(url: String): String = runCatching {
    val uri = URI(url)
    "${uri.scheme}://${uri.host}"
}.getOrDefault(url)

private suspend fun mfCfGetText(
    url: String,
    headers: Map<String, String> = emptyMap(),
    referer: String? = null,
    timeoutSec: Long = 18L,
    allowRedirects: Boolean = true
): String = MovieFoxInAppCfBypass.get(
    url = url,
    headers = headers,
    referer = referer,
    timeoutSec = timeoutSec,
    allowRedirects = allowRedirects
)

private suspend fun mfCfPostText(
    url: String,
    headers: Map<String, String> = emptyMap(),
    data: Map<String, String> = emptyMap(),
    json: Any? = null,
    referer: String? = null,
    timeoutSec: Long = 20L
): String = MovieFoxInAppCfBypass.post(
    url = url,
    headers = headers,
    data = data,
    json = json,
    referer = referer,
    timeoutSec = timeoutSec
)

private suspend fun mfLatestBaseUrl(baseUrl: String, source: String): String {
    mfDynamicUrlCache[source]?.let { return it }
    return runCatching {
        val fbRaw = runCatching {
            mfCfGetText("https://cloudstreampluginhelper-default-rtdb.firebaseio.com/.json", timeoutSec = 5L)
        }.getOrDefault("")
        val fbObj = runCatching { JSONObject(fbRaw) }.getOrNull()
        val fromFb = listOf(source, "justplay_$source", "${source}_url", "${source}_domain")
            .firstNotNullOfOrNull { k -> fbObj?.optString(k)?.trim()?.trimEnd('/')?.takeIf { it.startsWith("http") } }
        if (fromFb != null) {
            mfDynamicUrlCache[source] = fromFb
            return fromFb
        }

        val raw = mfCfGetText(
            "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json",
            timeoutSec = 8L
        )
        val resolved = JSONObject(raw).optString(source).trim()
        if (resolved.startsWith("http")) {
            mfDynamicUrlCache[source] = resolved.trimEnd('/')
            resolved.trimEnd('/')
        } else {
            val phRaw = runCatching {
                mfCfGetText("https://raw.githubusercontent.com/phisher98/TVVVV/refs/heads/main/domains.json", timeoutSec = 8L)
            }.getOrDefault("")
            val phResolved = runCatching {
                JSONObject(phRaw).optString(if (source.equals("hdhub4u", true)) "HDHUB4u" else source).trim().trimEnd('/')
            }.getOrDefault("")
            if (phResolved.startsWith("http")) {
                mfDynamicUrlCache[source] = phResolved
                phResolved
            } else {
                baseUrl
            }
        }
    }.getOrDefault(baseUrl)
}

private fun mfIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value
    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
    val lower = str.lowercase()
    return when {
        lower.contains("8k") -> 4320
        lower.contains("4k") || lower.contains("uhd") -> 2160
        lower.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

private fun mfFormatBytes(bytes: Long): String = when {
    bytes < 1024L * 1024 * 1024 -> "%.2f MB".format(bytes.toDouble() / (1024 * 1024))
    else -> "%.2f GB".format(bytes.toDouble() / (1024 * 1024 * 1024))
}

internal fun mfIsBrokenContainerUrl(url: String): Boolean {
    val clean = url.substringBefore("?").substringBefore("#").lowercase()
    return clean.endsWith(".css") ||
        clean.endsWith(".js") ||
        clean.endsWith(".html") ||
        clean.endsWith(".png") ||
        clean.endsWith(".jpg") ||
        clean.endsWith(".jpeg") ||
        clean.endsWith(".webp") ||
        url.contains("neopulse.vip/range/", ignoreCase = true)
}

private suspend fun <A, B> Iterable<A>.mfSafeAmap(
    concurrency: Int = 7,
    f: suspend (A) -> B?
): List<B> = supervisorScope {
    val semaphore = Semaphore(concurrency)
    map { item ->
        async<B?>(Dispatchers.IO) {
            semaphore.withPermit {
                try {
                    f(item)
                } catch (e: CancellationException) {
                    if (!isActive) throw e
                    null
                } catch (e: Throwable) {
                    Log.e("mfSafeAmap", "Item failed: ${e.message}")
                    null
                }
            }
        }
    }.awaitAll().filterNotNull()
}

// ── CineStream ExtractorApi Classes inside Extractor.kt ───────────────────────────

open class CsVideostr : ExtractorApi() {
    override val name = "Videostr"
    override val mainUrl = "https://videostr.net"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val base = mfLatestBaseUrl(mainUrl, "videostr")
        val headers = mapOf("Accept" to "*/*", "X-Requested-With" to "XMLHttpRequest", "Referer" to "$base/")
        val html = mfCfGetText(url, headers, timeoutSec = 15L)
        if (html.isBlank()) return
        val fileId = Jsoup.parse(html).selectFirst("[id$=\"-player\"]")?.attr("data-id").orEmpty()
        if (fileId.isEmpty()) return
        val triple = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""", RegexOption.DOT_MATCHES_ALL).find(html)
        val single = Regex("""\b[a-zA-Z0-9]{48}\b""").find(html)
        val nonce = if (triple != null) triple.groupValues[1] + triple.groupValues[2] + triple.groupValues[3] else single?.value ?: return
        val json = mfCfGetText("$base/embed-1/v3/e-1/getSources?id=$fileId&_k=$nonce", headers, timeoutSec = 12L)
        val m3u8 = runCatching { JSONObject(json).optJSONArray("sources")?.optJSONObject(0)?.optString("file") }.getOrNull().orEmpty()
        if (m3u8.startsWith("http") && !mfIsBrokenContainerUrl(m3u8)) {
            generateM3u8(name, m3u8, base, headers = headers).forEach(callback)
        }
    }
}

class CsStreameeeeee : CsVideostr() {
    override var name = "Streameeeeee"
    override var mainUrl = "https://streameeeeee.site"
}

open class CsGofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val apiBase = "https://api.gofile.io"
    private val secret = "12af056dacea0b"
    private val lang = "en-GB"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val ua = MovieFoxSettingsDialog.CF_BYPASS_UA
        val accRaw = mfCfPostText("$apiBase/accounts", headers = mapOf("User-Agent" to ua, "Referer" to "$mainUrl/", "Origin" to mainUrl))
        val token = runCatching { JSONObject(accRaw).getJSONObject("data").getString("token") }.getOrDefault("")
        if (token.isBlank()) return
        val slot = System.currentTimeMillis() / 1000 / 14400
        val wt = sha256("$ua::$lang::$token::$slot::$secret")
        val contRaw = mfCfGetText(
            "$apiBase/contents/$id?page=1&pageSize=100&sortField=name&sortDirection=1",
            headers = mapOf("Referer" to "$mainUrl/", "User-Agent" to ua, "Authorization" to "Bearer $token", "X-BL" to lang, "X-Website-Token" to wt)
        )
        val children = runCatching { JSONObject(contRaw).getJSONObject("data").optJSONObject("children") }.getOrNull() ?: return
        val names = children.names() ?: return
        for (i in 0 until names.length()) {
            val f = children.optJSONObject(names.optString(i)) ?: continue
            if (f.optString("type") != "file") continue
            val link = f.optString("link").trim()
            if (!link.startsWith("http") || mfIsBrokenContainerUrl(link)) continue
            val fName = f.optString("name")
            val sz = f.optLong("size", 0L)
            callback(newExtractorLink("Gofile", "[Gofile] $fName [${mfFormatBytes(sz)}]", link, ExtractorLinkType.VIDEO) {
                this.quality = mfIndexQuality(fName)
                this.headers = mapOf("Cookie" to "accountToken=$token")
            })
        }
    }

    private fun sha256(s: String): String =
        MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

open class CsWootly : ExtractorApi() {
    override var name = "Wootly"
    override var mainUrl = "https://www.wootly.ch"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = mfCfGetText(url, referer = referer)
        val iframe = Jsoup.parse(html).selectFirst("iframe")?.attr("src") ?: return
        val iHtml = mfCfPostText(iframe, data = mapOf("qdfx" to "1"), referer = url)
        val vd = Regex("""var\s+vd\s*=\s*["']([^"']+)["']""").find(iHtml)?.groupValues?.get(1) ?: return
        val tk = Regex("""tk\s*=\s*["']([^"']+)["']""").find(iHtml)?.groupValues?.get(1) ?: return
        val streamUrl = mfCfGetText("https://web.wootly.ch/grabm?t=$tk&id=$vd", referer = iframe).trim()
        if (streamUrl.startsWith("http") && !mfIsBrokenContainerUrl(streamUrl)) {
            callback(newExtractorLink(name, name, streamUrl, ExtractorLinkType.VIDEO) { this.referer = referer ?: "" })
        }
    }
}

open class CsVibePlayer : ExtractorApi() {
    override val name = "VibePlayer"
    override val mainUrl = "https://vibeplayer.site"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = mfCfGetText(url, referer = referer)
        val videoUrl = Regex("""const\s+src\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1) ?: return
        if (!mfIsBrokenContainerUrl(videoUrl)) generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
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
        val html = mfCfGetText(url, referer = referer ?: "https://anikototv.to/")
        val el = Jsoup.parse(html).selectFirst("#megaplay-player")
        val streamId = el?.attr("data-id") ?: el?.attr("data-realid") ?: Regex("""/stream/s-\d+/(\d+)/""").find(url)?.groupValues?.get(1) ?: return
        val type = if (url.contains("/dub", true)) "dub" else "sub"
        val jsonText = mfCfGetText("$mainUrl/stream/getSources?id=$streamId&type=$type", referer = url)
        val root = runCatching { JSONObject(jsonText) }.getOrNull() ?: return
        val m3u8 = root.optJSONObject("sources")?.optString("file").orEmpty()
        val h = mapOf("Accept" to "*/*", "Origin" to mainUrl, "Referer" to "$mainUrl/")
        if (m3u8.isNotBlank() && !mfIsBrokenContainerUrl(m3u8)) {
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
                val fUrl = t.optString("file")
                if ((kind == "captions" || kind == "subtitles") && fUrl.startsWith("http")) {
                    subtitleCallback(newSubtitleFile(t.optString("label", "Unknown"), fUrl))
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
        val html = mfCfGetText(url, referer = referer)
        val script = Jsoup.parse(html).selectFirst("div#player + script")?.html() ?: return
        val unpacked = JsUnpacker(script).unpack() ?: return
        Regex("""window\.atob\("([^"]+)"\)""").findAll(unpacked).forEach { m ->
            val decoded = runCatching { base64Decode(m.groupValues[1]) }.getOrNull().orEmpty()
            if (decoded.contains(".mp4") && !mfIsBrokenContainerUrl(decoded)) {
                callback(newExtractorLink("AsianLoad (mp4)", "AsianLoad", decoded, ExtractorLinkType.VIDEO) { this.referer = url })
            } else if (decoded.contains(".m3u8") && !mfIsBrokenContainerUrl(decoded)) {
                callback(newExtractorLink("AsianLoad (m3u8)", "AsianLoad", decoded, ExtractorLinkType.M3U8) { this.referer = url })
            }
        }
    }
}

class CsEmbedload : CsAsianload() { override val name = "Embedload"; override val mainUrl = "https://embedload.cfd" }

open class CsFlixCloud : ExtractorApi() {
    override val name = "FlixCloud"
    override val mainUrl = "https://flixcloud.cc"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val html = mfCfGetText(url, referer = "$mainUrl/")
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
                val sUrl = s.optString("url")
                if (sUrl.startsWith("http")) subtitleCallback(newSubtitleFile(s.optString("language", "English"), sUrl))
            }
        }
        data.remove("subtitles")
        val resText = mfCfPostText(
            "https://enc-dec.app/api/dec-flixcloud?type=token",
            json = mapOf("data" to data),
            headers = mapOf("Content-Type" to "application/json"),
            timeoutSec = 10L
        )
        val resJson = runCatching { JSONObject(resText) }.getOrNull() ?: return
        val resultObj = resJson.optJSONObject("result") ?: return
        val token = resultObj.optString("token")
        val ctxObj = resultObj.optJSONObject("context") ?: return
        if (token.isBlank()) return
        val tokenText = mfCfGetText("$mainUrl/api/m3u8/$token", referer = "$mainUrl/", timeoutSec = 10L)
        val streamRespObj = runCatching { JSONObject(tokenText) }.getOrNull() ?: return
        val decBody = JSONObject().put("data", JSONObject().put("context", ctxObj).put("stream_response", streamRespObj)).toString()
        val decText = mfCfPostText("https://enc-dec.app/api/dec-flixcloud?type=stream", json = decBody, timeoutSec = 10L)
        val decResult = runCatching { JSONObject(decText).optJSONObject("result") }.getOrNull() ?: return
        val stream = decResult.optString("stream")
        val wPayload = decResult.optJSONObject("context")?.optString("w_payload").orEmpty()
        if (stream.isBlank()) return
        val parseManifest = "https://enc-dec.app/api/parse-flixcloud?url=${java.net.URLEncoder.encode(stream, "UTF-8")}&w_payload=${java.net.URLEncoder.encode(wPayload, "UTF-8")}"
        callback(newExtractorLink(name, name, parseManifest, ExtractorLinkType.M3U8) {
            this.headers = mapOf("Origin" to mainUrl, "Referer" to "$mainUrl/")
            this.quality = Qualities.P1080.value
        })
    }
}

open class CsPpzjYoutube : ExtractorApi() {
    override val name = "PpzjYoutube"
    override val mainUrl = "https://if9.ppzj-youtube.cfd"
    override val requiresReferer = true
    private val apiUrl = "https://api-play-270325.ppzj-youtube.cfd/api/tp1rd/playiframe"

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val domain = URI(url).let { "${it.scheme}://${it.host}" }
            val headers = mapOf("Referer" to domain, "X-Requested-With" to "XMLHttpRequest", "Content-Type" to "application/x-www-form-urlencoded")
            val html = mfCfGetText(url, headers)
            val matches = Regex("""const\s*id(?:User|file)_enc\s*=\s*"([^"]+)"""").findAll(html).map { it.groupValues[1] }.toList()
            if (matches.size < 2) return@runCatching
            val fileId = decryptHexAES(matches[0], "jcLycoRJT6OWjoWspgLMOZwS3aSS0lEn")
            val userId = decryptHexAES(matches[1], "PZZ3J3LDbLT0GY7qSA5wW5vchqgpO36O")
            val payload = """{"idfile":"$fileId","iduser":"$userId","domain_play":"${referer ?: "https://m4uhd.vip"}","platform":"Linux armv81","hlsSupport":true}"""
            val encPayload = encryptHexAES(payload, "vlVbUQhkOhoSfyteyzGeeDzU0BHoeTyZ")
            val sig = md5(encPayload + "KRWN3AdgmxEMcd2vLN1ju9qKe8Feco5h")
            val resText = mfCfPostText(apiUrl, data = mapOf("data" to "$encPayload|$sig"), headers = headers)
            val encVid = runCatching { JSONObject(resText).optString("data").substringBefore("|") }.getOrDefault("")
            if (encVid.isBlank()) return@runCatching
            val videoUrl = decryptHexAES(encVid, "oJwmvmVBajMaRCTklxbfjavpQO7SZpsL")
            if (!mfIsBrokenContainerUrl(videoUrl)) {
                generateM3u8(name, videoUrl, domain, Qualities.P1080.value).forEach(callback)
            }
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

// ── Existing MovieFox Extractors ────────────────────────────────────────────────

internal object GokuHDSources {

    class Server(val name: String, val url: String, val headers: Map<String, String> = emptyMap())

    class DrivePage(
        val servers: List<Pair<String, String>>,
        val episodes: Map<Int, List<Pair<String, String>>>
    )

    private val deadHosts = Regex("""(?i)megaup|viking|vik1ng|telegram|t\.me|transfer\.it|bzzhr\.co""")
    private val buttonJunk = Regex(
        """(?i)vglist|t\.me|telegram|imdb\.com|google\.com/search|whatsapp|share|facebook|""" +
            """twitter|pinterest|report|hubcloud\.(foo|cx)|/admin|login|signup|\bidm\b|\bida\b|""" +
            """category|/tag/|/author/|/page/"""
    )

    private val pageCache = ConcurrentHashMap<String, DrivePage>()

    fun isServerLink(href: String): Boolean {
        if (deadHosts.containsMatchIn(href) || mfIsBrokenContainerUrl(href)) return false
        val h = href.lowercase()
        return href.startsWith("http") && (
            h.contains("nexdrive") ||
                h.contains("mobilejsr") ||
                h.contains("mdrive.cloud") ||
                h.contains("m-drive.") ||
                h.contains("vcloud") ||
                h.contains("mcloud.") ||
                h.contains("vegadrive") ||
                h.contains("fastdl") ||
                h.contains("hubcdn.") ||
                h.contains("filebee") || h.contains("filepress") || h.contains("fpgo") ||
                h.contains("dotflix") ||
                h.contains("gofile") ||
                h.contains("hubcloud") ||
                h.contains("pixeldrain") ||
                h.contains("gpdl") ||
                h.contains(".r2.dev") || h.contains(".r2.cloudflarestorage.com") ||
                h.contains("buzzserver") || h.contains("10gbps") || h.contains("instantcloud")
            )
    }

    suspend fun fetchDrivePage(url: String): DrivePage? {
        pageCache[url]?.let { return it }
        val html = mfCfGetText(
            url = url,
            headers = mapOf("User-Agent" to USER_AGENT),
            referer = "https://nexdrive.beer/",
            timeoutSec = 18L
        )
        if (html.isBlank() || Regex("""(?i)file (is )?deleted|not found""").containsMatchIn(html)) return null
        val page = parseDrivePage(html)
        pageCache[url] = page
        return page
    }

    private fun parseDrivePage(html: String): DrivePage {
        val doc = Jsoup.parse(html)
        val scope = doc.selectFirst(".entry, article, .post-inner") ?: doc

        val servers = mutableListOf<Pair<String, String>>()
        for (a in scope.select("a[href]")) {
            val href = a.attr("href").trim()
            val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                ?: a.text().replace(Regex("""\s+"""), " ").trim()
            if (label.isBlank() || !isServerLink(href)) continue
            if (buttonJunk.containsMatchIn(href) || buttonJunk.containsMatchIn(label)) continue
            if (Regex("""(?i)batch|zip\b""").containsMatchIn(label)) continue
            servers.add(label to href)
        }

        val episodes = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
        for (head in scope.select("h3, h4, h5")) {
            val num = Regex("""(?i)ep(?:isode)?s?\s*:?\s*0*(\d{1,3})""").find(head.text())?.groupValues?.get(1)?.toIntOrNull()
                ?: continue
            val links = mutableListOf<Pair<String, String>>()
            var sib = head.nextElementSibling()
            while (sib != null && !listOf("h3", "h4", "h5").contains(sib.tagName())) {
                for (a in sib.select("a[href]")) {
                    val href = a.attr("href").trim()
                    val label = a.selectFirst("button")?.text()?.replace(Regex("""\s+"""), " ")?.trim()
                        ?: a.text().replace(Regex("""\s+"""), " ").trim()
                    if (label.isBlank() || !isServerLink(href)) continue
                    if (buttonJunk.containsMatchIn(href) || buttonJunk.containsMatchIn(label)) continue
                    links.add(label to href)
                }
                sib = sib.nextElementSibling()
            }
            if (links.isNotEmpty()) {
                episodes.getOrPut(num) { mutableListOf() }.addAll(links)
            }
        }
        return DrivePage(servers.distinctBy { it.second }, episodes.mapValues { it.value.distinctBy { p -> p.second } })
    }

    suspend fun resolve(href: String, episodeNum: Int? = null): List<Server> {
        if (!href.startsWith("http") || deadHosts.containsMatchIn(href) || mfIsBrokenContainerUrl(href)) return emptyList()
        val h = href.lowercase()
        return try {
            when {
                h.contains("nexdrive") || h.contains("mobilejsr") || h.contains("mdrive.cloud") || h.contains("m-drive.") -> {
                    val page = fetchDrivePage(href) ?: return emptyList()
                    val targets = if (episodeNum != null) {
                        page.episodes[episodeNum] ?: page.servers.takeIf { page.episodes.isEmpty() }
                    } else {
                        page.servers.ifEmpty { page.episodes.values.flatten() }
                    }
                    val out = mutableListOf<Server>()
                    for ((_, link) in targets.orEmpty()) {
                        if (link.contains("nexdrive", true) || link.contains("mobilejsr", true)) {
                            val nested = fetchDrivePage(link)
                            val innerTargets = if (episodeNum != null) {
                                nested?.episodes?.get(episodeNum) ?: nested?.servers
                            } else {
                                nested?.servers ?: nested?.episodes?.values?.flatten()
                            }
                            for ((_, innerLink) in innerTargets.orEmpty()) {
                                out.addAll(resolve(innerLink, episodeNum))
                            }
                        } else {
                            out.addAll(resolve(link, episodeNum))
                        }
                    }
                    out.distinctBy { it.url }
                }
                h.contains(".r2.cloudflarestorage.com") -> listOf(Server("FSLv2", href))
                h.contains(".r2.dev") -> listOf(Server("FSL", href))
                h.contains("pixeldrain") -> {
                    val id = href.substringBefore("?").substringBefore("#").substringAfterLast("/")
                    if (id.isNotBlank()) listOf(Server("PixelDrain", "https://pixeldrain.dev/api/file/$id")) else emptyList()
                }
                h.contains("vcloud") || h.contains("mcloud.") || h.contains("hubcloud") -> {
                    val resHtml = mfCfGetText(
                        url = href,
                        headers = mapOf("User-Agent" to USER_AGENT, "Cookie" to "xla=s4t"),
                        timeoutSec = 18L
                    )
                    val doc = Jsoup.parse(resHtml)
                    val out = mutableListOf<Server>()
                    for (a in doc.select("a[href]")) {
                        val u = a.attr("href").trim()
                        val lbl = a.text().replace(Regex("""\s+"""), " ").trim()
                        if (!u.startsWith("http") || mfIsBrokenContainerUrl(u) || buttonJunk.containsMatchIn(u)) continue
                        val sName = when {
                            lbl.contains("fslv2", true) || lbl.contains("fsl v2", true) || u.contains(".r2.cloudflarestorage.com", true) -> "FSLv2"
                            lbl.contains("fsl", true) || u.contains(".r2.dev", true) -> "FSL"
                            lbl.contains("pixel", true) || u.contains("pixeldrain", true) -> "PixelDrain"
                            lbl.contains("gofile", true) || u.contains("gofile", true) -> "GoFile"
                            lbl.contains("10gbps", true) || u.contains("gpdl", true) -> "10Gbps"
                            else -> null
                        } ?: continue
                        val finalU = if (sName == "PixelDrain") {
                            val pid = u.substringBefore("?").substringBefore("#").substringAfterLast("/")
                            if (pid.isNotBlank()) "https://pixeldrain.dev/api/file/$pid" else u
                        } else {
                            u
                        }
                        out.add(Server(sName, finalU))
                    }
                    out.distinctBy { it.url }
                }
                h.contains("fastdl") || h.contains("hubcdn.") -> {
                    val direct = PlayDirectStub.resolve(href, null)
                    if (!direct.isNullOrBlank() && !mfIsBrokenContainerUrl(direct)) {
                        listOf(Server("G-Direct", direct, mapOf("Referer" to mfBaseUrl(href) + "/")))
                    } else {
                        emptyList()
                    }
                }
                h.contains("gofile") -> {
                    val code = href.substringAfterLast("/").substringBefore("#").substringBefore("?")
                    if (code.isBlank()) return emptyList()
                    val token = runCatching {
                        JSONObject(mfCfPostText("https://api.gofile.io/accounts", timeoutSec = 12L)).getJSONObject("data").getString("token")
                    }.getOrNull() ?: return emptyList()
                    val data = runCatching {
                        JSONObject(mfCfGetText("https://api.gofile.io/contents/$code?wt=$token", headers = mapOf("Authorization" to "Bearer $token"), timeoutSec = 15L))
                            .getJSONObject("data").optJSONObject("children")
                    }.getOrNull() ?: return emptyList()
                    val out = mutableListOf<Server>()
                    var bestLink: String? = null
                    var bestSize = -1L
                    val keysArr = data.names()
                    if (keysArr != null) {
                        for (idx in 0 until keysArr.length()) {
                            val child = data.optJSONObject(keysArr.optString(idx)) ?: continue
                            val cName = child.optString("name")
                            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(cName)) continue
                            val link = child.optString("link").trim()
                            if (link.startsWith("http") && !mfIsBrokenContainerUrl(link)) {
                                out.add(Server("GoFile", link, mapOf("Cookie" to "accountToken=$token")))
                                val sz = child.optLong("size", 0L)
                                if (sz > bestSize) {
                                    bestSize = sz
                                    bestLink = link
                                }
                            }
                        }
                    }
                    if (bestLink != null) {
                        out.sortedByDescending { if (it.url == bestLink) 1 else 0 }
                    } else {
                        out
                    }
                }
                else -> emptyList()
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

class GokuHDExtractor : ExtractorApi() {
    override val name: String = "GokuHD"
    override val mainUrl: String = "https://nexdrive.beer"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        GokuHDSources.resolve(url).forEach { srv ->
            if (!mfIsBrokenContainerUrl(srv.url)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = srv.name,
                        url = srv.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = srv.headers
                        this.referer = referer ?: mainUrl
                    }
                )
            }
        }
    }
}

class VegaDriveExtractor : ExtractorApi() {
    override val name: String = "V-Drive"
    override val mainUrl: String = "https://one.vegadrive.me"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val page = app.get(url, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "https://nexdrive.beer/"), timeout = 20L)
            val base = mfBaseUrl(page.url).ifBlank { "https://one.vegadrive.app" }
            val token = url.substringAfter("/s/").substringBefore("?").substringBefore("#").substringAfter("/d/").substringBefore("/")
            if (token.isBlank() || token == url) return

            for (slug in listOf("skydrop", "pixeldrain", "buzzheavier", "telegram")) {
                val start = if (slug == "skydrop") "$base/go/$token/skydrop" else "$base/d/$token/$slug"
                val hop = app.get(start, headers = mapOf("User-Agent" to USER_AGENT, "Referer" to "$base/"), allowRedirects = false, timeout = 15L)
                val loc = hop.headers["location"]?.trim().orEmpty()
                runCatching { hop.okhttpResponse.close() }
                if (!loc.startsWith("http") || mfIsBrokenContainerUrl(loc)) continue
                when (slug) {
                    "skydrop" -> if (loc.contains("googleusercontent") && !loc.substringAfterLast("/").contains(".zip", true)) {
                        callback.invoke(newExtractorLink(name, "V-Drive Vegadrop (10Gbps)", loc, ExtractorLinkType.VIDEO))
                    }
                    "pixeldrain" -> if (loc.contains("pixeldrain")) {
                        val id = loc.substringBefore("?").substringBefore("#").substringAfterLast("/")
                        if (id.isNotBlank()) callback.invoke(newExtractorLink(name, "V-Drive Pixeldrain", "https://pixeldrain.dev/api/file/$id", ExtractorLinkType.VIDEO))
                    }
                    "buzzheavier" -> if (loc.contains("bzzhr.co")) {
                        callback.invoke(newExtractorLink(name, "V-Drive Buzzheavier", loc, ExtractorLinkType.VIDEO))
                    }
                    "telegram" -> if (loc.contains("tgfiles")) {
                        callback.invoke(newExtractorLink(name, "V-Drive Telegram", loc, ExtractorLinkType.VIDEO))
                    }
                }
            }
        } catch (_: Exception) {}
    }
}

class FilePressExtractor : ExtractorApi() {
    override val name: String = "FilePress"
    override val mainUrl: String = "https://filebee.xyz"
    override val requiresReferer: Boolean = false
    private val fileIdRegex = Regex("""/file/([a-f0-9]{16,40})""")

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = fileIdRegex.find(url)?.groupValues?.get(1) ?: return
        try {
            val infoText = mfCfGetText("$mainUrl/api/file/get/$id", headers = mapOf("User-Agent" to USER_AGENT), referer = "$mainUrl/", timeoutSec = 15L)
            val info = runCatching { JSONObject(infoText).optJSONObject("data") }.getOrNull() ?: return
            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(info.optString("name"))) return

            val dotflixShare = downloadTask(id, "dotFlixDownlaod")
            if (!dotflixShare.isNullOrBlank() && dotflixShare.startsWith("http")) {
                resolveDotFlix(dotflixShare)?.let { direct ->
                    if (!mfIsBrokenContainerUrl(direct)) {
                        callback.invoke(newExtractorLink(name, "FilePress Instant", direct, ExtractorLinkType.VIDEO))
                    }
                }
            }

            val indexTask = downloadTask(id, "indexDownlaod")
            if (!indexTask.isNullOrBlank() && indexTask.matches(Regex("[a-f0-9]{16,40}"))) {
                finalTask(indexTask, "indexDownlaod")?.let { direct ->
                    if (direct.startsWith("http") && !mfIsBrokenContainerUrl(direct)) {
                        callback.invoke(newExtractorLink(name, "FilePress Direct", direct, ExtractorLinkType.VIDEO))
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun downloadTask(id: String, method: String): String? = try {
        val body = JSONObject().put("captchaValue", "").put("id", id).put("method", method).toString()
        val res = mfCfPostText(
            url = "$mainUrl/api/file/downlaod/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to mainUrl,
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            json = body,
            referer = "$mainUrl/",
            timeoutSec = 25L
        )
        val parsed = JSONObject(res)
        if (!parsed.optBoolean("status")) null else (parsed.opt("data") as? String)?.takeIf { it.isNotBlank() }
    } catch (_: Exception) {
        null
    }

    private suspend fun finalTask(taskId: String, method: String): String? = try {
        val body = JSONObject().put("captchaValue", "").put("id", taskId).put("method", method).toString()
        val res = mfCfPostText(
            url = "$mainUrl/api/file/downlaod2/",
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to mainUrl,
                "Content-Type" to "application/json",
                "Accept" to "application/json"
            ),
            json = body,
            referer = "$mainUrl/",
            timeoutSec = 30L
        )
        val parsed = JSONObject(res)
        if (!parsed.optBoolean("status")) null else when (val d = parsed.opt("data")) {
            is String -> d.takeIf { it.startsWith("http") }
            is org.json.JSONArray -> (0 until d.length()).firstNotNullOfOrNull { idx -> d.optString(idx).takeIf { it.startsWith("http") } }
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    private suspend fun resolveDotFlix(shareUrl: String): String? = try {
        val ref = if (shareUrl.contains("dotflix.fit", true)) "https://new3.dotflix.shop/" else "https://new2.dotflix.shop/"
        val apiEndpoint = if (shareUrl.contains("dotflix.fit", true)) "https://dotflix.fit/api/extract-download" else "https://dotflix.store/api/extract-download"
        val text = mfCfGetText(shareUrl, headers = mapOf("User-Agent" to USER_AGENT), referer = ref, timeoutSec = 20L)
        val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return null
        val obfuscated = Base64.encodeToString(code.toByteArray(), Base64.NO_WRAP).reversed()
        val requestId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val timestamp = System.currentTimeMillis().toString()
        val body = JSONObject().put("requestId", requestId).put("timestamp", timestamp).put("data", obfuscated).toString()
        val res = mfCfPostText(
            url = apiEndpoint,
            headers = mapOf(
                "User-Agent" to USER_AGENT,
                "Origin" to ref.trimEnd('/'),
                "Content-Type" to "application/json",
                "Accept" to "application/json",
                "X-Request-ID" to requestId,
                "X-Timestamp" to timestamp
            ),
            json = body,
            referer = ref,
            timeoutSec = 25L
        )
        val parsed = JSONObject(res)
        if (!parsed.optBoolean("success")) null else parsed.optString("downloadUrl").takeIf { it.startsWith("http") }
    } catch (_: Exception) {
        null
    }
}

class InstantCloudExtractor : ExtractorApi() {
    override val name = "InstantCloud"
    override val mainUrl = "https://instantcloud.org"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val headers = mapOf("User-Agent" to USER_AGENT, "Accept" to "application/json")
            var link = fetchDownloadLink(url, headers)
            if (link == null) {
                var ready = false
                repeat(3) {
                    if (ready) return@repeat
                    val meta = mfCfGetText("$url?ajax=1&refresh=1", headers, referer = url, timeoutSec = 15L)
                    val obj = runCatching { JSONObject(meta) }.getOrNull() ?: return
                    ready = obj.optBoolean("downloadable") || obj.optString("gp_status").equals("VALID", true)
                    if (!ready && obj.optBoolean("needs_poll")) delay(1500L)
                }
                if (ready) link = fetchDownloadLink(url, headers)
            }
            if (!link.isNullOrBlank() && link.startsWith("http") && !mfIsBrokenContainerUrl(link)) {
                callback.invoke(newExtractorLink(name, name, link, ExtractorLinkType.VIDEO) {
                    this.quality = mfIndexQuality(url)
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun fetchDownloadLink(url: String, headers: Map<String, String>): String? = try {
        val body = mfCfGetText("$url/download?json=1", headers, referer = url, timeoutSec = 20L)
        JSONObject(body).optString("download_url").trim().takeIf { it.startsWith("http") }
    } catch (_: Exception) {
        null
    }
}

class ZephyrixExtractor : ExtractorApi() {
    override val name = "Anime World India"
    override val mainUrl = "https://watchanimeworld.top"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val videoId = Regex("/video/([a-f0-9]+)", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1) ?: return
            val playerBase = Regex("(https?://[^/]+)").find(url)?.groupValues?.get(1) ?: return
            val origin = referer?.takeIf { it.startsWith("http") }?.let { mfBaseUrl(it) } ?: mainUrl
            val res = mfCfPostText(
                url = "$playerBase/player/index.php?data=$videoId&do=getVideo",
                data = mapOf("hash" to videoId, "r" to "$origin/"),
                headers = mapOf(
                    "X-Requested-With" to "XMLHttpRequest",
                    "Origin" to playerBase,
                    "Accept" to "application/json, text/javascript, */*; q=0.01",
                    "User-Agent" to USER_AGENT
                ),
                referer = "$playerBase/video/$videoId",
                timeoutSec = 30L
            )
            val obj = JSONObject(res)
            val rawSrc = obj.optString("videoSource").trim().ifBlank { obj.optString("securedLink").trim() }
            if (rawSrc.isBlank()) return
            val streamUrl = if (rawSrc.startsWith("http")) rawSrc else "$playerBase$rawSrc"
            if (mfIsBrokenContainerUrl(streamUrl)) return
            callback.invoke(
                newExtractorLink(name, name, streamUrl, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.Unknown.value
                    this.referer = "$playerBase/"
                    this.headers = mapOf("Accept" to "*/*", "User-Agent" to USER_AGENT)
                }
            )
        } catch (_: Exception) {}
    }
}

class Streameeeeee : Videostr() {
    override var name = "Streameeeeee"
    override var mainUrl = "https://streameeeeee.site"
}

open class Videostr : ExtractorApi() {
    override val name = "Videostr"
    override val mainUrl = "https://videostr.net"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val resolvedBase = mfLatestBaseUrl(mainUrl, "videostr")
        val mainHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Origin" to resolvedBase,
            "Referer" to "$resolvedBase/"
        )
        val headers = mapOf(
            "Accept" to "*/*",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "$resolvedBase/",
            "User-Agent" to USER_AGENT
        )

        val htmlResponse = mfCfGetText(url, headers)
        if (htmlResponse.isBlank()) return
        val videoTag = Jsoup.parse(htmlResponse).selectFirst("[id$=\"-player\"]") ?: return
        val fileId = videoTag.attr("data-id")
        if (fileId.isEmpty()) return

        val tripleRegex = Regex("""\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b.*?\b([a-zA-Z0-9]{16})\b""", RegexOption.DOT_MATCHES_ALL)
        val singleRegex = Regex("""\b[a-zA-Z0-9]{48}\b""")
        val tripleMatch = tripleRegex.find(htmlResponse)
        val singleMatch = singleRegex.find(htmlResponse)
        val nonce = if (tripleMatch != null) {
            tripleMatch.groupValues[1] + tripleMatch.groupValues[2] + tripleMatch.groupValues[3]
        } else {
            singleMatch?.value ?: return
        }

        val apiUrl = "$resolvedBase/embed-1/v3/e-1/getSources?id=$fileId&_k=$nonce"
        val jsonResponse = mfCfGetText(apiUrl, headers)
        val sourcesArray = runCatching { JSONObject(jsonResponse).optJSONArray("sources") }.getOrNull() ?: return
        if (sourcesArray.length() == 0) return

        val m3u8 = sourcesArray.optJSONObject(0)?.optString("file")?.trim().orEmpty()
        if (!m3u8.startsWith("http") || mfIsBrokenContainerUrl(m3u8)) return

        generateM3u8(name, m3u8, resolvedBase, headers = mainHeaders).forEach(callback)
    }
}

class Gofile : ExtractorApi() {
    override val name = "Gofile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false
    private val mainApi = "https://api.gofile.io"
    private val browserLanguage = "en-GB"
    private val secret = "12af056dacea0b"
    private val userAgent = "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/138.0.0.0 Safari/537.36"

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val id = Regex("/(?:\\?c=|d/)([\\da-zA-Z-]+)").find(url)?.groupValues?.get(1) ?: return
        val defaultHeaders = mapOf("User-Agent" to userAgent, "Referer" to "$mainUrl/", "Origin" to mainUrl)
        val accRaw = mfCfPostText("$mainApi/accounts", headers = defaultHeaders)
        val token = runCatching { JSONObject(accRaw).getJSONObject("data").getString("token") }.getOrDefault("")
        if (token.isBlank()) return
        val hashedToken = generateWebsiteToken(userAgent, token)
        val headers = mapOf(
            "Referer" to "$mainUrl/",
            "User-Agent" to userAgent,
            "Authorization" to "Bearer $token",
            "X-BL" to browserLanguage,
            "X-Website-Token" to hashedToken
        )

        val parsedResponse = mfCfGetText(
            "$mainApi/contents/$id?page=1&pageSize=100&sortField=name&sortDirection=1",
            headers = headers
        )
        val children = runCatching { JSONObject(parsedResponse).getJSONObject("data").optJSONObject("children") }.getOrNull() ?: return
        val names = children.names() ?: return
        for (i in 0 until names.length()) {
            val f = children.optJSONObject(names.optString(i)) ?: continue
            val fileLink = f.optString("link").trim()
            if (!fileLink.startsWith("http") || f.optString("type") != "file" || mfIsBrokenContainerUrl(fileLink)) continue
            val fileName = f.optString("name")
            val size = f.optLong("size", 0L)
            callback.invoke(
                newExtractorLink("Gofile", "[Gofile] $fileName [${mfFormatBytes(size)}]", fileLink, ExtractorLinkType.VIDEO) {
                    this.quality = mfIndexQuality(fileName)
                    this.headers = mapOf("Cookie" to "accountToken=$token")
                }
            )
        }
    }

    private fun generateWebsiteToken(userAgent: String, accountToken: String): String {
        val timeSlot = System.currentTimeMillis() / 1000 / 14400
        return sha256("$userAgent::$browserLanguage::$accountToken::$timeSlot::$secret")
    }

    private fun sha256(input: String): String =
        MessageDigest.getInstance("SHA-256").digest(input.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
}

class Wootly : ExtractorApi() {
    override var name = "Wootly"
    override var mainUrl = "https://www.wootly.ch"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageHtml = mfCfGetText(url, referer = referer)
        val iframe = Jsoup.parse(pageHtml).selectFirst("iframe")?.attr("src") ?: return
        val iframeHtml = mfCfPostText(iframe, headers = mapOf("Referer" to url), data = mapOf("qdfx" to "1"))
        val vd = Regex("""var\s+vd\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
        val tk = Regex("""tk\s*=\s*["']([^"']+)["']""").find(iframeHtml)?.groupValues?.get(1)
        if (vd.isNullOrBlank() || tk.isNullOrBlank()) return

        val streamUrl = mfCfGetText("https://web.wootly.ch/grabm?t=$tk&id=$vd", headers = mapOf("Referer" to iframe)).trim()
        if (streamUrl.isBlank() || !streamUrl.startsWith("http") || mfIsBrokenContainerUrl(streamUrl)) return

        callback.invoke(newExtractorLink(this.name, this.name, streamUrl, ExtractorLinkType.VIDEO) { this.referer = referer ?: "" })
    }
}

class GDLink : GDFlix() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : GDFlix() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : GDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override var mainUrl = "https://*.gdflix.*" }
class GDFlixNet : GDFlix() { override var mainUrl = "https://new18.gdflix.*" }
class GDFlix15 : GDFlix() { override var mainUrl = "https://new15.gdflix.*" }

open class GDFlix : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = mfBaseUrl(url)
        val latestBaseUrl = mfLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val document = Jsoup.parse(mfCfGetText(newUrl))
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ").orEmpty()
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ").orEmpty()
        val quality = mfIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http") || mfIsBrokenContainerUrl(link)) return
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${fileName}[${fileSize}]", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
            })
        }

        document.select("div.text-center a").mfSafeAmap { anchor ->
            val text = anchor.select("a").text()
            val link = anchor.attr("href")
            when {
                text.contains("FSL V2") -> myCallback(link, "[FSL V2]")
                text.contains("DIRECT DL") -> myCallback(link, "[Direct]")
                text.contains("DIRECT SERVER") -> myCallback(link, "[Direct]")
                text.contains("CLOUD DOWNLOAD [R2]") -> myCallback(link, "[Cloud]")
                text.contains("GD Index") -> {
                    listOf(1, 2).mfSafeAmap { cfType ->
                        Jsoup.parse(mfCfGetText("$baseUrl$link?type=$cfType")).select("a.btn-success").mfSafeAmap { cfBtn ->
                            myCallback(cfBtn.attr("href"), "[CF]")
                        }
                    }
                }
                text.contains("FAST CLOUD") -> {
                    val dlink = Jsoup.parse(mfCfGetText(baseUrl + link)).select("div.card-body a").attr("href")
                    if (dlink.isNotBlank()) myCallback(dlink, "[FAST CLOUD]")
                }
                link.contains("pixeldra") -> {
                    val finalURL = if (link.contains("download", true)) link
                    else "https://pixeldrain.dev/api/file/${link.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("GoFile") -> {
                    runCatching {
                        Jsoup.parse(mfCfGetText(link)).select(".row .row a").mfSafeAmap { gofileAnchor ->
                            val gLink = gofileAnchor.attr("href")
                            if (gLink.contains("gofile")) loadExtractor(gLink, "", subtitleCallback, callback)
                        }
                    }
                }
            }
        }
    }
}

internal object PlayDirectStub {
    suspend fun resolve(url: String, referer: String?): String? = try {
        val text = mfCfGetText(url, headers = mapOf("User-Agent" to USER_AGENT), referer = referer ?: "https://nexdrive.fit/", timeoutSec = 20L)
        val embedded = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
            ?: Regex("""'(https://hubcdn[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
            ?: return null
        val wrapped = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(embedded)?.groupValues?.get(1)
        val carrier = if (wrapped != null) {
            val padded = if (wrapped.length % 4 > 0) wrapped + "=".repeat(4 - wrapped.length % 4) else wrapped
            runCatching { base64Decode(padded) }.getOrNull() ?: return null
        } else {
            embedded
        }
        val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return null
        val direct = URLDecoder.decode(encoded, "UTF-8")
        direct.takeIf { it.startsWith("http") }
    } catch (_: Exception) {
        null
    }
}

class FastDlExtractor : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer)
        if (!direct.isNullOrBlank() && !mfIsBrokenContainerUrl(direct)) {
            callback.invoke(newExtractorLink("G-Direct", "G-Direct", direct, ExtractorLinkType.VIDEO) {
                this.headers = mapOf("Referer" to "$mainUrl/")
            })
            return
        }
        val loc = app.get(url, allowRedirects = false).headers["location"]
        if (!loc.isNullOrBlank()) loadExtractor(loc, "", subtitleCallback, callback)
    }
}

open class fastdlserver : ExtractorApi() {
    override val name = "fastdlserver"
    override var mainUrl = "https://fastdlserver.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val direct = PlayDirectStub.resolve(url, referer)
        if (!direct.isNullOrBlank() && !mfIsBrokenContainerUrl(direct)) {
            callback.invoke(newExtractorLink("G-Direct", "G-Direct", direct, ExtractorLinkType.VIDEO) {
                this.headers = mapOf("Referer" to mfBaseUrl(url) + "/")
            })
            return
        }
        val location = app.get(url, allowRedirects = false).headers["location"]
        if (!location.isNullOrBlank()) loadExtractor(location, "", subtitleCallback, callback)
    }
}

class Linksmod : ExtractorApi() {
    override val name = "Linksmod"
    override var mainUrl = "https://linksmod.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = Jsoup.parse(mfCfGetText(url))
        document.select("div .view-well > a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), "", subtitleCallback, callback)
        }
    }
}

class HdStream4u : VidHidePro() { override var mainUrl = "https://hdstream4u.com" }
class Hubstream : VidStack() { override var mainUrl = "https://hubstream.art" }

open class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Jsoup.parse(mfCfGetText(url)).select("div#primary p > a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), "", subtitleCallback, callback)
        }
    }
}

open class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val href = Jsoup.parse(mfCfGetText(url)).select(".btn.btn-primary.btn-user.btn-success1.m-1").attr("href")
        if (href.isNotBlank()) loadExtractor(href, "", subtitleCallback, callback)
    }
}

class Driveseed : Driveleech() {
    override val name: String = "Driveseed"
    override val mainUrl: String = "https://driveseed.*"
}

open class Driveleech : ExtractorApi() {
    override val name: String = "Driveleech"
    override val mainUrl: String = "https://driveleech.*"
    override val requiresReferer = false

    private suspend fun CFType(url: String): List<String> {
        val downloadLinks = mutableListOf<String>()
        listOf("1", "2").forEach { t ->
            downloadLinks.addAll(Jsoup.parse(mfCfGetText("$url?type=$t")).select("a.btn-success").mapNotNull { it.attr("href") })
        }
        return downloadLinks
    }

    private suspend fun resumeCloudLink(baseUrl: String, url: String): String? =
        Jsoup.parse(mfCfGetText(baseUrl + url)).selectFirst("a.btn-success")?.attr("href")

    private suspend fun resumeBot(url: String): String {
        val resumeBotResponse = app.get(url)
        val resumeBotDoc = resumeBotResponse.document.toString()
        val ssid = resumeBotResponse.cookies["PHPSESSID"]
        val resumeBotToken = Regex("formData\\.append\\('token', '([a-f0-9]+)'\\)").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotPath = Regex("fetch\\('/download\\?id=([a-zA-Z0-9/+]+)'").find(resumeBotDoc)?.groups?.get(1)?.value
        val resumeBotBaseUrl = url.split("/download")[0]
        val jsonResponse = mfCfPostText(
            url = "$resumeBotBaseUrl/download?id=$resumeBotPath",
            data = mapOf("token" to "$resumeBotToken"),
            headers = mapOf("Accept" to "*/*", "Origin" to resumeBotBaseUrl, "Sec-Fetch-Site" to "same-origin", "Cookie" to "PHPSESSID=$ssid"),
            referer = url
        )
        return JSONObject(jsonResponse).getString("url")
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val baseUrl = mfBaseUrl(url)
        val document = if (url.contains("r?key=")) {
            val rawFirst = mfCfGetText(url)
            val temp = Jsoup.parse(rawFirst).selectFirst("script")?.data()
                ?.substringAfter("replace(\"")?.substringBefore("\")").orEmpty()
            Jsoup.parse(mfCfGetText(baseUrl + temp))
        } else {
            Jsoup.parse(mfCfGetText(url))
        }

        val fileName = document.select("ul > li.list-group-item:contains(Name)").text().substringAfter("Name : ")
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text().substringAfter("Size : ")
        val quality = mfIndexQuality(fileName)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http") || mfIsBrokenContainerUrl(link)) return
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${fileName}[${fileSize}]", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
            })
        }

        document.select("div.text-center > a").mfSafeAmap { element ->
            val text = element.text()
            val href = element.attr("href")
            when {
                text.contains("Cloud Download") -> myCallback(href, "[Cloud]")
                text.contains("Resume Worker Bot") -> runCatching { myCallback(resumeBot(href), "[ResumeBot]") }
                text.contains("Direct Links") -> runCatching { CFType(baseUrl + href).forEach { myCallback(it, "[CF]") } }
                text.contains("Resume Cloud") -> runCatching { resumeCloudLink(baseUrl, href)?.let { myCallback(it, "[ResumeCloud]") } }
                text.contains("gofile") -> loadExtractor(href, "", subtitleCallback, callback)
            }
        }
    }
}

class Howblogs : ExtractorApi() {
    override val name: String = "Howblogs"
    override val mainUrl: String = "https://howblogs.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        Jsoup.parse(mfCfGetText(url, referer = referer)).select("div.center_it a").mfSafeAmap { item ->
            loadExtractor(item.attr("href"), referer, subtitleCallback, callback)
        }
    }
}

class VCloud : HubCloud() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
}

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? =
        Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(html)?.groupValues?.get(1)

    fun extractDoubleAtob(html: String): String? =
        Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(html)?.groupValues?.get(1)?.let { base64Decode(base64Decode(it)) }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = mfBaseUrl(url)
        val latestBaseUrl = if (url.contains("hubcloud")) mfLatestBaseUrl(baseUrl, "hubcloud") else mfLatestBaseUrl(baseUrl, "vcloud")
        var newUrl = url
        if (baseUrl != latestBaseUrl) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val rawFirst = mfCfGetText(newUrl)
        val doc = Jsoup.parse(rawFirst)
        var link = if (newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href").orEmpty()
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString().orEmpty()
            if (newUrl.contains("vcloud")) extractDoubleAtob(scriptTag).orEmpty()
            else Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1).orEmpty()
        }
        if (link.isBlank()) return
        if (!link.startsWith("https://")) link = baseUrl + link

        val rawSecond = mfCfGetText(link)
        val document = Jsoup.parse(rawSecond)
        val header = document.select("div.card-header").text()
        val size = document.select("i#size").text()
        val quality = mfIndexQuality(header)

        suspend fun myCallback(link: String, server: String = "") {
            if (!link.startsWith("http") || mfIsBrokenContainerUrl(link)) return
            callback.invoke(newExtractorLink("${name}${server}", "${name}${server} ${header}[${size}]", link, ExtractorLinkType.VIDEO) {
                this.quality = quality
            })
        }

        document.select("h2 a.btn").mfSafeAmap { btn ->
            val btnLink = btn.attr("href")
            val text = btn.text()
            when {
                text.contains("FSL Server") -> myCallback(btnLink, "[FSL Server]")
                text.contains("FSLv2") -> myCallback(btnLink, "[FSLv2 Server]")
                text.contains("Mega Server") -> myCallback(btnLink, "[Mega Server]")
                text.contains("Download File") -> myCallback(btnLink)
                btnLink.contains("pixeldra") -> {
                    val pixelLink = extractPxlUrl(rawSecond) ?: return@mfSafeAmap
                    val finalURL = if (pixelLink.contains("download", true)) pixelLink
                    else "https://pixeldrain.dev/api/file/${pixelLink.substringAfterLast("/")}?download"
                    myCallback(finalURL, "[Pixeldrain]")
                }
                text.contains("Buzz Server") -> {
                    val redirectUrl = Jsoup.parse(mfCfGetText(btnLink)).selectFirst(".download-btn")?.attr("href") ?: return@mfSafeAmap
                    myCallback(mfBaseUrl(btnLink) + redirectUrl, "[Buzz Server]")
                }
                text.contains("Gofile") -> loadExtractor(btnLink, "", subtitleCallback, callback)
            }
        }
    }
}

open class PixeldrainExtractor : ExtractorApi() {
    override var name = "Pixeldrain"
    override var mainUrl = "https://pixeldrain.com"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val fileId = Regex("""pixeldrain\.[a-z]+/(?:u|api/file|l)/([a-zA-Z0-9_-]+)""", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1) ?: return
        val directUrl = "https://pixeldrain.dev/api/file/$fileId"
        if (mfIsBrokenContainerUrl(directUrl)) return
        callback.invoke(newExtractorLink(source = name, name = name, url = directUrl, type = ExtractorLinkType.VIDEO) {
            this.referer = "https://pixeldrain.dev/"
            this.quality = Qualities.P1080.value
        })
    }
}

class PixeldrainDevExtractor : PixeldrainExtractor() { override var mainUrl = "https://pixeldrain.dev" }

open class VoeExtractor : ExtractorApi() {
    override var name = "Voe"
    override var mainUrl = "https://voe.sx"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = mfCfGetText(url, referer = referer ?: "$mainUrl/")
        val hlsB64 = Regex("""['"]hls['"]\s*:\s*['"]([A-Za-z0-9+/=]+)['"]""").find(html)?.groupValues?.get(1)
        if (!hlsB64.isNullOrBlank()) {
            val decoded = runCatching { base64Decode(hlsB64).trim() }.getOrNull().orEmpty()
            if (decoded.startsWith("http") && !mfIsBrokenContainerUrl(decoded)) {
                callback.invoke(newExtractorLink(name, "$name HLS", decoded, ExtractorLinkType.M3U8) {
                    this.referer = url
                    this.quality = Qualities.P1080.value
                })
                return
            }
        }
        val m3u8 = Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").find(html)?.groupValues?.get(1).orEmpty()
        if (m3u8.startsWith("http") && !mfIsBrokenContainerUrl(m3u8)) {
            generateM3u8(name, m3u8, url).forEach(callback)
        }
    }
}

open class StreamTapeExtractor : ExtractorApi() {
    override var name = "StreamTape"
    override var mainUrl = "https://streamtape.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = mfCfGetText(url.replace("/v/", "/e/"), referer = referer ?: "$mainUrl/")
        val m = Regex("""document\.getElementById\(['"](?:no)?robotlink['"]\)\.innerHTML\s*=\s*["']([^"']+)["']\s*\+\s*\(?["']([^"']+)["']\)?\.substring\((\d+)\)""")
            .find(html) ?: return
        val sub = m.groupValues[3].toIntOrNull() ?: 0
        val p2 = if (sub > 0 && m.groupValues[2].length > sub) m.groupValues[2].substring(sub) else m.groupValues[2]
        var finalUrl = m.groupValues[1] + p2
        if (finalUrl.startsWith("//")) finalUrl = "https:$finalUrl"
        if (finalUrl.startsWith("http") && !mfIsBrokenContainerUrl(finalUrl)) {
            callback.invoke(newExtractorLink(name, name, finalUrl, ExtractorLinkType.VIDEO) {
                this.referer = url
                this.quality = Qualities.P1080.value
            })
        }
    }
}

open class NxStreamHubExtractor : ExtractorApi() {
    override var name = "NxStreamHub"
    override var mainUrl = "https://proxy.nxstreamhub.workers.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = mfCfGetText(url, referer = referer ?: "$mainUrl/")
        val hls = Regex("""https?://[^\s"'<>\\]+\.m3u8(?:\?[^\s"'<>\\]*)?""").find(html)?.value.orEmpty()
        if (hls.startsWith("http") && !mfIsBrokenContainerUrl(hls)) {
            generateM3u8(name, hls, url).forEach(callback)
        }
    }
}

class NitroxExtractor : NxStreamHubExtractor() {
    override var name = "Nitrox"
    override var mainUrl = "https://wts.itsnitrox.tech"
}

class VibuxerExtractor : NxStreamHubExtractor() {
    override var name = "Vibuxer"
    override var mainUrl = "https://vibuxer.com"
}

class Vivibebe : VibePlayer() { override var mainUrl = "https://vivibebe.site" }
class Bibiemb : VibePlayer() { override var mainUrl = "https://bibiemb.xyz" }

open class VibePlayer : ExtractorApi() {
    override val name = "VibePlayer"
    override val mainUrl = "https://vibeplayer.site"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val response = mfCfGetText(url, referer = referer)
        val videoUrl = Regex("""const\s+src\s*=\s*["']([^"']+)["']""").find(response)?.groups?.get(1)?.value ?: return
        if (!mfIsBrokenContainerUrl(videoUrl)) generateM3u8(name, videoUrl, "$mainUrl/").forEach(callback)
    }
}

class Playmogo : DoodLaExtractor() { override var mainUrl = "https://playmogo.com" }
class Otakuvid : VidHidePro() { override var mainUrl = "https://otakuvid.online" }
class Otakuhg : VidHidePro() { override var mainUrl = "https://otakuhg.site" }
class Minochinos : VidHidePro() { override var mainUrl = "https://minochinos.com" }

class Vidwish : MegaPlay() { override val name = "Vidwish"; override val mainUrl = "https://vidwish.live" }
class Vidtube : MegaPlay() { override val name = "Vidtube"; override val mainUrl = "https://vidtube.site" }

open class MegaPlay : ExtractorApi() {
    override val name = "MegaPlay"
    override val mainUrl = "https://megaplay.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = mfCfGetText(url, headers = mapOf("User-Agent" to USER_AGENT), referer = referer ?: "$mainUrl/")
        val doc = Jsoup.parse(html)
        val streamId = doc.selectFirst("#megaplay-player")?.attr("data-id") ?: return
        val type = if (url.contains("/dub", ignoreCase = true)) "dub" else "sub"
        val jsonText = mfCfGetText("$mainUrl/stream/getSources?id=$streamId&type=$type", referer = url)
        val m3u8 = runCatching { JSONObject(jsonText).optJSONObject("sources")?.optString("file")?.trim().orEmpty() }.getOrDefault("")
        if (m3u8.startsWith("http") && !mfIsBrokenContainerUrl(m3u8)) {
            generateM3u8(name, m3u8, mainUrl).forEach(callback)
        }
    }
}

open class Modiplay : ExtractorApi() {
    override val name = "Modiplay"
    override val mainUrl = "https://rozgarlelo.modiplay.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val html = mfCfGetText(url)
        val urls = Regex("""switchServer\(['"](https?:\/\/[^'"]+)""").findAll(html).map { it.groupValues[1] }.toList()
        urls.mfSafeAmap { embed -> loadExtractor(embed, "", subtitleCallback, callback) }
    }
}

class Iqsmartgames : GDMirrorbot() {
    override var name = "Iqsmartgames"
    override var mainUrl = "https://streams.iqsmartgames.com"
    override var requiresReferer = true
}

