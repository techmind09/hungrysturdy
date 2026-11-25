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
        "title_not%5B%5D=1&title_not%5B%5D=2&title_not%5B%5D=3&title_not%5B%5D=4&title_not%5B%5D=5&title_not%5B%5D=6&title_not%5B%5D=7&title_not%5B%5D=8&cn_multi%5B%5D=1&cn_multi%5B%5D=3&cn_multi%5B%5D=4&cn_multi%5B%5D=5&cn_multi%5B%5D=6&cn_multi%5B%5D=7&cn_multi%5B%5D=8&cache=home" to "Trending Now",
        "media_type=movie" to "Latest Movies",
        "media_type=tv" to "Latest TV Series"
    )

    private val apiHeaders = mapOf(
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl,
        "Accept" to "application/json, text/plain, */*"
    )

    // Agar api2.imdb4.shop future mein rotate ho, to netmirror.gold ke JS bundle se naya API domain nikal lega
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

        // Domain rotate hone par fresh domain fetch karke retry karein
        val refreshedBase = resolveApiBaseUrl(true)
        return app.get("$refreshedBase$pathAndQuery", headers = apiHeaders).text
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val apiPage = (page - 1).coerceAtLeast(0)
        val rawText = apiGet("/api/movies/filter?page=$apiPage&${request.data}&items_per_page=20")

        val root = runCatching { mapper.readTree(rawText) }.getOrNull()
        val items = root?.let { extractResults(it) }
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        if (items.isEmpty() && page == 1) {
            throw ErrorLoadingException("Parse failed. Sample JSON: ${rawText.take(220)}")
        }

        return newHomePageResponse(
            list = HomePageList(request.name, items),
            hasNext = items.size >= 7
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isEmpty()) return emptyList()

        // Primary: NetMirror ka exact search/related endpoint (/api/related/Ramayana?page=0)
        val pathEncoded = URLEncoder.encode(cleanQuery, "UTF-8").replace("+", "%20")
        val relatedRaw = runCatching {
            apiGet("/api/related/$pathEncoded?page=0")
        }.getOrDefault("")

        val relatedRoot = runCatching { mapper.readTree(relatedRaw) }.getOrNull()
        val relatedItems = relatedRoot?.let { extractResults(it) }
            ?.mapNotNull { it.toSearchResponse() }
            ?: emptyList()

        if (relatedItems.isNotEmpty()) return relatedItems

        // Fallback: filter search
        val queryEncoded = URLEncoder.encode(cleanQuery, "UTF-8")
        val filterRaw = runCatching {
            apiGet("/api/movies/filter?page=0&search=$queryEncoded&items_per_page=20")
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
        val detailNode = when {
            root == null -> null
            root.has("data") && root["data"].isObject -> root["data"]
            root.has("movie") && root["movie"].isObject -> root["movie"]
            root.has("result") && root["result"].isObject -> root["result"]
            else -> root
        }

        val title = detailNode?.firstText("title", "name", "movie_title", "original_title")
            ?.trim()
            ?: "NetMirror #$nid"

        val poster = fixImageUrl(
            detailNode?.firstText(
                "poster_path", "poster", "backdrop_path", "backdrop", "image", "img", "thumbnail"
            )
        )

        val year = detailNode?.firstText("release_date", "year", "release_year")
            ?.take(4)
            ?.toIntOrNull()

        val plot = detailNode?.firstText("overview", "description", "plot", "story")

        // Agar detail JSON mein signed watchbox.php / external.php URLs hon to directly extract karein
        val signedPlayerRegex = Regex("""https?://[^"'\s]+(?:watchbox\.php|external\.php)[^"'\s]*""")
        val directPlayerUrls = signedPlayerRegex.findAll(rawText)
            .map { it.value.replace("\\/", "/") }
            .distinct()
            .toList()

        return if (isSeries) {
            val epArray = detailNode?.get("episodes")?.takeIf { it.isArray }?.toList() ?: emptyList()
            val episodes = epArray.mapIndexed { idx, ep ->
                val sNum = ep.firstText("season", "season_number")?.toIntOrNull() ?: 1
                val eNum = ep.firstText("episode", "episode_number")?.toIntOrNull() ?: (idx + 1)
                val epTitle = ep.firstText("title", "name")?.trim() ?: "Episode $eNum"
                val epStill = fixImageUrl(ep.firstText("still_path", "still", "backdrop_path", "image"))

                newEpisode(data = "$cleanUrl?s=$sNum&e=$eNum") {
                    this.name = epTitle
                    this.season = sNum
                    this.episode = eNum
                    this.posterUrl = epStill ?: poster
                }
            }

            newTvSeriesLoadResponse(title, cleanUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            val movieData = directPlayerUrls.takeIf { it.isNotEmpty() }?.joinToString("|") ?: cleanUrl
            newMovieLoadResponse(
                name = title,
                url = cleanUrl,
                type = TvType.Movie,
                dataUrl = movieData
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
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

        // Case 2: NetMirror page (/movie/126904) se signed player URL ya direct stream intercept karein
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

    // --- JSON Parsing Helpers ---
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
        return when {
            cleaned.startsWith("http") -> cleaned
            cleaned.startsWith("//") -> "https:$cleaned"
            cleaned.startsWith("/") -> "https://image.tmdb.org/t/p/w500$cleaned"
            else -> "$cachedApiUrl/$cleaned"
        }
    }

    private fun JsonNode.toSearchResponse(): SearchResponse? {
        val itemId = firstText("id", "nid", "movie_id", "tm_id") ?: return null
        val itemTitle = firstText("title", "name", "movie_title")?.trim() ?: return null
        val rawImage = firstText(
            "poster_path", "poster", "backdrop_path", "backdrop", "image", "img", "thumbnail"
        )
        val mediaType = firstText("media_type", "type", "content_type")
        val releaseYear = firstText("release_date", "year")?.take(4)?.toIntOrNull()

        val isTv = mediaType.equals("tv", ignoreCase = true) ||
                mediaType.equals("series", ignoreCase = true) ||
                !firstText("first_air_date").isNullOrBlank()

        val detailUrl = if (isTv) "$mainUrl/tv/$itemId" else "$mainUrl/movie/$itemId"

        return if (isTv) {
            newTvSeriesSearchResponse(itemTitle, detailUrl, TvType.TvSeries) {
                this.posterUrl = fixImageUrl(rawImage)
                this.year = releaseYear
            }
        } else {
            newMovieSearchResponse(itemTitle, detailUrl, TvType.Movie) {
                this.posterUrl = fixImageUrl(rawImage)
                this.year = releaseYear
            }
        }
    }
}

