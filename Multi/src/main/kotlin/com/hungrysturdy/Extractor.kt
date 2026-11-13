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
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI
import android.util.Base64

// ==========================================
// 1. STANDARD CLONE EXTRACTORS (Vidhide, Lulustream, Filemoon)
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

class Filemoon : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

// ==========================================
// 2. KDHINDIDUBBED SERVERS EXTRACTOR
// ==========================================
class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }
        private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun isCloudflareChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 503, 429)) return true
        val body = res.text
        return body.contains("Just a moment", true) || body.contains("cf-browser-verification", true) || body.contains("cf-turnstile", true)
    }

    private suspend fun cfGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap(), allowRedirects: Boolean = true): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)
        val res = app.get(url, referer = referer, headers = reqHeaders, cookies = cookies, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) return app.get(url, referer = referer, headers = headers, cookies = cookies, interceptor = cfKiller, allowRedirects = allowRedirects)
        return res
    }

    private suspend fun cfPost(url: String, referer: String? = null, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap(), data: Map<String, String> = emptyMap(), allowRedirects: Boolean = true): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)
        val res = app.post(url, referer = referer, headers = reqHeaders, cookies = cookies, data = data, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) return app.post(url, referer = referer, headers = headers, cookies = cookies, data = data, interceptor = cfKiller, allowRedirects = allowRedirects)
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

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val targetUrl = unwrapRedirect(url.trim(), referer ?: mainUrl)
            val lower = targetUrl.lowercase()

            when {
                lower.contains("momofile.shop") && lower.contains(".php?id=") -> extractSingleMomoPhp(targetUrl, referer ?: mainUrl, subtitleCallback, callback)
                lower.contains("momofile") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                lower.contains("xcloud") -> extractXCloud(targetUrl, subtitleCallback, callback)
                lower.contains("tmbcloud") -> extractTmbCloud(targetUrl, subtitleCallback, callback)
                lower.contains("pixeldrain") -> UniversalExtractors.invoke(targetUrl, targetUrl, subtitleCallback, callback)
                lower.contains("theditch") || lower.contains("fileditch") -> extractFileDitch(targetUrl, subtitleCallback, callback)
                lower.contains("vik1ngfile") || lower.contains("vikingfile") -> extractVikingFile(targetUrl, subtitleCallback, callback)
                lower.contains("send.now") || lower.contains("sendnow") -> extractSendNow(targetUrl, subtitleCallback, callback)
                lower.contains("susanexpectcare") || lower.contains("voe.sx") || lower.contains("voe.") -> extractVoe(targetUrl, referer ?: targetUrl, subtitleCallback, callback)
                lower.contains("buzzheavier") -> extractBuzzheavier(targetUrl, subtitleCallback, callback)
                lower.contains("kdhindidubbed") || lower.contains("dramacool") -> {
                    val doc = cfGet(targetUrl, referer).document
                    val subLinks = doc.select(".entry-content a[href], a[href]").mapNotNull { a ->
                        val h = a.attr("href").trim()
                        if (h.startsWith("http") && !h.contains("dramacool.website") && !h.contains("kdhindidubbed") && !h.contains("t.me")) h else null
                    }.distinct()
                    
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

            for (el in doc.select("video source[src], video[src], iframe[src]")) {
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
            for (service in services) {
                extractSingleMomoPhp("$baseUrl/$service.php?id=$fileId", landingUrl, subtitleCallback, callback, sessionCookies)
            }
        }
    }

    private suspend fun extractSingleMomoPhp(phpUrl: String, refererUrl: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit, cookies: Map<String, String> = emptyMap()) {
        try {
            val baseUrl = "${URI(phpUrl).scheme}://${URI(phpUrl).host}"
            val apiRes = cfGet(phpUrl, referer = refererUrl, cookies = cookies, headers = mapOf("Accept" to "application/json, text/javascript, */*; q=0.01", "X-Requested-With" to "XMLHttpRequest"))
            val text = apiRes.text.trim()
            if (text.startsWith("{")) {
                val json = JSONObject(text)
                val rawUrl = json.optString("url").ifBlank { json.optString("link") }.trim()
                if (rawUrl.isNotBlank()) {
                    val resolvedUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                    UniversalExtractors.invoke(resolvedUrl, refererUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractXCloud(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanId = URI(url).path.trimEnd('/').substringAfterLast('/').removePrefix("preview_")
            if (cleanId.isBlank()) return
            
            val streamRes = cfGet("https://stream.xcloud1.lol/$cleanId", referer = "https://xcloud1.lol/", allowRedirects = true)
            val videoTagSrc = streamRes.document.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
                ?: Regex("""(?:file|src|url|sources)\s*[:=]\s*\[?\s*\{?\s*(?:file\s*:\s*)?["'](https?://[^"']+|/[^"']+)["']""").find(streamRes.text)?.groupValues?.get(1)
            
            if (!videoTagSrc.isNullOrBlank()) {
                val fullStream = if (videoTagSrc.startsWith("/")) "https://stream.xcloud1.lol$videoTagSrc" else videoTagSrc
                callback.invoke(newExtractorLink("XCloud", "XCloud [Stream Player]", fullStream, if (fullStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://stream.xcloud1.lol/$cleanId"
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractTmbCloud(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        loadExtractor(url, "https://tmbcloud.lol/", subtitleCallback, callback)
    }

    private suspend fun extractFileDitch(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
         loadExtractor(url, "https://fileditchvids.st/", subtitleCallback, callback)
    }

    private suspend fun extractVikingFile(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
         loadExtractor(url, "https://vikingfile.com/", subtitleCallback, callback)
    }

    private suspend fun extractSendNow(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
         loadExtractor(url, "https://send.now/", subtitleCallback, callback)
    }

    private suspend fun extractVoe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = referer, allowRedirects = true)
            val hostBase = "${URI(res.url).scheme}://${URI(res.url).host}/"
            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
            
            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    callback.invoke(newExtractorLink("SusanExpectCare", "SusanExpectCare [Watch]", decoded.trim(), ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P720.value
                        this.referer = hostBase
                    })
                    return
                }
            }
            loadExtractor(res.url, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    private suspend fun extractBuzzheavier(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet("${url.trimEnd('/')}/download", referer = url, headers = mapOf("HX-Request" to "true"), allowRedirects = false)
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                callback.invoke(newExtractorLink("Buzzheavier", "Buzzheavier [Direct]", hxRedirect, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = url
                })
                return
            }
        } catch (_: Exception) {}
        loadExtractor(url, "https://momofile.shop/", subtitleCallback, callback)
    }
}

// ==========================================
// 3. UNIVERSAL ROUTER (For KatDrama & Fallbacks)
// ==========================================
object UniversalExtractors {
    suspend fun invoke(
        href: String,
        data: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val host = href.lowercase()

        suspend fun pushDirect(name: String, directUrl: String, type: ExtractorLinkType? = null) {
            val linkType = type ?: if (directUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
            callback.invoke(
                newExtractorLink(source = name, name = name, url = directUrl, type = linkType) {
                    this.referer = href
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        when {
            host.contains("hakunaymatata") -> pushDirect("Fast Server [KatDrama]", href, ExtractorLinkType.DASH)
            host.contains("devcorp.me") -> pushDirect("DevCorp Stream [KatDrama]", href, ExtractorLinkType.M3U8)
            host.contains("modiplay.xyz") || host.contains("rozgarlelo") || host.contains("fvibuxer") -> {
                val targetUrl = try {
                    if (href.contains("url=")) java.net.URLDecoder.decode(href.substringAfter("url=").substringBefore("&"), "UTF-8") else href
                } catch (e: Exception) { href }
                pushDirect("Hindi Proxy [KatDrama]", targetUrl)
            }
            host.contains("vidmoly") || host.contains("staticmoly") -> {
                loadExtractor(href.replace("staticmoly.me/static/jquery.min.js", "vidmoly.org"), data, subtitleCallback, callback)
            }
            host.contains("pixeldrain") -> {
                val fileId = href.trimEnd('/').substringAfterLast('/').substringBefore("?")
                if (fileId.isNotBlank()) {
                    pushDirect("PixelDrain [Direct]", "https://pixeldrain.com/api/file/$fileId", ExtractorLinkType.VIDEO)
                } else {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
            host.contains("vcloud") || host.contains("vegadrive") || host.contains("fastdl") || 
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") || 
            host.contains("gofile") || host.contains("gpdl") || host.contains("hubcloud") -> {
                loadExtractor(href, data, subtitleCallback, callback)
            }
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
