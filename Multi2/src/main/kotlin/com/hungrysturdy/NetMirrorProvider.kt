package com.hungrysturdy

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

class NetMirrorProvider : MainAPI() {
    override var mainUrl = "https://netmirror.gold"
    private val apiUrl = "https://api2.imdb3.shop"
    override var name = "NetMirror"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    // Har category ke filter query parameters yahan set karein
    override val mainPage = mainPageOf(
        "title_not%5B%5D=1&title_not%5B%5D=2&title_not%5B%5D=3&title_not%5B%5D=4&title_not%5B%5D=5&title_not%5B%5D=6&title_not%5B%5D=7&title_not%5B%5D=8&cn_multi%5B%5D=1&cn_multi%5B%5D=3&cn_multi%5B%5D=4&cn_multi%5B%5D=5&cn_multi%5B%5D=6&cn_multi%5B%5D=7&cn_multi%5B%5D=8&cache=home" to "Trending Now",
        "lang=Hindi" to "Latest Hindi",
        "lang=Telugu" to "Latest Telugu",
        "lang=Tamil" to "Latest Tamil",
        "lang=English" to "Latest English",
        "type=tv" to "Latest TV Shows"
    )

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        // api2.imdb3.shop 0-indexed page leta hai (page=0 for 1st page)
        val apiPage = (page - 1).coerceAtLeast(0)
        val url = "$apiUrl/api/movies/filter?page=$apiPage&${request.data}&items_per_page=20"

        val response = app.get(url, headers = apiHeaders).parsedSafe<NetMirrorListResponse>()
        val items = response?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()

        if (items.isEmpty()) {
            return newHomePageResponse(emptyList(), false)
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.size >= 10
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        // Search endpoint check (filter?search= ya search?query=)
        val searchUrl = "$apiUrl/api/movies/filter?page=0&search=$encoded&items_per_page=20"
        val response = app.get(searchUrl, headers = apiHeaders).parsedSafe<NetMirrorListResponse>()

        return response?.items?.mapNotNull { it.toSearchResponse() } ?: emptyList()
    }

    override suspend fun load(url: String): LoadResponse? {
        // URL format: https://netmirror.gold/movie/126904 ya https://netmirror.gold/tv/126904
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val nid = cleanUrl.substringAfterLast("/")
        val isSeries = cleanUrl.contains("/tv/", ignoreCase = true) ||
                cleanUrl.contains("/series/", ignoreCase = true)

        val detailApiUrl = "$apiUrl/api/movies/$nid"
        val detail = app.get(detailApiUrl, headers = apiHeaders).parsedSafe<NetMirrorDetailResponse>()

        val title = detail?.title ?: "NetMirror #$nid"
        val poster = fixPosterUrl(detail?.poster)
        val year = detail?.year?.take(4)?.toIntOrNull()

        return if (isSeries) {
            val episodes = detail?.episodes?.map { ep ->
                val epData = ep.streamUrls?.takeIf { it.isNotEmpty() }?.joinToString("|")
                    ?: "$cleanUrl?s=${ep.season ?: 1}&e=${ep.episode ?: 1}"

                newEpisode(data = epData) {
                    this.name = ep.title ?: "Episode ${ep.episode}"
                    this.season = ep.season ?: 1
                    this.episode = ep.episode
                    this.posterUrl = fixPosterUrl(ep.still) ?: poster
                }
            } ?: emptyList()

            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail?.overview
            }
        } else {
            val movieData = detail?.streamUrls?.takeIf { it.isNotEmpty() }?.joinToString("|")
                ?: cleanUrl

            newMovieLoadResponse(
                name = title,
                url = cleanUrl,
                type = TvType.Movie,
                dataUrl = movieData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = detail?.overview
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

        // Case 1: Signed watchbox.php / external.php URLs direct mile hon
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

        // Case 2: NetMirror movie/tv page se signed player URL ya stream intercept karein
        val pageResolver = WebViewResolver(
            interceptUrl = Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)"""),
            additionalUrls = listOf(Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)""")),
            useOkhttp = false,
            timeout = 18_000L
        )

        val intercepted = app.get(data, headers = apiHeaders, interceptor = pageResolver)
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

        // Step 1: Fast regex check for direct .mp4 / .m3u8 links
        val html = app.get(playerUrl, headers = playerHeaders).text
        val streamRegex = Regex("""["'](https?://[^"']+\.(?:mp4|m3u8)[^"']*)["']""")
        val matches = streamRegex.findAll(html)
            .map { it.groupValues[1].replace("\\/", "/") }
            .distinct()
            .toList()

        if (matches.isNotEmpty()) {
            val sortedLinks = matches.sortedByDescending { it.contains(".mp4") }
            for (streamUrl in sortedLinks) {
                emitStreamLink(streamUrl, serverName, playerUrl, callback)
            }
            return true
        }

        // Step 2: WebViewResolver fallback for JS-loaded streams
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

    private fun fixPosterUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        return when {
            raw.startsWith("http") -> raw
            raw.startsWith("//") -> "https:$raw"
            raw.startsWith("/") -> "https://image.tmdb.org/t/p/w500$raw"
            else -> "$apiUrl/$raw"
        }
    }

    private fun NetMirrorItem.toSearchResponse(): SearchResponse? {
        val itemId = nid ?: id ?: return null
        val itemTitle = title ?: name ?: return null
        val isTv = type.equals("tv", ignoreCase = true) ||
                type.equals("series", ignoreCase = true) ||
                !firstAirDate.isNullOrBlank()

        val detailUrl = if (isTv) "$mainUrl/tv/$itemId" else "$mainUrl/movie/$itemId"

        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, detailUrl, TvType.TvSeries) {
                this.posterUrl = fixPosterUrl(poster)
            }
        } else {
            newMovieSearchResponse(itemTitle, detailUrl, TvType.Movie) {
                this.posterUrl = fixPosterUrl(poster)
            }
        }
    }

    // --- Flexible JSON Models for api2.imdb3.shop ---
    data class NetMirrorListResponse(
        @JsonProperty("data")
        @JsonAlias("results", "movies", "items")
        val items: List<NetMirrorItem>? = null
    )

    data class NetMirrorItem(
        @JsonProperty("id")
        @JsonAlias("movie_id", "_id")
        val id: String? = null,

        @JsonProperty("nid")
        val nid: String? = null,

        @JsonProperty("title")
        @JsonAlias("movie_title", "original_title")
        val title: String? = null,

        @JsonProperty("name")
        val name: String? = null,

        @JsonProperty("poster")
        @JsonAlias("poster_path", "image", "img", "thumbnail")
        val poster: String? = null,

        @JsonProperty("type")
        @JsonAlias("media_type", "content_type")
        val type: String? = null,

        @JsonProperty("first_air_date")
        val firstAirDate: String? = null
    )

    data class NetMirrorDetailResponse(
        @JsonProperty("id")
        @JsonAlias("nid")
        val id: String? = null,

        @JsonProperty("title")
        @JsonAlias("name", "movie_title")
        val title: String? = null,

        @JsonProperty("overview")
        @JsonAlias("description", "plot", "story")
        val overview: String? = null,

        @JsonProperty("poster")
        @JsonAlias("poster_path", "image", "img")
        val poster: String? = null,

        @JsonProperty("year")
        @JsonAlias("release_date", "release_year")
        val year: String? = null,

        @JsonProperty("stream_urls")
        val streamUrls: List<String>? = null,

        @JsonProperty("episodes")
        val episodes: List<NetMirrorEpisode>? = null
    )

    data class NetMirrorEpisode(
        @JsonProperty("season")
        @JsonAlias("season_number")
        val season: Int? = null,

        @JsonProperty("episode")
        @JsonAlias("episode_number")
        val episode: Int? = null,

        @JsonProperty("title")
        @JsonAlias("name")
        val title: String? = null,

        @JsonProperty("still")
        @JsonAlias("still_path", "image")
        val still: String? = null,

        @JsonProperty("stream_urls")
        val streamUrls: List<String>? = null
    )
}

