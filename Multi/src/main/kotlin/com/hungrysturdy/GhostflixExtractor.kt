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
import java.net.URI

open class GhostFlixExtractor : ExtractorApi() {

    override val name = "GhostFlix"
    override val mainUrl = "https://ghostflix.st"
    override val requiresReferer = true

    companion object {
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36"

        private val URL_REGEX = Regex(
            """https?://[^\s"'<>\\]+""",
            RegexOption.IGNORE_CASE
        )

        private val SUBTITLE_REGEX = Regex(
            """https?://[^\s"'<>\\]+\.(?:vtt|srt)(?:\?[^\s"'<>\\]*)?""",
            RegexOption.IGNORE_CASE
        )
    }

    private data class StreamResponse(
        @JsonProperty("url") val url: String? = null,
        @JsonProperty("file") val file: String? = null,
        @JsonProperty("source") val source: String? = null,
        @JsonProperty("stream") val stream: String? = null,
        @JsonProperty("hls") val hls: String? = null,
        @JsonProperty("playlist") val playlist: String? = null,
        @JsonProperty("src") val src: String? = null
    )

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val origin = getOrigin(url)
        val effectiveReferer = referer ?: "$origin/"

        val headers = mapOf(
            "User-Agent" to USER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/json,*/*",
            "Referer" to effectiveReferer,
            "Origin" to origin
        )

        val emitted = mutableSetOf<String>()

        suspend fun emitStream(
            streamUrl: String,
            pageReferer: String
        ) {
            val stream = streamUrl
                .trim()
                .trimEnd(',', ';', ')', ']', '}')

            if (!isStreamUrl(stream)) return
            if (!emitted.add(stream)) return

            val host = try {
                URI(stream).host.orEmpty().lowercase()
            } catch (_: Exception) {
                ""
            }

            val sourceName = when {
                host.contains("cinesrc.st") -> "CineSrc"
                host.contains("vidrift.net") -> "Vidrift"
                host.contains("greenkite.top") || host.contains("goldenkite.top") -> "GreenKite"
                host.contains("vidy.st") -> "Vidy"
                host.contains("vidcore.org") -> "Vidcore"
                host.contains("vidlink.pro") -> "VidLink"
                host.contains("vidsrc") -> "VidSrc"
                host.contains("ghostflix") -> "Ghostflix"
                else -> name
            }

            val quality = getQuality(stream)

            val streamType = if (
                stream.contains(".mp4", true) &&
                !stream.contains(".m3u8", true) &&
                !stream.contains("/hls", true)
            ) {
                ExtractorLinkType.VIDEO
            } else {
                ExtractorLinkType.M3U8
            }

            callback(
                newExtractorLink(
                    source = sourceName,
                    name = "$sourceName - ${qualityLabel(quality)}",
                    url = stream,
                    type = streamType
                ) {
                    this.referer = pageReferer
                    this.quality = quality
                    this.headers = headers
                }
            )
        }

        suspend fun processResponse(
            responseText: String,
            baseUrl: String
        ) {
            val clean = responseText
                .replace("\\/", "/")
                .replace("\\u0026", "&")
                .replace("&amp;", "&")

            // Try parsing as JSON first
            val parsedJson = tryParseJson<StreamResponse>(clean)
            val jsonUrl = parsedJson?.url
                ?: parsedJson?.file
                ?: parsedJson?.source
                ?: parsedJson?.stream
                ?: parsedJson?.hls
                ?: parsedJson?.playlist
                ?: parsedJson?.src

            if (!jsonUrl.isNullOrBlank()) {
                emitStream(jsonUrl, baseUrl)
            }

            if (clean.trimStart().startsWith("#EXTM3U")) {
                emitStream(baseUrl, baseUrl)
                return
            }

            // Direct URLs
            URL_REGEX.findAll(clean).forEach { match ->
                val candidate = match.value
                    .trimEnd(',', ';', ')', ']', '}')

                if (isStreamUrl(candidate)) {
                    emitStream(candidate, baseUrl)
                }
            }

            // Relative URLs
            val relativeRegex = Regex(
                """["']([^"']*(?:\.m3u8|\.mp4|/api/orion/|/api/playlist/|/api/proxy/hls)[^"']*)["']""",
                RegexOption.IGNORE_CASE
            )

            relativeRegex.findAll(clean).forEach { match ->
                try {
                    val candidate = URI(baseUrl)
                        .resolve(match.groupValues[1])
                        .toString()

                    emitStream(candidate, baseUrl)
                } catch (_: Exception) {
                }
            }

            // Subtitles
            SUBTITLE_REGEX.findAll(clean)
                .map { it.value }
                .distinct()
                .forEach { subtitle ->
                    if (!subtitle.contains("thumbs.vtt", true)) {
                        subtitleCallback(
                            SubtitleFile(
                                "English",
                                subtitle
                            )
                        )
                    }
                }
        }

        // Direct HLS / MP4 / playlist endpoints
        if (isStreamUrl(url)) {
            emitStream(url, effectiveReferer)
            return
        }

        // 1. Try fetching the embed page
        try {
            val response = app.get(url, headers = headers, timeout = 20)
            if (response.code == 200) {
                processResponse(response.text, url)
            }
        } catch (_: Exception) {
        }

        // 2. If no links found yet, try probing alternative API resolve paths based on URL
        if (emitted.isEmpty()) {
            val match = Regex("""/(?:movie|tv)/(\d+)(?:/(\d+)/(\d+))?""").find(url)
            if (match != null) {
                val id = match.groupValues[1]
                val season = match.groupValues[2]
                val episode = match.groupValues[3]
                val isTv = url.contains("/tv/") && season.isNotEmpty() && episode.isNotEmpty()

                val apiCandidates = if (isTv) {
                    listOf(
                        "$origin/api/resolve?type=tv&id=$id&season=$season&episode=$episode",
                        "$origin/api/playlist/tv/$id/$season/$episode",
                        "$origin/api/source/tv/$id/$season/$episode"
                    )
                } else {
                    listOf(
                        "$origin/api/resolve?type=movie&id=$id",
                        "$origin/api/playlist/movie/$id",
                        "$origin/api/source/movie/$id"
                    )
                }

                for (apiUrl in apiCandidates) {
                    try {
                        val apiResp = app.get(apiUrl, headers = headers, timeout = 10)
                        if (apiResp.code == 200 && apiResp.text.isNotBlank()) {
                            processResponse(apiResp.text, apiUrl)
                            if (emitted.isNotEmpty()) break
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getOrigin(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (_: Exception) {
            mainUrl
        }
    }

    private fun isStreamUrl(url: String): Boolean {
        val value = url.lowercase()

        return value.contains(".m3u8") ||
            value.contains(".mp4") ||
            value.contains("/api/orion/") ||
            value.contains("/api/playlist/") ||
            value.contains("/api/proxy/hls") ||
            value.contains("/vd/")
    }

    private fun getQuality(url: String): Int {
        val value = url.lowercase()

        return when {
            value.contains("2160") || value.contains("4k") -> Qualities.P2160.value
            value.contains("1080") -> Qualities.P1080.value
            value.contains("720") -> Qualities.P720.value
            value.contains("480") -> Qualities.P480.value
            value.contains("360") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    private fun qualityLabel(quality: Int): String {
        return when (quality) {
            Qualities.P2160.value -> "2160p"
            Qualities.P1080.value -> "1080p"
            Qualities.P720.value -> "720p"
            Qualities.P480.value -> "480p"
            Qualities.P360.value -> "360p"
            else -> "Auto"
        }
    }
}

class GhostflixStExtractor : GhostFlixExtractor() {
    override val name = "Ghostflix"
    override val mainUrl = "https://ghostflix.st"
}

class GhostflixUkExtractor : GhostFlixExtractor() {
    override val name = "Ghostflix"
    override val mainUrl = "https://ghostflix.uk"
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
