package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class AbyssPlayerExtractor : ExtractorApi() {
    override val name = "AbyssPlayer"
    override val mainUrl = "https://player.abyssplayer.com"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer).text
            val streamRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            streamRegex.findAll(res).forEach { match ->
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = streamUrl.contains(".m3u8", true)
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        streamUrl,
                        url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        Qualities.Unknown.value
                    )
                )
            }
        } catch (_: Exception) {}
    }
}

class RavokExtractor : ExtractorApi() {
    override val name = "Ravok"
    override val mainUrl = "https://ravok.buzz"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val res = app.get(url, referer = referer).text
            val streamRegex = Regex("""["'](https?://[^"']+\.(?:m3u8|mp4)[^"']*)["']""")
            streamRegex.findAll(res).forEach { match ->
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                val isM3u8 = streamUrl.contains(".m3u8", true)
                callback.invoke(
                    ExtractorLink(
                        name,
                        name,
                        streamUrl,
                        url,
                        if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO,
                        Qualities.Unknown.value
                    )
                )
            }
        } catch (_: Exception) {}
    }
}
