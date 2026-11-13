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
import java.net.URI
import android.util.Base64

// ==========================================
// 1. GENERIC EXTRACTORS
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
class Xtapes : VidhideExtractor() { override var mainUrl = "https://xtapes.porn"; override var name = "Xtapes" }

open class LuluStream : ExtractorApi() {
    override var name = "Lulustream"
    override var mainUrl = "https://lulustream.com"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}
class Luluvid : LuluStream() { override var mainUrl = "https://luluvid.com" }

class Filemoon : ExtractorApi() {
    override var name = "Filemoon"
    override var mainUrl = "https://filemoon.sx"
    override val requiresReferer = false
    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {}
}

// ==========================================
// 2. MAIN KDHINDIDUBBED SERVER HANDLER 
// ==========================================
class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://dramacool.website"
    override val requiresReferer = false

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/119.0.0.0 Safari/537.36"
    }

    private fun isCloudflareChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 503, 429)) return true
        val body = res.text
        return body.contains("Just a moment", true) || body.contains("cf-browser-verification", true) || body.contains("cf-turnstile", true)
    }

    private suspend fun cfGet(url: String, referer: String? = null, headers: Map<String, String> = emptyMap(), cookies: Map<String, String> = emptyMap()): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)
        val res = app.get(url, referer = referer, headers = reqHeaders, cookies = cookies, allowRedirects = true)
        if (isCloudflareChallenge(res)) return app.get(url, referer = referer, headers = headers, cookies = cookies, interceptor = cfKiller, allowRedirects = true)
        return res
    }

    override suspend fun getUrl(url: String, referer: String?, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            var targetUrl = url.trim()
            
            // Bypass redirectors safely
            if (targetUrl.contains("redir") || targetUrl.contains("fifa") || targetUrl.contains("?url=") || targetUrl.contains("goto")) {
                val res = cfGet(targetUrl, referer)
                targetUrl = res.url
                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    targetUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                }
            }

            val lower = targetUrl.lowercase()

            when {
                // 1. Momofile Main Host
                lower.contains("momofile.shop") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                
                // 2. PixelDrain (.dev and .com)
                lower.contains("pixeldrain.dev") || lower.contains("pixeldrain.com") -> {
                    val fileId = targetUrl.trimEnd('/').substringAfterLast('/').substringBefore("?")
                    if (fileId.isNotBlank()) {
                        callback.invoke(newExtractorLink(name, "PixelDrain", "https://pixeldrain.com/api/file/$fileId", ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://pixeldrain.com"
                        })
                    } else {
                        loadExtractor(targetUrl, targetUrl, subtitleCallback, callback)
                    }
                }
                
                // 3. Voe (SusanExpectCare)
                lower.contains("susanexpectcare") || lower.contains("voe.sx") -> {
                    extractVoe(targetUrl, targetUrl, subtitleCallback, callback)
                }
                
                // 4. Send.now
                lower.contains("send.now") -> {
                    loadExtractor(targetUrl, "https://send.now/", subtitleCallback, callback)
                }

                // 5. Vik1ngfile
                lower.contains("vik1ngfile.site") || lower.contains("vikingfile.com") -> {
                    loadExtractor(targetUrl, "https://vik1ngfile.site/", subtitleCallback, callback)
                }

                // 6. Fileditch
                lower.contains("fileditchvids.st") || lower.contains("fileditch") -> {
                    loadExtractor(targetUrl, "https://fileditchvids.st/", subtitleCallback, callback)
                }

                // 7. Tmbcloud & Sub-servers (fslv2, cloudfilesv2, cloudfiles)
                lower.contains("tmbcloud.lol") -> {
                    loadExtractor(targetUrl, "https://tmbcloud.lol/", subtitleCallback, callback)
                }

                // 8. Buzzheavier
                lower.contains("buzzheavier") -> {
                    extractBuzzheavier(targetUrl, callback)
                }

                // 9. XCloud
                lower.contains("xcloud") -> {
                    extractXCloud(targetUrl, callback)
                }
                
                else -> {
                    loadExtractor(targetUrl, referer, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractMomoFile(url: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        val uri = URI(url)
        val baseUrl = "${uri.scheme}://${uri.host}"
        val fileId = uri.query?.substringAfter("id=")?.substringBefore("&")?.takeIf { it.isNotBlank() } ?: uri.path.trimEnd('/').substringAfterLast('/')
        val landingUrl = "$baseUrl/d/$fileId"

        try {
            val res = cfGet(landingUrl, referer = baseUrl)
            val doc = res.document

            // Check Direct Embedded Video Tags
            for (el in doc.select("video source[src], video[src], iframe[src]")) {
                val fullSrc = el.attr("src").trim().let { if (it.startsWith("/")) "$baseUrl$it" else it }
                if (fullSrc.isNotBlank() && !fullSrc.startsWith("javascript")) {
                    if (fullSrc.contains("voe") || fullSrc.contains("susanexpectcare") || fullSrc.contains("/access/")) {
                        extractVoe(fullSrc, landingUrl, subtitleCallback, callback)
                    }
                }
            }

            // Extract from internal Momofile APIs
            if (fileId.isNotBlank() && fileId != "d") {
                // ALL Requested services added back so episodes fetch properly
                val allowedServices = listOf("pixeldrain", "sendnow", "vikingfile", "fileditch", "buzzheavier") 
                for (service in allowedServices) {
                    val apiRes = cfGet("$baseUrl/$service.php?id=$fileId", referer = landingUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    val text = apiRes.text.trim()
                    if (text.startsWith("{")) {
                        val json = org.json.JSONObject(text)
                        val rawUrl = json.optString("url").ifBlank { json.optString("link") }.trim()
                        if (rawUrl.isNotBlank()) {
                            val resolvedUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                            // Route it back through getUrl to hit the correct domain logic
                            getUrl(resolvedUrl, landingUrl, subtitleCallback, callback) 
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val success = loadExtractor(url, referer, subtitleCallback, callback)
            if (!success) {
                 val res = cfGet(url, referer = referer)
                 val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
                 
                 if (!hlsMatch.isNullOrBlank()) {
                     val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                     if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                         callback.invoke(newExtractorLink(name, "Voe (Susan) [Watch]", decoded.trim(), ExtractorLinkType.M3U8) {
                             this.quality = Qualities.P1080.value
                             this.referer = "${URI(res.url).scheme}://${URI(res.url).host}/"
                         })
                     }
                 }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = app.get("${url.trimEnd('/')}/download", referer = url, headers = mapOf("HX-Request" to "true"), allowRedirects = false)
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                callback.invoke(newExtractorLink("Buzzheavier", "Buzzheavier [Direct]", hxRedirect, ExtractorLinkType.VIDEO) {
                    this.quality = Qualities.P720.value
                    this.referer = url
                })
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractXCloud(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanId = URI(url).path.trimEnd('/').substringAfterLast('/').removePrefix("preview_")
            if (cleanId.isBlank()) return
            
            val streamRes = cfGet("https://stream.xcloud1.lol/$cleanId", referer = "https://xcloud1.lol/")
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
}

// ==========================================
// 3. UNIVERSAL ROUTER (For KatDrama Servers)
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
            // Direct file checks to prevent OOM
            href.matches(Regex(".*\\.(mkv|mp4|ts|m3u8)$", RegexOption.IGNORE_CASE)) -> {
                pushDirect("Direct Media", href)
            }
            
            // KatDrama specific servers
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
            
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
