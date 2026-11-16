package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

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
        val ref = referer ?: "https://toonplay.in/"
        val playerHeaders = mapOf(
            "Referer" to "https://ravok.buzz/",
            "Origin" to "https://ravok.buzz"
        )

        // 1. Fast direct regex check in page source
        try {
            val res = app.get(url, referer = ref).text
            val streamRegex = Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']""")
            var found = false
            for (match in streamRegex.findAll(res)) {
                found = true
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://ravok.buzz/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
            if (found) return
        } catch (_: Exception) {}

        // 2. WebView interception with auto-play click for JWPlayer CDN HLS (master.m3u8?md5=...)
        try {
            val intercepted = app.get(
                url,
                referer = ref,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|cdn/hls|master|playlist)"""),
                    additionalUrls = listOf(Regex("""(\.m3u8|\.mp4|cdn/hls|master|playlist)""")),
                    script = """
                        setInterval(() => {
                            try {
                                const btn = document.querySelector('.jw-icon-display, .jw-display-icon-container, video, button');
                                if (btn) btn.click();
                                const v = document.querySelector('video');
                                if (v && v.paused) v.play();
                            } catch (e) {}
                        }, 600);
                    """.trimIndent(),
                    timeout = 12000L
                )
            )
            val streamUrl = intercepted.url
            if (streamUrl.contains(".m3u8", true) || streamUrl.contains(".mp4", true) || streamUrl.contains("cdn/hls", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://ravok.buzz/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
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
        val ref = referer ?: "https://animesalt.streamindia.co.in/"
        val playerHeaders = mapOf(
            "Referer" to "https://player.abyssplayer.com/",
            "Origin" to "https://player.abyssplayer.com"
        )

        try {
            val res = app.get(url, referer = ref).text
            val streamRegex = Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']""")
            var found = false
            for (match in streamRegex.findAll(res)) {
                found = true
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://player.abyssplayer.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
            if (found) return
        } catch (_: Exception) {}

        try {
            val intercepted = app.get(
                url,
                referer = ref,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|master|playlist)"""),
                    additionalUrls = listOf(Regex("""(\.m3u8|\.mp4|master|playlist)""")),
                    script = """
                        setInterval(() => {
                            try {
                                const btn = document.querySelector('.jw-icon-display, .plyr__control--overlaid, video, button');
                                if (btn) btn.click();
                                const v = document.querySelector('video');
                                if (v && v.paused) v.play();
                            } catch (e) {}
                        }, 600);
                    """.trimIndent(),
                    timeout = 12000L
                )
            )
            val streamUrl = intercepted.url
            if (streamUrl.contains(".m3u8", true) || streamUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://player.abyssplayer.com/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
        } catch (_: Exception) {}
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
        val ref = referer ?: "https://toonplay.in/"
        val playerHeaders = mapOf(
            "Referer" to "https://flixcloud.cc/",
            "Origin" to "https://flixcloud.cc"
        )

        try {
            val res = app.get(url, referer = ref).text
            val streamRegex = Regex("""["'](https?://[^"']+\.m3u8(?:\?[^"']*)?)["']""")
            var found = false
            for (match in streamRegex.findAll(res)) {
                found = true
                val streamUrl = match.groupValues[1].replace("\\/", "/")
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://flixcloud.cc/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
            if (found) return
        } catch (_: Exception) {}

        try {
            val intercepted = app.get(
                url,
                referer = ref,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(\.m3u8|\.mp4|master|playlist|fetch9)"""),
                    additionalUrls = listOf(Regex("""(\.m3u8|\.mp4|master|playlist)""")),
                    script = """
                        setInterval(() => {
                            try {
                                const btn = document.querySelector('.art-icon-play, .art-state, video, button');
                                if (btn) btn.click();
                                const v = document.querySelector('video');
                                if (v && v.paused) v.play();
                            } catch (e) {}
                        }, 600);
                    """.trimIndent(),
                    timeout = 12000L
                )
            )
            val streamUrl = intercepted.url
            if (streamUrl.contains(".m3u8", true) || streamUrl.contains(".mp4", true)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = streamUrl,
                        type = if (streamUrl.contains(".mp4", true)) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
                    ) {
                        this.referer = "https://flixcloud.cc/"
                        this.quality = Qualities.Unknown.value
                        this.headers = playerHeaders
                    }
                )
            }
        } catch (_: Exception) {}
    }
}
