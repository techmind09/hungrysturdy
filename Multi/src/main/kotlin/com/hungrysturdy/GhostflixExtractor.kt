package com.hungrysturdy

import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

class GhostFlixExtractor : ExtractorApi() {
    override val name: String = "GhostFlixEmbed"
    override val mainUrl: String = "https://ghostflix.uk"
    override val requiresReferer = true

    private data class StreamResponse(
        val url: String? = null,
        val file: String? = null
    )

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

            if (response.contains(".m3u8") || response.contains(".mp4")) {
                extractLinks(response, url, subtitleCallback, callback)
            } else {
                val parsedJson = tryParseJson<StreamResponse>(response)
                val streamUrl = parsedJson?.url ?: parsedJson?.file
                if (!streamUrl.isNullOrEmpty()) {
                    extractLinks(streamUrl, url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractLinks(
        text: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")

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
                newExtractorLink(
                    source = name,
                    name = name,
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                ) {
                    this.referer = referer
                    this.quality = quality
                }
            )
        }

        val subRegex = Regex("""https?://[^\s"'<>\\]+\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""")
        subRegex.findAll(unescaped).forEach { match ->
            val subUrl = match.value
            if (!subUrl.contains("thumbs.vtt", ignoreCase = true)) {
                subtitleCallback.invoke(SubtitleFile("English", subUrl))
            }
        }
    }
}
