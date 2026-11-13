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
// KDHINDIDUBBED SERVER HANDLER (Only Voe, PixelDrain, Send)
// ==========================================
class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://dramacool.website"
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
                // 1. Momofile (Check internally if it has Voe/Pixeldrain)
                lower.contains("momofile.shop") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                
                // 2. PixelDrain (Direct Native Extractor)
                lower.contains("pixeldrain") -> {
                    val fileId = targetUrl.trimEnd('/').substringAfterLast('/').substringBefore("?")
                    if (fileId.isNotBlank()) {
                        callback.invoke(newExtractorLink(name, "PixelDrain [Direct]", "https://pixeldrain.com/api/file/$fileId", ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.referer = "" // Blank referer to prevent 403 API blocking
                        })
                    } else {
                        loadExtractor(targetUrl, targetUrl, subtitleCallback, callback)
                    }
                }
                
                // 3. Voe (SusanExpectCare)
                lower.contains("susanexpectcare") || lower.contains("voe.sx") || lower.contains("voe.") -> {
                    extractVoe(targetUrl, targetUrl, subtitleCallback, callback)
                }
                
                // 4. Send.now (Requires WebView for Cloudstream currently)
                lower.contains("send.now") || lower.contains("sendnow") -> {
                    loadExtractor(targetUrl, "https://send.now/", subtitleCallback, callback)
                }
                
                // IGNORE ALL OTHER SERVERS (Buzzheavier, Tmbcloud, Fileditch, Vikingfile, XCloud, etc.)
                else -> {
                    // Do nothing for other servers as requested.
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

            // Check if Momofile embeds Voe directly via tags
            for (el in doc.select("video source[src], video[src], iframe[src]")) {
                val fullSrc = el.attr("src").trim().let { if (it.startsWith("/")) "$baseUrl$it" else it }
                if (fullSrc.isNotBlank() && !fullSrc.startsWith("javascript")) {
                    if (fullSrc.contains("voe") || fullSrc.contains("susanexpectcare") || fullSrc.contains("/access/")) {
                        extractVoe(fullSrc, landingUrl, subtitleCallback, callback)
                    }
                }
            }

            // Fallback internal services (Check only for Pixeldrain or Sendnow on Momofile's PHP endpoints)
            if (fileId.isNotBlank() && fileId != "d") {
                // Restricted list to ONLY requested servers
                val allowedServices = listOf("pixeldrain", "sendnow") 
                for (service in allowedServices) {
                    val apiRes = cfGet("$baseUrl/$service.php?id=$fileId", referer = landingUrl, headers = mapOf("X-Requested-With" to "XMLHttpRequest"))
                    val text = apiRes.text.trim()
                    if (text.startsWith("{")) {
                        val json = org.json.JSONObject(text)
                        val rawUrl = json.optString("url").ifBlank { json.optString("link") }.trim()
                        if (rawUrl.isNotBlank()) {
                            val resolvedUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                            // Re-route back through main getUrl to apply strict filtering
                            getUrl(resolvedUrl, landingUrl, subtitleCallback, callback) 
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(url: String, referer: String, subtitleCallback: (SubtitleFile) -> Unit, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = referer)
            // Voe obfuscates HLS link inside JS
            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(res.text)?.groupValues?.get(1)
            
            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    callback.invoke(newExtractorLink(name, "Voe (Susan) [Watch]", decoded.trim(), ExtractorLinkType.M3U8) {
                        this.quality = Qualities.P1080.value
                        this.referer = "${URI(res.url).scheme}://${URI(res.url).host}/"
                    })
                    return
                }
            }
            // Fallback to internal extractor if custom parsing fails
            loadExtractor(res.url, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }
}
