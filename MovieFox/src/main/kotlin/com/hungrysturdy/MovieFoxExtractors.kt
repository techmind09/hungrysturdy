package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import kotlinx.coroutines.delay
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import java.net.URI
import java.net.URLDecoder
import java.util.concurrent.ConcurrentHashMap

internal object ExtractorUtils {
    const val UA =
        "Mozilla/5.0 (Linux; Android 15; Pixel 9) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    val cfKiller by lazy { CloudflareKiller() }

    private val dynamicUrlCache = ConcurrentHashMap<String, String>()
    private var dynamicUrlsLoaded = false

    val junkRegex = Regex(
        """(?i)tinyurl|t\.me|telegram|/tg/|winexch|a-ads|snvhost|one\.one\.one\.one|google\.com/search|hubcloud\.fans|drive/admin"""
    )

    fun headers(url: String, referer: String? = null): Map<String, String> {
        val ctx = CloudStreamApp.context
        val savedCookie = MovieFoxSettingsDialog.getCookieForDomain(ctx, url)
        val out = mutableMapOf<String, String>()
        out["User-Agent"] = if (!savedCookie.isNullOrBlank()) MovieFoxSettingsDialog.CF_BYPASS_UA else UA
        if (!savedCookie.isNullOrBlank()) out["Cookie"] = savedCookie
        if (!referer.isNullOrBlank()) out["Referer"] = referer
        return out
    }

    fun getBaseUrl(url: String): String = try {
        URI(url).let { "${it.scheme}://${it.host}" }
    } catch (_: Exception) {
        url.substringBefore("/", url)
    }

    fun absolute(href: String, base: String): String {
        val clean = href.replace("\\/", "/").trim()
        return when {
            clean.startsWith("http://") || clean.startsWith("https://") -> clean
            clean.startsWith("//") -> "https:$clean"
            else -> "${base.trimEnd('/')}/${clean.trimStart('/')}"
        }
    }

    fun getIndexQuality(str: String?): Int {
        if (str.isNullOrBlank()) return Qualities.Unknown.value
        Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let { return it }
        val low = str.lowercase()
        return when {
            low.contains("8k") -> 4320
            low.contains("4k") -> 2160
            low.contains("2k") -> 1440
            else -> Qualities.P1080.value
        }
    }

    suspend fun getLatestBaseUrl(baseUrl: String, source: String): String {
        if (!dynamicUrlsLoaded) {
            dynamicUrlsLoaded = true
            runCatching {
                val map = app.get(
                    "https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json",
                    timeout = 5L
                ).parsedSafe>()
                map?.forEach { (k, v) -> if (v.isNotBlank()) dynamicUrlCache[k] = v }
            }
        }
        return dynamicUrlCache[source]?.takeIf { it.isNotBlank() } ?: baseUrl
    }

    suspend fun resolveFinalUrl(startUrl: String, referer: String? = null, maxRedirects: Int = 7): String? {
        var currentUrl = startUrl
        repeat(maxRedirects) {
            val res = runCatching {
                app.head(currentUrl, headers = headers(currentUrl, referer), allowRedirects = false, timeout = 6L)
            }.getOrNull() ?: return currentUrl
            if (res.code in 300..399) {
                val loc = res.headers["location"] ?: res.headers["Location"]
                if (loc.isNullOrBlank()) return currentUrl
                currentUrl = absolute(loc, getBaseUrl(currentUrl))
            } else {
                return if (res.code in 200..299) currentUrl else null
            }
        }
        return currentUrl
    }

    fun pixelApiUrl(pageHtml: String, buttonHref: String): String? {
        val pxl = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""").find(pageHtml)?.groupValues?.get(1)
        val link = pxl?.takeIf { it.startsWith("http") } ?: buttonHref
        if (!link.startsWith("http")) return null
        if (link.contains("download", true)) return link
        val id = link.substringBefore("?").substringBefore("#").substringAfterLast("/")
        if (id.isBlank()) return null
        return "${getBaseUrl(link)}/api/file/$id?download"
    }

    fun extractDoubleAtob(text: String): String? {
        val raw = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
            .find(text)?.groupValues?.get(1) ?: return null
        return runCatching { base64Decode(base64Decode(raw)) }.getOrNull()
    }
}

// =========================================================
//  HUBCLOUD (FSL, FSLv2, PixelDrain, Buzz, 10Gbps, Instant, GoFile)
// =========================================================

open class HubCloud : ExtractorApi() {
    override val name: String = "Hub-Cloud"
    override val mainUrl: String = "https://hubcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        var baseUrl = ExtractorUtils.getBaseUrl(url)
        val latest = ExtractorUtils.getLatestBaseUrl(baseUrl, if (url.contains("vcloud", true)) "vcloud" else "hubcloud")
        var newUrl = url
        if (baseUrl != latest) {
            newUrl = url.replace(baseUrl, latest)
            baseUrl = latest
        }

        val res = runCatching {
            app.get(newUrl, headers = ExtractorUtils.headers(newUrl, referer), interceptor = ExtractorUtils.cfKiller, timeout = 12L)
        }.getOrNull() ?: return

        val doc = res.document
        var link = if (newUrl.contains("/video/", true)) {
            doc.selectFirst("div.vd > center > a")?.attr("href").orEmpty()
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString().orEmpty()
            if (newUrl.contains("vcloud", true)) {
                ExtractorUtils.extractDoubleAtob(scriptTag).orEmpty()
            } else {
                Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(scriptTag)?.groupValues?.get(1).orEmpty()
            }
        }

        if (link.isBlank()) {
            val gen = doc.select("a.btn, a[download]").firstOrNull { it.text().contains("generate", true) }?.attr("href").orEmpty()
            if (gen.isNotBlank()) link = gen
        }

        val targetDoc = if (link.isNotBlank()) {
            val absLink = ExtractorUtils.absolute(link, baseUrl)
            runCatching {
                app.get(absLink, headers = ExtractorUtils.headers(absLink, newUrl), interceptor = ExtractorUtils.cfKiller, timeout = 12L).document
            }.getOrNull() ?: doc
        } else {
            doc
        }

        emitHubServers(targetDoc, baseUrl, newUrl, seen, subtitleCallback, callback)
    }

    companion object {
        suspend fun emitHubServers(
            document: Document,
            baseUrl: String,
            pageUrl: String,
            seen: MutableSet<String>,
            subtitleCallback: (SubtitleFile) -> Unit,
            callback: (ExtractorLink) -> Unit
        ): Boolean {
            val header = document.select("div.card-header").text().trim()
            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(header)) return false
            val size = document.select("i#size").text().trim()
            val quality = ExtractorUtils.getIndexQuality(header)
            val extras = listOf(header, size).filter { it.isNotBlank() }.joinToString(" ")
            var anyEmitted = false

            suspend fun emitUnique(link: String, serverTag: String) {
                val clean = link.trim()
                if (!clean.startsWith("http") || ExtractorUtils.junkRegex.containsMatchIn(clean) || !seen.add(clean)) return
                val label = if (extras.isNotBlank()) "HubCloud $serverTag [$extras]" else "HubCloud $serverTag"
                callback(
                    newExtractorLink("Hub-Cloud$serverTag", label, clean, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                        this.headers = mapOf("User-Agent" to ExtractorUtils.UA)
                    }
                )
                anyEmitted = true
            }

            document.select("h2 a.btn, a.btn[href], a[download][href]").amap { el ->
                val rawHref = el.attr("href").trim()
                val text = el.text().trim()
                if (rawHref.isBlank() || ExtractorUtils.junkRegex.containsMatchIn(rawHref)) return@amap
                val abs = ExtractorUtils.absolute(rawHref, baseUrl)
                val low = text.lowercase()

                when {
                    low.contains("fslv2") || low.contains("fsl v2") -> emitUnique(abs, "[FSLv2]")
                    low.contains("fsl") || abs.contains(".r2.dev") || abs.contains("cloudflarestorage.com") -> emitUnique(abs, "[FSL]")
                    low.contains("mega server") -> emitUnique(abs, "[Mega]")
                    low.contains("s3 server") || low.contains("pdl") -> emitUnique(abs, "[$text]")
                    low.contains("download file") -> emitUnique(abs, "[Direct]")
                    low.contains("buzzserver") || abs.contains("fuckingfast") -> {
                        runCatching {
                            val dlink = app.get("$abs/download", referer = abs, allowRedirects = false, timeout = 10L)
                                .headers["hx-redirect"].orEmpty()
                            if (dlink.isNotBlank()) {
                                emitUnique(ExtractorUtils.absolute(dlink, ExtractorUtils.getBaseUrl(abs)), "[BuzzServer]")
                            }
                        }
                    }
                    low.contains("pixeldra") || low.contains("pixelserver") || low.contains("pixel server") || abs.contains("pixeldra") -> {
                        ExtractorUtils.pixelApiUrl(document.toString(), abs)?.let { emitUnique(it, "[Pixeldrain]") }
                    }
                    low.contains("10gbps") -> {
                        var redirectUrl = ExtractorUtils.resolveFinalUrl(abs, pageUrl) ?: return@amap
                        if (redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                        emitUnique(redirectUrl, "[10Gbps]")
                    }
                    low.contains("instant dl") || low.contains("instant download") -> {
                        runCatching {
                            val loc = app.get(abs, headers = ExtractorUtils.headers(abs, pageUrl), allowRedirects = false, timeout = 8L)
                                .headers["location"].orEmpty()
                            val direct = when {
                                loc.contains("url=") -> loc.substringAfter("url=")
                                loc.contains("link=") -> loc.substringAfter("link=")
                                else -> loc
                            }
                            if (direct.startsWith("http")) emitUnique(direct, "[Instant]")
                        }
                    }
                    low.contains("gofile") || abs.contains("gofile.io") -> {
                        Gofile().getUrl(abs, pageUrl, subtitleCallback) { ex ->
                            if (seen.add(ex.url)) {
                                anyEmitted = true
                                callback(ex)
                            }
                        }
                    }
                }
            }
            return anyEmitted
        }
    }
}

// =========================================================
//  VCLOUD
// =========================================================

class VCloud : ExtractorApi() {
    override val name = "V-Cloud"
    override val mainUrl = "https://vcloud.*"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        val res = runCatching {
            app.get(url, headers = ExtractorUtils.headers(url, referer), interceptor = ExtractorUtils.cfKiller, timeout = 15L)
        }.getOrNull() ?: return

        val doc = res.document
        val base = ExtractorUtils.getBaseUrl(res.url)
        var targetDoc = doc
        var link = extractVCloudLink(doc)

        if (link.isNullOrBlank()) {
            val hop = doc.selectFirst("div.main h4 a")?.attr("href")?.trim()
            if (!hop.isNullOrBlank()) {
                val hopUrl = ExtractorUtils.absolute(hop, base)
                runCatching {
                    app.get(hopUrl, headers = ExtractorUtils.headers(hopUrl, base), interceptor = ExtractorUtils.cfKiller, timeout = 15L)
                }.getOrNull()?.let {
                    targetDoc = it.document
                    link = extractVCloudLink(targetDoc)
                }
            }
        }
        if (link.isNullOrBlank() && res.url.contains("/video/")) {
            link = targetDoc.selectFirst("div.vd > center > a")?.attr("href")?.trim()
        }
        if (link.isNullOrBlank()) return

        val abs = ExtractorUtils.absolute(link!!, base)
        val finalRes = runCatching {
            app.get(abs, headers = ExtractorUtils.headers(abs, base), interceptor = ExtractorUtils.cfKiller, timeout = 15L)
        }.getOrNull() ?: return

        val emitted = HubCloud.emitHubServers(finalRes.document, ExtractorUtils.getBaseUrl(abs), abs, seen, subtitleCallback, callback)
        if (!emitted && (abs.contains("drive.google.com") || abs.contains("googleusercontent")) && seen.add(abs)) {
            callback(newExtractorLink(name, name, abs, ExtractorLinkType.VIDEO) { this.referer = "$base/" })
        }
    }

    private fun extractVCloudLink(doc: Document): String? {
        val script = doc.selectFirst("script:containsData(url)")?.data().orEmpty()
        ExtractorUtils.extractDoubleAtob(script)?.takeIf { it.startsWith("http") }?.let { return it }
        Regex("""var\s+url\s*=\s*['"]([^'"]*)['"]""").find(script)?.groupValues?.get(1)
            ?.takeIf { it.startsWith("http") }?.let { return it }
        return doc.selectFirst("div.card-body h2 a.btn[href]")?.attr("href")?.trim()?.takeIf { it.startsWith("http") }
    }
}

// =========================================================
//  GDFLIX + MIRRORS (DriveBot, Fast Cloud, GD Index, Instant, FSL, PixelDrain)
// =========================================================

class GDLink : GDFlix() { override var mainUrl = "https://gdlink.*" }
class GDFlixApp : GDFlix() { override var mainUrl = "https://new.gdflix.*" }
class GdFlix1 : GDFlix() { override var mainUrl = "https://new1.gdflix.*" }
class GdFlix2 : GDFlix() { override var mainUrl = "https://*.gdflix.*" }
class GDFlixNet : GDFlix() { override var mainUrl = "https://new15.gdflix.*" }

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
        val seen = ConcurrentHashMap.newKeySet<String>()
        var baseUrl = ExtractorUtils.getBaseUrl(url)
        val latest = ExtractorUtils.getLatestBaseUrl(baseUrl, "gdflix")
        var newUrl = url
        if (baseUrl != latest) {
            newUrl = url.replace(baseUrl, latest)
            baseUrl = latest
        }

        val res = runCatching {
            app.get(newUrl, headers = ExtractorUtils.headers(newUrl, referer), interceptor = ExtractorUtils.cfKiller, timeout = 12L)
        }.getOrNull() ?: return

        val document = res.document
        val fileName = document.select("ul > li.list-group-item:contains(Name)").text()
            .substringAfter("Name :").trim()
            .ifBlank { document.title().substringAfter("GDFlix |").trim() }
        val fileSize = document.select("ul > li.list-group-item:contains(Size)").text()
            .substringAfter("Size :").substringBefore("|").trim()
        val quality = ExtractorUtils.getIndexQuality(fileName)

        suspend fun emitUnique(link: String, server: String = "") {
            val clean = link.trim()
            if (!clean.startsWith("http") || ExtractorUtils.junkRegex.containsMatchIn(clean) || !seen.add(clean)) return
            callback(
                newExtractorLink("$name$server", "$name$server $fileName[$fileSize]", clean, ExtractorLinkType.VIDEO) {
                    this.quality = quality
                    this.referer = baseUrl
                }
            )
        }

        document.select("div.text-center a[href], div.mb-4 > a[href], a.btn[href]").amap { anchor ->
            val text = anchor.text().trim()
            val rawLink = anchor.attr("href").trim()
            if (rawLink.isBlank() || rawLink.startsWith("#") || rawLink.startsWith("/login")) return@amap
            val abs = ExtractorUtils.absolute(rawLink, baseUrl)

            when {
                text.contains("FSL V2", true) -> emitUnique(abs, "[FSL V2]")
                text.contains("FSL", true) -> emitUnique(abs, "[FSL]")
                text.contains("DIRECT DL", true) || text.contains("DIRECT SERVER", true) -> emitUnique(abs, "[Direct]")
                text.contains("CLOUD DOWNLOAD", true) -> emitUnique(abs, "[Cloud]")
                text.contains("GD Index", true) -> {
                    listOf(1, 2).amap { cfType ->
                        runCatching {
                            app.get("$abs?type=$cfType", headers = ExtractorUtils.headers(abs, newUrl), timeout = 10L)
                                .document.select("a.btn-success[href]")
                                .amap { emitUnique(it.attr("href"), "[CF]") }
                        }
                    }
                }
                text.contains("FAST CLOUD", true) || text.contains("ZIPDISK", true) -> {
                    runCatching {
                        val dlink = app.get(abs, headers = ExtractorUtils.headers(abs, newUrl), timeout = 10L)
                            .document.selectFirst("div.card-body a[href]")?.attr("href").orEmpty()
                        if (dlink.isNotBlank()) emitUnique(dlink, "[FastCloud]")
                    }
                }
                text.contains("DRIVEBOT", true) || abs.contains("drivebot", true) -> {
                    resolveDriveBot(abs, fileName, fileSize, quality, seen, callback)
                }
                text.contains("10GBPS", true) -> {
                    var target = ExtractorUtils.resolveFinalUrl(abs, newUrl) ?: return@amap
                    if (target.contains("link=")) target = target.substringAfter("link=")
                    emitUnique(target, "[10Gbps]")
                }
                rawLink.contains("pixeldra", true) -> {
                    val finalUrl = if (abs.contains("download", true)) abs
                    else "${ExtractorUtils.getBaseUrl(abs)}/api/file/${abs.substringAfterLast("/")}?download"
                    emitUnique(finalUrl, "[Pixeldrain]")
                }
                text.contains("Instant DL", true) || text.contains("Instant Download", true) -> {
                    runCatching {
                        val loc = app.get(abs, headers = ExtractorUtils.headers(abs, newUrl), allowRedirects = false, timeout = 8L)
                            .headers["location"].orEmpty()
                        val direct = when {
                            loc.contains("url=") -> loc.substringAfter("url=")
                            loc.contains("link=") -> loc.substringAfter("link=")
                            else -> loc
                        }
                        if (direct.startsWith("http")) emitUnique(direct, "[Instant]")
                    }
                }
                text.contains("GoFile", true) || abs.contains("gofile.io", true) -> {
                    runCatching {
                        if (abs.contains("gofile.io", true)) {
                            Gofile().getUrl(abs, newUrl, subtitleCallback) { ex -> if (seen.add(ex.url)) callback(ex) }
                        } else {
                            app.get(abs, headers = ExtractorUtils.headers(abs, newUrl), timeout = 10L)
                                .document.select(".row .row a[href]").amap { g ->
                                    val gUrl = g.attr("href")
                                    if (gUrl.contains("gofile", true)) {
                                        Gofile().getUrl(gUrl, abs, subtitleCallback) { ex -> if (seen.add(ex.url)) callback(ex) }
                                    }
                                }
                        }
                    }
                }
            }
        }
    }

    private suspend fun resolveDriveBot(
        url: String,
        fileName: String,
        sizeText: String,
        quality: Int,
        seen: MutableSet<String>,
        callback: (ExtractorLink) -> Unit
    ) {
        runCatching {
            val page = app.get(url, headers = ExtractorUtils.headers(url), timeout = 12L)
            val body = page.text
            val token = Regex("""formData\.append\('token',\s*'([a-f0-9]+)'""").find(body)?.groupValues?.get(1) ?: return
            val downloadId = Regex("""fetch\('/download\?id=([a-zA-Z0-9/+]+)'""").find(body)?.groupValues?.get(1) ?: return
            val answer = app.post(
                ExtractorUtils.absolute("/download?id=$downloadId", ExtractorUtils.getBaseUrl(page.url)),
                headers = mapOf(
                    "User-Agent" to ExtractorUtils.UA,
                    "Referer" to page.url,
                    "Content-Type" to "application/x-www-form-urlencoded"
                ),
                data = mapOf("token" to token),
                timeout = 12L
            ).text
            val link = Regex(""""url"\s*:\s*"(.*?)"""").find(answer)?.groupValues?.get(1)?.replace("\\", "").orEmpty()
            if (link.startsWith("http") && seen.add(link)) {
                callback(
                    newExtractorLink("GDFlix", "GDFlix [DriveBot] $fileName[$sizeText]", link, ExtractorLinkType.VIDEO) {
                        this.quality = quality
                    }
                )
            }
        }
    }
}

// =========================================================
//  FASTDL / HUBCDN / INSTANTCLOUD / VEGADRIVE / FILEPRESS
// =========================================================

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
        val location = runCatching {
            app.get(url, headers = ExtractorUtils.headers(url, referer), allowRedirects = false, timeout = 8L).headers["location"]
        }.getOrNull()
        if (!location.isNullOrBlank()) {
            loadExtractor(location, url, subtitleCallback, callback)
        }
    }
}

internal object DirectStubResolver {
    suspend fun resolve(url: String, referer: String?): String? = runCatching {
        val text = app.get(url, headers = ExtractorUtils.headers(url, referer ?: "https://nexdrive.fit/"), timeout = 12L).text
        val embedded = Regex("""var\s+reurl\s*=\s*"([^"]+)"""").find(text)?.groupValues?.get(1)
            ?: Regex("""'(https://fastdl[^']*dl\.php\?link=[^']+)'""").find(text)?.groupValues?.get(1)
            ?: return null
        val wrapped = Regex("""[?&]r=([A-Za-z0-9+/=_-]+)""").find(embedded)?.groupValues?.get(1)
        val carrier = if (wrapped != null) {
            val padded = if (wrapped.length % 4 > 0) wrapped + "=".repeat(4 - wrapped.length % 4) else wrapped
            runCatching { base64Decode(padded) }.getOrNull() ?: return null
        } else embedded
        val encoded = Regex("""link=(https?://[^&"']+)""").find(carrier)?.groupValues?.get(1) ?: return null
        URLDecoder.decode(encoded, "UTF-8").takeIf { it.startsWith("http") }
    }.getOrNull()
}

class FastDl : ExtractorApi() {
    override val name = "G-Direct"
    override val mainUrl = "https://fastdl.zip"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val direct = DirectStubResolver.resolve(url, referer) ?: return
        callback(newExtractorLink("G-Direct", "G-Direct", direct, ExtractorLinkType.VIDEO) {
            this.headers = mapOf("Referer" to "https://fastdl.zip/")
        })
    }
}

class HubCdn : ExtractorApi() {
    override val name = "HubCdn"
    override val mainUrl = "https://hubcdn.wiki"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val direct = DirectStubResolver.resolve(url, referer) ?: return
        callback(newExtractorLink("HubCdn", "HubCdn G-Direct", direct, ExtractorLinkType.VIDEO) {
            this.headers = mapOf("Referer" to "https://hubcdn.wiki/")
        })
    }
}

class InstantCloud : ExtractorApi() {
    override val name = "InstantCloud"
    override val mainUrl = "https://instantcloud.org"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        runCatching {
            val h = mapOf("User-Agent" to ExtractorUtils.UA, "Accept" to "application/json", "Referer" to url)
            suspend fun fetchLink(): String? {
                val body = runCatching { app.get("$url/download?json=1", headers = h, timeout = 10L).text }.getOrNull() ?: return null
                return runCatching { JSONObject(body).optString("download_url").trim().takeIf { it.startsWith("http") } }.getOrNull()
            }
            var link = fetchLink()
            if (link == null) {
                repeat(3) {
                    val meta = runCatching { JSONObject(app.get("$url?ajax=1&refresh=1", headers = h, timeout = 10L).text) }.getOrNull() ?: return
                    if (meta.optBoolean("downloadable") || meta.optString("gp_status").equals("VALID", true)) {
                        link = fetchLink()
                        return@repeat
                    }
                    if (meta.optBoolean("needs_poll")) delay(1200L)
                }
            }
            link?.let { dl ->
                callback(newExtractorLink(name, name, dl, ExtractorLinkType.VIDEO) { this.quality = ExtractorUtils.getIndexQuality(url) })
            }
        }
    }
}

class VegaDrive : ExtractorApi() {
    override val name = "V-Drive"
    override val mainUrl = "https://one.vegadrive.app"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val page = app.get(url, headers = ExtractorUtils.headers(url, "https://nexdrive.fit/"), timeout = 12L)
            val base = ExtractorUtils.getBaseUrl(page.url)
            val token = url.substringAfter("/s/").substringBefore("?")
            suspend fun provider(p: String): String? {
                val start = if (p == "skydrop") "$base/go/$token/skydrop" else "$base/d/$token/$p"
                return ExtractorUtils.resolveFinalUrl(start, "$base/")
            }
            provider("skydrop")?.takeIf { it.contains("googleusercontent") && !it.endsWith(".zip", true) && seen.add(it) }?.let {
                callback(newExtractorLink(name, "V-Drive 10Gbps", it, ExtractorLinkType.VIDEO))
            }
            provider("pixeldrain")?.takeIf { it.contains("pixeldrain.com/u/") }?.let { p ->
                val id = p.substringBefore("?").substringBefore("#").substringAfterLast("/")
                val api = "https://pixeldrain.com/api/file/$id"
                if (id.isNotBlank() && seen.add(api)) callback(newExtractorLink(name, "V-Drive Pixeldrain", api, ExtractorLinkType.VIDEO))
            }
            provider("buzzheavier")?.takeIf { it.contains("bzzhr.co") && seen.add(it) }?.let {
                callback(newExtractorLink(name, "V-Drive Buzzheavier", it, ExtractorLinkType.VIDEO))
            }
        }
    }
}

class FilePress : ExtractorApi() {
    override val name = "FilePress"
    override val mainUrl = "https://filebee.xyz"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val id = Regex("""/file/([a-f0-9]{16,40})""").find(url)?.groupValues?.get(1) ?: return
        val seen = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val infoRes = app.get("$mainUrl/api/file/get/$id", headers = ExtractorUtils.headers(mainUrl, "$mainUrl/"), timeout = 10L)
            val info = JSONObject(infoRes.text).optJSONObject("data") ?: return
            if (Regex("""(?i)\.(zip|rar|7z)\s*$""").containsMatchIn(info.optString("name").trim())) return

            suspend fun callDl(endpoint: String, targetId: String, method: String): String? {
                val res = runCatching {
                    app.post(
                        "$mainUrl/api/file/$endpoint/",
                        headers = mapOf("User-Agent" to ExtractorUtils.UA, "Referer" to "$mainUrl/", "Origin" to mainUrl, "Content-Type" to "application/json"),
                        json = mapOf("captchaValue" to "", "id" to targetId, "method" to method),
                        timeout = 15L
                    )
                }.getOrNull() ?: return null
                val parsed = runCatching { JSONObject(res.text) }.getOrNull() ?: return null
                if (!parsed.optBoolean("status")) return null
                return when (val d = parsed.opt("data")) {
                    is String -> d.takeIf { it.isNotBlank() }
                    is JSONArray -> (0 until d.length()).firstNotNullOfOrNull { d.optString(it).takeIf { s -> s.startsWith("http") } }
                    else -> null
                }
            }

            callDl("downlaod", id, "dotFlixDownlaod")?.takeIf { it.startsWith("http") }?.let { shareUrl ->
                resolveDotFlix(shareUrl)?.takeIf { seen.add(it) }?.let {
                    callback(newExtractorLink(name, "FilePress Instant", it, ExtractorLinkType.VIDEO))
                }
            }
            callDl("downlaod", id, "indexDownlaod")?.takeIf { it.matches(Regex("[a-f0-9]{16,40}")) }?.let { task ->
                callDl("downlaod2", task, "indexDownlaod")?.takeIf { it.startsWith("http") && seen.add(it) }?.let {
                    callback(newExtractorLink(name, "FilePress Direct", it, ExtractorLinkType.VIDEO))
                }
            }
        }
    }

    private suspend fun resolveDotFlix(shareUrl: String): String? = runCatching {
        val text = app.get(shareUrl, headers = ExtractorUtils.headers(shareUrl, "https://new2.dotflix.shop/"), timeout = 12L).text
        val code = Regex("""btoa\('([^']+)'\)""").find(text)?.groupValues?.get(1) ?: return null
        val obfuscated = Base64.encodeToString(code.toByteArray(), Base64.NO_WRAP).reversed()
        val reqId = List(13) { "abcdefghijklmnopqrstuvwxyz0123456789".random() }.joinToString("")
        val ts = System.currentTimeMillis().toString()
        val res = app.post(
            "https://dotflix.store/api/extract-download",
            headers = mapOf(
                "User-Agent" to ExtractorUtils.UA,
                "Content-Type" to "application/json",
                "X-Request-ID" to reqId,
                "X-Timestamp" to ts,
                "Origin" to "https://new2.dotflix.shop",
                "Referer" to "https://new2.dotflix.shop/"
            ),
            json = mapOf("requestId" to reqId, "timestamp" to ts, "data" to obfuscated),
            timeout = 12L
        )
        val parsed = JSONObject(res.text)
        if (parsed.optBoolean("success")) parsed.optString("downloadUrl").takeIf { it.startsWith("http") } else null
    }.getOrNull()
}

// =========================================================
//  GOFILE / HBLINKS / HUBDRIVE
// =========================================================

class Gofile : ExtractorApi() {
    override val name = "GoFile"
    override val mainUrl = "https://gofile.io"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val code = url.substringAfterLast("/").substringBefore("#").substringBefore("?")
            if (code.isBlank()) return
            val api = "https://api.gofile.io"
            val token = JSONObject(app.post("$api/accounts", timeout = 10L).text).getJSONObject("data").getString("token")
            val wt = runCatching {
                Regex("""appdata\.wt\s*=\s*["']([^"']+)["']""").find(app.get("$api/dist/js/global.js", timeout = 10L).text)?.groupValues?.get(1)
            }.getOrNull()
            val contentUrl = if (wt.isNullOrBlank()) "$api/contents/$code?wt=$token" else "$api/contents/$code?wt=$wt"
            val children = JSONObject(app.get(contentUrl, headers = mapOf("Authorization" to "Bearer $token"), timeout = 12L).text)
                .getJSONObject("data").optJSONObject("children") ?: return
            for (k in children.keys()) {
                val child = children.optJSONObject(k) ?: continue
                val link = child.optString("link").takeIf { it.startsWith("http") && seen.add(it) } ?: continue
                val fName = child.optString("name")
                val sizeMb = child.optLong("size", 0L).let { if (it > 0) "${it / 1024 / 1024} MB" else "" }
                val inner = listOf(fName.take(55), sizeMb).filter { it.isNotBlank() }.joinToString(" | ")
                callback(
                    newExtractorLink("GoFile", if (inner.isBlank()) "GoFile" else "GoFile [$inner]", link, ExtractorLinkType.VIDEO) {
                        this.quality = ExtractorUtils.getIndexQuality(fName)
                        this.headers = mapOf("Authorization" to "Bearer $token")
                    }
                )
            }
        }
    }
}

class Hblinks : ExtractorApi() {
    override val name = "Hblinks"
    override val mainUrl = "https://hblinks.*"
    override val requiresReferer = true

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val doc = app.get(url, headers = ExtractorUtils.headers(url, referer), interceptor = ExtractorUtils.cfKiller, timeout = 12L).document
            doc.select("div.entry-content a[href]").amap { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && seen.add(href)) {
                    loadExtractor(href, url, subtitleCallback) { ex -> if (seen.add(ex.url)) callback(ex) }
                }
            }
        }
    }
}

class Hubdrive : ExtractorApi() {
    override val name = "Hubdrive"
    override val mainUrl = "https://hubdrive.*"
    override val requiresReferer = false

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val seen = ConcurrentHashMap.newKeySet<String>()
        runCatching {
            val doc = app.get(url, headers = ExtractorUtils.headers(url, referer), interceptor = ExtractorUtils.cfKiller, timeout = 12L).document
            doc.select("div.entry-content a[href], main a[href], a[href*='hubcloud']").amap { a ->
                val href = a.attr("href").trim()
                if (href.startsWith("http") && !href.contains("/tg/") && href.contains("hubcloud", true) && seen.add(href)) {
                    HubCloud().getUrl(href, url, subtitleCallback) { ex -> if (seen.add(ex.url)) callback(ex) }
                }
            }
        }
    }
}

// =========================================================
//  UNIVERSAL GENERIC EXTRACTOR (Fallback for all other hosts)
// =========================================================

open class CineStreamGenericExtractor(
    override val name: String,
    override val mainUrl: String,
    override val requiresReferer: Boolean = false
) : ExtractorApi() {

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cleanUrl = url.replace("\\/", "/").trim()
        if (cleanUrl.isBlank()) return
        val seen = ConcurrentHashMap.newKeySet<String>()

        if (Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm)(\?|$)""").containsMatchIn(cleanUrl) && seen.add(cleanUrl)) {
            val isHls = cleanUrl.contains(".m3u8", true) || cleanUrl.contains(".mpd", true)
            callback(
                newExtractorLink(name, "$name • ${if (isHls) "Stream" else "Direct"}", cleanUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = referer ?: "$mainUrl/"
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("User-Agent" to ExtractorUtils.UA, "Referer" to (referer ?: "$mainUrl/"))
                }
            )
            return
        }

        val html = runCatching {
            app.get(cleanUrl, headers = ExtractorUtils.headers(cleanUrl, referer ?: "$mainUrl/"), interceptor = ExtractorUtils.cfKiller, timeout = 8L).text
        }.getOrNull().orEmpty()
        if (html.isBlank()) return

        val searchBlob = "$html\n${runCatching { getAndUnpack(html) }.getOrDefault("")}"
        val foundUrls = LinkedHashSet<String>()

        Regex("""https?://[^\s"'<>\\)\]]+\.(?:m3u8|mpd|mp4|mkv|webm)(?:\?[^\s"'<>\\)\]]*)?""", RegexOption.IGNORE_CASE)
            .findAll(searchBlob)
            .forEach { foundUrls.add(it.value.replace("\\/", "/")) }

        Jsoup.parse(html, cleanUrl).select("video source[src], a[href]").forEach { el ->
            val raw = el.attr("src").ifBlank { el.attr("href") }.trim()
            if (raw.startsWith("http") && Regex("""(?i)\.(m3u8|mpd|mp4|mkv|webm|r2\.dev|r2\.cloudflarestorage\.com|pixeldrain)""").containsMatchIn(raw)) {
                foundUrls.add(raw)
            }
        }

        foundUrls.forEach { mediaUrl ->
            if (!seen.add(mediaUrl)) return@forEach
            val isHls = mediaUrl.contains(".m3u8", true) || mediaUrl.contains(".mpd", true)
            callback(
                newExtractorLink(name, "$name • ${if (isHls) "HLS" else "MP4"}", mediaUrl, if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.referer = cleanUrl
                    this.quality = Qualities.P1080.value
                    this.headers = mapOf("User-Agent" to ExtractorUtils.UA, "Referer" to cleanUrl)
                }
            )
        }
    }
}

class Driveseed : CineStreamGenericExtractor("Driveseed", "https://driveseed.org")
class Driveleech : CineStreamGenericExtractor("Driveleech", "https://driveleech.org")
class Howblogs : CineStreamGenericExtractor("Howblogs", "https://howblogs.xyz")
class Wootly : CineStreamGenericExtractor("Wootly", "https://wootly.ch")
class Videostr : CineStreamGenericExtractor("Videostr", "https://videostr.net")
class Streameeeeee : CineStreamGenericExtractor("Streameeeeee", "https://streamtape.com")
class PpzjYoutube : CineStreamGenericExtractor("PpzjYoutube", "https://youtube.com")
class Asianload : CineStreamGenericExtractor("Asianload", "https://asianload.io")
class Embedload : CineStreamGenericExtractor("Embedload", "https://embedload.com")
class FlixCloud : CineStreamGenericExtractor("FlixCloud", "https://flixcloud.icu")
class VibePlayer : CineStreamGenericExtractor("VibePlayer", "https://vibeplayer.net")
class Bibiemb : CineStreamGenericExtractor("Bibiemb", "https://bibiemb.com")
class Vivibebe : CineStreamGenericExtractor("Vivibebe", "https://vivibebe.com")
class Playmogo : CineStreamGenericExtractor("Playmogo", "https://playmogo.com")
class Otakuvid : CineStreamGenericExtractor("Otakuvid", "https://otakuvid.com")
class Otakhug : CineStreamGenericExtractor("Otakhug", "https://otakhug.com")
class MegaPlay : CineStreamGenericExtractor("MegaPlay", "https://megaplay.buzz")
class Vidtube : CineStreamGenericExtractor("Vidtube", "https://vidtube.one")
class Vidwish : CineStreamGenericExtractor("Vidwish", "https://vidwish.live")
class Modiplay : CineStreamGenericExtractor("Modiplay", "https://modiplay.xyz")
class Iqsmartgames : CineStreamGenericExtractor("Iqsmartgames", "https://iqsmartgames.com")
class Minochinos : CineStreamGenericExtractor("Minochinos", "https://minochinos.com")
class HdStream4u : CineStreamGenericExtractor("HdStream4u", "https://hdstream4u.com")
class Hubstream : CineStreamGenericExtractor("Hubstream", "https://hubstream.art")
