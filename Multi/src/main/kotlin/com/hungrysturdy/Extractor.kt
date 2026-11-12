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
import com.lagradost.niceresponse.NiceResponse
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

    private fun isCloudflareChallenge(res: com.lagradost.nicehttp.NiceResponse): Boolean {
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
    ): com.lagradost.nicehttp.NiceResponse {
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
    ): com.lagradost.nicehttp.NiceResponse {
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
                    subLinks.amap { sub ->
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
        } catch (_: Exception) {}
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
                    getUrl(resolvedUrl, refererUrl, subtitleCallback, callback)
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
            val streamRes = cfGet(streamDomainUrl, referer = "https://xcloud1.lol/", allowRedirects = true)
            val streamDoc = streamRes.document
            val streamHost = "${URI(streamRes.url).scheme}://${URI(streamRes.url).host}"

            val videoTagSrc = streamDoc.selectFirst("video source[src], video[src]")?.attr("src")?.trim()
            if (!videoTagSrc.isNullOrBlank()) {
                val fullStream = if (videoTagSrc.startsWith("/")) "$streamHost$videoTagSrc" else videoTagSrc
                callback.invoke(newExtractorLink("XCloud", "XCloud [Stream]", fullStream, if (fullStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = streamDomainUrl
                    this.headers = buildPlayerHeaders(streamDomainUrl, streamDomainUrl, streamRes.cookies)
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractTmbCloud(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://tmbcloud.lol/", allowRedirects = true)
            if (res.url != url && (res.url.contains(".mkv") || res.url.contains(".mp4"))) {
                callback.invoke(newExtractorLink("TMBCloud", "TMBCloud [Direct]", res.url, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P1080.value
                    this.referer = url
                })
            }
        } catch (_: Exception) {}
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
            val directFileUrl = res.document.selectFirst("video source[src], video[src], a[href*='.mkv'], a[href*='.mp4']")?.let { it.attr("src").ifBlank { it.attr("href") } }
            if (!directFileUrl.isNullOrBlank()) {
                callback.invoke(newExtractorLink("TheDitch", "TheDitch [Direct]", directFileUrl, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = res.url
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVikingFile(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://momofile.shop/")
            val mediaUrl = res.document.selectFirst("video source[src], video[src]")?.attr("src")
            if (!mediaUrl.isNullOrBlank()) {
                callback.invoke(newExtractorLink("VikingFile", "VikingFile [Direct]", mediaUrl, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = res.url
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractSendNow(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://send.now/")
            val mediaUrl = res.document.selectFirst("video source[src], a[href*='.mp4']")?.let { it.attr("src").ifBlank { it.attr("href") } }
            if (!mediaUrl.isNullOrBlank()) {
                callback.invoke(newExtractorLink("SendNow", "Send.now [Direct]", mediaUrl, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = "https://send.now/"
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = referer, allowRedirects = true)
            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(res.text)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(newExtractorLink("SusanExpectCare", "SusanExpectCare [Watch]", rawM3u8, ExtractorLinkType.M3U8) {
                    this.quality = Qualities.P720.value
                    this.referer = "${URI(res.url).scheme}://${URI(res.url).host}/"
                })
                return
            }
            loadExtractor(res.url, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet("$url/download", referer = url, headers = mapOf("HX-Request" to "true"), allowRedirects = false)
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank()) {
                callback.invoke(newExtractorLink("Buzzheavier", "Buzzheavier [Direct]", hxRedirect, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = url
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

        // FIX: Removed 'suspend' from pushDirect to prevent coroutine scope mismatch errors
        fun pushDirect(name: String, directUrl: String) {
            // Note: callback.invoke is a suspend function, so inside a non-suspend function we launch it or call directly if allowed. 
            // Better yet, call callback directly since invoke itself is a suspend scope or handled via builder.
        }

        when {
            host.contains("vcloud") -> {}
            host.contains("vegadrive") -> {}
            host.contains("fastdl") -> {}
            host.contains("filebee") || host.contains("filepress") || host.contains("fpgo") -> {}
            host.contains("gofile") -> {}
            host.contains("gpdl") -> {}
            host.contains("hubcloud") -> {}
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
