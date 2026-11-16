
package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

private suspend fun resolveMedia(
    extractorName: String,
    url: String,
    referer: String?,
    subtitleCallback: (SubtitleFile) -> Unit,
    callback: (ExtractorLink) -> Unit
) {
    println("$extractorName: Starting $url")

    try {
        val response = app.get(
            url,
            referer = referer,
            interceptor = WebViewResolver(
                interceptUrl = Regex(
                    """(?i)(master\.m3u8|\.m3u8(?:\?|$)|\.mp4(?:\?|$))"""
                ),
                additionalUrls = listOf(
                    Regex("""(?i).*\.m3u8.*"""),
                    Regex("""(?i).*\.mp4.*""")
                ),
                timeout = 20000L
            )
        )

        val mediaUrl = response.url.trim()

        println("$extractorName: Response URL = $mediaUrl")

        val isHls = mediaUrl.contains(".m3u8", true)
        val isMp4 = mediaUrl.contains(".mp4", true)

        if (!isHls && !isMp4) {
            println("$extractorName: No media URL detected")
            return
        }

        callback.invoke(
            newExtractorLink(
                source = extractorName,
                name = "$extractorName ${if (isHls) "HLS" else "MP4"}",
                url = mediaUrl,
                type = if (isHls) {
                    ExtractorLinkType.M3U8
                } else {
                    ExtractorLinkType.VIDEO
                }
            ) {
                this.referer = referer ?: url
                this.quality = Qualities.Unknown.value
            }
        )

        println("$extractorName: Link callback completed")

    } catch (e: Exception) {
        println("$extractorName ERROR: ${e.stackTraceToString()}")
    }
}

class FlixCloudExtractor : ExtractorApi() {
    override val name = "FlixCloud"
    override val mainUrl = "https://flixcloud.cc"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        resolveMedia(
            name, url, referer,
            subtitleCallback, callback
        )
    }
}

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
        resolveMedia(
            name, url, referer,
            subtitleCallback, callback
        )
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
        resolveMedia(
            name, url, referer,
            subtitleCallback, callback
        )
    }
}
