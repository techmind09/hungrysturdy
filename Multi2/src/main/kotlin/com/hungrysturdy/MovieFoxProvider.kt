
package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "en"
    override val hasMainPage = true

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Trending Now",
        "$mainUrl/movies" to "Movies",
        "$mainUrl/tv" to "TV Shows",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/dramas" to "Dramas"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val document = app.get(request.data).document
        val home = mutableListOf<HomePageList>()

        document.select("section").forEach { row ->

            val title = row.select("h2").text()
                .ifEmpty { return@forEach }

            val items = row.select(
                "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/]"
            ).mapNotNull { element ->
                toSearchResult(element)
            }.distinctBy { it.url }

            if (items.isNotEmpty()) {
                home.add(HomePageList(title, items))
            }
        }

        return newHomePageResponse(home)
    }

    private fun toSearchResult(element: Element): SearchResponse? {

        val href = element.attr("href")

        val title = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .ifEmpty {
                element.select("img").attr("alt")
            }
            .ifEmpty {
                element.text()
            }

        val poster = element.select("img").let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        if (href.isBlank() || title.isBlank()) return null

        val fixedUrl = when {
            href.startsWith("http") -> href
            else -> "$mainUrl/${href.trimStart('/')}"
        }

        val type = when {
            href.contains("/anime/") -> TvType.Anime
            href.contains("/dramas/") -> TvType.AsianDrama
            href.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (
            type == TvType.TvSeries ||
            type == TvType.AsianDrama ||
            type == TvType.Anime
        ) {
            newTvSeriesSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val document = app.get(
            "$mainUrl/search?q=$query"
        ).document

        return document.select(
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/]"
        ).mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {

        val document = app.get(url).document

        val title = document.select("h1").text()
            .ifEmpty {
                document.select("title").text().substringBefore("—")
            }

        val poster = document.select(
            "img.object-cover, img[data-nimg='fill']"
        ).firstOrNull()?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        val description = document.select(
            "p.text-muted, .text-fg\\/80"
        ).text()

        val type = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dramas/") -> TvType.AsianDrama
            url.contains("/tv/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val isTv = type != TvType.Movie

        return if (isTv) {

            val episodes = mutableListOf<Episode>()

            document.select(
                ".episode-item, .seasons-list a"
            ).forEachIndexed { index, element ->

                val epUrl = element.attr("href")

                if (epUrl.isNotBlank()) {

                    val fixedEpUrl = if (epUrl.startsWith("http")) {
                        epUrl
                    } else {
                        "$mainUrl/${epUrl.trimStart('/')}"
                    }

                    val epName = element.select(".ep-title")
                        .text()
                        .ifEmpty {
                            "Episode ${index + 1}"
                        }

                    episodes.add(
                        newEpisode(fixedEpUrl) {
                            this.name = epName
                            this.episode = index + 1
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(
                title,
                url,
                type,
                episodes
            ) {
                this.posterUrl = poster
                this.plot = description
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    // Stream API models

    data class StreamResponse(
        val streams: List<StreamItem> = emptyList()
    )

    data class StreamItem(
        val url: String,
        val type: String = "",
        val quality: String? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    data class SubtitleItem(
        val language: String = "",
        val label: String = "",
        val url: String
    )

    private fun getQuality(value: String?): Int {

        val quality = value?.uppercase().orEmpty()

        return when {
            quality.contains("2160") ||
                quality.contains("4K") -> Qualities.P2160.value

            quality.contains("1080") -> Qualities.P1080.value

            quality.contains("720") -> Qualities.P720.value

            quality.contains("480") -> Qualities.P480.value

            else -> Qualities.P720.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        return try {

            val matchId = Regex(
                "-(?:movie|tv|dramas|anime)-(\\d+)"
            ).find(data)?.groupValues?.get(1)
                ?: Regex("(\\d+)(?:\\D*)$")
                    .find(data)?.groupValues?.get(1)
                ?: return false

            val contentType = when {
                data.contains("/tv/") ||
                    data.contains("/dramas/") ||
                    data.contains("/anime/") -> "tv"

                else -> "movie"
            }

            val apiUrl =
                "$mainUrl/api/stream/debrid?type=$contentType&id=$matchId"

            val response = app.get(
                apiUrl,
                referer = "$mainUrl/"
            ).text

            val parsedJson = parseJson<StreamResponse>(response)

            if (parsedJson.streams.isEmpty()) {
                return false
            }

            var found = false

            parsedJson.streams.forEach { stream ->

                val streamUrl = if (
                    stream.url.startsWith("http")
                ) {
                    stream.url
                } else {
                    "$mainUrl/${stream.url.trimStart('/')}"
                }

                if (streamUrl.isBlank()) return@forEach

                // Subtitles

                stream.subtitles?.forEach { sub ->

                    val subUrl = if (
                        sub.url.startsWith("http")
                    ) {
                        sub.url
                    } else {
                        "$mainUrl/${sub.url.trimStart('/')}"
                    }

                    if (subUrl.isNotBlank()) {
                        subtitleCallback(
                            SubtitleFile(
                                lang = sub.label.ifBlank {
                                    sub.language.ifBlank { "Unknown" }
                                },
                                url = subUrl
                            )
                        )
                    }
                }

                // HLS / MP4 stream

                val isHls =
                    stream.type.equals("hls", true) ||
                    streamUrl.contains(".m3u8", true)

                callback(
                    newExtractorLink(
                        source = name,
                        name = "MovieFox (${stream.quality ?: "HD"})",
                        url = streamUrl,
                        type = if (isHls) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = getQuality(stream.quality)
                    }
                )

                found = true
            }

            found

        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
