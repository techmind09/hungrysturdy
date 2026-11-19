package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import org.json.JSONArray
import org.json.JSONObject
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
        "$mainUrl/trending" to "Trending Now",
        "$mainUrl/movies?list=popular" to "Popular Movies",
        "$mainUrl/movies?list=top_rated" to "Top Rated Movies",
        "$mainUrl/movies?list=now_playing" to "In Theaters Now",
        "$mainUrl/tv?list=popular" to "Popular TV Shows",
        "$mainUrl/tv?list=top_rated" to "Top Rated TV Shows",
        "$mainUrl/tv?lang=ko" to "K-Drama",
        "$mainUrl/movies?lang=ko" to "Korean Movies",
        "$mainUrl/movies?lang=hi" to "Indian Movies (Hindi)",
        "$mainUrl/tv?lang=hi" to "Indian TV Shows",
        "$mainUrl/movies?lang=pa" to "Punjabi Movies",
        "$mainUrl/movies?lang=ta" to "Tamil Movies",
        "$mainUrl/movies?lang=te" to "Telugu Movies",
        "$mainUrl/movies?lang=en" to "American / Hollywood Movies",
        "$mainUrl/tv?lang=en" to "American / English TV Shows",
        "$mainUrl/movies?lang=ur" to "Pakistani / Urdu Movies",
        "$mainUrl/tv?lang=ur" to "Pakistani Dramas",
        "$mainUrl/tv?lang=tr" to "Turkish Dramas",
        "$mainUrl/tv?lang=zh" to "Chinese Dramas (C-Drama)",
        "$mainUrl/anime" to "Anime",
        "$mainUrl/tv?lang=ja" to "Japanese Series",
        "$mainUrl/movies?lang=ja" to "Japanese Movies",
        "$mainUrl/movies?lang=es" to "Spanish Movies",
        "$mainUrl/tv?lang=es" to "Spanish TV Shows",
        "$mainUrl/dramas" to "Dramas",
        "$mainUrl/dramas?category=classics" to "Classic Dramas",
        "$mainUrl/universes" to "Cinematic Universes"
    )

    private val ignoredLinkTexts = setOf(
        "where to watch",
        "more info",
        "watch now",
        "view all",
        "play",
        "play now",
        "trailer",
        "all options",
        "go home",
        "search"
    )

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

        val rawHtml = app.get(targetUrl, interceptor = cfKiller).text
        val document = org.jsoup.Jsoup.parse(rawHtml, mainUrl)
        val home = mutableListOf<HomePageList>()

        val cardSelector =
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        val fallbackLinkSelector =
            "a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]"

        val elements = document.select(cardSelector).ifEmpty { document.select(fallbackLinkSelector) }
        val items = elements
            .mapNotNull { element -> toSearchResult(element) }
            .distinctBy { it.url }

        if (items.isNotEmpty()) {
            home.add(HomePageList(request.name, items))
        }

        return newHomePageResponse(home, hasNext = items.isNotEmpty())
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val href = element.attr("href").trim()
        if (href.isBlank()) return null

        val rawText = element.text().trim()
        if (rawText.lowercase() in ignoredLinkTexts) return null

        val article = element.closest("article")
        val img = element.selectFirst("img") ?: article?.selectFirst("img")
        val isSpecialCard = href.contains("/universe") || href.contains("/channel/")

        if (img == null && !isSpecialCard) return null

        val ariaTitle = element.attr("aria-label")
            .replace(Regex("^Number \\d+:\\s*"), "")
            .substringBefore(" (20")
            .substringBefore(" (19")
            .trim()

        val imgAlt = img?.attr("alt")?.trim().orEmpty()
        val headingTitle = article?.selectFirst("h3, h4")?.text()?.trim().orEmpty()
        val title = ariaTitle.ifEmpty { imgAlt }.ifEmpty { headingTitle }.ifEmpty { rawText }

        if (title.isBlank() || title.lowercase() in ignoredLinkTexts) return null

        val poster = img?.let {
            it.attr("src")
                .ifEmpty { it.attr("data-src") }
                .ifEmpty { it.attr("srcset").substringBefore(" ") }
        }

        val fixedUrl = if (href.startsWith("http")) href else "$mainUrl/${href.trimStart('/')}"
        val cfType = article?.attr("data-cf-type").orEmpty()

        val type = when {
            fixedUrl.contains("/anime/") -> TvType.Anime
            fixedUrl.contains("/dramas/") || fixedUrl.contains("/channel/") -> TvType.AsianDrama
            cfType.equals("tv", true) ||
                fixedUrl.contains("/tv/") ||
                fixedUrl.contains("/series/") ||
                fixedUrl.contains("/universe") -> TvType.TvSeries
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
        val elements = document.select(
            "article[data-cf] a[aria-label][href], a[href*=/universes/], a[href*=/universe/], a[href*=/channel/]"
        ).ifEmpty {
            document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/dramas/], a[href*=/anime/], a[href*=/series/]")
        }
        return elements.mapNotNull { toSearchResult(it) }.distinctBy { it.url }
    }

    private fun extractSeriesId(url: String): String? {
        return Regex("-(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE)
            .find(url)?.groupValues?.get(1)
            ?: Regex("/(?:movie|tv|dramas|anime|series)/(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            ?: Regex("(?:movie|tv|dramas|anime|series)-(\\d+)", RegexOption.IGNORE_CASE)
                .find(url)?.groupValues?.get(1)
            ?: Regex("[?&]id=(\\d+)").find(url)?.groupValues?.get(1)
    }

    private fun extractUnescapedFlightText(rawHtml: String): String {
        val sb = StringBuilder()
        Regex("""self\.__next_f\.push\(\[\d+,"((?:\\.|[^"\\])*)"\]\)""").findAll(rawHtml).forEach { m ->
            val unescaped = runCatching {
                JSONObject("{\"v\":\"${m.groupValues[1]}\"}").getString("v")
            }.getOrDefault(m.groupValues[1])
            sb.append(unescaped).append('\n')
        }
        return if (sb.isNotEmpty()) sb.toString() else rawHtml
    }

    private fun extractJsonArrayAfterKey(text: String, key: String): String? {
        val keyIdx = text.indexOf("\"$key\":")
        if (keyIdx == -1) return null
        val start = text.indexOf('[', keyIdx + key.length + 2)
        if (start == -1) return null

        var depth = 0
        var inString = false
        var escape = false
        for (i in start until text.length) {
            val c = text[i]
            if (escape) {
                escape = false
                continue
            }
            if (c == '\\' && inString) {
                escape = true
                continue
            }
            if (c == '"') {
                inString = !inString
                continue
            }
            if (!inString) {
                if (c == '[') depth++
                else if (c == ']') {
                    depth--
                    if (depth == 0) return text.substring(start, i + 1)
                }
            }
        }
        return null
    }

    override suspend fun load(url: String): LoadResponse {
        val responseText = app.get(url, interceptor = cfKiller).text
        val document = org.jsoup.Jsoup.parse(responseText, mainUrl)
        val flightText = extractUnescapedFlightText(responseText)

        val title = document.selectFirst("h1")?.text()?.trim()
            .orEmpty()
            .ifEmpty {
                Regex("\"title\":\"([^\"]+)\"").find(flightText)?.groupValues?.get(1).orEmpty()
            }
            .ifEmpty { document.select("title").text().substringBefore("—").trim() }

        val posterPath = Regex("\"posterPath\":\"(/[^\"\\\\]+)\"").find(flightText)?.groupValues?.get(1)
        val poster = if (!posterPath.isNullOrBlank()) {
            "https://image.tmdb.org/t/p/w500$posterPath"
        } else {
            document.select("main img, img.object-cover, img[data-nimg='fill']").firstOrNull()?.let {
                it.attr("src").ifEmpty { it.attr("data-src") }
            }
        }

        val jsonOverview = Regex("\"overview\":\"((?:\\\\.|[^\"\\\\])*)\"")
            .find(flightText)?.groupValues?.get(1)
            ?.let { raw -> runCatching { JSONObject("{\"v\":\"$raw\"}").getString("v") }.getOrDefault(raw) }
            ?.trim()
            .orEmpty()

        val description = jsonOverview.ifEmpty {
            document.select("main p.max-w-3xl, main p")
                .map { it.text().trim() }
                .firstOrNull { text ->
                    text.length > 30 &&
                        !text.contains("MovieFox indexes third-party content", ignoreCase = true) &&
                        !text.contains("All rights reserved", ignoreCase = true)
                }.orEmpty()
        }

        val type = when {
            url.contains("/anime/") -> TvType.Anime
            url.contains("/dramas/") || url.contains("/channel/") -> TvType.AsianDrama
            url.contains("/tv/") || url.contains("/series/") || url.contains("/universe") -> TvType.TvSeries
            else -> TvType.Movie
        }

        val isTv = type != TvType.Movie

        return if (isTv) {
            val baseSeriesId = extractSeriesId(url)
            val baseUrlWithoutQuery = url.substringBefore("?")
            val episodes = mutableListOf<Episode>()

            extractJsonArrayAfterKey(flightText, "episodes")?.let { arrJson ->
                runCatching {
                    val arr = JSONArray(arrJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val sNum = obj.optInt("seasonNumber", -1)
                        val eNum = obj.optInt("episodeNumber", -1)
                        if (sNum <= 0 || eNum <= 0) continue

                        val epTitle = obj.optString("name").ifBlank { "Episode $eNum" }
                        val epOverview = obj.optString("overview").takeIf { it.isNotBlank() }
                        val stillPath = obj.optString("stillPath").takeIf { it.startsWith("/") }
                        val epThumb = stillPath?.let { "https://image.tmdb.org/t/p/w500$it" }

                        episodes.add(
                            newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                                this.name = epTitle
                                this.season = sNum
                                this.episode = eNum
                                this.description = epOverview
                                this.posterUrl = epThumb
                            }
                        )
                    }
                }
            }

            extractJsonArrayAfterKey(flightText, "seasons")?.let { seasonsJson ->
                runCatching {
                    val arr = JSONArray(seasonsJson)
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val sNum = obj.optInt("seasonNumber", -1)
                        val epCount = obj.optInt("episodeCount", 0)
                        if (sNum <= 0 || epCount <= 0) continue

                        for (eNum in 1..epCount) {
                            if (episodes.none { it.season == sNum && it.episode == eNum }) {
                                episodes.add(
                                    newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                                        this.name = "Episode $eNum"
                                        this.season = sNum
                                        this.episode = eNum
                                    }
                                )
                            }
                        }
                    }
                }
            }

            document.select("a[href*='/watch/']").forEach { element ->
                val epHref = element.attr("href").trim()
                if (baseSeriesId != null) {
                    val linkId = extractSeriesId(epHref)
                    if (linkId != null && linkId != baseSeriesId) return@forEach
                }

                val sNum = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                val eNum = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE)
                    .find(epHref)?.groupValues?.get(1)?.toIntOrNull() ?: return@forEach
                if (sNum <= 0 || eNum <= 0) return@forEach

                if (episodes.none { it.season == sNum && it.episode == eNum }) {
                    val label = element.attr("aria-label")
                        .replace(Regex("^Watch episode \\d+:\\s*", RegexOption.IGNORE_CASE), "")
                        .trim()
                        .ifEmpty {
                            element.parent()?.selectFirst("p")?.text()
                                ?.replace(Regex("^\\d+\\.\\s*"), "")
                                ?.trim()
                                .orEmpty()
                        }
                        .ifEmpty { "Episode $eNum" }

                    val thumb = element.selectFirst("img")?.attr("src")?.takeIf { it.startsWith("http") }

                    episodes.add(
                        newEpisode("$baseUrlWithoutQuery?season=$sNum&episode=$eNum") {
                            this.name = label
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = thumb
                        }
                    )
                }
            }

            if (episodes.isEmpty()) {
                for (s in 1..3) {
                    for (e in 1..8) {
                        episodes.add(
                            newEpisode("$baseUrlWithoutQuery?season=$s&episode=$e") {
                                this.name = "Episode $e"
                                this.season = s
                                this.episode = e
                            }
                        )
                    }
                }
            }

            val sortedEpisodes = episodes
                .distinctBy { Pair(it.season, it.episode) }
                .sortedWith(compareBy({ it.season }, { it.episode }))

            newTvSeriesLoadResponse(title, url, type, sortedEpisodes) {
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
            val cleanPageUrl = data.substringBefore("?")
            val rawHtml = app.get(cleanPageUrl, interceptor = cfKiller).text
            val flightText = extractUnescapedFlightText(rawHtml)

            val matchId = extractSeriesId(cleanPageUrl)
                ?: Regex("\"id\":(\\d+)").find(flightText)?.groupValues?.get(1)
                ?: Regex("(\\d+)(?:\\D*)$").find(cleanPageUrl)?.groupValues?.get(1)
                ?: return false

            val imdbId = Regex("\"imdbId\":\"(tt\\d+)\"").find(flightText)?.groupValues?.get(1)
                ?: Regex("tt\\d+").find(rawHtml)?.value.orEmpty()

            val isTv = cleanPageUrl.contains("/tv/") ||
                cleanPageUrl.contains("/dramas/") ||
                cleanPageUrl.contains("/anime/") ||
                cleanPageUrl.contains("/series/") ||
                data.contains("season=", true)
            val contentType = if (isTv) "tv" else "movie"

            val season = Regex("[?&]s(?:eason)?=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"
            val episode = Regex("[?&]e(?:pisode)?=(\\d+)", RegexOption.IGNORE_CASE).find(data)?.groupValues?.get(1) ?: "1"

            val tvQuery = if (isTv) "&season=$season&episode=$episode" else ""
            val imdbQuery = if (imdbId.isNotBlank()) "&imdbId=$imdbId" else ""

            val endpoints = listOf(
                "purstream" to "$mainUrl/api/stream/purstream?type=$contentType&id=$matchId$tvQuery",
                "castle" to "$mainUrl/api/stream/castle?type=$contentType&id=$matchId$tvQuery",
                "direct" to "$mainUrl/api/stream/direct?type=$contentType&id=$matchId$tvQuery",
                "debrid" to "$mainUrl/api/stream/debrid?type=$contentType&id=$matchId$imdbQuery$tvQuery"
            )

            val headers = mapOf(
                "Referer" to cleanPageUrl,
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT
            )

            val seenUrls = mutableSetOf<String>()
            var found = false

            // 1. Direct API streams (Purstream, Castle, Direct, Debrid)
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

            // 2. Embed Servers from `/watch/{type}-{id}` page (`"sources":[{...}]`)
            val watchUrl = if (isTv) {
                "$mainUrl/watch/tv-$matchId?s=$season&e=$episode"
            } else {
                "$mainUrl/watch/movie-$matchId"
            }

            val watchHtml = runCatching {
                app.get(watchUrl, interceptor = cfKiller).text
            }.getOrNull().orEmpty()

            if (watchHtml.isNotBlank()) {
                val watchFlight = extractUnescapedFlightText(watchHtml)
                extractJsonArrayAfterKey(watchFlight, "sources")?.let { sourcesJson ->
                    runCatching {
                        val arr = JSONArray(sourcesJson)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val embedUrl = obj.optString("url").trim()
                            if (embedUrl.isBlank() || !embedUrl.startsWith("http")) continue
                            if (!seenUrls.add(embedUrl)) continue

                            val extracted = runCatching {
                                loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                            }.getOrDefault(false)

                            if (extracted) {
                                found = true
                            }
                        }
                    }
                }
            }

            found
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}
