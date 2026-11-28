package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class ArrowTvProvider : MainAPI() {
    override var mainUrl = "https://arrowtv.net"
    override var name = "ArrowTV"
    override val hasMainPage = true
    override var lang = "en"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )

    private val punkflixBase = "https://punkflix.net"
    private val subtitleApiBase = "https://proxy1.flikhub.net/subtitles"
    private val playerReferer = "https://player.cinezo.live/"
    private val playerOrigin = "https://player.cinezo.live"

    private val tmdbPosterBase = "https://image.tmdb.org/t/p/w500"
    private val tmdbBackdropBase = "https://image.tmdb.org/t/p/w1280"
    private val tmdbStillBase = "https://image.tmdb.org/t/p/w342"

    override val mainPage = mainPageOf(
        "" to "Home",
        "/4k" to "Stream in 4K",
        "/movies" to "Movies",
        "/tv" to "TV Shows",
        "/anime" to "Anime",
        "/kdrama" to "K-Drama",
        "/api/platform?platform=netflix&type=movie" to "Netflix Movies",
        "/api/platform?platform=netflix&type=tv" to "Netflix Series",
        "/api/platform?platform=prime&type=movie" to "Prime Video Movies",
        "/api/platform?platform=prime&type=tv" to "Prime Video Series",
        "/api/platform?platform=disney&type=movie" to "Disney+ Movies",
        "/api/platform?platform=disney&type=tv" to "Disney+ Series",
        "/api/platform?platform=apple&type=movie" to "Apple TV+ Movies",
        "/api/platform?platform=apple&type=tv" to "Apple TV+ Series",
        "/api/platform?platform=hbo&type=movie" to "HBO Max Movies",
        "/api/platform?platform=hbo&type=tv" to "HBO Max Series",
        "/api/platform?platform=hulu&type=movie" to "Hulu Movies",
        "/api/platform?platform=hulu&type=tv" to "Hulu Series",
        "/api/platform?platform=paramount&type=movie" to "Paramount+ Movies",
        "/api/platform?platform=paramount&type=tv" to "Paramount+ Series",
        "/api/platform?platform=crunchyroll&type=tv" to "Crunchyroll Anime",
        "/api/platform?platform=peacock&type=movie" to "Peacock Movies",
        "/api/platform?platform=starz&type=movie" to "Starz Movies",
        "/api/platform?platform=amc&type=tv" to "AMC+ Series"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        if (request.data.startsWith("/api/")) {
            val apiUrl = "$mainUrl${request.data}&page=$page"
            val defaultType = if (request.data.contains("type=tv")) "tv" else "movie"
            val responseText = app.get(apiUrl, referer = "$mainUrl/").text

            val (items, hasNext) = parseTmdbJsonList(responseText, defaultType, page)
            return newHomePageResponse(
                list = HomePageList(request.name, items),
                hasNext = hasNext
            )
        }

        val url = when {
            request.data.isEmpty() -> mainUrl
            page > 1 -> "$mainUrl${request.data}?page=$page"
            else -> "$mainUrl${request.data}"
        }

        val document = app.get(url).document
        val homePageLists = mutableListOf<HomePageList>()

        val sections = document.select("section.a-sec")
        if (sections.isNotEmpty() && page == 1) {
            sections.forEach { section ->
                if (section.hasClass("a-apps") || section.hasClass("a-swap")) return@forEach

                val sectionTitle = section.selectFirst("h2.a-sec-title")?.text()?.trim()
                    ?: return@forEach

                val items = section.select("a.a-card").mapNotNull { it.toSearchResponse() }
                if (items.isNotEmpty()) {
                    homePageLists.add(
                        HomePageList(
                            name = if (request.data.isEmpty()) sectionTitle else "${request.name} - $sectionTitle",
                            list = items,
                            isHorizontalImages = section.hasClass("a-top10")
                        )
                    )
                }
            }
        }

        if (homePageLists.isEmpty()) {
            val items = document.select("a.a-card").mapNotNull { it.toSearchResponse() }
            if (items.isNotEmpty()) {
                homePageLists.add(HomePageList(request.name, items))
            }
        }

        val hasNext = request.data.isNotEmpty() &&
            document.selectFirst("a[href*=page=${page + 1}]") != null

        return newHomePageResponse(homePageLists, hasNext = hasNext)
    }

    private fun parseTmdbJsonList(
        jsonText: String,
        defaultMediaType: String,
        currentPage: Int
    ): Pair<List<SearchResponse>, Boolean> {
        val trimmed = jsonText.trim()
        if (trimmed.isEmpty()) return emptyList<SearchResponse>() to false

        var totalPages = currentPage
        val array: JSONArray = if (trimmed.startsWith("[")) {
            JSONArray(trimmed)
        } else {
            val obj = JSONObject(trimmed)
            totalPages = obj.optInt("total_pages", obj.optInt("totalPages", currentPage + 1))
            obj.optJSONArray("results")
                ?: obj.optJSONArray("items")
                ?: obj.optJSONArray("data")
                ?: JSONArray()
        }

        val list = mutableListOf<SearchResponse>()
        for (i in 0 until array.length()) {
            val item = array.optJSONObject(i) ?: continue
            val id = item.optInt("id", -1)
            if (id == -1) continue

            val mediaType = item.optString("media_type").ifBlank { defaultMediaType }
            val isTv = mediaType.equals("tv", ignoreCase = true)

            val title = item.optString("title")
                .ifBlank { item.optString("name") }
                .ifBlank { item.optString("original_title") }
                .ifBlank { item.optString("original_name") }
            if (title.isBlank()) continue

            val posterUrl = buildTmdbImg(item.optString("poster_path"), tmdbPosterBase)
            val href = if (isTv) "$mainUrl/tv/$id" else "$mainUrl/movie/$id"

            val response = if (isTv) {
                newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                    this.posterUrl = posterUrl
                }
            } else {
                newMovieSearchResponse(title, href, TvType.Movie) {
                    this.posterUrl = posterUrl
                }
            }
            list.add(response)
        }

        val hasNext = list.isNotEmpty() && currentPage < totalPages
        return list to hasNext
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        if (selectFirst(".a-soon") != null) return null

        val href = fixUrlNull(attr("href")) ?: return null
        if (!href.contains("/movie/") && !href.contains("/tv/")) return null

        val title = selectFirst(".a-cap b")?.text()?.trim()
            ?: attr("aria-label").replace(Regex("^\\d+\\.\\s*"), "").trim()
        if (title.isBlank()) return null

        val posterUrl = selectFirst("img.a-img")?.attr("src")?.let { fixUrlNull(it) }
        val isTv = href.contains("/tv/")
        val is4k = selectFirst(".a-4k") != null

        return if (isTv) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                if (is4k) this.quality = SearchQuality.UHD
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                if (is4k) this.quality = SearchQuality.UHD
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val document = app.get("$mainUrl/search?q=$encoded").document
        return document.select("a.a-card").mapNotNull { it.toSearchResponse() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val isTv = url.contains("/tv/")
        val type = if (isTv) "tv" else "movie"
        val tmdbId = Regex("/(?:watch/)?(?:movie|tv)/(\\d+)").find(url)?.groupValues?.get(1)
            ?: return null

        val pageUrl = "$mainUrl/$type/$tmdbId"

        val mediaJson = runCatching {
            val raw = app.get("$mainUrl/api/media-info?id=$tmdbId&type=$type", referer = "$mainUrl/").text.trim()
            if (raw.startsWith("{")) JSONObject(raw) else null
        }.getOrNull()

        val dataObj = mediaJson?.optJSONObject("data") ?: mediaJson

        var title = dataObj?.optString("title")
            ?.ifBlank { dataObj.optString("name") }
            ?.ifBlank { dataObj.optString("original_title") }
            ?.ifBlank { dataObj.optString("original_name") }
            ?.ifBlank { null }

        var poster = buildTmdbImg(dataObj?.optString("poster_path"), tmdbPosterBase)
        val backdrop = buildTmdbImg(dataObj?.optString("backdrop_path"), tmdbBackdropBase)
        var plot = dataObj?.optString("overview")?.ifBlank { null }

        val dateStr = dataObj?.optString("release_date")
            ?.ifBlank { dataObj.optString("first_air_date") }
        val year = dateStr?.take(4)?.toIntOrNull()

        val tags = mutableListOf<String>()
        val genresArr = dataObj?.optJSONArray("genres")
        if (genresArr != null) {
            for (i in 0 until genresArr.length()) {
                val g = genresArr.optJSONObject(i)?.optString("name")
                    ?: genresArr.optString(i)
                if (!g.isNullOrBlank()) tags.add(g)
            }
        }

        if (title.isNullOrBlank()) {
            val doc = app.get(pageUrl, referer = "$mainUrl/").document
            title = doc.selectFirst("h1.cz-watch-title, h1")?.text()?.trim()
                ?: doc.selectFirst("meta[property=og:title]")?.attr("content")
                    ?.replace(" - ArrowTV", "")?.trim()
                ?: "TMDB $tmdbId"
            if (poster == null) {
                poster = doc.selectFirst("meta[property=og:image]")?.attr("content")
            }
            if (plot == null) {
                plot = doc.selectFirst("meta[name=description]")?.attr("content")
            }
        }

        return if (isTv) {
            val episodes = fetchAllTvEpisodes(tmdbId, dataObj).ifEmpty {
                listOf(
                    newEpisode("tv|$tmdbId|1|1") {
                        this.name = "Season 1 Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, pageUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                if (tags.isNotEmpty()) this.tags = tags
            }
        } else {
            val duration = dataObj?.optInt("runtime", 0)?.takeIf { it > 0 }
            newMovieLoadResponse(title, pageUrl, TvType.Movie, "movie|$tmdbId") {
                this.posterUrl = poster
                this.backgroundPosterUrl = backdrop
                this.plot = plot
                this.year = year
                this.duration = duration
                if (tags.isNotEmpty()) this.tags = tags
            }
        }
    }

    private suspend fun fetchAllTvEpisodes(
        tmdbId: String,
        mediaInfoObj: JSONObject?
    ): List<Episode> {
        val seasonNumbers = mutableListOf<Int>()

        val seasonsRaw = runCatching {
            app.get("$mainUrl/api/tv-seasons?id=$tmdbId", referer = "$mainUrl/").text.trim()
        }.getOrNull()

        val seasonsArray: JSONArray? = when {
            seasonsRaw.isNullOrBlank() -> mediaInfoObj?.optJSONArray("seasons")
            seasonsRaw.startsWith("[") -> JSONArray(seasonsRaw)
            seasonsRaw.startsWith("{") -> {
                val root = JSONObject(seasonsRaw)
                root.optJSONArray("seasons")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
            }
            else -> null
        }

        if (seasonsArray != null) {
            for (i in 0 until seasonsArray.length()) {
                val sObj = seasonsArray.optJSONObject(i) ?: continue
                val sNum = sObj.optInt(
                    "season_number",
                    sObj.optInt("seasonNumber", sObj.optInt("season", -1))
                )
                if (sNum > 0) seasonNumbers.add(sNum)
            }
        }

        if (seasonNumbers.isEmpty()) seasonNumbers.add(1)

        return seasonNumbers.distinct().sorted().amap { seasonNum ->
            fetchSeasonEpisodes(tmdbId, seasonNum)
        }.flatten()
    }

    private suspend fun fetchSeasonEpisodes(tmdbId: String, seasonNum: Int): List<Episode> {
        val raw = runCatching {
            app.get(
                "$mainUrl/api/tv-episodes?id=$tmdbId&season=$seasonNum",
                referer = "$mainUrl/"
            ).text.trim()
        }.getOrNull() ?: return emptyList()

        if (raw.isEmpty()) return emptyList()

        val epArray: JSONArray = if (raw.startsWith("[")) {
            JSONArray(raw)
        } else {
            val root = JSONObject(raw)
            root.optJSONArray("episodes")
                ?: root.optJSONArray("data")
                ?: root.optJSONArray("results")
                ?: JSONArray()
        }

        val list = mutableListOf<Episode>()
        for (i in 0 until epArray.length()) {
            val epObj = epArray.optJSONObject(i) ?: continue
            val epNum = epObj.optInt(
                "episode_number",
                epObj.optInt("episodeNumber", epObj.optInt("episode", i + 1))
            )
            val epName = epObj.optString("name")
                .ifBlank { epObj.optString("title") }
                .ifBlank { "Episode $epNum" }
            val epDesc = epObj.optString("overview").ifBlank { null }
            val epPoster = buildTmdbImg(
                epObj.optString("still_path").ifBlank { epObj.optString("poster_path") },
                tmdbStillBase
            )
            val runtime = epObj.optInt("runtime", 0).takeIf { it > 0 }

            list.add(
                newEpisode("tv|$tmdbId|$seasonNum|$epNum") {
                    this.name = epName
                    this.season = seasonNum
                    this.episode = epNum
                    this.description = epDesc
                    this.posterUrl = epPoster
                    this.runTime = runtime
                }
            )
        }
        return list
    }

    private fun buildTmdbImg(path: String?, base: String): String? {
        if (path.isNullOrBlank() || path == "null") return null
        return if (path.startsWith("http")) path else "$base$path"
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val parts = data.split("|")
        val punkflixUrl: String
        val subtitleApiUrl: String

        when (parts.firstOrNull()) {
            "movie" -> {
                punkflixUrl = "$punkflixBase/movie/${parts[1]}.json"
                subtitleApiUrl = "$subtitleApiBase/movie/${parts[1]}"
            }
            "tv" -> {
                punkflixUrl = "$punkflixBase/tv/${parts[1]}/${parts[2]}/${parts[3]}.json"
                subtitleApiUrl = "$subtitleApiBase/tv/${parts[1]}/${parts[2]}/${parts[3]}"
            }
            else -> return false
        }

        val headers = mapOf(
            "Referer" to playerReferer,
            "Origin" to playerOrigin,
            "User-Agent" to USER_AGENT
        )

        fetchSubtitles(subtitleApiUrl, headers, subtitleCallback)

        val raw = runCatching {
            app.get(punkflixUrl, referer = playerReferer, headers = headers).text.trim()
        }.getOrNull() ?: return false

        if (raw.isEmpty()) return false

        val foundStreams = linkedMapOf<String, String>()

        runCatching {
            if (raw.startsWith("{")) {
                collectMediaFromJson(JSONObject(raw), foundStreams, subtitleCallback)
            } else if (raw.startsWith("[")) {
                collectMediaFromJsonArray(JSONArray(raw), foundStreams, subtitleCallback)
            }
        }

        if (foundStreams.isEmpty()) {
            val unescaped = raw.replace("\\/", "/")
            Regex("""https?://[^\s"'<>\\]+\.(?:m3u8|mp4)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
                .findAll(unescaped)
                .forEachIndexed { idx, match ->
                    foundStreams.putIfAbsent(match.value, "Cinezo ${idx + 1}")
                }
        }

        if (foundStreams.isEmpty()) return false

        var emitted = false
        foundStreams.forEach { (streamUrl, label) ->
            if (streamUrl.contains(".m3u8", ignoreCase = true)) {
                val generated = runCatching {
                    M3u8Helper.generateM3u8(
                        source = label,
                        streamUrl = streamUrl,
                        referer = playerReferer,
                        headers = headers
                    )
                }.getOrDefault(emptyList())

                if (generated.isNotEmpty()) {
                    generated.forEach(callback)
                    emitted = true
                } else {
                    callback.invoke(
                        newExtractorLink(
                            source = label,
                            name = label,
                            url = streamUrl,
                            type = ExtractorLinkType.M3U8
                        ) {
                            this.referer = playerReferer
                            this.headers = headers
                            this.quality = Qualities.Unknown.value
                        }
                    )
                    emitted = true
                }
            } else {
                callback.invoke(
                    newExtractorLink(
                        source = label,
                        name = label,
                        url = streamUrl,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = playerReferer
                        this.headers = headers
                        this.quality = Qualities.Unknown.value
                    }
                )
                emitted = true
            }
        }

        return emitted
    }

    private suspend fun collectMediaFromJson(
        obj: JSONObject,
        streams: MutableMap<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val directUrl = obj.optString("url")
            .ifBlank { obj.optString("file") }
            .ifBlank { obj.optString("src") }
            .ifBlank { obj.optString("stream") }
            .ifBlank { obj.optString("hls") }
            .ifBlank { obj.optString("playlist") }

        if (directUrl.startsWith("http") &&
            (directUrl.contains(".m3u8", true) || directUrl.contains(".mp4", true) || directUrl.contains("punkflix.net", true))
        ) {
            val label = obj.optString("name")
                .ifBlank { obj.optString("server") }
                .ifBlank { obj.optString("quality") }
                .ifBlank { obj.optString("label") }
                .ifBlank { "Cinezo" }
            streams.putIfAbsent(directUrl, label)
        }

        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            val value = obj.opt(key)
            when (value) {
                is JSONObject -> collectMediaFromJson(value, streams, subtitleCallback)
                is JSONArray -> {
                    if (key.contains("sub", true) || key.contains("track", true) || key.contains("caption", true)) {
                        for (i in 0 until value.length()) {
                            val subObj = value.optJSONObject(i) ?: continue
                            val subUrl = subObj.optString("url")
                                .ifBlank { subObj.optString("file") }
                                .ifBlank { subObj.optString("src") }
                            if (subUrl.startsWith("http")) {
                                val lang = subObj.optString("display")
                                    .ifBlank { subObj.optString("label") }
                                    .ifBlank { subObj.optString("lang") }
                                    .ifBlank { "Subtitle" }
                                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
                            }
                        }
                    } else {
                        collectMediaFromJsonArray(value, streams, subtitleCallback)
                    }
                }
                is String -> {
                    if (value.startsWith("http") &&
                        (value.contains(".m3u8", true) || value.contains(".mp4", true))
                    ) {
                        streams.putIfAbsent(value, "Cinezo ($key)")
                    }
                }
            }
        }
    }

    private suspend fun collectMediaFromJsonArray(
        arr: JSONArray,
        streams: MutableMap<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        for (i in 0 until arr.length()) {
            when (val item = arr.opt(i)) {
                is JSONObject -> collectMediaFromJson(item, streams, subtitleCallback)
                is JSONArray -> collectMediaFromJsonArray(item, streams, subtitleCallback)
                is String -> {
                    if (item.startsWith("http") &&
                        (item.contains(".m3u8", true) || item.contains(".mp4", true))
                    ) {
                        streams.putIfAbsent(item, "Cinezo ${i + 1}")
                    }
                }
            }
        }
    }

    private suspend fun fetchSubtitles(
        apiUrl: String,
        headers: Map<String, String>,
        subtitleCallback: (SubtitleFile) -> Unit
    ) {
        val raw = runCatching {
            app.get(apiUrl, referer = playerReferer, headers = headers).text.trim()
        }.getOrNull() ?: return

        if (raw.isEmpty()) return

        runCatching {
            val array: JSONArray = if (raw.startsWith("[")) {
                JSONArray(raw)
            } else {
                val root = JSONObject(raw)
                root.optJSONArray("subtitles")
                    ?: root.optJSONArray("tracks")
                    ?: root.optJSONArray("data")
                    ?: root.optJSONArray("results")
                    ?: JSONArray()
            }

            for (i in 0 until array.length()) {
                val item = array.optJSONObject(i) ?: continue
                val subUrl = item.optString("url")
                    .ifBlank { item.optString("file") }
                    .ifBlank { item.optString("src") }
                    .ifBlank { item.optString("link") }
                if (subUrl.isBlank()) continue

                val lang = item.optString("display")
                    .ifBlank { item.optString("label") }
                    .ifBlank { item.optString("language") }
                    .ifBlank { item.optString("lang") }
                    .ifBlank { "Unknown" }

                subtitleCallback.invoke(newSubtitleFile(lang, subUrl))
            }
        }
    }
}

