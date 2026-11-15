package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.app

class GhostflixExtractor : ExtractorApi() {
    override val name: String = "GhostFlixEmbed"
    override val mainUrl: String = "https://ghostflix.uk"
    override var requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val headers = mapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
            "Accept" to "*/*",
            "Referer" to (referer ?: "$mainUrl/")
        )

        try {
            val response = app.get(url, headers = headers).text
            
            // Check if response contains direct streaming proxy JSON or a nested HLS link
            if (response.contains(".m3u8") || response.contains(".mp4")) {
                extractLinks(response, url, subtitleCallback, callback)
            } else {
                // Try parsing if it's a JSON wrapper containing stream URLs
                val parsedJson = tryParseJson>(response)
                parsedJson?.let { map ->
                    val streamUrl = map["url"] as? String ?: map["file"] as? String
                    if (!streamUrl.isNullOrEmpty()) {
                        extractLinks(streamUrl, url, subtitleCallback, callback)
                    }
                }
            }
        } catch (_: Exception) {}
    }

    private fun extractLinks(
        text: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")
        
        // Match m3u8 or mp4 stream links inside the text/JSON
        val regex = Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""")
        regex.findAll(unescaped).forEach { match ->
            val streamUrl = match.value
            if (streamUrl.contains("track_") && !streamUrl.contains("video")) return@forEach

            val isM3u8 = streamUrl.contains(".m3u8")
            val quality = when {
                streamUrl.contains("2160") || streamUrl.contains("4k", true) -> Qualities.P2160.value
                streamUrl.contains("1080") -> Qualities.P1080.value
                streamUrl.contains("720") -> Qualities.P720.value
                streamUrl.contains("480") -> Qualities.P480.value
                streamUrl.contains("360") -> Qualities.P360.value
                else -> Qualities.Unknown.value
            }

            callback.invoke(
                ExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    referer = referer,
                    quality = quality,
                    isM3u8 = isM3u8
                )
            )
        }

        // Extract subtitles if available
        val subRegex = Regex("""https?://[^\s"'<>\\]+\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""")
        subRegex.findAll(unescaped).forEach { match ->
            val subUrl = match.value
            if (!subUrl.contains("thumbs.vtt", ignoreCase = true)) {
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }
    }
}
