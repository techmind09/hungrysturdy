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
// 1. GENERIC CLONE EXTRACTORS
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
// 2. KDHINDIDUBBED MULTI-SERVER EXTRACTOR
// ==========================================
class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
    }

    private fun isCloudflareChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 503, 429)) return true
        val body = res.text
        return body.contains("Just a moment", true) || body.contains("cf-browser-verification", true) || body.contains("cf-turnstile", true)
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

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var targetUrl = url.trim()
            if (!targetUrl.startsWith("http")) return

            if (targetUrl.matches(Regex(".*\\.(mp4|mkv|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
                callback.invoke(
                    newExtractorLink(name, "$name [Direct]", targetUrl, if (targetUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P1080.value
                        this.referer = referer ?: mainUrl
                    }
                )
                return
            }

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
                lower.contains("momofile.shop") || lower.contains("momofile") -> {
                    extractMomoFile(targetUrl, subtitleCallback, callback)
                }
                lower.contains("pixeldrain") -> {
                    extractPixelDrain(targetUrl, callback)
                }
                lower.contains("susanexpectcare") || lower.contains("voe.sx") || lower.contains("voe.") -> {
                    extractVoe(targetUrl, referer ?: "https://momofile.shop/", subtitleCallback, callback)
                }
                lower.contains("send.now") || lower.contains("sendnow") -> {
                    extractSendNow(targetUrl, subtitleCallback, callback)
                }
                lower.contains("vik1ngfile.site") || lower.contains("vikingfile") -> {
                    loadExtractor(targetUrl, "https://vik1ngfile.site/", subtitleCallback, callback)
                }
                lower.contains("fileditchvids.st") || lower.contains("fileditch") || lower.contains("theditch") -> {
                    extractFileDitch(targetUrl, subtitleCallback, callback)
                }
                lower.contains("tmbcloud.lol") || lower.contains("tmbcloud") -> {
                    extractTmbCloud(targetUrl, subtitleCallback, callback)
                }
                lower.contains("xcloud") -> {
                    extractXCloud(targetUrl, subtitleCallback, callback)
                }
                lower.contains("buzzheavier") -> {
                    extractBuzzheavier(targetUrl, callback)
                }
                else -> {
                    loadExtractor(targetUrl, referer, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun resolveFinalStreamUrl(startUrl: String, refererUrl: String): String {
        return try {
            val resp = app.get(startUrl, referer = refererUrl, allowRedirects = true)
            resp.url.ifEmpty { startUrl }
        } catch (_: Exception) {
            startUrl
        }
    }

    private suspend fun extractPixelDrain(url: String, callback: (ExtractorLink) -> Unit) {
        val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
        if (fileId.isBlank()) return

        // Both .dev and .com variants emitted for robust connectivity
        callback.invoke(
            newExtractorLink("PixelDrain.dev", "PixelDrain [pixeldrain.dev]", "https://pixeldrain.dev/api/file/$fileId?download", ExtractorLinkType.VIDEO) {
                this.quality = Qualities.P1080.value
                this.referer = "https://pixeldrain.dev/u/$fileId"
            }
        )
        callback.invoke(
            newExtractorLink("PixelDrain", "PixelDrain [pixeldrain.com]", "https://pixeldrain.com/api/file/$fileId?download", ExtractorLinkType.VIDEO) {
                this.quality = Qualities.P1080.value
                this.referer = "https://pixeldrain.com/u/$fileId"
            }
        )
    }

    private suspend fun extractMomoFile(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = URI(url)
        val baseUrl = "${uri.scheme}://${uri.host}"
        val fileId = uri.query?.substringAfter("id=")?.substringBefore("&")?.takeIf { it.isNotBlank() }
            ?: uri.path.trimEnd('/').substringAfterLast('/')
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
                    } else if (fullSrc.matches(Regex(".*\\.(mp4|mkv|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE))) {
                        callback.invoke(
                            newExtractorLink("momofile.shop", "MomoFile [Direct]", fullSrc, if (fullSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO) {
                                this.quality = Qualities.P720.value
                                this.referer = landingUrl
                            }
                        )
                    }
                }
            }

            for (a in doc.select("a[href]")) {
                val rawHref = a.attr("href").trim()
                if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript")) continue
                val href = if (rawHref.startsWith("/")) "$baseUrl$rawHref" else rawHref

                if (href.contains("voe") || href.contains("susanexpectcare") || href.contains("/access/")) {
                    extractVoe(href, landingUrl, subtitleCallback, callback)
                } else if (!href.contains(uri.host)) {
                    getUrl(href, landingUrl, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}

        if (fileId.isNotBlank() && fileId != "d") {
            val services = listOf("pixeldrain", "sendnow", "fileditch", "vikingfile", "buzzheavier")
            for (service in services) {
                try {
                    val phpUrl = "$baseUrl/$service.php?id=$fileId"
                    val apiRes = cfGet(
                        phpUrl,
                        referer = landingUrl,
                        cookies = sessionCookies,
                        headers = mapOf(
                            "Accept" to "application/json, text/javascript, */*; q=0.01",
                            "X-Requested-With" to "XMLHttpRequest"
                        )
                    )
                    val text = apiRes.text.trim()
                    if (text.startsWith("{")) {
                        val json = JSONObject(text)
                        val rawUrl = json.optString("url").ifBlank { json.optString("link") }.trim()
                        if (rawUrl.isNotBlank()) {
                            val resolvedUrl = if (rawUrl.startsWith("/")) "$baseUrl$rawUrl" else rawUrl
                            getUrl(resolvedUrl, landingUrl, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun extractVoe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = referer)
            val pageText = res.text

            val jsRedirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(pageText)?.groupValues?.get(1)
            val finalRes = if (!jsRedirect.isNullOrBlank()) cfGet(jsRedirect, referer = url) else res
            val body = finalRes.text
            val hostBase = "${URI(finalRes.url).scheme}://${URI(finalRes.url).host}/"

            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(body)?.groupValues?.get(1)
                ?: Regex("""['"]file['"]\s*:\s*['"](https?://[^'"]+\.m3u8[^'"]*)['"]""").find(body)?.groupValues?.get(1)

            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) {
                    hlsMatch
                } else {
                    runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                }
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    val directCdnUrl = resolveFinalStreamUrl(decoded.trim(), hostBase)
                    callback.invoke(
                        newExtractorLink("Voe", "Voe / SusanExpectCare", directCdnUrl, ExtractorLinkType.M3U8) {
                            this.quality = Qualities.P720.value
                            this.referer = hostBase
                        }
                    )
                    return
                }
            }

            loadExtractor(finalRes.url, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    private suspend fun extractSendNow(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = "https://send.now/")
            val directVideo = res.document.selectFirst("video source[src], video[src], a#download_link[href]")?.attr("src")
                ?.ifBlank { res.document.selectFirst("a#download_link[href]")?.attr("href") }

            if (!directVideo.isNullOrBlank() && directVideo.startsWith("http")) {
                val directCdnUrl = resolveFinalStreamUrl(directVideo.trim(), "https://send.now/")
                callback.invoke(
                    newExtractorLink("send.now", "Send.now [Direct]", directCdnUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://send.now/"
                    }
                )
                return
            }
        } catch (_: Exception) {}
        loadExtractor(url, "https://send.now/", subtitleCallback, callback)
    }

    private suspend fun extractFileDitch(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = "https://fileditchvids.st/")
            val src = res.document.selectFirst("video source[src], video[src], a.download-button[href]")?.let {
                it.attr("src").ifBlank { it.attr("href") }
            }
            if (!src.isNullOrBlank() && src.startsWith("http")) {
                val directCdnUrl = resolveFinalStreamUrl(src.trim(), "https://fileditchvids.st/")
                callback.invoke(
                    newExtractorLink("fileditchvids.st", "Fileditch [Direct]", directCdnUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://fileditchvids.st/"
                    }
                )
                return
            }
        } catch (_: Exception) {}
        loadExtractor(url, "https://fileditchvids.st/", subtitleCallback, callback)
    }

    private suspend fun extractTmbCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = "https://tmbcloud.lol/")
            val doc = res.document
            var foundDirect = false

            for (a in doc.select("a[href]")) {
                val href = a.attr("href").trim()
                if (href.contains("/fslv2") || href.contains("/cloudfilesv2") || href.contains("/cloudfiles") ||
                    href.contains("r2.dev") || href.contains("workers.dev") ||
                    href.matches(Regex(".*\\.(mp4|mkv)(\\?.*)?$", RegexOption.IGNORE_CASE))
                ) {
                    val fullHref = if (href.startsWith("/")) "https://tmbcloud.lol$href" else href
                    val directCdnUrl = resolveFinalStreamUrl(fullHref, "https://tmbcloud.lol/")
                    val serverLabel = when {
                        fullHref.contains("fslv2", true) -> "tmbcloud.lol/fslv2"
                        fullHref.contains("cloudfilesv2", true) -> "tmbcloud.lol/cloudfilesv2"
                        fullHref.contains("cloudfiles", true) -> "tmbcloud.lol/cloudfiles"
                        else -> "tmbcloud.lol"
                    }
                    foundDirect = true
                    callback.invoke(
                        newExtractorLink(serverLabel, serverLabel, directCdnUrl, ExtractorLinkType.VIDEO) {
                            this.quality = Qualities.P1080.value
                            this.referer = "https://tmbcloud.lol/"
                        }
                    )
                }
            }
            if (foundDirect) return
        } catch (_: Exception) {}
        loadExtractor(url, "https://tmbcloud.lol/", subtitleCallback, callback)
    }

    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = app.get("${url.trimEnd('/')}/download", referer = url, headers = mapOf("HX-Request" to "true"), allowRedirects = false)
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                val directCdnUrl = resolveFinalStreamUrl(hxRedirect.trim(), url)
                callback.invoke(
                    newExtractorLink("Buzzheavier", "Buzzheavier [Direct]", directCdnUrl, ExtractorLinkType.VIDEO) {
                        this.quality = Qualities.P720.value
                        this.referer = url
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val xCloudResponse = app.get(url)
            val xCloudDoc = xCloudResponse.document
            val resolvedPageUrl = xCloudResponse.url.ifEmpty { url }
            val baseCookies = xCloudResponse.cookies

            val mediaId = resolvedPageUrl.substringAfterLast("/").substringBefore("?").trim()
            val baseUri = try {
                val u = URI(resolvedPageUrl)
                "${u.scheme}://${u.host}"
            } catch (_: Exception) {
                "https://stream.xcloud1.lol"
            }

            val streamCandidates = linkedSetOf<String>()
            xCloudDoc.selectFirst("div.vd a[href*='stream_'], a[href*='/stream_']")
                ?.absUrl("href")
                ?.takeIf { it.isNotEmpty() }
                ?.let { streamCandidates.add(it) }

            if (mediaId.isNotEmpty()) {
                streamCandidates.add("$baseUri/stream_$mediaId")
                streamCandidates.add("https://stream.xcloud1.lol/$mediaId")
            }

            for (streamPageUrl in streamCandidates) {
                try {
                    val streamResp = app.get(streamPageUrl, cookies = baseCookies)
                    val finalPlayerUrl = streamResp.url.ifEmpty { streamPageUrl }
                    val streamHtml = streamResp.text

                    Regex("""\{[^{}]*"lang"\s*:\s*"([^"]+)"[^{}]*"tgid"\s*:\s*(\d+)[^{}]*\}""")
                        .findAll(streamHtml)
                        .forEach { m ->
                            subtitleCallback.invoke(
                                SubtitleFile(m.groupValues[1], "https://sub.xcloud.icu/${m.groupValues[2]}")
                            )
                        }

                    val varPatterns = listOf(
                        "PRIMARY_URL" to ("XCloud Server 1" to ExtractorLinkType.VIDEO),
                        "WASABI_URL" to ("XCloud Server 2" to ExtractorLinkType.VIDEO),
                        "HLS_URL" to ("XCloud HLS" to ExtractorLinkType.M3U8)
                    )

                    for ((varName, info) in varPatterns) {
                        val match = Regex("""var\s+$varName\s*=|["']$varName["']\s*[:=]\s*["']([^"']+)["']""").find(streamHtml)
                        val rawVal = match?.groupValues?.get(1)
                        if (!rawVal.isNullOrEmpty() && rawVal != "null") {
                            val redirectUrl = try {
                                URI(finalPlayerUrl).resolve(rawVal.replace("\\/", "/")).toString()
                            } catch (_: Exception) {
                                rawVal
                            }
                            val directCdnUrl = resolveFinalStreamUrl(redirectUrl, finalPlayerUrl)

                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = info.first,
                                    url = directCdnUrl,
                                    type = info.second
                                ) {
                                    this.referer = ""
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
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
            href.matches(Regex(".*\\.(mkv|mp4|ts|m3u8)(\\?.*)?$", RegexOption.IGNORE_CASE)) -> {
                pushDirect("Direct Media", href)
            }
            host.contains("hakunaymatata") -> pushDirect("Fast Server [KatDrama]", href, ExtractorLinkType.DASH)
            host.contains("devcorp.me") -> pushDirect("DevCorp Stream [KatDrama]", href, ExtractorLinkType.M3U8)
            host.contains("modiplay.xyz") || host.contains("rozgarlelo") || host.contains("fvibuxer") -> {
                val targetUrl = try {
                    if (href.contains("url=")) java.net.URLDecoder.decode(href.substringAfter("url=").substringBefore("&"), "UTF-8") else href
                } catch (_: Exception) { href }
                pushDirect("Hindi Proxy [KatDrama]", targetUrl)
            }
            host.contains("vidmoly") || host.contains("staticmoly") -> {
                loadExtractor(href.replace("staticmoly.me/static/jquery.min.js", "vidmoly.org"), data, subtitleCallback, callback)
            }
            host.contains("pixeldrain") -> {
                val fileId = href.trimEnd('/').substringAfterLast('/').substringBefore("?")
                if (fileId.isNotBlank()) {
                    pushDirect("PixelDrain.dev", "https://pixeldrain.dev/api/file/$fileId?download", ExtractorLinkType.VIDEO)
                    pushDirect("PixelDrain", "https://pixeldrain.com/api/file/$fileId?download", ExtractorLinkType.VIDEO)
                } else {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
            else -> {
                if (href.startsWith("http")) {
                    loadExtractor(href, data, subtitleCallback, callback)
                }
            }
        }
    }
}
