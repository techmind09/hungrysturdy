package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI
import com.lagradost.api.Log

suspend fun resolveFinalUrl(startUrl: String): String? {
    var currentUrl = startUrl
    var loopCount = 0
    val maxRedirects = 7

    while (loopCount < maxRedirects) {
        try {
            val res = app.head(currentUrl, allowRedirects = false, timeout = 2500L)
            if (res.code == 200 || res.code in 300..399) {
                val location = res.headers.get("Location")
                if(location.isNullOrEmpty()) break
                currentUrl = location
            } else {
                return null
            }
            loopCount++
        } catch (e: Exception) {
            return null
        }
    }
    return currentUrl
}

fun getBaseUrl(url: String): String {
    try {
        return URI(url).let {
            "${it.scheme}://${it.host}"
        }
    } catch (e: Exception) {
        return url
    }
}

fun getIndexQuality(str: String?): Int {
    if (str.isNullOrBlank()) return Qualities.Unknown.value

    Regex("""(\d{3,4})[pP]""").find(str)?.groupValues?.getOrNull(1)?.toIntOrNull()?.let {
        return it
    }

    val lowerStr = str.lowercase()
    return when {
        lowerStr.contains("8k") -> 4320
        lowerStr.contains("4k") -> 2160
        lowerStr.contains("2k") -> 1440
        else -> Qualities.Unknown.value
    }
}

suspend fun getLatestBaseUrl(baseUrl: String, source: String): String {
    return try {
        val dynamicUrls = app.get("https://raw.githubusercontent.com/SaurabhKaperwan/Utils/refs/heads/main/urls.json")
            .parsedSafe>()
        dynamicUrls?.get(source)?.takeIf { it.isNotBlank() } ?: baseUrl
    } catch (e: Exception) {
        baseUrl
    }
}

open class VCloud : ExtractorApi() {
    override val name: String = "V-Cloud"
    override val mainUrl: String = "https://vcloud.*"
    override val requiresReferer = false

    fun extractPxlUrl(html: String): String? {
        val regex = Regex("""var\s+pxl\s*=\s*["']([^"']+)["']""")
        return regex.find(html)?.groupValues?.get(1)
    }

    fun extractDoubleAtob(html: String): String? {
        val regex = Regex("""var\s+url\s*=\s*atob\s*\(\s*atob\s*\(\s*['"]([^'"]+)['"]\s*\)\s*\)""")
        return regex.find(html)?.groupValues?.get(1)?.let { base64Decode(base64Decode(it)) }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        var baseUrl = getBaseUrl(url)

        val latestBaseUrl = if(url.contains("hubcloud") || url.contains("nexdrive")) {
            getLatestBaseUrl(baseUrl, "hubcloud")
        } else {
            getLatestBaseUrl(baseUrl, "vcloud")
        }

        var newUrl = url

        if(baseUrl != latestBaseUrl && !url.contains("nexdrive")) {
            newUrl = url.replace(baseUrl, latestBaseUrl)
            baseUrl = latestBaseUrl
        }

        val doc = app.get(newUrl).document

        var link = if(newUrl.contains("/video/")) {
            doc.selectFirst("div.vd > center > a")?.attr("href") ?: ""
        } else {
            val scriptTag = doc.selectFirst("script:containsData(url)")?.toString() ?: ""

            if(newUrl.contains("vcloud")) {
                extractDoubleAtob(scriptTag) ?: ""
            } else {
                Regex("var url = '([^']*)'").find(scriptTag)?.groupValues?.get(1) ?: ""
            }
        }

        if(link.isNotEmpty() && !link.startsWith("https://")) link = baseUrl + link

        // Agar direct link page nahi hai toh current document hi use karein
        val document = if (link.isNotEmpty() && link.startsWith("http")) {
            app.get(link).document
        } else {
            doc
        }

        val header = document.select("div.card-header").text().ifEmpty { doc.select("h4").text() }
        val size = document.select("i#size").text()
        val quality = getIndexQuality(header)

        suspend fun myCallback(lnk: String, server: String = "") {
            if (lnk.isBlank()) return
            // Agar link hubcloud ya filepress ka hai toh loadExtractor use karein taaki andar ke final links mil sakein
            if (lnk.contains("hubcloud") || lnk.contains("filepress") || lnk.contains("drive") || lnk.contains("nexdrive")) {
                loadExtractor(lnk, newUrl, subtitleCallback, callback)
            } else {
                callback.invoke(
                    newExtractorLink(
                        "$name$server",
                        "$name$server $header[$size]",
                        lnk,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = quality
                    }
                )
            }
        }

        // Sabhi download buttons / links ko target karna
        document.select("h2 a.btn, .wp-download-btn, a.btn-download, a.btn").amap {
            val lnk = it.attr("href")
            val text = it.text()

            if (lnk.isBlank() || !lnk.startsWith("http")) return@amap

            if (text.contains("FSL Server")) myCallback(lnk, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(lnk, "[FSLv2 Server]")
            else if (text.contains("Mega Server")) myCallback(lnk, "[Mega Server]")
            else if (text.contains("G-Direct") || text.contains("Filepress") || text.contains("Download File")) {
                myCallback(lnk, if(text.contains("G-Direct")) "[G-Direct]" else "[Filepress]")
            }
            else if (text.contains("BuzzServer")) {
                val dlink = app.get("$lnk/download", referer = lnk, allowRedirects = false).headers["hx-redirect"] ?: ""
                val bUrl = getBaseUrl(lnk)
                if(dlink != "") myCallback(bUrl + dlink, "[BuzzServer]")
            }
            else if (lnk.contains("pixeldra")) {
                val pixelLink = extractPxlUrl(document.toString()) ?: return@amap
                val baseUrlLink = getBaseUrl(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink
                else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Server : 10Gbps")) {
                var redirectUrl = resolveFinalUrl(lnk) ?: return@amap
                if(redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[Download]")
            }
            else if (text.contains("Gofile") || lnk.contains("gofile")) {
                loadExtractor(lnk, "", subtitleCallback, callback)
            }
            else {
                // Fallback for nexdrive / hubcloud redirect links
                if (lnk.contains("nexdrive") || lnk.contains("hubcloud")) {
                    loadExtractor(lnk, newUrl, subtitleCallback, callback)
                } else {
                    Log.d("VCloud", "No Server matched for: $text -> $lnk")
                }
            }
        }
    }
}
