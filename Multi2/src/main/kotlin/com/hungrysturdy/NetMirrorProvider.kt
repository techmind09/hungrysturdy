package com.hungrysturdy

import com.fasterxml.jackson.databind.JsonNode
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import java.net.URLEncoder

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

    @Volatile
    private var cachedApiUrl: String = "https://api2.imdb4.shop"

    override val mainPage = mainPageOf(
        "filter:title_not%5B%5D=1&title_not%5B%5D=2&title_not%5B%5D=3&title_not%5B%5D=4&title_not%5B%5D=5&title_not%5B%5D=6&title_not%5B%5D=7&title_not%5B%5D=8&cn_multi%5B%5D=1&cn_multi%5B%5D=3&cn_multi%5B%5D=4&cn_multi%5B%5D=5&cn_multi%5B%5D=6&cn_multi%5B%5D=7&cn_multi%5B%5D=8&items_per_page=7&cache=home|related:2026" to "Trending Now",
        "filter:dubbing=Hindi&sort_by=date&items_per_page=20|related:%5BHindi%5D" to "Latest Hindi",
        "filter:lang=Telugu&sort_by=date&items_per_page=20|related:%5BTelugu%5D" to "Latest Telugu",
        "filter:lang=Tamil&sort_by=date&items_per_page=20|related:%5BTamil%5D" to "Latest Tamil",
        "filter:dubbing=Hindi&type=movie&sort_by=date&items_per_page=20|related:Hindi" to "Latest Hindi Dub",
        "filter:lang=Kannada&sort_by=date&items_per_page=20|related:%5BKannada%5D" to "Latest Kannada",
        "filter:lang=Malayalam&sort_by=date&items_per_page=20|related:%5BMalayalam%5D" to "Latest Malayalam",
        "filter:sort_by=created&items_per_page=20|related:2026" to "Newly Added",
        "filter:type=tv&dubbing=Hindi&sort_by=date&items_per_page=20|related:S1" to "Latest TV Shows",
        "filter:type=movie&sort_by=date&items_per_page=20|related:2026" to "Trending in Cinema",
        "filter:category=South+Indian&sort_by=date&items_per_page=20|related:%5BHindi%5D" to "South Indian",
        "filter:category=Bollywood&sort_by=date&items_per_page=20|related:Hindi" to "Bollywood",
        "filter:category=Hollywood&sort_by=date&items_per_page=20|related:English" to "Hollywood",
        "filter:category=Reality-TV&sort_by=date&items_per_page=20|related:S1" to "Reality-TV",
        "filter:category=Indian+Drama&sort_by=date&items_per_page=20|related:S1" to "Indian Drama",
        "filter:category=K-Drama&sort_by=date&items_per_page=20|related:S1" to "K-Drama",
        "filter:category=C-Drama&sort_by=date&items_per_page=20|related:S1" to "C-Drama",
        "filter:category=Western+TV&sort_by=date&items_per_page=20|related:S1" to "Western TV",
        "filter:category=Turkish+Drama&sort_by=date&items_per_page=20|related:S1" to "Turkish Drama",
        "filter:type=tv&sort_by=rating&items_per_page=20|related:S1" to "Top Rated IMDb Series",
        "filter:type=movie&sort_by=rating&items_per_page=20|related:Hindi" to "Movies"
    )

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    private suspend fun resolveApiBaseUrl(forceRefresh: Boolean = false): String {
        if (!forceRefresh) return cachedApiUrl
        val found = runCatching {
            val homeHtml = app.get(mainUrl).text
            val jsPath = Regex("""src="(/assets/index-[^"]+\.js)"""")
                .find(homeHtml)?.groupValues?.get(1) ?: return@runCatching null
            val jsText = app.get("$mainUrl$jsPath").text
            Regex("""https?://api\d*\.[a-z0-9.-]+\.[a-z]+""")
                .find(jsText)?.value
        }.getOrNull()

        if (!found.isNullOrBlank()) {
            cachedApiUrl = found.trimEnd('/')
        }
        return cachedApiUrl
    }

    private suspend fun apiGet(pathAndQuery: String): String {
        val primaryBase = resolveApiBaseUrl(false)
        val firstTry = runCatching {
            app.get("$primaryBase$pathAndQuery", headers = apiHeaders).text
        }.getOrNull()

        if (!firstTry.isNullOrBlank() && firstTry.trimStart().startsWith("{")) {
            return firstTry
        }

        val refreshedBase = resolveApiBaseUrl(true)
        return app.get("$refreshedBase$pathAndQuery", headers = apiHeaders).text
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val apiPage = (page - 1).coerceAtLeast(0)
        val sources = request.data.split("|")
        var items: List<SearchResponse> = emptyList()

        for (src in sources) {
            val endpoint = when {
                src.startsWith("filter:") -> {
                    val query = src.removePrefix("filter:")
                    "/api/movies/filter?page=$apiPage&$query"
                }
                src.startsWith("related:") -> {
                    val term = src.removePrefix("related:")
                    "/api/related/$term?page=$apiPage"
                }
                else -> "/api/movies/filter?page=$apiPage&$src"
            }

            val rawText = runCatching { apiGet(endpoint) }.getOrDefault("")
            val root = runCatching { mapper.readTree(rawText) }.getOrNull()
            val parsed = root?.let { extractResults(it) }
                ?.mapNotNull { it.toSearchResponse() }
                ?: emptyList()

            if (parsed.isNotEmpty()) {
                items = parsed
                break
            }
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.isNotEmpty()
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        val encoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val relatedRaw = runCatching {
            apiGet("/api/related/$encoded?page=0")
        }.getOrDefault("")

        val relatedRoot = runCatching { mapper.readTree(relatedRaw) }.getOrNull()
        val relatedItems = relatedRoot?.let { extractResults(it) }
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        if (relatedItems.isNotEmpty()) return relatedItems

        val filterRaw = runCatching {
            apiGet("/api/movies/filter?page=0&search=$encoded&items_per_page=20")
        }.getOrDefault("")

        val filterRoot = runCatching { mapper.readTree(filterRaw) }.getOrNull() ?: return emptyList()
        return extractResults(filterRoot).mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val nid = cleanUrl.substringAfterLast("/")
        val isSeries = cleanUrl.contains("/tv/", ignoreCase = true) ||
                cleanUrl.contains("/series/", ignoreCase = true)

        val candidateEndpoints = listOf(
            "/api/movies/$nid",
            "/api/movie/$nid",
            "/api/tv/$nid",
            "/api/detail/$nid"
        )

        var rawText = ""
        for (ep in candidateEndpoints) {
            val res = runCatching { apiGet(ep) }.getOrDefault("")
            if (res.trimStart().startsWith("{") && !res.contains("\"error\"", ignoreCase = true)) {
                rawText = res
                break
            }
        }

        val root = runCatching { mapper.readTree(rawText) }.getOrNull()
        val detailNode = root?.get("results")?.takeIf { it.isArray && it.size() > 0 }?.get(0)
            ?: root

        val title = detailNode?.firstText("title", "name", "movie_title", "original_title")
            ?.trim()
            ?: "NetMirror #$nid"

        val poster = fixImageUrl(
            detailNode?.firstText(
                "backdrop_path", "poster_path", "poster", "backdrop", "image", "img", "thumbnail"
            )
        )

        val year = detailNode?.firstText("release_date", "year", "release_year")?.let { dateStr ->
            Regex("""\b(19|20)\d{2}\b""").find(dateStr)?.value?.toIntOrNull()
        }

        val plot = detailNode?.firstText("dis", "overview", "description", "plot", "story")?.trim('"')
        val voteAvg = detailNode?.firstText("vote_average", "rating")

        val castList = detailNode?.get("stafflist")?.takeIf { it.isArray }?.mapNotNull { staff ->
            val actorName = staff.firstText("name") ?: return@mapNotNull null
            val role = staff.firstText("character")
            val avatar = fixImageUrl(staff.firstText("avatarUrl", "avatar", "image"))
            ActorData(Actor(actorName, avatar), roleString = role)
        }

        val hasSeasons = detailNode?.get("season")?.let { it.isArray && it.size() > 0 } == true

        return if (isSeries || hasSeasons) {
            val episodes = parseEpisodes(detailNode, cleanUrl, poster).ifEmpty {
                listOf(
                    newEpisode(data = "$cleanUrl?s=1&e=1") {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                        this.posterUrl = poster
                    }
                )
            }

            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(voteAvg)
                if (!castList.isNullOrEmpty()) this.actors = castList
            }
        } else {
            newMovieLoadResponse(
                name = title,
                url = cleanUrl,
                type = TvType.Movie,
                dataUrl = cleanUrl
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
                this.score = Score.from10(voteAvg)
                if (!castList.isNullOrEmpty()) this.actors = castList
            }
        }
    }

    private fun parseEpisodes(
        detailNode: JsonNode?,
        cleanUrl: String,
        defaultPoster: String?
    ): List<Episode> {
        if (detailNode == null) return emptyList()
        val episodeList = mutableListOf<Episode>()

        val seasonsNode = detailNode.get("season")?.takeIf { it.isArray }
        if (seasonsNode != null) {
            for (seasonObj in seasonsNode) {
                val sNum = seasonObj.firstText("se", "season", "season_number")?.toIntOrNull() ?: continue
                val epCount = seasonObj.firstText("ep", "episodes", "episode_count")?.toIntOrNull() ?: 0
                val allEpRaw = seasonObj.firstText("allEp").orEmpty()

                val epNumbers = if (allEpRaw.isNotBlank()) {
                    allEpRaw.split(",")
                        .mapNotNull { it.trim().toIntOrNull() }
                        .ifEmpty { (1..epCount).toList() }
                } else {
                    (1..epCount).toList()
                }

                for (eNum in epNumbers) {
                    episodeList.add(
                        newEpisode(data = "$cleanUrl?s=$sNum&e=$eNum") {
                            this.name = "Episode $eNum"
                            this.season = sNum
                            this.episode = eNum
                            this.posterUrl = defaultPoster
                        }
                    )
                }
            }
        }

        return episodeList
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        var foundAny = false

        val requestedSeason = Regex("""[?&]s=(\d+)""").find(data)?.groupValues?.get(1)
        val requestedEpisode = Regex("""[?&]e=(\d+)""").find(data)?.groupValues?.get(1)

        if (data.contains("watchbox.php") || data.contains("external.php")) {
            val urls = data.split("|").map { it.trim() }.filter { it.isNotEmpty() }
            for (rawPlayerUrl in urls) {
                val playerUrl = applySeasonEpisode(rawPlayerUrl, requestedSeason, requestedEpisode)
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

        val pageResolver = WebViewResolver(
            interceptUrl = Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)"""),
            additionalUrls = listOf(Regex("""(watchbox\.php|external\.php|\.mp4|\.m3u8)""")),
            useOkhttp = false,
            timeout = 18_000L
        )

        val intercepted = app.get(data, headers = apiHeaders, interceptor = pageResolver)
        val interceptedUrl = applySeasonEpisode(intercepted.url, requestedSeason, requestedEpisode)

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

    private fun applySeasonEpisode(
        url: String,
        season: String?,
        episode: String?
    ): String {
        if (season.isNullOrBlank() && episode.isNullOrBlank()) return url
        var updated = url
        if (!season.isNullOrBlank()) {
            updated = if (updated.contains(Regex("""([?&])se=\d+"""))) {
                updated.replace(Regex("""([?&])se=\d+"""), "$1se=$season")
            } else if (updated.contains("watchbox.php") || updated.contains("external.php")) {
                "$updated&se=$season"
            } else {
                updated
            }
        }
        if (!episode.isNullOrBlank()) {
            updated = if (updated.contains(Regex("""([?&])ep=\d+"""))) {
                updated.replace(Regex("""([?&])ep=\d+"""), "$1ep=$episode")
            } else if (updated.contains("watchbox.php") || updated.contains("external.php")) {
                "$updated&ep=$episode"
            } else {
                updated
            }
        }
        return updated
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

    private fun extractResults(node: JsonNode): List<JsonNode> {
        if (node.isArray) return node.toList()
        if (node.isObject) {
            for (key in listOf("results", "data", "movies", "items", "posts", "list")) {
                val child = node.get(key) ?: continue
                if (child.isArray) return child.toList()
            }
        }
        return emptyList()
    }

    private fun JsonNode.firstText(vararg keys: String): String? {
        for (key in keys) {
            val value = this.get(key) ?: continue
            if (!value.isNull) {
                val text = value.asText().trim()
                if (text.isNotEmpty() && text != "null") return text
            }
        }
        return null
    }

    private fun fixImageUrl(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.replace("\\/", "/").trim()
        val fullUrl = when {
            cleaned.startsWith("http") -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "https://image.tmdb.org/t/p/w500$cleaned"
            else -> "$cachedApiUrl/$cleaned"
        }
        return fullUrl.replace("_UY300_", "_UY1000_")
    }

    private fun JsonNode.toSearchResponse(): SearchResponse? {
        val itemId = firstText("id", "nid", "movie_id", "tm_id") ?: return null
        val itemTitle = firstText("title", "name", "movie_title")?.trim() ?: return null
        val rawImage = firstText(
            "poster_path", "poster", "backdrop_path", "backdrop", "image", "img", "thumbnail"
        )
        val mediaType = firstText("media_type", "type", "content_type")
        val releaseYear = firstText("release_date", "year")?.let { dateStr ->
            Regex("""\b(19|20)\d{2}\b""").find(dateStr)?.value?.toIntOrNull()
        }
        val voteAvg = firstText("vote_average", "rating")
        val hasSeasonArray = this.get("season")?.let { it.isArray && it.size() > 0 } == true

        val isTv = hasSeasonArray ||
                mediaType.equals("tv", ignoreCase = true) ||
                mediaType.equals("series", ignoreCase = true) ||
                !firstText("first_air_date").isNullOrBlank()

        val detailUrl = if (isTv) "$mainUrl/tv/$itemId" else "$mainUrl/movie/$itemId"

        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, detailUrl, TvType.TvSeries) {
                this.posterUrl = fixImageUrl(rawImage)
                this.year = releaseYear
                this.score = Score.from10(voteAvg)
            }
        } else {
            newMovieSearchResponse(itemTitle, detailUrl, TvType.Movie) {
                this.posterUrl = fixImageUrl(rawImage)
                this.year = releaseYear
                this.score = Score.from10(voteAvg)
            }
        }
    }
}

