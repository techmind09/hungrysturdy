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

class GokuHDExtractor : ExtractorApi() {
    override val name: String = "GokuHD Extractor"
    override val mainUrl: String = "https://nexdrive.beer"
    override val requiresReferer: Boolean = false

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
        if (url.contains("gofile.io") || 
            url.contains("1fichier.com") ||
            url.contains("vikingfile") ||
            url.contains("buzzheavier")
        ) {
            loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
            return
        }

        if (url.contains("fastdl.beer") || url.contains("vglist")) {
            val doc = app.get(url).document
            val directLink = doc.selectFirst("a.btn-download, a#download, a:contains(Download Now)")?.attr("href")
                ?: doc.selectFirst("a[href*='googleusercontent']")?.attr("href")

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [10Gbps FastDL]",
                        directLink,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url
                    }
                )
            }
            return
        }

        val baseUrl = getBaseUrl(url)
        val doc = try {
            app.get(url).document
        } catch (e: Exception) {
            app.get(url).document
        }

        val header = doc.select("div.card-header, h4, .title").text()
        val size = doc.select("i#size, .size").text()
        val quality = getIndexQuality(header)

        suspend fun myCallback(lnk: String, server: String = "") {
            if (lnk.isBlank()) return
            if (lnk.contains("hubcloud") || lnk.contains("filepress") || lnk.contains("drive.google") || lnk.contains("vegadrive") || lnk.contains("nexdrive")) {
                loadExtractor(lnk, url, subtitleCallback, callback)
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

        doc.select("h2 a.btn, a.wp-download-btn, a.btn-download, a.btn, div.download-links a, center a").amap {
            val lnk = it.attr("href")
            val text = it.text()

            if (lnk.isBlank() || !lnk.startsWith("http")) return@amap

            if (text.contains("FSL Server")) myCallback(lnk, "[FSL Server]")
            else if (text.contains("FSLv2")) myCallback(lnk, "[FSLv2 Server]")
            else if (text.contains("Mega Server") || lnk.contains("transfer.it")) myCallback(lnk, "[Mega/Transferit]")
            else if (text.contains("G-Direct") || text.contains("Filepress") || text.contains("Vegadrop") || text.contains("Download File")) {
                myCallback(lnk, if(text.contains("G-Direct")) "[G-Direct]" else "[Filepress]")
            }
            else if (text.contains("BuzzServer") || text.contains("Buzzheavier")) {
                val dlink = app.get("$lnk/download", referer = lnk, allowRedirects = false).headers["hx-redirect"] ?: ""
                val bUrl = getBaseUrl(lnk)
                if(dlink != "") myCallback(bUrl + dlink, "[BuzzServer]")
                else myCallback(lnk, "[BuzzServer]")
            }
            else if (lnk.contains("pixeldra")) {
                val pixelLink = extractPxlUrl(doc.toString()) ?: return@amap
                val baseUrlLink = getBaseUrl(pixelLink)
                val finalURL = if (pixelLink.contains("download", true)) pixelLink
                else "$baseUrlLink/api/file/${pixelLink.substringAfterLast("/")}?download"
                myCallback(finalURL, "[Pixeldrain]")
            }
            else if (text.contains("Server : 10Gbps") || lnk.contains("fastdl.beer")) {
                var redirectUrl = resolveFinalUrl(lnk) ?: lnk
                if(redirectUrl.contains("link=")) redirectUrl = redirectUrl.substringAfter("link=")
                myCallback(redirectUrl, "[10Gbps]")
            }
            else if (text.contains("Gofile") || lnk.contains("gofile")) {
                loadExtractor(lnk, "", subtitleCallback, callback)
            }
            else {
                if (lnk.contains("nexdrive") || lnk.contains("hubcloud") || lnk.contains("vegadrive") || lnk.contains("filepress")) {
                    loadExtractor(lnk, url, subtitleCallback, callback)
                } else {
                    myCallback(lnk, if (text.isNotBlank()) " [$text]" else "")
                }
            }
        }
    }
}
