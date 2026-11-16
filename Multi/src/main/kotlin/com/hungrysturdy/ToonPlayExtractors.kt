package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

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
        try {
            val intercepted = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|master|playlist|fetch9)"""),
                    additionalUrls = listOf(Regex(""".*""")),
                    timeout = 12000L
                )
            )
            val finalUrl = intercepted.url
            if (finalUrl.contains(".m3u8", true) || finalUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (finalUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
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
        try {
            val intercepted = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|master|playlist)"""),
                    additionalUrls = listOf(Regex(""".*""")),
                    timeout = 12000L
                )
            )
            val finalUrl = intercepted.url
            if (finalUrl.contains(".m3u8", true) || finalUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (finalUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
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
            val intercepted = app.get(
                url,
                referer = referer,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|master|playlist|cdn/hls)"""),
                    additionalUrls = listOf(Regex(""".*""")),
                    timeout = 12000L
                )
            )
            val finalUrl = intercepted.url
            if (finalUrl.contains(".m3u8", true) || finalUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = finalUrl,
                        type = if (finalUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
