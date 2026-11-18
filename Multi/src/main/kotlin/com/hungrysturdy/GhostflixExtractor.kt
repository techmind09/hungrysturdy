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
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("playlist") val playlist: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = extractOrigin(url)
        val effectiveReferer = referer ?: "$mainUrl/"
        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Referer" to effectiveReferer,
            "Origin" to origin
        )

        try {
            val response = app.get(url, headers = headers, timeout = 15).text
            if (response.isNotBlank()) {
                val parsedJson = tryParseJson<StreamResponse>(response)
                val jsonStreamUrl = parsedJson?.url
                    ?: parsedJson?.file
                    ?: parsedJson?.source
                    ?: parsedJson?.stream
                    ?: parsedJson?.hls
                    ?: parsedJson?.playlist

                if (!jsonStreamUrl.isNullOrBlank()) {
                    extractLinks(jsonStreamUrl, url, origin, subtitleCallback, callback)
                }

                extractLinks(response, url, origin, subtitleCallback, callback)
            }
        } catch (_: Exception) {}

        val idMatch = Regex("""/(?:movie|tv)/(\d+)(?:/(\d+)/(\d+))?""").find(url)
        if (idMatch != null) {
            val tmdbId = idMatch.groupValues[1]
            val season = idMatch.groupValues[2]
            val episode = idMatch.groupValues[3]
            val isTv = url.contains("/tv/") && season.isNotEmpty() && episode.isNotEmpty()

            val apiEndpoints = if (isTv) {
                listOf(
                    "$origin/api/resolve?type=tv&id=$tmdbId&season=$season&episode=$episode",
                    "$origin/api/source/tv/$tmdbId/$season/$episode",
                    "$origin/api/playlist/tv/$tmdbId/$season/$episode"
                )
            } else {
                listOf(
                    "$origin/api/resolve?type=movie&id=$tmdbId",
                    "$origin/api/source/movie/$tmdbId",
                    "$origin/api/playlist/movie/$tmdbId"
                )
            }

            val apiHeaders = mapOf(
                "User-Agent" to USER_AGENT,
                "Accept" to "application/json, */*",
                "Referer" to url,
                "Origin" to origin
            )

            for (apiUrl in apiEndpoints) {
                try {
                    val apiResp = app.get(apiUrl, headers = apiHeaders, timeout = 10).text
                    if (apiResp.contains(".m3u8") || apiResp.contains(".mp4") || apiResp.contains("/api/proxy/hls") || apiResp.contains("/api/playlist/")) {
                        extractLinks(apiResp, url, origin, subtitleCallback, callback)
                        break
                    }
                } catch (_: Exception) {}
            }
        }
    }

    private fun extractOrigin(url: String): String {
        if (!url.startsWith("http")) return mainUrl
        val parts = url.split("/")
        return if (parts.size >= 3) "${parts[0]}//${parts[2]}" else mainUrl
    }

    private suspend fun extractLinks(
        text: String,
        referer: String,
        origin: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val unescaped = text.replace("\\/", "/").replace("\\u0026", "&")

        val streamHeaders = mapOf(
            "User-Agent" to USER_AGENT,
            "Referer" to referer,
            "Origin" to origin,
            "Accept" to "*/*"
        )

        // Updated Regex to match /api/playlist/ and /api/orion/ paths along with standard .m3u8/.mp4
        val regex = Regex("""https?://[^\s"'<>\\]+(?:\.(?:m3u8|mp4)|/api/proxy/hls|/api/orion/|/api/playlist/)[^\s"'<>\\]*""")
        val matchedUrls = regex.findAll(unescaped)
            .map { it.value.trimEnd(',', ';', ')', ']') }
            .distinct()
            .toList()

        for (streamUrl in matchedUrls) {
            if (streamUrl.contains("track_") && !streamUrl.contains("video")) continue

            val isM3u8 = streamUrl.contains("m3u8", ignoreCase = true) ||
                streamUrl.contains("/hls", ignoreCase = true) ||
                streamUrl.contains("/playlist/", ignoreCase = true) ||
                streamUrl.contains("/orion/", ignoreCase = true)

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

class CineSrcExtractor : GhostFlixExtractor() {
    override val name = "CineSrc"
    override val mainUrl = "https://cinesrc.st"
}

class VidriftExtractor : GhostFlixExtractor() {
    override val name = "Vidrift"
    override val mainUrl = "https://embed.vidrift.net"
}

class VidyExtractor : GhostFlixExtractor() {
    override val name = "Vidy"
    override val mainUrl = "https://www.vidy.st"
}

class VidcoreExtractor : GhostFlixExtractor() {
    override val name = "Vidcore"
    override val mainUrl = "https://vidcore.org"
}
