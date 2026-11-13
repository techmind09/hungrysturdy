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
    }

    private suspend fun cfGet(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        allowRedirects: Boolean = true
    ): NiceResponse {
        val res = app.get(url, referer = referer, headers = headers, allowRedirects = allowRedirects)
        if (res.code in listOf(403, 503) || res.text.contains("Just a moment", true) || res.text.contains("cf-browser-verification", true) || res.text.contains("Verifying...", true)) {
            return app.get(url, referer = referer, headers = headers, interceptor = cfKiller, allowRedirects = allowRedirects)
        }
        return res
    }

    private suspend fun cfPost(
        url: String,
        referer: String? = null,
        headers: Map<String, String> = emptyMap(),
        data: Map<String, String> = emptyMap()
    ): NiceResponse {
        val res = app.post(url, referer = referer, headers = headers, data = data)
        if (res.code in listOf(403, 503) || res.text.contains("Just a moment", true)) {
            return app.post(url, referer = referer, headers = headers, data = data, interceptor = cfKiller)
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
            val targetUrl = unwrapRedirect(url.trim(), referer ?: mainUrl)
            val lower = targetUrl.lowercase()

            when {
                lower.contains("momofile") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                lower.contains("xcloud") -> extractXCloud(targetUrl, subtitleCallback, callback)
                lower.contains("tmbcloud") -> extractTmbCloud(targetUrl, subtitleCallback, callback)
                lower.contains("pixeldrain") -> extractPixelDrain(targetUrl, callback)
                lower.contains("theditch") || lower.contains("fileditch") -> extractFileDitch(targetUrl, callback)
                lower.contains("vik1ngfile") || lower.contains("vikingfile") -> extractVikingFile(targetUrl, subtitleCallback, callback)
                lower.contains("send.now") || lower.contains("sendnow") -> extractSendNow(targetUrl, callback)
                lower.contains("susanexpectcare") || lower.contains("voe.sx") -> extractVoe(targetUrl, referer ?: targetUrl, subtitleCallback, callback)
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
    // 1. MOMOFILE.SHOP HANDLER (Online Watching Link 2 + All 5 PHP APIs)
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

        // A) Page HTML scan for Online Watching Link 2 (voe.sx -> susanexpectcare) & any pre-generated buttons
        try {
            val res = cfGet(url, referer = baseUrl)
            val doc = res.document

            // Direct video/iframe on MomoFile page
            doc.select("video source[src], video[src], iframe[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.startsWith("http")) {
                    if (src.contains("voe") || src.contains("susanexpectcare")) {
                        extractVoe(src, url, subtitleCallback, callback)
                    } else if (src.contains(".mp4") || src.contains(".mkv") || src.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "MomoFile",
                                name = "MomoFile [Direct Player]",
                                url = src,
                                type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = getQualityFromName(doc.title())
                                this.referer = url
                            }
                        )
                    }
                }
            }

            // All anchor buttons (Online Watching Link 2, Download from PixelDrain, etc.)
            val anchors = doc.select("a[href]")
            anchors.amap { a ->
                val href = a.attr("href").trim()
                val text = a.text().trim()
                if (href.startsWith("http") && !href.contains(uri.host)) {
                    when {
                        href.contains("voe.sx") || href.contains("susanexpectcare") || text.contains("Online Watching", true) -> {
                            extractVoe(href, url, subtitleCallback, callback)
                        }
                        href.contains("pixeldrain") -> extractPixelDrain(href, callback)
                        href.contains("fileditch") || href.contains("theditch") -> extractFileDitch(href, callback)
                        href.contains("vik1ngfile") || href.contains("vikingfile") -> extractVikingFile(href, subtitleCallback, callback)
                        href.contains("send.now") -> extractSendNow(href, callback)
                        href.contains("buzzheavier") -> extractBuzzheavier(href, callback)
                        else -> getUrl(href, url, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}

        // B) Parallel fetch MomoFile PHP APIs (pixeldrain.php, fileditch.php, vikingfile.php, sendnow.php, buzzheavier.php)
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
                                "vikingfile" -> extractVikingFile(genUrl, subtitleCallback, callback)
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
    // 3. FILEDITCH / THEDITCH (https://fileditchvids.st -> https://theditch.st/jypzukwy)
    // =========================================================================
    private suspend fun extractFileDitch(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, allowRedirects = true)
            val finalUrl = res.url
            val contentType = res.headers["content-type"] ?: res.headers["Content-Type"] ?: ""

            // Agar URL khud direct video file hai
            if (contentType.contains("video", true) || contentType.contains("octet-stream", true) ||
                finalUrl.endsWith(".mkv", true) || finalUrl.endsWith(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = "TheDitch",
                        name = "TheDitch [Direct]",
                        url = finalUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://fileditchvids.st/"
                    }
                )
                return
            }

            val doc = res.document
            // Video 00:52 mein input box aur download button dono mein theditch.st link hota hai
            val extractedLink = doc.selectFirst("input[value*='theditch'], a[href*='theditch'], a.download-button[href], video source[src], video[src], a[href*='download']")?.let {
                it.attr("value").ifBlank { it.attr("href") }.ifBlank { it.attr("src") }
            } ?: Regex("""https?://[a-zA-Z0-9.-]*theditch\.[a-z]+/[^\s"'<>]+""").find(res.text)?.value

            val streamUrl = if (!extractedLink.isNullOrBlank()) fixUrl(extractedLink.trim()) else finalUrl
            if (streamUrl.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "TheDitch",
                        name = "TheDitch [Direct Stream]",
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = finalUrl
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 4. VIKINGFILE (https://vik1ngfile.site/f/qw3rACmN5B & https://vikingfile.com/f/qw3rACmN5B)
    // =========================================================================
    private suspend fun extractVikingFile(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
            val urlsToTry = listOf(
                url,
                "https://vikingfile.com/f/$fileId",
                "https://vik1ngfile.site/f/$fileId"
            ).distinct()

            for (target in urlsToTry) {
                try {
                    val res = cfGet(target, referer = url)
                    val doc = res.document
                    val base = "${URI(res.url).scheme}://${URI(res.url).host}"

                    // 1. Check <video> / <source> tag or direct download button
                    val directMedia = doc.selectFirst("video source[src], video[src], a#download-url[href], a.btn[href*='http'], a[href*='.mkv'], a[href*='.mp4'], a[href*='.m3u8']")?.let {
                        it.attr("src").ifBlank { it.attr("href") }
                    } ?: Regex("""https?://[^\s"'<>]+\.(?:mp4|mkv|m3u8)[^\s"'<>]*""").find(res.text)?.value
                    ?: Regex("""(?:file|src|url|downloadUrl)\s*[:=]\s*["'](https?://[^"']+)["']""").find(res.text)?.groupValues?.get(1)

                    if (!directMedia.isNullOrBlank() && !directMedia.endsWith("/f/$fileId")) {
                        val finalStream = if (directMedia.startsWith("/")) "$base$directMedia" else directMedia
                        callback.invoke(
                            newExtractorLink(
                                source = "VikingFile",
                                name = "VikingFile [Direct]",
                                url = finalStream,
                                type = if (finalStream.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P720.value
                                this.referer = "$base/"
                                this.headers = cfKiller.getCookieHeaders(res.url).toMap()
                            }
                        )
                        return
                    }
                } catch (_: Exception) {}
            }

            loadExtractor(url, url, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 5. SEND.NOW (https://send.now/vdzny9gb149c)
    // =========================================================================
    private suspend fun extractSendNow(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val res = cfGet(url, referer = "https://send.now/")
            val doc = res.document

            // Check direct video/download link first
            var dlLink = doc.selectFirst("video source[src], video[src], a#download_link[href], a.btn-download[href], a[href*='.mkv'], a[href*='.mp4']")?.let {
                it.attr("src").ifBlank { it.attr("href") }
            }

            // XFileSharing form POST (op=download2)
            if (dlLink.isNullOrBlank()) {
                val form = doc.selectFirst("form[name='F1'], form[method='POST']")
                if (form != null) {
                    val postData = mutableMapOf<String, String>()
                    form.select("input[name]").forEach { input ->
                        postData[input.attr("name")] = input.attr("value")
                    }
                    if (!postData.containsKey("op")) postData["op"] = "download2"

                    val postRes = app.post(
                        res.url,
                        referer = res.url,
                        data = postData,
                        interceptor = cfKiller,
                        allowRedirects = false
                    )
                    val redirectLoc = postRes.headers["location"] ?: postRes.headers["Location"]
                    dlLink = if (!redirectLoc.isNullOrBlank() && redirectLoc.startsWith("http")) {
                        redirectLoc
                    } else {
                        postRes.document.selectFirst("a#download_link[href], a.btn[href*='http'], span#direct_link a[href], a[href*='.mkv'], a[href*='.mp4']")?.attr("href")
                            ?: Regex("""https?://[^\s"'<>]+\.(?:mkv|mp4)[^\s"'<>]*""").find(postRes.text)?.value
                    }
                }
            }

            if (!dlLink.isNullOrBlank() && dlLink.startsWith("http")) {
                callback.invoke(
                    newExtractorLink(
                        source = "SendNow",
                        name = "Send.now [Direct]",
                        url = dlLink.trim(),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://send.now/"
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

            // 1. Base64 or plain 'hls' / 'mp4' key in script
            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!hlsMatch.isNullOrBlank()) {
                val decoded = if (hlsMatch.startsWith("http")) hlsMatch else runCatching { String(Base64.decode(hlsMatch, Base64.DEFAULT)) }.getOrNull()
                if (!decoded.isNullOrBlank() && decoded.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "SusanExpectCare (VOE)",
                            name = "SusanExpectCare [Online Watch]",
                            url = decoded,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = hostBase
                        }
                    )
                    return
                }
            }

            // 2. Direct .m3u8 URL in HTML
            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "SusanExpectCare (VOE)",
                        name = "SusanExpectCare [Online Watch]",
                        url = rawM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = hostBase
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

            // Video 01:24 - 01:36: Direct download buttons hamesha https://xcloud1.lol/<cleanId> par milte hain
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

                    // Check if preview page has direct <video> tag
                    doc.select("video source[src], video[src]").forEach { v ->
                        val vSrc = v.attr("src").trim()
                        if (vSrc.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "XCloud",
                                    name = "XCloud [Online Stream]",
                                    url = vSrc,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P720.value
                                    this.referer = "$hostBase/"
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
                                // Resolve /apis/ redirect to get direct CDN URL (e.g. cdn.stepnestph.com)
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

            // Case A: Agar URL /links/716cacc7a622 hai, toh uske andar /download/ ya external servers ke links hote hain
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

            // Case B: Direct /download/ID page
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
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
