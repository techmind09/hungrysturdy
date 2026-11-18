package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI
import java.net.URLDecoder

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://hubcloud.mov"
    override val requiresReferer = false

    companion object {
        private val cfKiller by lazy { CloudflareKiller() }
        private const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
    }

    private fun isChallenge(res: NiceResponse): Boolean {
        if (res.code in listOf(403, 503, 429)) return true
        val body = res.text
        return body.contains("Just a moment", true) ||
               body.contains("cf-browser-verification", true) ||
               body.contains("Verifying...", true) ||
               body.contains("challenge-platform", true)
    }

    private suspend fun safeGet(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true
    ): NiceResponse {
        val reqHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        reqHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        reqHeaders.putAll(headers)

        val res = app.get(url, referer = referer, headers = reqHeaders, allowRedirects = allowRedirects)
        if (isChallenge(res)) {
            return app.get(url, referer = referer, headers = headers, interceptor = cfKiller, allowRedirects = allowRedirects)
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
            val targetUrl = url.trim()
            val lower = targetUrl.lowercase()

            when {
                // 1. Direct hbplay.pages.dev/?u=<BASE64> player link
                lower.contains("hbplay.pages.dev") -> {
                    extractHbPlay(targetUrl, callback)
                }

                // 2. HubCloud / GamerXYT links (hubcloud.mov/drive/..., hubcloud.cx/drive/..., gamerxyt.com/...)
                lower.contains("hubcloud") || lower.contains("gamerxyt") -> {
                    extractHubCloud(targetUrl, referer ?: mainUrl, subtitleCallback, callback)
                }

                // 3. 10Gbps Cryptonewz -> video-downloads.googleusercontent.com
                lower.contains("cryptonewz") || lower.contains("video-downloads.googleusercontent.com") -> {
                    extractCryptoNewz(targetUrl, referer ?: "https://gamerxyt.com/", callback)
                }

                // 4. PixelDrain (/u/ID -> /api/file/ID)
                lower.contains("pixeldrain") -> {
                    extractPixelDrain(targetUrl, callback)
                }

                // 5. Direct R2 Cloudflare Storage / Workers / GoogleUserContent
                lower.contains("r2.cloudflarestorage.com") || lower.contains(".r2.dev") || lower.contains(".workers.dev") -> {
                    callback.invoke(
                        newExtractorLink(
                            source = "HubCloud",
                            name = "HubCloud [FSL Server]",
                            url = targetUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = getQualityFromName(targetUrl)
                            this.referer = "https://hbplay.pages.dev/"
                        }
                    )
                }

                // 6. MomoFile / TMBCloud / Others
                lower.contains("momofile") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                lower.contains("tmbcloud") -> extractTmbCloud(targetUrl, callback)
                lower.contains("xcloud") -> extractXCloud(targetUrl, subtitleCallback, callback)
                lower.contains("theditch") || lower.contains("fileditch") -> extractFileDitch(targetUrl, callback)
                lower.contains("vik1ngfile") || lower.contains("vikingfile") -> extractVikingFile(targetUrl, callback)
                lower.contains("send.now") -> extractSendNow(targetUrl, callback)
                lower.contains("susanexpectcare") || lower.contains("voe.") -> extractVoe(targetUrl, referer ?: targetUrl, subtitleCallback, callback)

                else -> loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 1. HBPLAY.PAGES.DEV DECODER (?u=<base64_r2_url>&t=<base64_title>)
    // =========================================================================
    private suspend fun extractHbPlay(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val query = URI(url).rawQuery ?: return
            val params = query.split("&").associate {
                val idx = it.indexOf("=")
                if (idx > 0) it.substring(0, idx) to URLDecoder.decode(it.substring(idx + 1), "UTF-8")
                else it to ""
            }

            val encodedU = params["u"]?.takeIf { it.isNotBlank() } ?: return
            val decodedStreamUrl = decodeBase64Flexible(encodedU)

            val encodedTitle = params["t"].orEmpty()
            val decodedTitle = if (encodedTitle.isNotBlank()) decodeBase64Flexible(encodedTitle) else ""
            val qualityVal = getQualityFromName(decodedTitle.ifBlank { decodedStreamUrl })

            if (decodedStreamUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [Watch Online - R2]",
                        url = decodedStreamUrl,
                        type = if (decodedStreamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityVal
                        this.referer = "https://hbplay.pages.dev/"
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private fun decodeBase64Flexible(input: String): String {
        val clean = input.trim().replace("-", "+").replace("_", "/")
        val padded = when (clean.length % 4) {
            2 -> "$clean=="
            3 -> "$clean="
            else -> clean
        }
        return String(Base64.decode(padded, Base64.DEFAULT), Charsets.UTF_8)
    }

    // =========================================================================
    // 2. HUBCLOUD.MOV / HUBCLOUD.CX -> GAMERXYT.COM FLOW
    // =========================================================================
    private suspend fun extractHubCloud(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val firstRes = safeGet(url, referer = referer, allowRedirects = true)
            val firstDoc = firstRes.document
            val firstOrigin = "${URI(firstRes.url).scheme}://${URI(firstRes.url).host}"

            // Step 1: Check if already on gamerxyt / server list page or needs "Generate Direct Download Link" click
            val generateHref = firstDoc.select("a.btn[href], a[download][href], a[href]").firstOrNull { a ->
                val t = a.text().trim()
                val h = a.attr("href").trim()
                (t.contains("Generate Direct", true) || h.contains("gamerxyt") || h.contains("hubcloud.php")) &&
                h.isNotBlank() && !h.startsWith("#") && !h.startsWith("javascript")
            }?.attr("href")?.trim()

            val serverPageRes = if (!generateHref.isNullOrBlank()) {
                val nextUrl = if (generateHref.startsWith("http")) generateHref else "$firstOrigin/${generateHref.trimStart('/')}"
                safeGet(nextUrl, referer = firstRes.url, allowRedirects = true)
            } else {
                firstRes
            }

            val serverDoc = serverPageRes.document
            val fileTitle = serverDoc.selectFirst("div.card-header, h4, title")?.text().orEmpty()
            val detectedQuality = getQualityFromName(fileTitle)

            // Step 2: Parse all server buttons on gamerxyt.com/bgmi
            // [PixelServer : 2], [FSL Server], [Server : 10Gbps], [Watch Online]
            val anchors = serverDoc.select("a[href]")
            anchors.amap { a ->
                val href = a.attr("href").trim()
                val label = a.text().replace(Regex("""\s+"""), " ").trim()
                if (!href.startsWith("http")) return@amap
                if (href.contains("t.me") || href.contains("telegram") || href.contains("share") || href.contains("cloudflare.com")) return@amap

                when {
                    // Watch Online -> https://hbplay.pages.dev/?u=...
                    href.contains("hbplay.pages.dev") || label.contains("Watch Online", true) -> {
                        extractHbPlay(href, callback)
                    }

                    // Download [PixelServer : 2] -> https://pixeldrain.dev/u/...
                    href.contains("pixeldrain") || label.contains("PixelServer", true) -> {
                        extractPixelDrain(href, callback, detectedQuality)
                    }

                    // Download [FSL Server] -> https://...r2.cloudflarestorage.com/...
                    href.contains("r2.cloudflarestorage.com") || href.contains(".r2.dev") || label.contains("FSL", true) -> {
                        callback.invoke(
                            newExtractorLink(
                                source = "HubCloud",
                                name = "HubCloud [FSL Server]",
                                url = href,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = detectedQuality
                                this.referer = serverPageRes.url
                            }
                        )
                    }

                    // Download [Server : 10Gbps] -> https://cryptonewz.online/...
                    href.contains("cryptonewz") || href.contains("gpdl") || label.contains("10Gbps", true) -> {
                        extractCryptoNewz(href, serverPageRes.url, callback, detectedQuality)
                    }

                    // GoFile
                    href.contains("gofile.io") -> {
                        loadExtractor(href, serverPageRes.url, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 3. CRYPTONEWZ.ONLINE -> VIDEO-DOWNLOADS.GOOGLEUSERCONTENT.COM (10Gbps)
    // =========================================================================
    private suspend fun extractCryptoNewz(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        qualityVal: Int = Qualities.P720.value
    ) {
        try {
            if (url.contains("video-downloads.googleusercontent.com")) {
                callback.invoke(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [10Gbps Direct]",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityVal
                        this.referer = referer
                    }
                )
                return
            }

            val res = safeGet(url, referer = referer, allowRedirects = true)
            val finalUrl = res.url

            if (finalUrl.contains("video-downloads.googleusercontent.com") || finalUrl.endsWith(".mkv", true) || finalUrl.endsWith(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [10Gbps Direct]",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityVal
                        this.referer = url
                    }
                )
                return
            }

            // Parse "Download Here" anchor or JS redirect on cryptonewz.online
            val doc = res.document
            val dlHref = doc.selectFirst("a#download[href], a.btn[href*='googleusercontent'], a[href*='video-downloads'], a[href*='http']:contains(Download Here)")?.attr("href")?.trim()
                ?: Regex("""https?://video-downloads\.googleusercontent\.com/[^\s"'<>]+""").find(res.text)?.value
                ?: Regex("""window\.location(?:\.href)?\s*=\s*["'](https?://[^"']+)["']""").find(res.text)?.groupValues?.get(1)

            if (!dlHref.isNullOrBlank() && dlHref.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "HubCloud",
                        name = "HubCloud [10Gbps Direct]",
                        url = dlHref,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = qualityVal
                        this.referer = finalUrl
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 4. PIXELDRAIN (/u/ID -> /api/file/ID)
    // =========================================================================
    private suspend fun extractPixelDrain(
        url: String,
        callback: (ExtractorLink) -> Unit,
        qualityVal: Int = Qualities.P720.value
    ) {
        val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
        if (fileId.isBlank()) return
        val uri = URI(url)
        val domain = "${uri.scheme}://${uri.host}"
        val directStreamUrl = "$domain/api/file/$fileId"

        callback.invoke(
            newExtractorLink(
                source = "PixelDrain",
                name = "PixelDrain [Direct]",
                url = directStreamUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = qualityVal
                this.referer = "$domain/"
            }
        )
    }

    // =========================================================================
    // 5. OTHER SUPPORTED SERVERS (MomoFile, TMBCloud, XCloud, TheDitch, etc.)
    // =========================================================================
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

        val res = safeGet(landingUrl, referer = baseUrl)
        res.document.select("a[href]").amap { a ->
            val href = a.attr("href").trim()
            if (href.startsWith("http") && !href.contains(uri.host)) {
                getUrl(href, landingUrl, subtitleCallback, callback)
            }
        }

        if (fileId.isNotBlank() && fileId != "d") {
            listOf("pixeldrain", "buzzheavier", "fileditch", "vikingfile", "sendnow").amap { service ->
                try {
                    val apiRes = safeGet(
                        "$baseUrl/$service.php?id=$fileId",
                        referer = landingUrl,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                    if (apiRes.text.trim().startsWith("{")) {
                        val genUrl = JSONObject(apiRes.text).optString("url").trim()
                        if (genUrl.startsWith("http")) {
                            getUrl(genUrl, landingUrl, subtitleCallback, callback)
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private suspend fun extractTmbCloud(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = safeGet(url, referer = "https://tmbcloud.lol/")
            val doc = res.document
            val uri = URI(res.url)
            val baseUrl = "${uri.scheme}://${uri.host}"
            val pageId = doc.selectFirst("button[data-page], a[data-page]")?.attr("data-page")
                ?: uri.path.trimEnd('/').substringAfterLast('/')

            listOf("fslv2new", "fastcdnv2", "r2").amap { dataType ->
                try {
                    val postRes = app.post(
                        "$baseUrl/download/$pageId",
                        referer = res.url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest"),
                        data = mapOf("type" to dataType, "page" to pageId),
                        interceptor = cfKiller
                    )
                    if (postRes.text.trim().startsWith("{")) {
                        val dlUrl = JSONObject(postRes.text).optString("url")
                        if (dlUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "TMBCloud",
                                    name = "TMBCloud [${dataType.uppercase()}]",
                                    url = dlUrl,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P720.value
                                    this.referer = "$baseUrl/"
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val cleanId = URI(url).path.trimEnd('/').substringAfterLast('/').removePrefix("preview_")
            val res = safeGet("https://xcloud1.lol/$cleanId", referer = url)
            res.document.select("a[href]").amap { a ->
                val h = a.attr("href").trim()
                if (h.contains("/apis/") || h.contains("workers.dev") || h.contains("r2.dev")) {
                    val full = if (h.startsWith("/")) "https://xcloud1.lol$h" else h
                    val finalUrl = if (full.contains("/apis/")) {
                        safeGet(full, referer = res.url, allowRedirects = false).headers["location"] ?: full
                    } else full
                    if (finalUrl.startsWith("http")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "XCloud",
                                name = "XCloud - ${a.text().trim()}",
                                url = finalUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P720.value
                                this.referer = "https://xcloud1.lol/"
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractFileDitch(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = safeGet(url)
            val link = res.document.selectFirst("input[value*='theditch'], a[href*='theditch'], a[href*='.mkv'], a[href*='.mp4']")?.let {
                it.attr("value").ifBlank { it.attr("href") }
            }
            if (!link.isNullOrBlank() && link.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "TheDitch",
                        name = "TheDitch [Direct]",
                        url = link,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = res.url
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVikingFile(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = safeGet(url)
            val media = res.document.selectFirst("video source[src], video[src], a[download][href]")?.let {
                it.attr("src").ifBlank { it.attr("href") }
            }
            if (!media.isNullOrBlank() && media.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "VikingFile",
                        name = "VikingFile [Direct]",
                        url = media,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = res.url
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractSendNow(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = safeGet(url)
            val media = res.document.selectFirst("video source[src], video[src], a#download_link[href]")?.let {
                it.attr("src").ifBlank { it.attr("href") }
            }
            if (!media.isNullOrBlank() && media.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "SendNow",
                        name = "Send.now [Direct]",
                        url = media,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = res.url
                    }
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractVoe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = safeGet(url, referer = referer)
            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(res.text)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "VOE",
                        name = "Online Watch (HLS)",
                        url = rawM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = res.url
                    }
                )
            } else {
                loadExtractor(res.url, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}
