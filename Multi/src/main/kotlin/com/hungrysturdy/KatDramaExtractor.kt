package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

private val defaultHeaders = mapOf(
    "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36",
    "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/webp,*/*;q=0.8",
    "Accept-Language" to "en-US,en;q=0.9"
)

// =========================================================================
// Standalone Helper Extractors & Functions for HubCloud / GDFlix / PixelDrain
// =========================================================================

suspend fun extractKatPixelDrain(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    val fileId = url.substringAfter("/u/").substringAfter("/file/").substringBefore("?").substringBefore("/")
    if (fileId.isNotBlank()) {
        val host = if (url.contains("pixeldrain.dev")) "https://pixeldrain.dev" else "https://pixeldrain.com"
        callback.invoke(
            newExtractorLink(
                source = "PixelServer",
                name = "PixelServer [Direct]",
                url = "$host/api/file/$fileId?download",
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = "$host/"
                this.quality = quality
            }
        )
    }
}

suspend fun extractKatHbPlay(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    val encodedU = url.substringAfter("u=", "").substringBefore("&")
    if (encodedU.isNotBlank()) {
        try {
            val decodedUrl = String(Base64.decode(encodedU, Base64.DEFAULT)).trim()
            if (decodedUrl.startsWith("http")) {
                val finalQuality = if (quality != Qualities.Unknown.value) quality else getQualityFromName(decodedUrl)
                callback.invoke(
                    newExtractorLink(
                        source = "HBPlay",
                        name = "HubCloud [HBPlay Direct]",
                        url = decodedUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = "https://hbplay.pages.dev/"
                        this.quality = finalQuality
                    }
                )
            }
        } catch (_: Exception) {}
    }
}

suspend fun extractKatFuckingFast(
    url: String,
    quality: Int,
    callback: (ExtractorLink) -> Unit
) {
    try {
        val responseText = app.get(url, headers = defaultHeaders).text
        val directLink = Regex("""window\.open\(["'](https://fuckingfast\.net/dl/[^"']+)["']\)""")
            .find(responseText)?.groupValues?.get(1)
        if (!directLink.isNullOrBlank()) {
            callback.invoke(
                newExtractorLink(
                    source = "BuzzServer",
                    name = "HubCloud [Buzz Server]",
                    url = directLink,
                    type = ExtractorLinkType.VIDEO
                ) {
                    this.referer = url
                    this.quality = quality
                }
            )
        }
    } catch (_: Exception) {}
}
