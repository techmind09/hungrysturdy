package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.nicehttp.NiceResponse
import org.json.JSONObject
import java.net.URI
import android.util.Base64

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
        allowRedirects: Boolean = true
    ): NiceResponse {
        val baseHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        baseHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        baseHeaders.putAll(headers)

        val res = app.get(url, referer = referer, headers = baseHeaders, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) {
            return app.get(
                url,
                referer = referer,
                headers = headers,
                interceptor = cfKiller,
                allowRedirects = allowRedirects
            )
        }
        return res
    }

    private suspend fun cfPost(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true
    ): NiceResponse {
        val baseHeaders = mutableMapOf("User-Agent" to USER_AGENT)
        baseHeaders.putAll(cfKiller.getCookieHeaders(url).toMap())
        baseHeaders.putAll(headers)

        val res = app.post(url, referer = referer, headers = baseHeaders, data = data, allowRedirects = allowRedirects)
        if (isCloudflareChallenge(res)) {
            return app.post(
                url,
                referer = referer,
                headers = headers,
                data = data,
                interceptor = cfKiller,
                allowRedirects = allowRedirects
            )
        }
        return res
    }

    private fun buildPlayerHeaders(pageUrl: String, referer: String): Map<String, String> {
        val map = mutableMapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer
        )
        val cfCookies = cfKiller.getCookieHeaders(pageUrl).toMap()
        map.putAll(cfCookies)
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

    // =========================================================================
    // 1. MOMOFILE.SHOP HANDLER
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

        // A) Scan MomoFile HTML for "Online Watching Link 2" (VOE / SusanExpectCare) & direct links
        try {
            val res = cfGet(url, referer = baseUrl)
            val doc = res.document

            // Check any embedded iframe or video tag
            doc.select("iframe[src], video source[src], video[src]").forEach { el ->
                val rawSrc = el.attr("src").trim()
                if (rawSrc.isNotBlank() && !rawSrc.startsWith("javascript")) {
                    val fullSrc = if (rawSrc.startsWith("/")) "$baseUrl$rawSrc" else rawSrc
                    if (fullSrc.contains("voe") || fullSrc.contains("susanexpectcare") || fullSrc.contains("/access/")) {
                        extractVoe(fullSrc, url, subtitleCallback, callback)
                    } else if (fullSrc.contains(".mp4") || fullSrc.contains(".mkv") || fullSrc.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "MomoFile",
                                name = "MomoFile [Player]",
                                url = fullSrc,
                                type = if (fullSrc.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P720.value
                                this.referer = url
                                this.headers = buildPlayerHeaders(url, url)
                            }
                        )
                    }
                }
            }

            // Check all buttons on momofile page (including Online Watching Link 2)
            val anchors = doc.select("a[href]")
            anchors.amap { a ->
                val rawHref = a.attr("href").trim()
                val text = a.text().trim()
                if (rawHref.isNotBlank() && !rawHref.startsWith("#") && !rawHref.startsWith("javascript")) {
                    val href = if (rawHref.startsWith("/")) "$baseUrl$rawHref" else rawHref
                    when {
                        text.contains("Online Watching", true) || href.contains("voe") || href.contains("susanexpectcare") || href.contains("/access/") -> {
                            extractVoe(href, url, subtitleCallback, callback)
                        }
                        !href.contains(uri.host) -> {
                            getUrl(href, url, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        // B) Parallel fetch MomoFile PHP JSON APIs
        if (fileId.isNotBlank() && fileId != "d") {
            val services = listOf("pixeldrain", "fileditch", "vikingfile", "sendnow", "buzzheavier")
            services.amap { service ->
                try {
                    val endpoint = "$baseUrl/$service.php?id=$fileId"
                    val apiRes = cfGet(
                        endpoint,
                        referer = url,
                        headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                    )
                    val text = apiRes.text.trim()
                    if (text.startsWith("{")) {
                        val json = JSONObject(text)
                        val genUrl = json.optString("url").takeIf { it.startsWith("http") }
                        if (!genUrl.isNullOrBlank()) {
                            when (service) {
                                "pixeldrain" -> extractPixelDrain(genUrl, callback)
                                "fileditch" -> extractFileDitch(genUrl, callback)
                                "vikingfile" -> extractVikingFile(genUrl, callback)
                                "sendnow" -> extractSendNow(genUrl, callback)
                                "buzzheavier" -> extractBuzzheavier(genUrl, callback)
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // =========================================================================
    // 2. PIXELDRAIN (https://pixeldrain.dev/u/z6TyKYwY -> /api/file/z6TyKYwY)
    // =========================================================================
    private suspend fun extractPixelDrain(url: String, callback: (ExtractorLink) -> Unit) {
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
                this.quality = Qualities.P720.value
                this.referer = "$domain/"
            }
        )
    }

    // =========================================================================
    // 3. FILEDITCH / THEDITCH (Fixes 3003 Container Unsupported Error)
    // =========================================================================
    private suspend fun extractFileDitch(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            // Step 1: Open fileditchvids.st or theditch.st page
            val res = cfGet(url, allowRedirects = true)
            val currentUrl = res.url
            val doc = res.document

            // Check if page has an intermediate link to theditch.st/<id>
            val ditchPageLink = doc.selectFirst("input[value*='theditch'], a[href*='theditch']")?.let {
                it.attr("value").ifBlank { it.attr("href") }
            } ?: Regex("""https?://[a-zA-Z0-9.-]*theditch\.[a-z]+/[a-zA-Z0-9_-]+""").find(res.text)?.value

            val targetRes = if (!ditchPageLink.isNullOrBlank() && ditchPageLink != currentUrl) {
                cfGet(ditchPageLink.trim(), referer = currentUrl, allowRedirects = true)
            } else {
                res
            }

            val finalDoc = targetRes.document
            val finalPageUrl = targetRes.url
            val baseHost = "${URI(finalPageUrl).scheme}://${URI(finalPageUrl).host}"

            // Step 2: Extract the ACTUAL direct video file URL from theditch.st page (NOT the HTML page URL!)
            var directFileUrl = finalDoc.selectFirst(
                "video source[src], video[src], a.download-button[href], a#download-btn[href], a[href*='fileditchfiles'], a[href*='b-cdn.net'], a[href*='.mkv'], a[href*='.mp4'], a[download][href]"
            )?.let {
                it.attr("src").ifBlank { it.attr("href") }
            }

            if (directFileUrl.isNullOrBlank()) {
                directFileUrl = Regex("""https?://[^\s"'<>]+(?:\.mkv|\.mp4|fileditchfiles[^\s"'<>]+|/file/[^\s"'<>]+)""").find(targetRes.text)?.value
            }

            // If a button points to a redirect route on theditch.st (like /dl/... or ?download=1)
            if (directFileUrl.isNullOrBlank()) {
                val dlBtn = finalDoc.selectFirst("a.btn[href], a.button[href], a[href*='dl'], a[href*='get']")?.attr("href")
                if (!dlBtn.isNullOrBlank() && dlBtn != "#" && !dlBtn.endsWith(URI(finalPageUrl).path)) {
                    directFileUrl = if (dlBtn.startsWith("/")) "$baseHost$dlBtn" else dlBtn
                }
            }

            if (!directFileUrl.isNullOrBlank()) {
                val fullStreamUrl = if (directFileUrl.startsWith("/")) "$baseHost$directFileUrl" else directFileUrl.trim()
                // Ensure we are not passing the HTML landing page itself
                if (fullStreamUrl != finalPageUrl) {
                    callback.invoke(
                        newExtractorLink(
                            source = "TheDitch",
                            name = "TheDitch [Direct]",
                            url = fullStreamUrl,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = "$baseHost/"
                            this.headers = buildPlayerHeaders(finalPageUrl, "$baseHost/")
                        }
                    )
                }
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 4. VIKINGFILE (Fixes 2000 & 2004 HTTP Status Error)
    // =========================================================================
    private suspend fun extractVikingFile(
        url: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
            if (fileId.isBlank()) return

            val urlsToTry = listOf(
                "https://vikingfile.com/f/$fileId",
                "https://vik1ngfile.site/f/$fileId",
                url
            ).distinct()

            for (target in urlsToTry) {
                try {
                    val res = cfGet(target, referer = "https://momofile.shop/")
                    val doc = res.document
                    val base = "${URI(res.url).scheme}://${URI(res.url).host}"

                    // Strictly match only media sources or explicit download anchor (avoiding navigation/donation buttons)
                    var mediaUrl = doc.selectFirst("video source[src], video[src], a#download-url[href], a[download][href]")?.let {
                        it.attr("src").ifBlank { it.attr("href") }
                    }

                    if (mediaUrl.isNullOrBlank()) {
                        // Look for button whose text explicitly says "Download"
                        val dlAnchor = doc.select("a[href]").firstOrNull { a ->
                            val t = a.text().trim()
                            val h = a.attr("href").trim()
                            t.equals("Download", true) && h.isNotBlank() && !h.startsWith("#") && !h.startsWith("javascript")
                        }
                        mediaUrl = dlAnchor?.attr("href")
                    }

                    if (mediaUrl.isNullOrBlank()) {
                        // Look inside JS variables for the stream URL
                        mediaUrl = Regex("""(?:file|src|videoUrl|downloadUrl|url)\s*[:=]\s*["'](https?://[^"']+(?:\.mp4|\.mkv|\.m3u8|/d/[^"']+|/dl/[^"']+))["']""", RegexOption.IGNORE_CASE)
                            .find(res.text)?.groupValues?.get(1)
                    }

                    if (!mediaUrl.isNullOrBlank()) {
                        val resolvedUrl = if (mediaUrl.startsWith("/")) "$base$mediaUrl" else mediaUrl.trim()
                        if (!resolvedUrl.endsWith("/f/$fileId")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "VikingFile",
                                    name = "VikingFile [Direct]",
                                    url = resolvedUrl,
                                    type = if (resolvedUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P720.value
                                    this.referer = res.url
                                    this.headers = buildPlayerHeaders(res.url, res.url)
                                }
                            )
                            return
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 5. SEND.NOW (Fixes 3003 Container Unsupported Error)
    // =========================================================================
    private suspend fun extractSendNow(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://send.now/")
            val doc = res.document

            var dlLink: String? = null

            // XFileSharing form POST (op=download2)
            val form = doc.selectFirst("form[name='F1'], form[method='POST']")
            if (form != null) {
                val postData = mutableMapOf<String, String>()
                form.select("input[name]").forEach { input ->
                    postData[input.attr("name")] = input.attr("value")
                }
                postData["op"] = "download2"
                if (!postData.containsKey("id")) {
                    postData["id"] = url.trimEnd('/').substringAfterLast('/')
                }

                val postRes = cfPost(
                    res.url,
                    referer = res.url,
                    data = postData,
                    allowRedirects = false
                )

                val redirectLoc = postRes.headers["location"] ?: postRes.headers["Location"]
                dlLink = if (!redirectLoc.isNullOrBlank() && redirectLoc.startsWith("http")) {
                    redirectLoc
                } else {
                    postRes.document.selectFirst("a#download_link[href], span#direct_link a[href], a[href*='.mkv'], a[href*='.mp4'], video source[src]")?.let {
                        it.attr("href").ifBlank { it.attr("src") }
                    } ?: Regex("""https?://[^\s"'<>]+(?:\.mkv|\.mp4|/d/[a-zA-Z0-9]+/[^\s"'<>]+)""").find(postRes.text)?.value
                }
            }

            if (dlLink.isNullOrBlank()) {
                dlLink = doc.selectFirst("video source[src], video[src], a#download_link[href], a[href*='.mkv'], a[href*='.mp4']")?.let {
                    it.attr("src").ifBlank { it.attr("href") }
                }
            }

            // Only invoke callback if we got a real file URL and not the landing page itself
            if (!dlLink.isNullOrBlank() && dlLink.startsWith("http") && dlLink.trim() != res.url) {
                callback.invoke(
                    newExtractorLink(
                        source = "SendNow",
                        name = "Send.now [Direct]",
                        url = dlLink.trim(),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://send.now/"
                        this.headers = buildPlayerHeaders(res.url, "https://send.now/")
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 6. VOE.SX / SUSANEXPECTCARE.COM ("Online Watching Link 2")
    // =========================================================================
    private suspend fun extractVoe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = referer, allowRedirects = true)
            var html = res.text
            var finalUrl = res.url

            // Follow JS window.location.href redirect (voe.sx -> susanexpectcare.com)
            val jsRedirect = Regex("""window\.location(?:\.href)?\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!jsRedirect.isNullOrBlank()) {
                val res2 = cfGet(jsRedirect, referer = finalUrl, allowRedirects = true)
                html = res2.text
                finalUrl = res2.url
            }

            val hostBase = "${URI(finalUrl).scheme}://${URI(finalUrl).host}/"

            // 1. Check 'hls' or 'mp4' inside VOE sources object
            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "SusanExpectCare",
                            name = "SusanExpectCare [Online Watch]",
                            url = decoded.trim(),
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = hostBase
                            this.headers = buildPlayerHeaders(finalUrl, hostBase)
                        }
                    )
                    return
                }
            }

            // 2. Check Base64 encoded 'wc0' / 'a1' obfuscated strings in newer VOE pages
            val b64Candidates = Regex("""atob\(['"]([A-Za-z0-9+/=]{20,})['"]\)""").findAll(html)
            for (m in b64Candidates) {
                val decoded = runCatching { String(Base64.decode(m.groupValues[1], Base64.DEFAULT)) }.getOrNull() ?: continue
                val m3u8InDecoded = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(decoded)?.value
                if (!m3u8InDecoded.isNullOrBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            source = "SusanExpectCare",
                            name = "SusanExpectCare [Online Watch]",
                            url = m3u8InDecoded,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = hostBase
                            this.headers = buildPlayerHeaders(finalUrl, hostBase)
                        }
                    )
                    return
                }
            }

            // 3. Plain .m3u8 URL in HTML
            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "SusanExpectCare",
                        name = "SusanExpectCare [Online Watch]",
                        url = rawM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = hostBase
                        this.headers = buildPlayerHeaders(finalUrl, hostBase)
                    }
                )
                return
            }

            loadExtractor(finalUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 7. XCLOUD (https://xcloud.tattoo/ID & https://xcloud1.lol/preview_ID -> https://xcloud1.lol/ID)
    // =========================================================================
    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val rawSegment = URI(url).path.trimEnd('/').substringAfterLast('/')
            val cleanId = rawSegment.removePrefix("preview_")
            if (cleanId.isBlank()) return

            val candidatePages = listOf(
                "https://xcloud1.lol/$cleanId",
                "https://xcloud1.lol/preview_$cleanId",
                url
            ).distinct()

            for (pageUrl in candidatePages) {
                try {
                    val res = cfGet(pageUrl, referer = url)
                    val doc = res.document
                    val hostBase = "${URI(res.url).scheme}://${URI(res.url).host}"

                    // Check direct video tag on preview page
                    doc.select("video source[src], video[src]").forEach { v ->
                        val vSrc = v.attr("src").trim()
                        if (vSrc.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = "XCloud [Stream]",
                                    url = vSrc,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P720.value
                                    this.referer = "$hostBase/"
                                    this.headers = buildPlayerHeaders(res.url, "$hostBase/")
                                }
                            )
                        }
                    }

                    val buttons = doc.select("a[href]").filter { a ->
                        val h = a.attr("href").trim()
                        val t = a.text().trim()
                        (h.contains("/apis/") || h.contains("stepnestph") || h.contains("workers.dev") ||
                         h.contains("r2.dev") || h.contains("gofile") || t.contains("Download [", true)) &&
                        !h.endsWith("/$cleanId") && !h.contains("preview_")
                    }

                    if (buttons.isNotEmpty()) {
                        buttons.amap { btn ->
                            val rawHref = btn.attr("href").trim()
                            val fullHref = if (rawHref.startsWith("/")) "$hostBase$rawHref" else rawHref
                            val btnLabel = btn.text().trim().ifBlank { "Server" }

                            if (fullHref.contains("gofile")) {
                                loadExtractor(fullHref, res.url, subtitleCallback, callback)
                            } else if (fullHref.contains("/apis/")) {
                                val apiRes = cfGet(fullHref, referer = res.url, allowRedirects = false)
                                val redirectUrl = apiRes.headers["location"] ?: apiRes.headers["Location"]
                                val finalStream = if (!redirectUrl.isNullOrBlank() && redirectUrl.startsWith("http")) {
                                    redirectUrl
                                } else {
                                    cfGet(fullHref, referer = res.url, allowRedirects = true).url
                                }

                                if (finalStream.startsWith("http") && !finalStream.contains("/apis/")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "XCloud",
                                            name = "XCloud - $btnLabel",
                                            url = finalStream,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = Qualities.P720.value
                                            this.referer = "$hostBase/"
                                            this.headers = buildPlayerHeaders(res.url, "$hostBase/")
                                        }
                                    )
                                }
                            } else if (fullHref.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "XCloud",
                                        name = "XCloud - $btnLabel",
                                        url = fullHref,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.P720.value
                                        this.referer = "$hostBase/"
                                        this.headers = buildPlayerHeaders(res.url, "$hostBase/")
                                    }
                                )
                            }
                        }
                        break
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 8. TMBCLOUD.LOL (/links/716cacc7a622 & /download/413Y57)
    // =========================================================================
    private suspend fun extractTmbCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = cfGet(url, referer = "https://tmbcloud.lol/", allowRedirects = true)
            val doc = res.document
            val uri = URI(res.url)
            val baseUrl = "${uri.scheme}://${uri.host}"

            if (res.url.contains("/links/")) {
                val innerLinks = doc.select("a[href]").mapNotNull { a ->
                    val h = fixUrl(a.attr("href").trim())
                    if (h.startsWith("http") && h != res.url && !h.contains("telegram.me") && !h.contains("t.me")) h else null
                }.distinct()

                innerLinks.amap { link ->
                    if (link.contains("/download/")) {
                        extractTmbDownloadPage(link, res.url, callback)
                    } else {
                        getUrl(link, res.url, subtitleCallback, callback)
                    }
                }
                return
            }

            extractTmbDownloadPage(res.url, baseUrl, callback, doc)
        } catch (_: Exception) {}
    }

    private suspend fun extractTmbDownloadPage(
        url: String,
        referer: String,
        callback: (ExtractorLink) -> Unit,
        preloadedDoc: org.jsoup.nodes.Document? = null
    ) {
        val doc = preloadedDoc ?: cfGet(url, referer = referer).document
        val uri = URI(url)
        val baseUrl = "${uri.scheme}://${uri.host}"

        val pageId = doc.selectFirst("button[data-page], a[data-page], div[data-page]")?.attr("data-page")
            ?.takeIf { it.isNotBlank() }
            ?: uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "links" }

        if (!pageId.isNullOrBlank()) {
            val dynamicTypes = doc.select("button[data-type], a[data-type]").mapNotNull {
                it.attr("data-type").takeIf { t -> t.isNotBlank() }
            }
            val typesToTry = (dynamicTypes + listOf("fslv2new", "fastcdnv2", "r2", "fsl", "pixel")).distinct()

            typesToTry.amap { dataType ->
                try {
                    val apiRes = cfPost(
                        "$baseUrl/download/$pageId",
                        referer = url,
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to baseUrl
                        ),
                        data = mapOf("type" to dataType, "page" to pageId)
                    )
                    val text = apiRes.text.trim()
                    if (text.startsWith("{")) {
                        val json = JSONObject(text)
                        val dlUrl = json.optString("url").takeIf { it.startsWith("http") }
                            ?: json.optString("download_url").takeIf { it.startsWith("http") }
                            ?: json.optString("file").takeIf { it.startsWith("http") }
                        if (!dlUrl.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "TMBCloud",
                                    name = "TMBCloud [${dataType.uppercase()}]",
                                    url = dlUrl,
                                    type = if (dlUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.referer = "$baseUrl/"
                                    this.headers = buildPlayerHeaders(url, "$baseUrl/")
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        }
    }

    // =========================================================================
    // 9. BUZZHEAVIER
    // =========================================================================
    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanUrl = url.trimEnd('/')
            val downloadEndpoint = "$cleanUrl/download"
            val res = cfGet(
                downloadEndpoint,
                referer = url,
                headers = mapOf("HX-Request" to "true", "HX-Current-URL" to url),
                allowRedirects = false
            )
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "Buzzheavier",
                        name = "Buzzheavier [Direct]",
                        url = hxRedirect,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = url
                        this.headers = buildPlayerHeaders(url, url)
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
