package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI
import android.util.Base64

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val targetUrl = unwrapRedirect(url, referer ?: mainUrl)

            if (targetUrl.contains("kdhindidubbed") || targetUrl.contains("dramacool")) {
                val doc = app.get(targetUrl, referer = referer).document
                val subLinks = doc.select("a[href]").mapNotNull { el ->
                    val h = el.attr("href").trim()
                    if (h.contains("momofile") || h.contains("xcloud") || h.contains("tmbcloud") || h.contains("pixeldrain")) h else null
                }.distinct()
                subLinks.amap { sub ->
                    getUrl(sub, targetUrl, subtitleCallback, callback)
                }
                return
            }

            when {
                targetUrl.contains("momofile") -> extractMomoFile(targetUrl, subtitleCallback, callback)
                targetUrl.contains("xcloud") -> extractXCloud(targetUrl, subtitleCallback, callback)
                targetUrl.contains("tmbcloud") -> extractTmbCloud(targetUrl, callback)
                targetUrl.contains("pixeldrain") -> emitPixelDrain(targetUrl, callback)
                targetUrl.contains("susanexpectcare") || targetUrl.contains("voe.sx") -> extractVoe(targetUrl, targetUrl, subtitleCallback, callback)
                targetUrl.contains("fileditch") || targetUrl.contains("theditch") -> extractFileDitch(targetUrl, callback)
                else -> loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private suspend fun unwrapRedirect(url: String, referer: String): String {
        var current = url
        try {
            if (current.contains("redir") || current.contains("fifa") || current.contains("?url=") || current.contains("goto")) {
                val res = app.get(current, referer = referer, allowRedirects = true)
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

    // 1. MOMOFILE.SHOP HANDLER
    private suspend fun extractMomoFile(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val uri = URI(url)
        val baseUrl = "${uri.scheme}://${uri.host}"
        val fileId = uri.query?.substringAfter("id=")?.substringBefore("&")?.takeIf { it.isNotBlank() }
            ?: url.trimEnd('/').substringAfterLast('/')

        try {
            val pageRes = app.get(url, referer = baseUrl)
            val doc = pageRes.document

            doc.select("video source[src], video[src], iframe[src]").forEach { el ->
                val src = el.attr("src").trim()
                if (src.startsWith("http")) {
                    if (src.contains("susanexpectcare") || src.contains("voe")) {
                        extractVoe(src, url, subtitleCallback, callback)
                    } else if (src.contains(".mp4") || src.contains(".mkv") || src.contains(".m3u8")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "MomoFile",
                                name = "MomoFile - Online Player",
                                url = src,
                                type = if (src.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P720.value
                                this.referer = url
                            }
                        )
                    }
                }
            }

            val pageLinks = doc.select("a[href]")
            for (a in pageLinks) {
                val href = a.attr("href").trim()
                if (!href.startsWith("http") || href.contains(uri.host)) continue

                when {
                    href.contains("susanexpectcare") || href.contains("voe.sx") || a.text().contains("Online Watching", true) -> {
                        extractVoe(href, url, subtitleCallback, callback)
                    }
                    href.contains("pixeldrain") -> emitPixelDrain(href, callback)
                    href.contains("fileditch") || href.contains("theditch") -> extractFileDitch(href, callback)
                    else -> loadExtractor(href, url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}

        val services = listOf("pixeldrain", "fileditch", "buzzheavier", "vikingfile", "sendnow")
        services.amap { service ->
            try {
                val endpoint = "$baseUrl/$service.php?id=$fileId"
                val res = app.get(
                    endpoint,
                    referer = url,
                    headers = mapOf("X-Requested-With" to "XMLHttpRequest")
                )
                val text = res.text.trim()
                if (text.startsWith("{")) {
                    val json = JSONObject(text)
                    val dlUrl = json.optString("url").takeIf { it.startsWith("http") }
                    if (!dlUrl.isNullOrBlank()) {
                        when {
                            service == "pixeldrain" || dlUrl.contains("pixeldrain") -> emitPixelDrain(dlUrl, callback)
                            service == "fileditch" || dlUrl.contains("fileditch") -> extractFileDitch(dlUrl, callback)
                            service == "buzzheavier" || dlUrl.contains("buzzheavier") -> extractBuzzheavier(dlUrl, callback)
                            else -> {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "MomoFile",
                                        name = "MomoFile - ${service.uppercase()}",
                                        url = dlUrl,
                                        type = ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.P720.value
                                        this.referer = url
                                    }
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {}
        }
    }

    // 2. PIXELDRAIN DIRECT STREAM FIX (Added 'suspend' keyword here)
    private suspend fun emitPixelDrain(url: String, callback: (ExtractorLink) -> Unit) {
        val fileId = url.trimEnd('/').substringAfterLast('/').substringBefore("?")
        if (fileId.isBlank()) return
        val directVideoUrl = "https://pixeldrain.dev/api/file/$fileId"

        callback.invoke(
            newExtractorLink(
                source = "PixelDrain",
                name = "PixelDrain - Direct Stream",
                url = directVideoUrl,
                type = ExtractorLinkType.VIDEO
            ) {
                this.quality = Qualities.P720.value
                this.referer = "https://pixeldrain.dev/"
            }
        )
    }

    // 3. FILEDITCH HANDLER
    private suspend fun extractFileDitch(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            if (url.contains("theditch.") || url.endsWith(".mkv") || url.endsWith(".mp4")) {
                callback.invoke(
                    newExtractorLink(
                        source = "FileDitch",
                        name = "FileDitch - Direct",
                        url = url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = "https://fileditchvids.st/"
                    }
                )
                return
            }

            val doc = app.get(url, allowRedirects = true).document
            val directLink = doc.selectFirst("input[value*='theditch'], a.download-button[href], a[href*='theditch'], video source[src], video[src]")?.let {
                it.attr("value").ifBlank { it.attr("href") }.ifBlank { it.attr("src") }
            } ?: Regex("""https?://[a-zA-Z0-9.-]*theditch\.[a-z]+/[^\s"'<>]+""").find(doc.html())?.value

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "FileDitch",
                        name = "FileDitch - Direct Stream",
                        url = directLink.trim(),
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = url
                    }
                )
            }
        } catch (_: Exception) {}
    }

    // 4. BUZZHEAVIER HANDLER
    private suspend fun extractBuzzheavier(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val cleanUrl = url.trimEnd('/')
            val downloadEndpoint = "$cleanUrl/download"
            val res = app.get(
                downloadEndpoint,
                referer = url,
                headers = mapOf("HX-Request" to "true", "HX-Current-URL" to url),
                allowRedirects = false
            )
            val hxRedirect = res.headers["hx-redirect"] ?: res.headers["HX-Redirect"] ?: res.headers["location"]
            val finalStream = if (!hxRedirect.isNullOrBlank() && hxRedirect.startsWith("http")) {
                hxRedirect
            } else {
                downloadEndpoint
            }
            callback.invoke(
                newExtractorLink(
                    source = "Buzzheavier",
                    name = "Buzzheavier - Direct",
                    url = finalStream,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.quality = Qualities.P720.value
                    this.referer = url
                }
            )
        } catch (_: Exception) {}
    }

    // 5. SUSANEXPECTCARE.COM / VOE.SX HANDLER ("Online Watching Link 2")
    private suspend fun extractVoe(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer, allowRedirects = true)
            var html = res.text

            val jsRedirect = Regex("""window\.location\.href\s*=\s*['"](https?://[^'"]+)['"]""").find(html)?.groupValues?.get(1)
            val finalUrl = if (!jsRedirect.isNullOrBlank()) {
                html = app.get(jsRedirect, referer = url).text
                jsRedirect
            } else {
                res.url
            }

            val hlsMatch = Regex("""['"]hls['"]\s*:\s*['"]([^'"]+)['"]""").find(html)?.groupValues?.get(1)
            if (!hlsMatch.isNullOrBlank()) {
                val decodedHls = if (hlsMatch.startsWith("http")) hlsMatch else String(Base64.decode(hlsMatch, Base64.DEFAULT))
                if (decodedHls.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            source = "OnlineWatch (VOE)",
                            name = "Online Watching Link 2 (HLS)",
                            url = decodedHls,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.quality = Qualities.P720.value
                            this.referer = finalUrl
                        }
                    )
                    return
                }
            }

            val rawM3u8 = Regex("""https?://[^\s"'<>]+\.m3u8[^\s"'<>]*""").find(html)?.value
            if (!rawM3u8.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = "OnlineWatch (VOE)",
                        name = "Online Watching Link 2",
                        url = rawM3u8,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.quality = Qualities.P720.value
                        this.referer = finalUrl
                    }
                )
                return
            }

            loadExtractor(finalUrl, referer, subtitleCallback, callback)
        } catch (_: Exception) {}
    }

    // 6. XCLOUD.TATTOO -> XCLOUD1.LOL HANDLER
    private suspend fun extractXCloud(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val uri = URI(url)
            val fileId = uri.path.trimEnd('/').substringAfterLast('/')
            if (fileId.isBlank()) return

            val pagesToTry = listOf(
                "https://xcloud1.lol/$fileId",
                url
            ).distinct()

            for (pageUrl in pagesToTry) {
                try {
                    val res = app.get(pageUrl, referer = url, allowRedirects = true)
                    val doc = res.document
                    val hostBase = "${URI(res.url).scheme}://${URI(res.url).host}"

                    val buttons = doc.select("a[href]")
                    val validButtons = buttons.filter { a ->
                        val h = a.attr("href").trim()
                        val t = a.text().trim()
                        (h.contains("/apis/") || h.contains("workers.dev") || h.contains("r2.dev") ||
                         h.contains("stepnestph") || h.contains("hubcloud") || h.contains("gofile") ||
                         h.contains(".mkv") || h.contains(".mp4") ||
                         t.contains("Download [", true) || t.contains("Online Streaming", true)) &&
                        !h.endsWith("/$fileId")
                    }

                    if (validButtons.isNotEmpty()) {
                        validButtons.amap { btn ->
                            val rawHref = btn.attr("href").trim()
                            val btnHref = if (rawHref.startsWith("/")) "$hostBase$rawHref" else rawHref
                            val btnName = btn.text().trim().ifBlank { "Server" }

                            if (btnHref.contains("gofile")) {
                                loadExtractor(btnHref, res.url, subtitleCallback, callback)
                            } else if (btnHref.contains("/apis/")) {
                                val apiRedirect = app.get(btnHref, referer = res.url, allowRedirects = false)
                                val finalVideoUrl = apiRedirect.headers["location"] ?: apiRedirect.headers["Location"] ?: btnHref

                                if (finalVideoUrl.startsWith("http")) {
                                    callback.invoke(
                                        newExtractorLink(
                                            source = "XCloud",
                                            name = "XCloud - $btnName",
                                            url = finalVideoUrl,
                                            type = ExtractorLinkType.VIDEO
                                        ) {
                                            this.quality = Qualities.P720.value
                                            this.referer = "$hostBase/"
                                        }
                                    )
                                }
                            } else if (btnHref.startsWith("http")) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "XCloud",
                                        name = "XCloud - $btnName",
                                        url = btnHref,
                                        type = if (btnHref.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
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

    // 7. TMBCLOUD.LOL HANDLER
    private suspend fun extractTmbCloud(url: String, callback: (ExtractorLink) -> Unit) {
        try {
            val response = app.get(url, referer = "https://tmbcloud.lol", allowRedirects = true)
            val doc = response.document
            val uri = URI(response.url)
            val baseUrl = "${uri.scheme}://${uri.host}"

            val pageId = doc.selectFirst("button[data-page], a[data-page]")?.attr("data-page")
                ?: uri.path.trimEnd('/').substringAfterLast('/')

            val typesToTry = listOf("fslv2new", "fastcdnv2", "r2", "fsl", "pixel")
            typesToTry.amap { dataType ->
                try {
                    val apiRes = app.post(
                        "$baseUrl/download/$pageId",
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Origin" to baseUrl,
                            "Referer" to response.url
                        ),
                        data = mapOf("type" to dataType, "page" to (pageId ?: ""))
                    )
                    if (apiRes.text.trim().startsWith("{")) {
                        val json = JSONObject(apiRes.text)
                        val dlUrl = json.optString("url").takeIf { it.startsWith("http") }
                            ?: json.optString("download_url").takeIf { it.startsWith("http") }
                        if (!dlUrl.isNullOrBlank()) {
                            callback.invoke(
                                newExtractorLink(
                                    source = "TMBCloud",
                                    name = "TMBCloud - ${dataType.uppercase()}",
                                    url = dlUrl,
                                    type = if (dlUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = Qualities.P1080.value
                                    this.referer = baseUrl
                                }
                            )
                        }
                    }
                } catch (_: Exception) {}
            }
        } catch (_: Exception) {}
    }
}
