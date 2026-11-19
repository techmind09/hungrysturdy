package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.jsoup.nodes.Element

class MovieFoxProvider : MainAPI() {

    override var mainUrl = "https://moviefox.pro"
    override var name = "MovieFox"
    override var lang = "en"
    override val hasMainPage = true

    private val cfKiller by lazy { CloudflareKiller() }

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Top 10 Movies This Week",
        "$mainUrl/" to "Trending Series",
        "$mainUrl/" to "In Theaters Now",
        "$mainUrl/" to "Superhero",
        "$mainUrl/" to "Mind-Bending",
        "$mainUrl/" to "Popular Movies",
        "$mainUrl/" to "K-Drama",
        "$mainUrl/" to "Indian Movies",
        "$mainUrl/" to "Sci-Fi",
        "$mainUrl/" to "Horror",
        "$mainUrl/" to "Crime",
        "$mainUrl/" to "Punjabi Movies",
        "$mainUrl/" to "Pakistani Movies",
        "$mainUrl/" to "Islamic History Epics - Urdu",
        "$mainUrl/" to "Pakistani Dramas",
        "$mainUrl/" to "Popular TV Shows",
        "$mainUrl/" to "Anime Spotlight",
        "$mainUrl/" to "Animated Feature Films",
        "$mainUrl/movies?list=top_rated" to "Top Rated",
        "$mainUrl/" to "Recommended For You",
        "$mainUrl/universes" to "Universes",
        "$mainUrl/dramas" to "Dramas",
        "$mainUrl/dramas?category=classics" to "Classic Dramas"
    )

    private fun cleanHeading(text: String): String {
        return text
            .replace(Regex("View\\s*all.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("[›»>]+$"), "")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val targetUrl = if (page > 1) {
            val sep = if (request.data.contains("?")) "&" else "?"
            "${request.data}${sep}page=$page"
        } else {
            request.data
        }

        val document = app.get(targetUrl, interceptor = cfKiller).document
        val home = mutableListOf<HomePageList>()

        val linkSelector =
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"

        val sections = document.select("section")
            .ifEmpty { document.select("main > div, div:has(h2):has(a[href])") }

        val targetName = request.name.trim()
        var matchedSection = false

        sections.forEach { row ->
            val rawHeading = row.selectFirst("h2, h3")?.text().orEmpty()
            val title = cleanHeading(rawHeading)
            if (title.isEmpty()) return@forEach

            val isTarget = title.equals(targetName, ignoreCase = true) ||
                title.contains(targetName, ignoreCase = true) ||
                targetName.contains(title, ignoreCase = true)

            if (!isTarget) return@forEach

            val items = row.select(linkSelector)
                .mapNotNull { element -> toSearchResult(element) }
                .distinctBy { it.url }

            if (items.isNotEmpty()) {
                home.add(HomePageList(targetName, items))
                matchedSection = true
            }
        }

        // Dedicated pages like /universes, /dramas, /dramas?category=classics, or /movies?list=top_rated
        if (!matchedSection) {
            val gridItems = document.select(linkSelector)
                .mapNotNull { element -> toSearchResult(element) }
                .distinctBy { it.url }

            if (gridItems.isNotEmpty()) {
                home.add(HomePageList(targetName, gridItems))
            }
        }

        val isPaginatedCategory = request.data.contains("?")
        return newHomePageResponse(home, hasNext = isPaginatedCategory && home.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").trim()
        if (href.isBlank()) return null

        val ariaTitle = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .trim()

        val imgAlt = element.selectFirst("img")?.attr("alt")?.trim().orEmpty()
        val textTitle = element.selectFirst("h3, h4, .title")?.text()?.trim()
            ?: element.text().trim()

        val title = ariaTitle.ifEmpty { imgAlt }.ifEmpty { textTitle }
        if (title.isBlank()) return null

        val poster = element.selectFirst("img")?.let { img ->
            img.attr("src")
                .ifEmpty { img.attr("data-src") }
                .ifEmpty { img.attr("srcset").substringBefore(" ") }
        }

        val fixedUrl = if (href.startsWith("http")) href else "$mainUrl/${href.trimStart('/')}"

        val type = when {
            fixedUrl.contains("/anime/") -> TvType.Anime
            fixedUrl.contains("/dramas/") || fixedUrl.contains("/channel/") -> TvType.AsianDrama
            fixedUrl.contains("/tv/") || fixedUrl.contains("/series/") || fixedUrl.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return if (type != TvType.Movie) {
            newTvSeriesSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        } else {
            newMovieSearchResponse(title, fixedUrl, type) {
                this.posterUrl = poster
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/search?q=$query", interceptor = cfKiller).document
        return document.select(
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        ).mapNotNull { element ->
            toSearchResult(element)
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, interceptor = cfKiller).document

        val title = document.selectFirst("h1")?.text()?.trim()
            .orEmpty()
            .ifEmpty { document.select("title").text().substringBefore("—").trim() }

        val poster = document.select("img.object-cover, img[data-nimg='fill'], img").firstOrNull()?.let {
            it.attr("src").ifEmpty { it.attr("data-src") }
        }

        val description = document.select("p.text-muted, .text-fg\\/80, p").text().trim()

        val type = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dramas/") || url.contains("/channel/") -> TvType.AsianDrama
            url.contains("/tv/") || url.contains("/series/") || url.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val isTv = type != TvType.Movie

        return if (isTv) {
            val episodes = mutableListOf<Episode>()

            document.select("a[href*='/watch/'], a[href*='/season/'], a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], .episode-item")
                .forEachIndexed { index, element ->
                    val epUrl = element.attr("href")
                    val epName = element.select(".ep-title, span").text()
                        .ifEmpty { element.text() }
                        .ifEmpty { "Episode ${index + 1}" }

                    val fixedEpUrl =
                        if (epUrl.startsWith("http")) epUrl else "$mainUrl/${epUrl.trimStart('/')}"

                    val seasonMatch = Regex("s(?:eason)?[-/]?(\\d+)", RegexOption.IGNORE_CASE)
                        .find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                    val epMatch = Regex("e(?:pisode)?[-/]?(\\d+)", RegexOption.IGNORE_CASE)
                        .find(epUrl)?.groupValues?.get(1)?.toIntOrNull() ?: (index + 1)

                    val sep = if (fixedEpUrl.contains("?")) "&" else "?"
                    val finalEpUrl =
                        if (fixedEpUrl.contains("season=", true) || fixedEpUrl.contains("episode=", true)) {
                            fixedEpUrl
                        } else {
                            "$fixedEpUrl${sep}season=$seasonMatch&episode=$epMatch"
                        }

                    episodes.add(
                        newEpisode(finalEpUrl) {
                            this.name = epName
                            this.season = seasonMatch
                            this.episode = epMatch
                        }
                    )
                }

            if (episodes.isEmpty()) {
                val sep = if (url.contains("?")) "&" else "?"
                for (s in 1..3) {
                    val totalEps = 8
                    for (e in 1..totalEps) {
                        episodes.add(
                            newEpisode("$url${sep}season=$s&episode=$e") {
                                this.name = "Season $s Episode $e"
                                this.season = s
                                this.episode = e
                            }
                        )
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, type, episodes.distinctBy { Pair(it.season, it.episode) }) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    data class StreamResponse(
        val streams: List<StreamItem> = emptyList()
    )

    data class StreamItem(
        val url: String = "",
        val type: String = "",
        val id: String? = null,
        val provider: String? = null,
        val label: String? = null,
        val quality: String? = null,
        val expiresAt: Long? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    data class SubtitleItem(
        val language: String = "",
        val label: String = "",
        val url: String = ""
    )

    private fun getQuality(value: String?): Int {
        val quality = value?.uppercase().orEmpty()
        return when {
            quality.contains("2160") || quality.contains("4K") -> Qualities.P2160.value
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
            val doc = app.get(data, interceptor = cfKiller).document
            val pageHtml = doc.html()

            val matchId = Regex("-(?:movie|tv|dramas|anime|series)-(\\d+)").find(data)?.groupValues?.get(1)
                ?: Regex("[?&]id=(\\d+)").find(data)?.groupValues?.get(1)
                ?: Regex("(\\d+)(?:\\D*)$").find(data)?.groupValues?.get(1)
                ?: return false

            val imdbId = Regex("tt\\d+").find(pageHtml)?.value.orEmpty()
            val isTv = data.contains("/tv/") ||
                data.contains("/dramas/") ||
                data.contains("/anime/") ||
                data.contains("/series/") ||
                data.contains("season", true)
            val contentType = if (isTv) "tv" else "movie"

            val season = Regex("[?&]season=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1)
                ?: Regex("s(?:eason)?[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1)
                ?: "1"
            val episode = Regex("[?&]episode=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1)
                ?: Regex("e(?:pisode)?[-/]?(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1)
                ?: "1"

            val tvQuery = if (isTv) "&season=$season&episode=$episode" else ""
            val imdbQuery = if (imdbId.isNotBlank()) "&imdbId=$imdbId" else ""

            val endpoints = listOf(
                "purstream" to "$mainUrl/api/stream/purstream?type=$contentType&id=$matchId$tvQuery",
                "castle" to "$mainUrl/api/stream/castle?type=$contentType&id=$matchId$tvQuery",
                "direct" to "$mainUrl/api/stream/direct?type=$contentType&id=$matchId$tvQuery",
                "debrid" to "$mainUrl/api/stream/debrid?type=$contentType&id=$matchId$imdbQuery$tvQuery"
            )

            val headers = mapOf(
                "Referer" to data,
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT
            )

            val seenUrls = mutableSetOf<String>()
            var found = false

            for ((endpointName, apiUrl) in endpoints) {
                val rawText = runCatching {
                    app.get(apiUrl, headers = headers, interceptor = cfKiller).text
                }.getOrNull().orEmpty()
                if (rawText.isBlank()) continue

                val parsed = runCatching { parseJson<StreamResponse>(rawText) }.getOrNull() ?: continue
                if (parsed.streams.isEmpty()) continue

                parsed.streams.forEach { stream ->
                    var streamUrl = stream.url.trim()
                    if (streamUrl.isBlank()) return@forEach
                    if (!streamUrl.startsWith("http")) {
                        streamUrl = "$mainUrl/${streamUrl.trimStart('/')}"
                    }
                    if (!seenUrls.add(streamUrl)) return@forEach

                    stream.subtitles?.forEach { sub ->
                        var subUrl = sub.url.trim()
                        if (subUrl.isNotBlank() && !subUrl.startsWith("http")) {
                            subUrl = "$mainUrl/${subUrl.trimStart('/')}"
                        }
                        if (subUrl.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = sub.label.ifBlank { sub.language.ifBlank { "English" } },
                                    url = subUrl
                                )
                            )
                        }
                    }

                    val isHls = stream.type.equals("hls", true) ||
                        streamUrl.contains(".m3u8", true) ||
                        streamUrl.contains("/media?ticket=", true)

                    val sourceLabel = stream.provider?.takeIf { it.isNotBlank() } ?: endpointName
                    val displayName = stream.label?.takeIf { it.isNotBlank() }
                        ?: "MovieFox ($sourceLabel) - ${stream.quality ?: "HD"}"

                    callback(
                        newExtractorLink(
                            source = sourceLabel,
                            name = displayName,
                            url = streamUrl,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mainUrl
                            this.quality = getQuality(stream.quality)
                        }
                    )
                    found = true
                }
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
