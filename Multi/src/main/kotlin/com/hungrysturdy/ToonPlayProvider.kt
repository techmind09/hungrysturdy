package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLDecoder
import java.net.URLEncoder

class ToonPlayProvider : MainAPI() {
    override var mainUrl = "https://toonplay.in"
    override var name = "ToonPlay"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Cartoon
    )

    private val infoApiBase = "https://animesalt.streamindia.co.in/api"
    private val extractApiBases = listOf(
        "https://anime.streamindia.co.in/api",
        "https://animesalt.streamindia.co.in/api"
    )

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "latest_releases" to "Latest Episodes & Releases",
        "global_latest_releases" to "Global Latest Releases",
        "popular_series" to "Most Popular TV Series",
        "popular_movies" to "Blockbuster Anime Movies",
        "hero" to "Featured Spotlight",
        "lang:hin" to "Language: Hindi",
        "lang:tel" to "Language: Telugu",
        "lang:tam" to "Language: Tamil",
        "lang:mal" to "Language: Malayalam",
        "lang:kan" to "Language: Kannada",
        "lang:ben" to "Language: Bengali",
        "lang:eng" to "Language: English",
        "lang:jpn" to "Language: Japanese"
    )

    private fun apiHeaders(): Map<String, String> = mapOf(
        "Accept" to "application/json",
        "Referer" to "$mainUrl/",
        "Origin" to mainUrl
    )

    private fun createSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    private fun parseApiAnimeItem(item: JSONObject): SearchResponse? {
        val title = item.optString("title").trim()
        val id = item.optString("id").trim()
        if (title.isBlank() || id.isBlank()) return null

        val typeStr = item.optString("type").lowercase()
        val isMovie = typeStr.contains("movie") || id.startsWith("movies-") || id.startsWith("movie-")

        val poster = item.optString("image").ifBlank {
            item.optString("poster_image").ifBlank {
                item.optString("image_url").ifBlank {
                    item.optString("backdrop")
                }
            }
        }.trim()

        val epCount = item.optInt("latest_episode", 0).takeIf { it > 0 }
            ?: Regex("""EP:(\d+)""").find(item.optString("episodes"))?.groupValues?.get(1)?.toIntOrNull()

        val itemUrl = "$mainUrl/watch/$id"

        return newAnimeSearchResponse(title, itemUrl, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = fixUrlNull(poster)
            this.addDubStatus(DubStatus.Dubbed, epCount)
            this.addDubStatus(DubStatus.Subbed, epCount)
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val sectionKey = request.data
        val results = mutableListOf<SearchResponse>()

        if (page == 1 && !sectionKey.startsWith("lang:")) {
            try {
                val res = app.get("$infoApiBase/home", headers = apiHeaders()).text
                val root = JSONObject(res)
                val dataObj = root.optJSONObject("data")
                val arr = dataObj?.optJSONArray(sectionKey)

                if (arr != null) {
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        parseApiAnimeItem(obj)?.let { results.add(it) }
                    }
                }
            } catch (_: Exception) {}
        }

        // Paginated catalog via AniList so scrolling never runs out of posters
        if (results.isEmpty() || page > 1 || sectionKey.startsWith("lang:")) {
            val catalogItems = fetchAniListFallback(sectionKey, page)
            results.addAll(catalogItems)
        }

        val unique = results.distinctBy { it.url }
        return newHomePageResponse(request.name, unique, hasNext = unique.isNotEmpty())
    }

    private suspend fun fetchAniListFallback(section: String, page: Int): List<SearchResponse> {
        val sort = when (section) {
            "trending", "hero" -> "TRENDING_DESC"
            "latest_releases", "global_latest_releases" -> "UPDATED_AT_DESC"
            else -> "POPULARITY_DESC"
        }
        val format = if (section == "popular_movies") "MOVIE" else "TV"

        val query = """
            query {
              Page(page: $page, perPage: 30) {
                media(type: ANIME, format: $format, sort: [$sort]) {
                  id
                  title { english romaji }
                  coverImage { extraLarge large }
                  format
                  episodes
                }
              }
            }
        """.trimIndent()

        return try {
            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                json = mapOf("query" to query)
            ).text

            val mediaArray = JSONObject(res)
                .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()

            val list = mutableListOf<SearchResponse>()
            for (i in 0 until mediaArray.length()) {
                val item = mediaArray.optJSONObject(i) ?: continue
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: titleObj?.optString("romaji").orEmpty()
                if (title.isBlank()) continue

                val poster = item.optJSONObject("coverImage")?.let {
                    it.optString("extraLarge").ifBlank { it.optString("large") }
                }.orEmpty()

                val isMovie = item.optString("format") == "MOVIE"
                val slug = createSlug(title)
                val prefix = if (isMovie) "movies" else "series"
                val epCount = item.optInt("episodes", 0).takeIf { it > 0 }

                list.add(
                    newAnimeSearchResponse(title, "$mainUrl/watch/$prefix-$slug", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                        this.posterUrl = poster
                        this.addDubStatus(DubStatus.Dubbed, epCount)
                        this.addDubStatus(DubStatus.Subbed, epCount)
                    }
                )
            }
            list
        } catch (_: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encodedQuery = URLEncoder.encode(cleanQuery, "UTF-8")
        val results = mutableListOf<SearchResponse>()

        // 1. Direct Backend Search API
        try {
            val res = app.get("$infoApiBase/search?q=$encodedQuery", headers = apiHeaders()).text
            val root = JSONObject(res)
            val arr = root.optJSONArray("results")
                ?: root.optJSONArray("data")
                ?: root.optJSONObject("data")?.optJSONArray("results")
                ?: root.optJSONObject("data")?.optJSONArray("anime")

            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val obj = arr.optJSONObject(i) ?: continue
                    parseApiAnimeItem(obj)?.let { results.add(it) }
                }
            }
        } catch (_: Exception) {}

        // 2. Fallback: filter /api/home + AniList search if backend search returns empty
        if (results.isEmpty()) {
            try {
                val homeRes = app.get("$infoApiBase/home", headers = apiHeaders()).text
                val dataObj = JSONObject(homeRes).optJSONObject("data")
                val sections = listOf("trending", "popular_series", "popular_movies", "latest_releases", "global_latest_releases")
                for (sec in sections) {
                    val arr = dataObj?.optJSONArray(sec) ?: continue
                    for (i in 0 until arr.length()) {
                        val obj = arr.optJSONObject(i) ?: continue
                        val t = obj.optString("title")
                        if (t.contains(cleanQuery, ignoreCase = true)) {
                            parseApiAnimeItem(obj)?.let { results.add(it) }
                        }
                    }
                }
            } catch (_: Exception) {}
        }

        if (results.isEmpty()) {
            try {
                val escaped = cleanQuery.replace("\\", "\\\\").replace("\"", "\\\"")
                val gql = """
                    query {
                      Page(page: 1, perPage: 25) {
                        media(search: "$escaped", type: ANIME, sort: [POPULARITY_DESC]) {
                          title { english romaji }
                          coverImage { extraLarge large }
                          format
                          episodes
                        }
                      }
                    }
                """.trimIndent()
                val res = app.post(
                    "https://graphql.anilist.co",
                    headers = mapOf("Content-Type" to "application/json"),
                    json = mapOf("query" to gql)
                ).text
                val mediaArray = JSONObject(res)
                    .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media")
                if (mediaArray != null) {
                    for (i in 0 until mediaArray.length()) {
                        val item = mediaArray.optJSONObject(i) ?: continue
                        val titleObj = item.optJSONObject("title")
                        val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
                            ?: titleObj?.optString("romaji").orEmpty()
                        if (title.isBlank()) continue
                        val poster = item.optJSONObject("coverImage")?.let {
                            it.optString("extraLarge").ifBlank { it.optString("large") }
                        }.orEmpty()
                        val isMovie = item.optString("format") == "MOVIE"
                        val prefix = if (isMovie) "movies" else "series"
                        val slug = createSlug(title)
                        val epCount = item.optInt("episodes", 0).takeIf { it > 0 }

                        results.add(
                            newAnimeSearchResponse(title, "$mainUrl/watch/$prefix-$slug", if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                                this.posterUrl = poster
                                this.addDubStatus(DubStatus.Dubbed, epCount)
                                this.addDubStatus(DubStatus.Subbed, epCount)
                            }
                        )
                    }
                }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val slugId = url.substringAfter("id=")
            .substringAfter("/watch/")
            .substringAfter("/info/")
            .substringBefore("?")
            .substringBefore("&")
            .trim('/')

        var title = slugId.removePrefix("series-").removePrefix("movies-").removePrefix("movie-")
            .replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        var poster = ""
        var backdrop = ""
        var plot = ""
        var year: Int? = null
        val tags = mutableListOf<String>()
        var isMovie = slugId.startsWith("movies-") || slugId.startsWith("movie-")
        val episodes = mutableListOf<Episode>()
        val recommendations = mutableListOf<SearchResponse>()

        try {
            val res = app.get("$infoApiBase/info?id=$slugId", headers = apiHeaders()).text
            val root = JSONObject(res)
            val anime = root.optJSONObject("anime") ?: root.optJSONObject("data")

            if (anime != null) {
                title = anime.optString("title").ifBlank { title }
                poster = anime.optString("image").ifBlank { poster }
                backdrop = anime.optString("backdrop").ifBlank { poster }
                plot = anime.optString("overview").ifBlank { plot }
                year = anime.optString("year").toIntOrNull()

                val typeStr = anime.optString("type").lowercase()
                if (typeStr.contains("movie")) isMovie = true

                val genresArr = anime.optJSONArray("genres")
                if (genresArr != null) {
                    for (i in 0 until genresArr.length()) {
                        val g = genresArr.optString(i).trim()
                        if (g.isNotBlank()) tags.add(g)
                    }
                }

                val recsArr = anime.optJSONArray("recommendations")
                if (recsArr != null) {
                    for (i in 0 until recsArr.length()) {
                        val rObj = recsArr.optJSONObject(i) ?: continue
                        parseApiAnimeItem(rObj)?.let { recommendations.add(it) }
                    }
                }

                val seasonsList = anime.optJSONArray("seasonsList")
                if (seasonsList != null && seasonsList.length() > 0) {
                    for (sIdx in 0 until seasonsList.length()) {
                        val sObj = seasonsList.optJSONObject(sIdx) ?: continue
                        val seasonNum = sObj.optString("season").toIntOrNull() ?: (sIdx + 1)
                        val epsArr = sObj.optJSONArray("episodes") ?: continue

                        for (eIdx in 0 until epsArr.length()) {
                            val epObj = epsArr.optJSONObject(eIdx) ?: continue
                            val epNum = epObj.optInt("number", eIdx + 1)
                            val epTitle = epObj.optString("title").ifBlank { "Episode $epNum" }
                            val epId = epObj.optString("id").trim('/')
                            val epThumb = epObj.optString("image").trim()

                            val episodeUrl = when {
                                epId.startsWith("http") -> epId
                                epId.isNotBlank() -> "https://animesalt.cx/$epId/"
                                else -> "https://animesalt.cx/episode/${slugId.removePrefix("series-")}-${seasonNum}x$epNum/"
                            }

                            val epData = EpisodeData(
                                episodeUrl = episodeUrl,
                                slugId = slugId,
                                season = seasonNum,
                                episode = epNum
                            ).toJson()

                            episodes.add(
                                newEpisode(epData) {
                                    this.name = epTitle
                                    this.season = seasonNum
                                    this.episode = epNum
                                    this.posterUrl = fixUrlNull(epThumb)
                                }
                            )
                        }
                    }
                }
            }
        } catch (_: Exception) {}

        val cleanSlug = slugId.removePrefix("series-").removePrefix("movies-").removePrefix("movie-")

        return if (isMovie) {
            val movieEpisodeUrl = if (episodes.isNotEmpty()) {
                tryParseJson<EpisodeData>(episodes.first().data)?.episodeUrl
                    ?: "https://animesalt.cx/movies/$cleanSlug/"
            } else {
                "https://animesalt.cx/movies/$cleanSlug/"
            }

            val movieData = EpisodeData(
                episodeUrl = movieEpisodeUrl,
                slugId = slugId,
                season = 1,
                episode = 1
            ).toJson()

            newMovieLoadResponse(title, "$mainUrl/watch/$slugId", TvType.AnimeMovie, movieData) {
                this.posterUrl = fixUrlNull(poster)
                this.backgroundPosterUrl = fixUrlNull(backdrop)
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        } else {
            if (episodes.isEmpty()) {
                val epData = EpisodeData(
                    episodeUrl = "https://animesalt.cx/episode/$cleanSlug-1x1/",
                    slugId = slugId,
                    season = 1,
                    episode = 1
                ).toJson()
                episodes.add(
                    newEpisode(epData) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, "$mainUrl/watch/$slugId", TvType.Anime, episodes) {
                this.posterUrl = fixUrlNull(poster)
                this.backgroundPosterUrl = fixUrlNull(backdrop)
                this.plot = plot
                this.year = year
                this.tags = tags
                this.recommendations = recommendations
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val epData = tryParseJson<EpisodeData>(data)
        val candidateEpisodeUrls = mutableListOf<String>()

        if (epData != null) {
            candidateEpisodeUrls.add(epData.episodeUrl)
            // Fallback movie/episode URL variations on animesalt.cx
            val cleanSlug = epData.slugId.removePrefix("series-").removePrefix("movies-").removePrefix("movie-")
            if (epData.episodeUrl.contains("/movies/")) {
                candidateEpisodeUrls.add("https://animesalt.cx/episode/$cleanSlug-1x1/")
            }
        } else if (data.isNotBlank()) {
            candidateEpisodeUrls.add(data)
        }

        val serverPlayers = mutableListOf<Pair<String, String>>() // (serverName, playerUrl)

        for (epUrl in candidateEpisodeUrls.distinct()) {
            val encodedUrl = URLEncoder.encode(epUrl, "UTF-8")
            for (apiBase in extractApiBases) {
                try {
                    val res = app.get("$apiBase/extract?url=$encodedUrl", headers = apiHeaders()).text
                    val root = JSONObject(res)
                    val dataObj = root.optJSONObject("data") ?: continue

                    val watchServers = dataObj.optJSONArray("watchServers")
                    if (watchServers != null) {
                        for (i in 0 until watchServers.length()) {
                            val srv = watchServers.optJSONObject(i) ?: continue
                            val sName = srv.optString("name", "Server ${i + 1}")
                            val sUrl = srv.optString("url").trim()
                            if (sUrl.isNotBlank()) {
                                serverPlayers.add(sName to sUrl)
                            }
                        }
                    }

                    val playerUrls = dataObj.optJSONArray("videoPlayerUrls")
                    if (playerUrls != null) {
                        for (i in 0 until playerUrls.length()) {
                            val pUrl = playerUrls.optString(i).trim()
                            if (pUrl.isNotBlank()) {
                                serverPlayers.add("Server ${i + 1}" to pUrl)
                            }
                        }
                    }

                    val singleUrl = dataObj.optString("videoPlayerUrl").trim()
                    if (singleUrl.isNotBlank()) {
                        serverPlayers.add("Primary" to singleUrl)
                    }
                } catch (_: Exception) {}
            }
            if (serverPlayers.isNotEmpty()) break
        }

        val distinctPlayers = serverPlayers.distinctBy { it.second }
        if (distinctPlayers.isEmpty()) return false

        var foundLinks = false

        for ((serverName, playerUrl) in distinctPlayers) {
            if (playerUrl.contains("data=")) {
                // Multi-audio Base64 payload (Hindi, Tamil, Telugu, English, Japanese)
                try {
                    var decodedUrl = URLDecoder.decode(playerUrl, "UTF-8")
                    if (decodedUrl.contains("%3D") || decodedUrl.contains("%2F")) {
                        decodedUrl = URLDecoder.decode(decodedUrl, "UTF-8")
                    }
                    val rawBase64 = decodedUrl.substringAfter("data=").substringBefore("&").replace(" ", "+")
                    val jsonStr = String(Base64.decode(rawBase64, Base64.DEFAULT))
                    val jsonArray = JSONArray(jsonStr)

                    for (i in 0 until jsonArray.length()) {
                        val obj = jsonArray.optJSONObject(i) ?: continue
                        val langName = obj.optString("language", "Multi")
                        val innerLink = obj.optString("link").replace("\\/", "/").trim()
                        if (innerLink.isNotBlank()) {
                            val collected = mutableListOf<ExtractorLink>()
                            if (innerLink.contains("flixcloud", true)) {
                                FlixCloudExtractor().getUrl(innerLink, "https://animesalt.streamindia.co.in/", subtitleCallback) { link ->
                                    collected.add(link)
                                }
                            } else {
                                AbyssPlayerExtractor().getUrl(innerLink, "https://animesalt.streamindia.co.in/", subtitleCallback) { link ->
                                    collected.add(link)
                                }
                            }
                            for (link in collected) {
                                foundLinks = true
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "$name ($langName - $serverName)",
                                        url = link.url,
                                        type = link.type
                                    ) {
                                        this.referer = link.referer
                                        this.quality = link.quality
                                        this.headers = link.headers
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            } else {
                val collected = mutableListOf<ExtractorLink>()
                when {
                    playerUrl.contains("ravok", true) -> {
                        RavokExtractor().getUrl(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                            collected.add(link)
                        }
                    }
                    playerUrl.contains("flixcloud", true) -> {
                        FlixCloudExtractor().getUrl(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                            collected.add(link)
                        }
                    }
                    playerUrl.contains("abyss", true) -> {
                        AbyssPlayerExtractor().getUrl(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                            collected.add(link)
                        }
                    }
                    else -> {
                        loadExtractor(playerUrl, "$mainUrl/", subtitleCallback) { link ->
                            collected.add(link)
                        }
                    }
                }

                for (link in collected) {
                    foundLinks = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - ${link.name} ($serverName)",
                            url = link.url,
                            type = link.type
                        ) {
                            this.referer = link.referer
                            this.quality = link.quality
                            this.headers = link.headers
                        }
                    )
                }
            }
        }

        return foundLinks
    }

    data class EpisodeData(
        val episodeUrl: String,
        val slugId: String = "",
        val season: Int = 1,
        val episode: Int = 1
    )
}
