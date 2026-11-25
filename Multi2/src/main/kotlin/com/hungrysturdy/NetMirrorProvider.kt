package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*

class NetMirrorProvider : MainAPI() {
    override var mainUrl = "https://netmirror.gold"
    override var name = "NetMirror"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "latest-hindi" to "Latest Hindi",
        "latest-telugu" to "Latest Telugu",
        "latest-tamil" to "Latest Tamil",
        "latest-hindi-dub" to "Latest Hindi Dub",
        "latest-english" to "Latest English",
        "latest-kannada" to "Latest Kannada",
        "latest-malayalam" to "Latest Malayalam",
        "latest-tv-shows" to "Latest TV Shows",
        "south-indian" to "South Indian",
        "bollywood" to "Bollywood"
    )

    private val baseHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // TODO: NetMirror ke list API endpoint se replace karein
        val apiUrl = "$mainUrl/api/list?category=${request.data}&page=$page"
        val response = app.get(apiUrl, headers = baseHeaders).parsedSafe<NetMirrorListResponse>()

        val items = response?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        // TODO: NetMirror ke search API endpoint se replace karein
        val searchUrl = "$mainUrl/api/search?query=${query.trim()}"
        val response = app.get(searchUrl, headers = baseHeaders).parsedSafe<NetMirrorListResponse>()

        return response?.results?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanPath = url.removePrefix(mainUrl)
        val isSeries = cleanPath.contains("/tv/") || cleanPath.contains("/series/")

        // TODO: NetMirror ke detail API endpoint se replace karein
        val detailApiUrl = "$mainUrl/api$cleanPath"
        val detail = app.get(detailApiUrl, headers = baseHeaders).parsedSafe<NetMirrorDetailResponse>()
            ?: return null

        val title = detail.title ?: return null
        val poster = detail.poster
        val year = detail.year?.take(4)?.toIntOrNull()

        return if (isSeries) {
            val episodes = detail.episodes?.map { ep ->
                // Agar API direct signed watchbox/external URLs deti hai to unhe pipe (|) se join karke pass karein
                val epData = ep.streamUrls?.joinToString("|")
                    ?: "$url?s=${ep.season ?: 1}&e=${ep.episode ?: 1}"

                newEpisode(data = epData) {
                    this.name = ep.title ?: "Episode ${ep.episode}"
                    this.season = ep.season ?: 1
                    this.episode = ep.episode
                    this.posterUrl = ep.still ?: poster
                }
            } ?: emptyList()

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail.overview
            }
        } else {
            val movieData = detail.streamUrls?.joinToString("|") ?: url
            newMovieLoadResponse(
                name = title,
                url = url,
                type = TvType.Movie,
                dataUrl = movieData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail.overview
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        // Case 1: Agar data mein pehle se signed watch22.shop / watch21.shop URLs hain
        if (data.contains("watchbox.php") || data.contains("external.php")) {
            val urls = data.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            for (playerUrl in urls) {
                val serverLabel = if (playerUrl.contains("external.php")) {
                    "NetMirror - Multi-Lang"
                } else {
                    "NetMirror - Watchbox"
                }
                if (extractFromPlayerUrl(playerUrl, serverLabel, callback)) {
                    foundAny = true
                }
            }
            return foundAny
        }

        // Case 2: Agar data NetMirror page URL hai, to WebViewResolver se signed player ya direct stream intercept karein
        val pageResolver = WebViewResolver(
            interceptUrl = Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)"""),
            additionalUrls = listOf(Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)""")),
            useOkhttp = false,
            timeout = 15_000L
        )

        val intercepted = app.get(data, headers = baseHeaders, interceptor = pageResolver)
        val interceptedUrl = intercepted.url

        if (interceptedUrl.contains("watchbox.php") || interceptedUrl.contains("external.php")) {
            val serverLabel = if (interceptedUrl.contains("external.php")) {
                "NetMirror - Multi-Lang"
            } else {
                "NetMirror - Watchbox"
            }
            foundAny = extractFromPlayerUrl(interceptedUrl, serverLabel, callback)
        } else if (interceptedUrl.contains(".mp4") || interceptedUrl.contains(".m3u8")) {
            emitStreamLink(interceptedUrl, "NetMirror", "$mainUrl/", callback)
            foundAny = true
        }

        return foundAny
    }

    private suspend fun extractFromPlayerUrl(
        playerUrl: String,
        serverName: String,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val playerHeaders = mapOf(
            "Referer" to "$mainUrl/",
            "Origin" to mainUrl
        )

        // Step 1: Fast check — player page ke HTML/JS response mein direct .mp4 ya .m3u8 dhundhein
        val html = app.get(playerUrl, headers = playerHeaders).text
        val streamRegex = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
        val matches = streamRegex.findAll(html).map { it.groupValues[1].replace("\\/", "/") }.distinct().toList()

        if (matches.isNotEmpty()) {
            // Direct .mp4 links ko pehle emit karein
            val sortedLinks = matches.sortedByDescending { it.contains(".mp4") }
            for (streamUrl in sortedLinks) {
                emitStreamLink(streamUrl, serverName, playerUrl, callback)
            }
            return true
        }

        // Step 2: Fallback — agar stream XHR/JS se load hota hai to WebViewResolver use karein
        val mediaResolver = WebViewResolver(
            interceptUrl = Regex("""(\.mp4|\.m3u8|/playlist|/master)"""),
            additionalUrls = listOf(Regex("""(\.mp4|\.m3u8)""")),
            useOkhttp = false,
            timeout = 15_000L
        )

        val mediaRes = app.get(playerUrl, headers = playerHeaders, interceptor = mediaResolver)
        if (mediaRes.url.contains(".mp4") || mediaRes.url.contains(".m3u8")) {
            emitStreamLink(mediaRes.url, serverName, playerUrl, callback)
            return true
        }

        return false
    }

    private suspend fun emitStreamLink(
        streamUrl: String,
        sourceName: String,
        refererUrl: String,
        callback: (ExtractorLink) -> Unit
    ) {
        val isMp4 = streamUrl.contains(".mp4", ignoreCase = true)
        callback.invoke(
            newExtractorLink(
                source = sourceName,
                name = if (isMp4) "$sourceName (MP4)" else "$sourceName (HLS)",
                url = streamUrl,
                type = if (isMp4) ExtractorLinkType.VIDEO else ExtractorLinkType.M3U8
            ) {
                this.referer = refererUrl
                this.quality = Qualities.Unknown.value
            }
        )
    }

    private fun NetMirrorItem.toSearchResponse(): SearchResponse? {
        val itemId = id ?: return null
        val itemTitle = title ?: name ?: return null
        val isTv = mediaType.equals("tv", ignoreCase = true) || firstAirDate != null
        val detailUrl = if (isTv) "$mainUrl/detail/tv/$itemId" else "$mainUrl/detail/movie/$itemId"

        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, detailUrl, TvType.TvSeries) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(itemTitle, detailUrl, TvType.Movie) {
                this.posterUrl = poster
            }
        }
    }

    // --- JSON Models ---
    data class NetMirrorListResponse(
        @JsonProperty("results") val results: List<NetMirrorItem>? = null
    )

    data class NetMirrorItem(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("name") val name: String? = null,
        @JsonProperty("poster_path") val poster: String? = null,
        @JsonProperty("media_type") val mediaType: String? = null,
        @JsonProperty("first_air_date") val firstAirDate: String? = null
    )

    data class NetMirrorDetailResponse(
        @JsonProperty("id") val id: String? = null,
        @JsonProperty("title") val title: String? = null,
        @JsonProperty("overview") val overview: String? = null,
        @JsonProperty("poster_path") val poster: String? = null,
        @JsonProperty("release_date") val year: String? = null,
        @JsonProperty("stream_urls") val streamUrls: List<String>? = null,
        @JsonProperty("episodes") val episodes: List<NetMirrorEpisode>? = null
    )

    data class NetMirrorEpisode(
        @JsonProperty("season_number") val season: Int? = null,
        @JsonProperty("episode_number") val episode: Int? = null,
        @JsonProperty("name") val title: String? = null,
        @JsonProperty("still_path") val still: String? = null,
        @JsonProperty("stream_urls") val streamUrls: List<String>? = null
    )
}
