package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse // FIX 1: Corrected import to nicehttp
import org.json.JSONObject
import java.net.URI
import android.util.Base64

// ==========================================
// 1. Vidhide & Clones
// ==========================================
open class VidhideExtractor : ExtractorApi() {
    override var name = "Vidhide"
    override var mainUrl = "https://vidhide.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Vidsp : VidhideExtractor() { override var mainUrl = "https://vidsp.lol" }
class VidHideplus : VidhideExtractor() { override var mainUrl = "https://vidhideplus.com" }
class VidHidedht : VidhideExtractor() { override var mainUrl = "https://dhtpre.com" }
class Vidhidehub : VidhideExtractor() { override var mainUrl = "https://vidhidehub.com" }
class Vidhidetoul : VidhideExtractor() { override var mainUrl = "https://toul.hair" }
class Xtapes : VidhideExtractor() { 
    override var mainUrl = "https://xtapes.porn"
    override var name = "Xtapes"
}

// ==========================================
// 2. Lulustream & Clones
// ==========================================
open class LuluStream : ExtractorApi() {
    override var name = "Lulustream"
    override var mainUrl = "https://lulustream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

class Luluvid : LuluStream() {
    override var name = "Lulustream"
    override var mainUrl = "https://luluvid.com"
}

// ==========================================
// 3. Filemoon
// ==========================================
class Filemoon : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

// ==========================================
// 4. KDHindiDubbed Extractor
// ==========================================
class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun isCloudflareChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 503, 429)) return true
        val body = res.text
        return body.contains("Just a moment", true) ||
               body.contains("cf-browser-verification", true) ||
               body.contains("Verifying...", true) ||
               body.contains("challenge-platform", true) ||
               body.contains("_cf_chl_opt", true) ||
               body.contains("cf-turnstile", true)
    }

    private suspend fun cfGet(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true
    ): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)

        val res = app.get(url, referer = referer, headers = reqHeaders, cookies = cookies, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) {
            return app.get(url, referer = referer, headers = headers, cookies = cookies, interceptor = cfKiller, allowRedirects = allowRedirects)
        }
        return res
    }

    private suspend fun cfPost(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        cookies: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true
    ): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)

        val res = app.post(url, referer = referer, headers = reqHeaders, cookies = cookies, data = data, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) {
            return app.post(url, referer = referer, headers = headers, cookies = cookies, data = data, interceptor = cfKiller, allowRedirects = allowRedirects)
        }
        return res
    }

    private fun buildPlayerHeaders(pageUrl: String, referer: String, extraCookies: Map<String, String> = emptyMap()): Map<String, String> {
        val map = mutableMapOf("User-Agent" to USER_AGENT, "Referer" to referer)
        map.putAll(cfKiller.getCookieHeaders(pageUrl).toMap())
        if (extraCookies.isNotEmpty()) {
            val cookieStr = extraCookies.entries.joinToString("; ") { "${it.key}=${it.value}" }
            val existing = map["Cookie"] ?: map["cookie"]
            map["Cookie"] = if (!existing.isNullOrBlank()) "$existing; $cookieStr" else cookieStr
        }
        return map
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val targetUrl = unwrapRedirect(url.trim(), referer ?: mainUrl)
            val lower = targetUrl.lowercase()

            when {
                lower.contains("momofile.shop") && lower.contains(".php?id=") -> {
                    extractSingleMomoPhp(targetUrl, referer ?: mainUrl, subtitleCallback, callback)
                }
                lower.contains("momofile") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                lower.contains("xcloud") -> extractXCloud(targetUrl, subtitleCallback, callback)
                lower.contains("tmbcloud") -> extractTmbCloud(targetUrl, subtitleCallback, callback)
                lower.contains("pixeldrain") -> extractPixelDrain(targetUrl, callback)
                lower.contains("theditch") || lower.contains("fileditch") -> extractFileDitch(targetUrl, callback)
                lower.contains("vik1ngfile") || lower.contains("vikingfile") -> extractVikingFile(targetUrl, callback)
                lower.contains("send.now") || lower.contains("sendnow") -> extractSendNow(targetUrl, callback)
                lower.contains("susanexpectcare") || lower.contains("voe.sx") || lower.contains("voe.") -> {
                    extractVoe(targetUrl, referer ?: targetUrl, subtitleCallback, callback)
                }
                lower.contains("buzzheavier") -> extractBuzzheavier(targetUrl, callback)
                lower.contains("kdhindidubbed") || lower.contains("dramacool") -> {
                    val doc = cfGet(targetUrl, referer).document
                    val subLinks = doc.select(".entry-content a[href], a[href]").mapNotNull { a ->
                        val h = a.attr("href").trim()
                        if (h.startsWith("http") && !h.contains("dramacool.website") && !h.contains("kdhindidubbed") && !h.contains("t.me")) h else null
                    }.distinct()
                    
                    // FIX 2: Replaced .amap with a standard for loop to fix suspend/coroutine errors
                    for (sub in subLinks) {
                        getUrl(sub, targetUrl, subtitleCallback, callback)
                    }
                }
                else -> loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private suspend fun unwrapRedirect(url: String, referer: String): String {
        var current = url
        try {
            if (current.contains("redir") || current.contains("fifa") || current.contains("?url=") || current.contains("goto")) {
                val res = cfGet(current, referer = referer, allowRedirects = true)
                current = res.url
                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    current = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                } else {
                    val jsRedirect = Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""").find(res.text)?.groupValues?.get(1)
                    if (!jsRedirect.isNullOrBlank()) current = jsRedirect
                }
            }
        } catch (_: Exception) {}
        return current
    }

    private suspend fun extractMomoFile(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val uri = URI(url)
        val baseUrl = "${uri.scheme}://${uri.host}"
        val fileId = uri.query?.substringAfter("id=")?.substringBefore("&")?.takeIf { it.isNotBlank() } ?: uri.path.trimEnd('/').substringAfterLast('/')
        val landingUrl = "$baseUrl/d/$fileId"
        var sessionCookies = emptyMap<String, String>()

        try {
            val res = cfGet(landingUrl, referer = baseUrl)
            sessionCookies = res.cookies
            val doc = res.document

            doc.select("video source[src], video[src], iframe[src]").forEach { el ->
                val rawSrc = el.attr("src").trim()
                if (rawSrc.isNotBlank() && !rawSrc.startsWith("javascript")) {
                    val fullSrc = if (rawSrc.startsWith("/")) "$baseUrl$rawSrc" else rawSrc
                    if (fullSrc.contains("voe") || fullSrc.contains("susanexpectcare") || fullSrc.contains("/access/")) {
                        extractVoe(fullSrc, landingUrl, subtitleCallback, callback)
                    } else if (fullSrc.contains(".mp4") || fullSrc.contains(".mkv") || fullSrc.contains(".m3u8")) {
                        callback.invoke(newExtractorLink("MomoFile", "MomoFile [Direct]", fullSrc, if (fullSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P720.value
                            this.referer = landingUrl
                            this.headers = buildPlayerHeaders(landingUrl, landingUrl, sessionCookies)
                        })
                    }
                }
            }

            // FIX: Replaced .amap with a standard for loop
            for (a in doc.select("a[href]")) {
                val rawHref = a.attr("href").trim()
                val text = a.text().trim()
                if (rawHref.isNotBlank() && !rawHref.startsWith("#") && !rawHref.startsWith("javascript")) {
                    val href = if (rawHref.startsWith("/")) "$baseUrl$rawHref" else rawHref
                    if (text.contains("Online Watching", true) || href.contains("voe") || href.contains("susanexpectcare") || href.contains("/access/")) {
                        extractVoe(href, landingUrl, subtitleCallback, callback)
                    } else if (!href.contains(uri.host)) {
                        getUrl(href, landingUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}

        if (fileId.isNotBlank() && fileId != "d") {
            val services = listOf("pixeldrain", "buzzheavier", "fileditch", "vikingfile", "sendnow")
            // FIX: Replaced .amap with a standard for loop
            for (service in services) {
                val phpUrl = "$baseUrl/$service.php?id=$fileId"
                extractSingleMomoPhp(phpUrl, landingUrl, subtitleCallback, callback, sessionCookies)
            }
        }
    }

    private suspend fun extractSingleMomoPhp(phpUrl: String, refererUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, cookies: Map<String, String> = emptyMap()) {
        try {
            val uri = URI(phpUrl)
            val baseUrl = "${uri.scheme}://${uri.host}"
            val apiRes = cfGet(phpUrl, referer = refererUrl, cookies = cookies, headers = mapOf("Accept" to "application/json, text/javascript, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest"))
            val text = apiRes.text.trim()
            if (text.startsWith("{")) {
                val json = JSONObject(text)
                val rawUrl = json.optString("url").trim()
                if (rawUrl.isNotBlank()) {
                    val resolvedUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                    when {
                        phpUrl.contains("pixeldrain") || resolvedUrl.contains("pixeldrain") -> extractPixelDrain(resolvedUrl, callback)
                        phpUrl.contains("buzzheavier") || resolvedUrl.contains("buzzheavier") -> extractBuzzheavier(resolvedUrl, callback)
                        phpUrl.contains("fileditch") || resolvedUrl.contains("fileditch") || resolvedUrl.contains("theditch") -> extractFileDitch(resolvedUrl, callback)
                        phpUrl.contains("vikingfile") || resolvedUrl.contains("vikingfile") || resolvedUrl.contains("vik1ngfile") -> extractVikingFile(resolvedUrl, callback)
                        phpUrl.contains("sendnow") || resolvedUrl.contains("send.now") -> extractSendNow(resolvedUrl, callback)
                        else -> getUrl(resolvedUrl, refererUrl, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractXCloud(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val rawSegment = URI(url).path.trimEnd('/').substringAfterLast('/')
            val cleanId = rawSegment.removePrefix("preview_")
            if (cleanId.isBlank()) return
            val streamDomainUrl = "https://stream.xcloud1.lol/$cleanId"
            val downloadDomainUrl = "https://xcloud1.lol/$cleanId"

            try {
                val streamRes = cfGet(streamDomainUrl, referer = "https://xcloud1.lol/", allowRedirects = true)
                val streamDoc = streamRes.document
                val streamHost = "${URI(streamRes.url).scheme}://${URI(streamRes.url).host}"

                val contentType = streamRes.headers["content-type"] ?: streamRes.headers["Content-Type"] ?: ""
                if (contentType.contains("video", true) || contentType.contains("octet-stream", true) ||
                    streamRes.url.endsWith(".mkv", true) || streamRes.url.endsWith(".mp4", true) || streamRes.url.contains(".m3u8")) {
                    callback.invoke(newExtractorLink("XCloud", "XCloud [stream.xcloud1.lol]", streamRes.url, if (streamRes.url.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://stream.xcloud1.lol/"
                        this.headers = buildPlayerHeaders(streamDomainUrl, "https://stream.xcloud1.lol/", streamRes.cookies)
                    })
                }

                val videoTagSrc = streamDoc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
                    ?: Regex("""(?:file|src|url|sources)\s*[:=]\s*\[?\s*\{?\s*(?:file\s*:\s*)?["'](https?://[^"']+|/[^"']+)["']""").find(streamRes.text)?.groupValues?.get(1)
                    ?: Regex("""https?://[^\s"'<>]+(?:\.mp4|\.mkv|\.m3u8|/apis/[^\s"'<>]+)""").find(streamRes.text)?.value

                if (!videoTagSrc.isNullOrBlank()) {
                    val fullStream = if (videoTagSrc.startsWith("/")) "$streamHost$videoTagSrc" else videoTagSrc
                    callback.invoke(newExtractorLink("XCloud", "XCloud [Stream Player]", fullStream, if (fullStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = streamDomainUrl
                        this.headers = buildPlayerHeaders(streamDomainUrl, streamDomainUrl, streamRes.cookies)
                    })
                }
                parseXCloudButtons(streamDoc, streamHost, streamRes.url, streamRes.cookies, subtitleCallback, callback)
            } catch (_: Exception) {}

            try {
                val dlRes = cfGet(downloadDomainUrl, referer = url, allowRedirects = true)
                val dlHost = "${URI(dlRes.url).scheme}://${URI(dlRes.url).host}"
                parseXCloudButtons(dlRes.document, dlHost, dlRes.url, dlRes.cookies, subtitleCallback, callback)
            } catch (_: Exception) {}
        } catch (_: Exception) {}
    }

    private suspend fun parseXCloudButtons(
        doc: org.jsoup.nodes.Document,
        hostBase: String,
        pageUrl: String,
        cookies: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val buttons = doc.select("a[href]").filter { a ->
            val h = a.attr("href").trim()
            val t = a.text().trim()
            (h.contains("/apis/") || h.contains("stream.xcloud") || h.contains("stepnestph") ||
             h.contains("workers.dev") || h.contains("r2.dev") || h.contains("gofile") ||
             h.contains(".mkv") || h.contains(".mp4") || t.contains("Download [", true) || t.contains("Stream", true)) &&
            !h.endsWith(URI(pageUrl).path)
        }

        // FIX: Replaced .amap with a standard for loop
        for (btn in buttons) {
            val rawHref = btn.attr("href").trim()
            val fullHref = if (rawHref.startsWith("/")) "$hostBase$rawHref" else rawHref
            val btnLabel = btn.text().trim().ifBlank { "Server" }

            when {
                fullHref.contains("gofile") -> loadExtractor(fullHref, pageUrl, subtitleCallback, callback)
                fullHref.contains("/apis/") -> {
                    val apiRes = cfGet(fullHref, referer = pageUrl, cookies = cookies, allowRedirects = false)
                    val redirectUrl = apiRes.headers["location"] ?: apiRes.headers["Location"]
                    val finalStream = if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                        redirectUrl
                    } else {
                        cfGet(fullHref, referer = pageUrl, cookies = cookies, allowRedirects = true).url
                    }
                    if (finalStream.startsWith("http") && !finalStream.contains("/apis/")) {
                        callback.invoke(newExtractorLink("XCloud", "XCloud - $btnLabel", finalStream, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P720.value
                            this.referer = "$hostBase/"
                            this.headers = buildPlayerHeaders(pageUrl, "$hostBase/", cookies)
                        })
                    }
                }
                fullHref.startsWith("http") -> {
                    callback.invoke(newExtractorLink("XCloud", "XCloud - $btnLabel", fullHref, if (fullHref.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = "$hostBase/"
                        this.headers = buildPlayerHeaders(pageUrl, "$hostBase/", cookies)
                    })
                }
            }
        }
    }

    private suspend fun extractTmbCloud(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://tmbcloud.lol/", allowRedirects = true)
            val doc = res.document
            val uri = URI(res.url)
            val baseUrl = "${uri.scheme}://${uri.host}"
            val cookies = res.cookies

            val copyInputUrl = doc.selectFirst("input[value*='/download/']")?.attr("value")?.trim()
            val pageId = doc.selectFirst("button[data-page], a[data-page], div[data-page]")?.attr("data-page")?.takeIf { it.isNotBlank() }
                ?: Regex("""page_id\s*[:=]\s*['"]([a-zA-Z0-9]+)['"]""").find(res.text)?.groupValues?.get(1)
                ?: copyInputUrl?.trimEnd('/')?.substringAfterLast('/')
                ?: uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "links" }

            val tokenB = Regex("""token_b\s*[:=]\s*['"]([a-zA-Z0-9]+)['"]""").find(res.text)?.groupValues?.get(1)
                ?: doc.selectFirst("input[name='token_b'], button[data-token]")?.let { it.attr("value").ifBlank { it.attr("data-token") } }
                ?: ""

            doc.select("a[href], button[data-href]").forEach { el ->
                val rawH = el.attr("href").ifBlank { el.attr("data-href") }.trim()
                if (rawH.isNotBlank() && !rawH.startsWith("#") && !rawH.startsWith("javascript")) {
                    val fullH = if (rawH.startsWith("/")) "$baseUrl$rawH" else rawH
                    if (fullH.contains("/fslv2/") || fullH.contains("/fastcdn/") || fullH.contains("/r2/")) {
                        resolveTmbSubPage(fullH, res.url, cookies, callback)
                    }
                }
            }

            if (!pageId.isNullOrBlank()) {
                val typesToTry = listOf("fslv2new", "fastcdnv2", "r2")
                // FIX: Replaced .amap with a standard for loop
                for (dataType in typesToTry) {
                    try {
                        val step2Res = cfPost("$baseUrl/step2.php", referer = res.url, cookies = cookies, headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With" to "XMLHttpRequest", "Origin" to baseUrl), data = mapOf("type" to dataType, "page_id" to pageId, "page" to pageId, "token_b" to tokenB))
                        handleTmbJsonResponse(step2Res.text, dataType, baseUrl, res.url, cookies, callback)
                    } catch (_: Exception) {}
                    try {
                        val genRes = cfPost("$baseUrl/gen-link.php", referer = res.url, cookies = cookies, headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With" to "XMLHttpRequest", "Origin" to baseUrl), data = mapOf("type" to dataType, "page_id" to pageId, "token_b" to tokenB))
                        handleTmbJsonResponse(genRes.text, dataType, baseUrl, res.url, cookies, callback)
                    } catch (_: Exception) {}
                    try {
                        val dlRes = cfPost("$baseUrl/download/$pageId", referer = res.url, cookies = cookies, headers = mapOf("Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8", "X-Requested-With" to "XMLHttpRequest", "Origin" to baseUrl), data = mapOf("type" to dataType, "page" to pageId))
                        handleTmbJsonResponse(dlRes.text, dataType, baseUrl, res.url, cookies, callback)
                    } catch (_: Exception) {}
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun resolveTmbSubPage(subUrl: String, referer: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(subUrl, referer = referer, cookies = cookies, allowRedirects = true)
            if (res.url != subUrl && (res.url.contains(".mkv") || res.url.contains(".mp4") || res.url.contains("r2.dev") || res.url.contains("workers.dev"))) {
                callback.invoke(newExtractorLink("TMBCloud", "TMBCloud [Direct]", res.url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P1080.value
                    this.referer = referer
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun handleTmbJsonResponse(rawText: String, dataType: String, baseUrl: String, refererUrl: String, cookies: Map<String, String>, callback: (ExtractorLink) -> Unit) {
        val text = rawText.trim()
        if (!text.startsWith("{")) return
        val json = JSONObject(text)
        val rawLink = json.optString("url").ifBlank { json.optString("download_url").ifBlank { json.optString("link").ifBlank { json.optString("redirect") } } }
        if (rawLink.isBlank()) return
        val fullLink = if (rawLink.startsWith("/")) "$baseUrl$rawLink" else rawLink

        if (fullLink.contains(URI(baseUrl).host) && !fullLink.contains(".mkv") && !fullLink.contains(".mp4")) {
            val subRes = cfGet(fullLink, referer = refererUrl, cookies = cookies, allowRedirects = true)
            val directInSub = subRes.document.selectFirst("a[href*='.mkv'], a[href*='.mp4'], a[href*='r2.dev'], a[href*='workers.dev'], a.btn[href*='http']")?.attr("href")
                ?: Regex("""https?://[^\s"'<>]+(?:\.mkv|\.mp4|r2\.dev[^\s"'<>]+|workers\.dev[^\s"'<>]+)""").find(subRes.text)?.value
            if (!directInSub.isNullOrBlank()) {
                callback.invoke(newExtractorLink("TMBCloud", "TMBCloud [${dataType.uppercase()}]", directInSub, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P1080.value
                    this.referer = "$baseUrl/"
                    this.headers = buildPlayerHeaders(refererUrl, "$baseUrl/", cookies)
                })
            }
        } else if (fullLink.startsWith("http")) {
            callback.invoke(newExtractorLink("TMBCloud", "TMBCloud [${dataType.uppercase()}]", fullLink, if (fullLink.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                this.quality = Qualities.P1080.value
                this.referer = "$baseUrl/"
                this.headers = buildPlayerHeaders(refererUrl, "$baseUrl/", cookies)
            })
        }
    }

    private suspend fun extractPixelDrain(url: String, callback: (ExtractorLink) -> Unit) {
        val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
        if (fileId.isBlank()) return
        val uri = URI(url)
        val domain = "${uri.scheme}://${uri.host}"
        callback.invoke(newExtractorLink("PixelDrain", "PixelDrain [Direct]", "$domain/api/file/$fileId", ExtractorLinkType.VIDEO) {
            this.quality = Qualities.P720.value
            this.referer = "$domain/"
        })
    }

    private suspend fun extractFileDitch(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, allowRedirects = true)
            val currentUrl = res.url
            val doc = res.document
            val ditchPageLink = doc.selectFirst("input[value*='theditch'], a[href*='theditch']")?.let { it.attr("value").ifBlank { it.attr("href") } } ?: Regex("""https?://[a-zA-Z0-9.-]*theditch\.[a-z]+/[a-zA-Z0-9_-]+""").find(res.text)?.value
            val targetRes = if (!ditchPageLink.isNullOrBlank() && ditchPageLink != currentUrl) cfGet(ditchPageLink.trim(), referer = currentUrl, cookies = res.cookies, allowRedirects = true) else res
            val finalDoc = targetRes.document
            val finalPageUrl = targetRes.url
            val baseHost = "${URI(finalPageUrl).scheme}://${URI(finalPageUrl).host}"
            var directFileUrl = finalDoc.selectFirst("video source[src], video[src], a.download-button[href], a#download-btn[href], a[href*='fileditchfiles'], a[href*='b-cdn.net'], a[href*='.mkv'], a[href*='.mp4'], a[download][href]")?.let { it.attr("src").ifBlank { it.attr("href") } }
            if (directFileUrl.isNullOrBlank()) directFileUrl = Regex("""https?://[^\s"'<>]+(?:\.mkv|\.mp4|fileditchfiles[^\s"'<>]+|/file/[^\s"'<>]+)""").find(targetRes.text)?.value
            if (!directFileUrl.isNullOrBlank()) {
                val fullStreamUrl = if (directFileUrl.startsWith("/")) "$baseHost$directFileUrl" else directFileUrl.trim()
                if (fullStreamUrl != finalPageUrl) {
                    callback.invoke(newExtractorLink("TheDitch", "TheDitch [Direct]", fullStreamUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = finalPageUrl
                        this.headers = buildPlayerHeaders(finalPageUrl, finalPageUrl, targetRes.cookies)
                    })
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVikingFile(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
            if (fileId.isBlank()) return
            val urlsToTry = listOf("https://vikingfile.com/f/$fileId", "https://vik1ngfile.site/f/$fileId", url).distinct()
            for (target in urlsToTry) {
                try {
                    val res = cfGet(target, referer = "https://momofile.shop/")
                    val doc = res.document
                    val base = "${URI(res.url).scheme}://${URI(res.url).host}"
                    var mediaUrl = doc.selectFirst("video source[src], video[src], a#download-url[href], a[download][href]")?.let { it.attr("src").ifBlank { it.attr("href") } }
                    if (mediaUrl.isNullOrBlank()) mediaUrl = doc.select("a[href]").firstOrNull { it.text().trim().equals("Download", true) && it.attr("href").isNotBlank() && !it.attr("href").startsWith("#") && !it.attr("href").startsWith("javascript") }?.attr("href")
                    if (mediaUrl.isNullOrBlank()) mediaUrl = Regex("""(?:file|src|videoUrl|downloadUrl|url)\s*[:=]\s*["'](https?://[^"']+(?:\.mp4|\.mkv|\.m3u8|/d/[^"']+|/dl/[^"']+))["']""", RegexOption.IGNORE_CASE).find(res.text)?.groupValues?.get(1)
                    if (!mediaUrl.isNullOrBlank()) {
                        val resolvedUrl = if (mediaUrl.startsWith("/")) "$base$mediaUrl" else mediaUrl.trim()
                        if (!resolvedUrl.endsWith("/f/$fileId")) {
                            callback.invoke(newExtractorLink("VikingFile", "VikingFile [Direct]", resolvedUrl, if (resolvedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.quality = Qualities.P720.value
                                this.referer = res.url
                                this.headers = buildPlayerHeaders(res.url, res.url, res.cookies)
                            })
                            return
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractSendNow(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://send.now/")
            val doc = res.document
            var dlLink: String? = null
            val form = doc.selectFirst("form[name='F1'], form[method='POST']")
            if (form != null) {
                val postData = mutableMapOf<String, String>()
                form.select("input[name]").forEach { input -> postData[input.attr("name")] = input.attr("value") }
                postData["op"] = "download2"
                if (!postData.containsKey("id")) postData["id"] = url.trimEnd('/').substringAfterLast('/')
                val postRes = cfPost(res.url, referer = res.url, cookies = res.cookies, data = postData, allowRedirects = false)
                val redirectLoc = postRes.headers["location"] ?: postRes.headers["Location"]
                dlLink = if (!redirectLoc.isNullOrBlank() && redirectLoc.startsWith("http")) redirectLoc else postRes.document.selectFirst("a#download_link[href], span#direct_link a[href], a[href*='.mkv'], a[href*='.mp4'], video source[src]")?.let { it.attr("href").ifBlank { it.attr("src") } } ?: Regex("""https?://[^\s"'<>]+(?:\.mkv|\.mp4|/d/[a-zA-Z0-9]+/[^\s"'<>]+)""").find(postRes.text)?.value
            }
            if (!dlLink.isNullOrBlank() && dlLink.startsWith("http") && dlLink.trim() != res.url) {
                callback.invoke(newExtractorLink("SendNow", "Send.now [Direct]", dlLink.trim(), ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://send.now/"
                    this.headers = buildPlayerHeaders(res.url, "https://send.now/", res.cookies)
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = referer, allowRedirects = true)
            var html = res.text
            var finalUrl = res.url
            val jsRedirect = Regex("""window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!jsRedirect.isNullOrBlank()) {
                val res2 = cfGet(jsRedirect, referer = finalUrl, allowRedirects = true)
                html = res2.text
                finalUrl = res2.url
            }
            val hostBase = "${URI(finalUrl).scheme}://${URI(finalUrl).host}/"
            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    callback.invoke(newExtractorLink("SusanExpectCare", "SusanExpectCare [Online Watch]", decoded.trim(), ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P720.value
                        this.referer = hostBase
                        this.headers = buildPlayerHeaders(finalUrl, hostBase)
                    })
                    return
                }
            }
            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(newExtractorLink("SusanExpectCare", "SusanExpectCare [Online Watch]", rawM3u8, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P720.value
                    this.referer = hostBase
                    this.headers = buildPlayerHeaders(finalUrl, hostBase)
                })
                return
            }
            loadExtractor(finalUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanUrl = url.trimEnd('/')
            val res = cfGet("$cleanUrl/download", referer = url, headers = mapOf("HX-Request" to "true", "HX-Current-URL" to url), allowRedirects = false)
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                callback.invoke(newExtractorLink("Buzzheavier", "Buzzheavier [Direct]", hxRedirect, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = url
                    this.headers = buildPlayerHeaders(url, url, res.cookies)
                })
            }
        } catch (_: Exception) {}
    }
}

// ==========================================
// 5. Universal Extractor Router
// ==========================================
object UniversalExtractors {
    suspend fun invoke(
        href: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = href.lowercase()

        fun pushDirect(name: String, directUrl: String) {
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = directUrl,
                    type = if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = href
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        when {
            host.contains("vcloud") -> {
                // vcloudServers(href).forEach { pushDirect(it.name, it.url) }
            }
            host.contains("vegadrive") -> {
                // vegadriveSkydrop(href)?.let { pushDirect("VegaDrive", it) }
            }
            host.contains("fastdl") -> {
                // fastdlDirect(href)?.let { pushDirect("G-Direct", it) }
            }
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") -> {
                // filepressDirect(href)?.let { pushDirect("Filepress", it) }
            }
            host.contains("gofile") -> {
                // gofileDirect(href)?.let { pushDirect("Gofile", it) }
            }
            host.contains("gpdl") -> {
                // gpdlDirect(href)?.let { pushDirect("10Gbps", it) }
            }
            host.contains("hubcloud") -> {
                // hubcloudServers(href).forEach { pushDirect(it.name, it.url) }
            }
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
