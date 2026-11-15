package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.app
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorApi
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.ExtractorLinkType
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.utils.newExtractorLink

open class GhostFlixExtractor : ExtractorApi() {
    override val name: String = "GhostFlixEmbed"
    override val mainUrl: String = "https://ghostflix.uk"
    override val requiresReferer = true

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
    }

    private data class StreamResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("source") val source: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val effectiveReferer = referer ?: "$mainUrl/"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "*/*",
            "Referer" to effectiveReferer,
            "Origin" to mainUrl
        )

        try {
            val response = app.get(url, headers = headers).text
            if (response.isBlank()) return

            // Pehle JSON response check karein
            val parsedJson = tryParseJson<StreamResponse>(response)
            val jsonStreamUrl = parsedJson?.url ?: parsedJson?.file ?: parsedJson?.source
            if (!jsonStreamUrl.isNullOrBlank()) {
                extractLinks(jsonStreamUrl, url, subtitleCallback, callback)
            }

            // Raw text / HTML / JS response se bhi direct aur proxy links nikalein
            extractLinks(response, url, subtitleCallback, callback)
        } catch (_: Exception) {
        }
    }

    private suspend fun extractLinks(
        text: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")
        val origin = referer.substringBefore("/", referer).let {
            if (referer.startsWith("http")) {
                val parts = referer.split("/")
                if (parts.size >= 3) "${parts[0]}//${parts[2]}" else mainUrl
            } else {
                mainUrl
            }
        }

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )

        // Direct .m3u8, .mp4 aur proxy HLS endpoints dono ko match karega
        val regex = Regex("""https?://[^\s"'<>\\]+(?:\.(?:m3u8|mp4)|/api/proxy/hls)[^\s"'<>\\]*""")
        val matchedUrls = regex.findAll(unescaped)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .distinct()
            .toList()

        for (streamUrl in matchedUrls) {
            if (streamUrl.contains("track_") && !streamUrl.contains("video")) continue

            val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true) ||
                streamUrl.contains("/hls", ignoreCase = true)

            val quality = when {
                streamUrl.contains("2160") || streamUrl.contains("4k", ignoreCase = true) -> Qualities.P2160.value
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
                    this.headers = streamHeaders
                }
            )
        }

        val subRegex = Regex("""https?://[^\s"'<>\\]+\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""")
        subRegex.findAll(unescaped)
            .map { it.value }
            .distinct()
            .forEach { subUrl ->
                if (!subUrl.contains("thumbs.vtt", ignoreCase = true)) {
                    subtitleCallback.invoke(SubtitleFile("English", subUrl))
                }
            }
    }
}
