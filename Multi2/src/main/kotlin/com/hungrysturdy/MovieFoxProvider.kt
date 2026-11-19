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
        val streams: List<StreamItem> = emptyList(),
        val sources: List<StreamItem> = emptyList(),
        val url: String? = null,
        val file: String? = null,
        val streamUrl: String? = null,
        val bid: Long? = null,
        val domains: List<String>? = null
    )

    data class StreamItem(
        val url: String = "",
        val file: String = "",
        val type: String = "",
        val id: String? = null,
        val provider: String? = null,
        val label: String? = null,
        val quality: String? = null,
        val expiresAt: Long? = null,
        val headers: Map<String, String>? = null,
        val subtitles: List<SubtitleItem>? = null
    )

    data class SubtitleItem(
        val language: String = "",
        val lang: String = "",
        val label: String = "",
        val url: String = "",
        val file: String = ""
    )

    private fun getQuality(value: String?): Int {
        val quality = value?.uppercase().orEmpty()
        return when {
            quality.contains("2160") || quality.contains("4K") -> Qualities.P2160.value
            quality.contains("1080") -> Qualities.P1080.value
            quality.contains("720") -> Qualities.P720.value
            quality.contains("480") -> Qualities.P480.value
            else -> Qualities.P1080.value
        }
    }

    private fun normalizeUrl(rawUrl: String): String {
        val trimmed = rawUrl.replace("\\/", "/").trim()
        return when {
            trimmed.startsWith("http://") || trimmed.startsWith("https://") -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> "$mainUrl/${trimmed.trimStart('/')}"
        }
    }

    private fun isAdUrl(url: String): Boolean {
        return url.contains("backhoemoya", true) ||
            url.contains("/cx/", true) ||
            url.contains("?md=[", true) ||
            url.contains("exoclick", true) ||
            url.contains("popads", true) ||
            url.contains("propeller", true)
    }

    private suspend fun isM3u8Stream(url: String, typeHint: String, headers: Map<String, String>): Boolean {
        if (
            typeHint.equals("hls", true) ||
            typeHint.equals("m3u8", true) ||
            url.contains(".m3u8", true) ||
            url.contains("type=hls", true) ||
            url.contains("itsnitrox.tech", true) ||
            url.contains("nxstreamhub", true) ||
            url.contains("stream_proxy.php", true) ||
            url.contains("vibuxer.com", true) ||
            url.contains("workers.dev", true) ||
            url.contains("/media?ticket=", true) ||
            url.contains("/playlist", true) ||
            url.contains("/manifest", true) ||
            url.contains("/proxy", true)
        ) {
            return true
        }

        return runCatching {
            val res = app.get(url, headers = headers, interceptor = cfKiller)
            val contentType = res.headers["Content-Type"].orEmpty()
            contentType.contains("mpegurl", true) || res.text.trimStart().startsWith("#EXTM3U")
        }.getOrDefault(false)
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

            // Aage chal kar FSL, FSL2, GDrive ya kisi bhi naye API endpoint ko bas is list mein add karein
            val allEndpoints = listOf(
                "purstream" to "$mainUrl/api/stream/purstream?type=$contentType&id=$matchId$tvQuery",
                "castle" to "$mainUrl/api/stream/castle?type=$contentType&id=$matchId$tvQuery",
                "direct" to "$mainUrl/api/stream/direct?type=$contentType&id=$matchId$tvQuery",
                "debrid" to "$mainUrl/api/stream/debrid?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "vidsrc" to "$mainUrl/api/stream/vidsrc?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "autoembed" to "$mainUrl/api/stream/autoembed?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "hls" to "$mainUrl/api/stream/hls?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "fsl" to "$mainUrl/api/stream/fsl?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "fsl2" to "$mainUrl/api/stream/fsl2?type=$contentType&id=$matchId$imdbQuery$tvQuery",
                "gdrive" to "$mainUrl/api/stream/gdrive?type=$contentType&id=$matchId$imdbQuery$tvQuery"
            )

            // Settings mein jo servers enabled hain sirf unhi ko filter karein
            val activeEndpoints = allEndpoints.filter { (key, _) ->
                MovieFoxPlugin.isServerEnabled(key)
            }

            val baseHeaders = mapOf(
                "Referer" to "$mainUrl/",
                "Origin" to mainUrl,
                "User-Agent" to USER_AGENT,
                "Accept" to "*/*"
            )

            val seenUrls = mutableSetOf<String>()
            var found = false

            // 1. Active API Endpoints (Purstream, Castle, Direct, Debrid, FSL, FSL2, GDrive, etc.)
            for ((endpointName, apiUrl) in activeEndpoints) {
                val response = runCatching {
                    app.get(apiUrl, headers = baseHeaders, interceptor = cfKiller)
                }.getOrNull() ?: continue

                val rawText = response.text
                if (rawText.isBlank()) continue

                val contentTypeHeader = response.headers["Content-Type"].orEmpty()
                if (
                    contentTypeHeader.contains("mpegurl", true) ||
                    rawText.trimStart().startsWith("#EXTM3U")
                ) {
                    if (seenUrls.add(apiUrl)) {
                        callback(
                            newExtractorLink(
                                source = endpointName.uppercase(),
                                name = "MovieFox (${endpointName.uppercase()}) - HLS",
                                url = apiUrl,
                                type = ExtractorLinkType.M3U8
                            ) {
                                this.referer = "$mainUrl/"
                                this.quality = Qualities.P1080.value
                                this.headers = baseHeaders
                            }
                        )
                        found = true
                    }
                    continue
                }

                val parsed = runCatching { parseJson<StreamResponse>(rawText) }.getOrNull() ?: continue
                if (parsed.bid != null || !parsed.domains.isNullOrEmpty()) continue

                val allStreams = buildList {
                    addAll(parsed.streams)
                    addAll(parsed.sources)
                    val fallbackSingle = parsed.url ?: parsed.file ?: parsed.streamUrl
                    if (!fallbackSingle.isNullOrBlank()) {
                        add(StreamItem(url = fallbackSingle, provider = endpointName))
                    }
                }

                if (allStreams.isEmpty()) continue

                for (stream in allStreams) {
                    val rawStreamUrl = stream.url.ifBlank { stream.file }.trim()
                    if (rawStreamUrl.isBlank()) continue

                    val streamUrl = normalizeUrl(rawStreamUrl)
                    if (isAdUrl(streamUrl) || !seenUrls.add(streamUrl)) continue

                    stream.subtitles?.forEach { sub ->
                        val rawSubUrl = sub.url.ifBlank { sub.file }.trim()
                        if (rawSubUrl.isNotBlank()) {
                            subtitleCallback(
                                SubtitleFile(
                                    lang = sub.label.ifBlank {
                                        sub.language.ifBlank {
                                            sub.lang.ifBlank { "English" }
                                        }
                                    },
                                    url = normalizeUrl(rawSubUrl)
                                )
                            )
                        }
                    }

                    // Check if stream URL is a known embed host (Voe, StreamTape, Vidara, Lulu, Pixeldrain, GDrive, etc.)
                    val isKnownHost = listOf(
                        "voe.", "pixeldrain.", "streamtape.", "shavetape.", "strcloud.",
                        "luluvid.", "lulustream.", "luluvdo.", "vidara.", "vidprime.",
                        "upbolt.", "abyssplayer.", "abysscdn.", "short.icu", "lplaygo.",
                        "vidmoly.", "earnvids.", "dingtezuni.", "rabbitstream.", "upcloud.",
                        "megacloud.", "streamwish.", "filemoon.", "drive.google.com"
                    ).any { streamUrl.contains(it, true) }

                    if (isKnownHost) {
                        if (MovieFoxPlugin.isServerEnabled("embeds")) {
                            val extracted = runCatching {
                                loadExtractor(streamUrl, cleanPageUrl, subtitleCallback, callback)
                            }.getOrDefault(false)
                            if (extracted) found = true
                        }
                        continue
                    }

                    val mergedHeaders = baseHeaders + (stream.headers ?: emptyMap())
                    val isHls = isM3u8Stream(streamUrl, stream.type, mergedHeaders)

                    val sourceLabel = when {
                        !stream.provider.isNullOrBlank() -> stream.provider
                        streamUrl.contains("itsnitrox.tech", true) -> "Nitrox (${endpointName.uppercase()})"
                        streamUrl.contains("nxstreamhub", true) -> "NxStream (${endpointName.uppercase()})"
                        streamUrl.contains("vibuxer.com", true) -> "Vibuxer (${endpointName.uppercase()})"
                        streamUrl.contains("workers.dev", true) -> "WorkerHLS (${endpointName.uppercase()})"
                        else -> endpointName.uppercase()
                    }

                    val displayName = stream.label?.takeIf { it.isNotBlank() }
                        ?: "MovieFox ($sourceLabel) - ${stream.quality ?: "HD"}"

                    callback(
                        newExtractorLink(
                            source = sourceLabel,
                            name = displayName,
                            url = streamUrl,
                            type = if (isHls) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = mergedHeaders["Referer"] ?: "$mainUrl/"
                            this.quality = getQuality(stream.quality)
                            this.headers = mergedHeaders
                        }
                    )
                    found = true
                }
            }

            // 2. Embed Servers from `/watch/{type}-{id}` page (Agar "embeds" setting ON hai)
            if (MovieFoxPlugin.isServerEnabled("embeds")) {
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

                    // Direct scan for supported embed hosts & worker URLs in watch page
                    val hostScanRegex = Regex(
                        """https?://(?:[a-zA-Z0-9.-]+\.)?(?:voe\.(?:sx|to|co)|pixeldrain\.(?:com|dev)|streamtape\.(?:com|to|net|xyz)|shavetape\.cash|strcloud\.link|luluvid\.com|lulustream\.com|luluvdo\.com|vidara\.(?:to|so)|vidprime\.top|upbolt\.(?:to|tv)|abysscdn\.com|short\.icu|lplaygo\.com|vidmoly\.(?:me|to)|earnvids\.com|dingtezuni\.com|rabbitstream\.net|upcloud\.to|megacloud\.(?:tv|club)|streamwish\.to|filemoon\.sx|drive\.google\.com|workers\.dev)/[^\s"'\\]+""",
                        RegexOption.IGNORE_CASE
                    )
                    hostScanRegex.findAll(watchFlight).forEach { m ->
                        val hostUrl = normalizeUrl(m.value)
                        if (!isAdUrl(hostUrl) && seenUrls.add(hostUrl)) {
                            if (hostUrl.contains("workers.dev", true)) {
                                callback(
                                    newExtractorLink(
                                        source = "WorkerHLS",
                                        name = "MovieFox (Worker HLS)",
                                        url = hostUrl,
                                        type = ExtractorLinkType.M3U8
                                    ) {
                                        this.referer = watchUrl
                                        this.quality = Qualities.P1080.value
                                        this.headers = baseHeaders + mapOf("Referer" to watchUrl)
                                    }
                                )
                                found = true
                            } else {
                                val extracted = runCatching {
                                    loadExtractor(hostUrl, watchUrl, subtitleCallback, callback)
                                }.getOrDefault(false)
                                if (extracted) found = true
                            }
                        }
                    }

                    extractJsonArrayAfterKey(watchFlight, "sources")?.let { sourcesJson ->
                        runCatching {
                            val arr = JSONArray(sourcesJson)
                            for (i in 0 until arr.length()) {
                                val obj = arr.optJSONObject(i) ?: continue
                                val embedUrl = normalizeUrl(obj.optString("url"))
                                if (embedUrl.isBlank() || !embedUrl.startsWith("http") || isAdUrl(embedUrl)) continue
                                if (!seenUrls.add(embedUrl)) continue

                                if (
                                    embedUrl.contains(".m3u8", true) ||
                                    embedUrl.contains("type=hls", true) ||
                                    embedUrl.contains("itsnitrox.tech", true) ||
                                    embedUrl.contains("nxstreamhub", true) ||
                                    embedUrl.contains("stream_proxy.php", true) ||
                                    embedUrl.contains("vibuxer.com", true) ||
                                    embedUrl.contains("workers.dev", true) ||
                                    obj.optString("type").equals("hls", true)
                                ) {
                                    val customHeaders = if (
                                        embedUrl.contains("itsnitrox.tech", true) ||
                                        embedUrl.contains("nxstreamhub", true) ||
                                        embedUrl.contains("vibuxer.com", true)
                                    ) {
                                        baseHeaders + mapOf("Referer" to "https://vidprime.top/", "Origin" to "https://vidprime.top")
                                    } else {
                                        baseHeaders + mapOf("Referer" to watchUrl)
                                    }

                                    callback(
                                        newExtractorLink(
                                            source = obj.optString("name").ifBlank { "MovieFox" },
                                            name = obj.optString("label").ifBlank { "MovieFox HLS" },
                                            url = embedUrl,
                                            type = ExtractorLinkType.M3U8
                                        ) {
                                            this.referer = customHeaders["Referer"] ?: watchUrl
                                            this.quality = getQuality(obj.optString("quality"))
                                            this.headers = customHeaders
                                        }
                                    )
                                    found = true
                                } else {
                                    val extracted = runCatching {
                                        loadExtractor(embedUrl, watchUrl, subtitleCallback, callback)
                                    }.getOrDefault(false)
                                    if (extracted) found = true
                                }
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
