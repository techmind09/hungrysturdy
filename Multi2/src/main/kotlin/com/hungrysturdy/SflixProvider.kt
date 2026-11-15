package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.LoadResponse.Companion.addActors
import com.lagradost.cloudstream3.LoadResponse.Companion.addTrailer
import com.lagradost.cloudstream3.utils.*
import org.json.JSONArray
import org.json.JSONObject
import org.jsoup.nodes.Element
import java.net.URLEncoder

class SflixProvider : MainAPI() {
    override var mainUrl = "https://sflix.film"
    private val playerUrl = "https://mzfi.me"
    override var name = "Sflix"
    override var hasMainPage = true
    override var supportedTypes = setOf(TvType.Movie, TvType.TvSeries, TvType.Anime)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Featured",
        "$mainUrl/ranking-list?id=1232643093049001320" to "Popular Series",
        "$mainUrl/ranking-list?id=997144265920760504" to "Popular Movie",
        "$mainUrl/search-result?keyword=Trending" to "Trending Now",
        "$mainUrl/search-result?keyword=Cinema" to "Cinema",
        "$mainUrl/upcoming" to "Coming Soon",
        "$mainUrl/ranking-list?id=3130672938110833528" to "Adult Animation",
        "$mainUrl/ranking-list?id=7219449993227633120" to "Epic Fantasy",
        "$mainUrl/ranking-list?id=62133389738001440" to "Anime [English Dubbed]",
        "$mainUrl/ranking-list?id=4380734070238626200" to "K-Drama",
        "$mainUrl/ranking-list?id=173752404280836544" to "C-Drama",
        "$mainUrl/ranking-list?id=7269188813871016696" to "Action Movies",
        "$mainUrl/ranking-list?id=8785384881686725944" to "Comedy",
        "$mainUrl/search-result?keyword=Horror" to "Horror Movies",
        "$mainUrl/ranking-list?id=1746633591129342248" to "Teen Romance",
        "$mainUrl/ranking-list?id=7317586330186645624" to "Superhero Series",
        "$mainUrl/search-result?keyword=Hot" to "Hot stort TV",
        "$mainUrl/search-result?keyword=Short" to "Trending Short Drama",
        "$mainUrl/ranking-list?id=8892055635953697752" to "Bl Story",
        "$mainUrl/ranking-list?id=3741804521853438336" to "Bet+",
        "$mainUrl/ranking-list?id=6978603205429526968" to "Action&Thriller",
        "$mainUrl/ranking-list?id=5475136475249497544" to "Gangster",
        "$mainUrl/ranking-list?id=6934224112055632896" to "Teen Fantasy",
        "$mainUrl/search-result?keyword=Romance" to "Romance",
        "$mainUrl/search-result?keyword=Suggestions" to "Suggestions"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val targetUrl = if (page <= 1) {
            request.data
        } else {
            val separator = if (request.data.contains("?")) "&" else "?"
            "${request.data}${separator}page=$page"
        }

        val document = app.get(targetUrl, referer = "$mainUrl/").document

        if (request.data.trimEnd('/') == mainUrl) {
            val lists = document.select("section.comp-box").mapNotNull { section ->
                val sectionTitle = section.selectFirst("h2.action-title")?.text()?.trim()
                    ?: return@mapNotNull null
                val items = section.select("a.video-item").mapNotNull { it.toSearchResult() }
                if (items.isEmpty()) null else HomePageList(sectionTitle, items)
            }
            if (lists.isNotEmpty()) {
                return newHomePageResponse(lists, hasNext = false)
            }
        }

        val items = document.select("a.video-item").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(
            listOf(HomePageList(request.name, items)),
            hasNext = items.isNotEmpty()
        )
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3.text, .video-name h3, .video-name")?.text()?.trim()
            ?: return null
        val rawHref = this.attr("href").ifEmpty { this.selectFirst("a")?.attr("href") ?: "" }
        if (rawHref.isEmpty()) return null
        val href = fixUrl(rawHref)

        val img = this.selectFirst("img")
        val posterUrl = img?.let {
            fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") })
        }

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search-result?keyword=$encoded"
        val document = app.get(searchUrl, referer = "$mainUrl/").document
        return document.select("a.video-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, referer = "$mainUrl/").document

        val title = document.selectFirst("h1.content-title")?.text()?.trim()
            ?: document.selectFirst("h2.title")?.text()?.trim()
            ?: return null

        val poster = document.selectFirst("div.info-image img")?.let {
            fixUrlNull(it.attr("data-src").ifEmpty { it.attr("src") })
        }
        val description = document.selectFirst("p.content-desc")?.text()?.trim()

        val ratingStr = document.selectFirst("div.rate-num")?.text()?.let {
            Regex("""(\d+(\.\d+)?)""").find(it)?.groupValues?.get(1)
        }

        val labelTexts = document.select("div.content-label-desc > div")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() }

        var year = labelTexts.firstOrNull { it.matches(Regex("""\d{4}""")) }?.toIntOrNull()
        var genres = labelTexts.dropWhile { !it.matches(Regex("""\d{4}""")) }
            .drop(1)
            .filter { it.length > 2 && it != "United States" && it != "United Kingdom" }

        val actors = document.select("section.top-cast-warp a.star-item").mapNotNull { star ->
            star.selectFirst(".star-name")?.text()?.trim()
        }

        val recommendations = document.select("section.more-like-warp a.video-item")
            .mapNotNull { it.toSearchResult() }

        var directContentUrl: String? = null
        document.select("script[type=application/ld+json]").forEach { script ->
            runCatching {
                val json = JSONObject(script.data())
                if (json.optString("@type") == "VideoObject") {
                    directContentUrl = json.optString("contentUrl").takeIf { it.startsWith("http") }
                } else if (json.has("mainEntity")) {
                    val arr = json.optJSONArray("mainEntity")
                    if (arr != null) {
                        for (i in 0 until arr.length()) {
                            val item = arr.optJSONObject(i)
                            if (item?.optString("@type") == "VideoObject") {
                                directContentUrl = item.optString("contentUrl").takeIf { it.startsWith("http") }
                            }
                        }
                    }
                }
            }
        }

        var subjectId = Regex("""[?&]id=(\d+)""").find(url)?.groupValues?.get(1) ?: ""
        var detailPath = url.substringAfter("/detail/").substringBefore("?")
        var subjectType = 1
        var trailerUrl: String? = null
        val episodes = mutableListOf<Episode>()

        val nuxtRaw = document.selectFirst("script#__NUXT_DATA__")?.data()
        if (!nuxtRaw.isNullOrEmpty()) {
            runCatching {
                val nuxtArr = JSONArray(nuxtRaw)
                var rootObj = nuxtArr.optJSONObject(3)
                for (i in 0 until nuxtArr.length()) {
                    val obj = nuxtArr.optJSONObject(i) ?: continue
                    if (obj.has("subject") && obj.has("resource")) {
                        rootObj = obj
                        break
                    }
                }

                val subjectObj = rootObj?.optInt("subject", -1)?.let { nuxtArr.optJSONObject(it) }
                if (subjectObj != null) {
                    subjectObj.optInt("subjectId", -1).takeIf { it >= 0 }?.let {
                        subjectId = nuxtArr.optString(it, subjectId)
                    }
                    subjectObj.optInt("subjectType", -1).takeIf { it >= 0 }?.let {
                        subjectType = nuxtArr.optInt(it, 1)
                    }
                    subjectObj.optInt("detailPath", -1).takeIf { it >= 0 }?.let {
                        detailPath = nuxtArr.optString(it, detailPath)
                    }
                    subjectObj.optInt("genre", -1).takeIf { it >= 0 }?.let { idx ->
                        val g = nuxtArr.optString(idx)
                        if (g.isNotEmpty()) genres = g.split(",").map { it.trim() }
                    }
                    subjectObj.optInt("releaseDate", -1).takeIf { it >= 0 }?.let { idx ->
                        val rd = nuxtArr.optString(idx)
                        if (rd.length >= 4) year = rd.take(4).toIntOrNull() ?: year
                    }

                    val trailerObj = subjectObj.optInt("trailer", -1).takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    val videoAddrObj = trailerObj?.optInt("videoAddress", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optJSONObject(it) }
                    trailerUrl = videoAddrObj?.optInt("url", -1)?.takeIf { it >= 0 }
                        ?.let { nuxtArr.optString(it) }
                        ?.takeIf { it.startsWith("http") }
                }

                val resourceObj = rootObj?.optInt("resource", -1)?.let { nuxtArr.optJSONObject(it) }
                val seasonsArr = resourceObj?.optInt("seasons", -1)?.takeIf { it >= 0 }
                    ?.let { nuxtArr.optJSONArray(it) }

                if (seasonsArr != null) {
                    for (s in 0 until seasonsArr.length()) {
                        val seObj = nuxtArr.optJSONObject(seasonsArr.optInt(s, -1)) ?: continue
                        val seasonNum = nuxtArr.optInt(seObj.optInt("se", -1), s + 1)
                        val maxEp = nuxtArr.optInt(seObj.optInt("maxEp", -1), 1)
                        for (ep in 1..maxEp) {
                            val playData = if (directContentUrl != null && maxEp == 1 && s == 0) {
                                directContentUrl!!
                            } else {
                                buildPlayUrl(detailPath, subjectId, seasonNum, ep, trailerUrl, directContentUrl)
                            }
                            episodes.add(
                                newEpisode(playData) {
                                    this.name = "Episode $ep"
                                    this.season = seasonNum
                                    this.episode = ep
                                }
                            )
                        }
                    }
                }
            }
        }

        val isSeries = subjectType == 2 || episodes.size > 1

        return if (isSeries) {
            if (episodes.isEmpty()) {
                val playData = directContentUrl ?: buildPlayUrl(detailPath, subjectId, 1, 1, trailerUrl, directContentUrl)
                episodes.add(
                    newEpisode(playData) {
                        this.name = "Episode 1"
                        this.season = 1
                        this.episode = 1
                    }
                )
            }
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        } else {
            val moviePlayData = directContentUrl ?: buildPlayUrl(detailPath, subjectId, 1, 1, trailerUrl, directContentUrl)
            newMovieLoadResponse(title, url, TvType.Movie, moviePlayData) {
                this.posterUrl = poster
                this.year = year
                this.plot = description
                this.tags = genres
                this.score = Score.from10(ratingStr)
                this.recommendations = recommendations
                addActors(actors)
                addTrailer(trailerUrl)
            }
        }
    }

    private fun buildPlayUrl(
        detailPath: String,
        subjectId: String,
        season: Int,
        episode: Int,
        trailerUrl: String?,
        directUrl: String?
    ): String {
        if (!directUrl.isNullOrEmpty()) return directUrl
        val base = "$playerUrl/spa/videoPlayPage/movies/$detailPath?id=$subjectId&se=$season&ep=$episode&detailPath=$detailPath"
        return if (!trailerUrl.isNullOrEmpty()) {
            "$base&fallbackTrailer=${URLEncoder.encode(trailerUrl, "UTF-8")}"
        } else {
            base
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http") && (data.contains(".mp4", true) || data.contains(".m3u8", true) || data.contains(".mpd", true))) {
            val streamType = when {
                data.contains(".mpd", true) -> ExtractorLinkType.DASH
                data.contains(".m3u8", true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = name,
                    url = data,
                    type = streamType
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val fallbackTrailer = Regex("""[?&]fallbackTrailer=([^&]+)""")
            .find(data)?.groupValues?.get(1)
            ?.let { java.net.URLDecoder.decode(it, "UTF-8") }

        val cleanPlayUrl = data.substringBefore("&fallbackTrailer=")
        val foundStreams = mutableSetOf<String>()
        val foundSubs = mutableSetOf<String>()

        runCatching {
            val intercepted = app.get(
                url = cleanPlayUrl,
                referer = "$mainUrl/",
                interceptor = WebViewResolver(
                    interceptUrl = Regex(
                        """https?://.*\.(mpd|m3u8|mp4)(\?.*)?$""",
                        RegexOption.IGNORE_CASE
                    ),
                    additionalUrls = listOf(
                        Regex("""https?://.*\.(vtt|srt)(\?.*)?$""", RegexOption.IGNORE_CASE)
                    ),
                    timeout = 15_000L
                )
            )
            val resolvedUrl = intercepted.url
            if (resolvedUrl.startsWith("http") && resolvedUrl != cleanPlayUrl) {
                foundStreams.add(resolvedUrl)
            }
        }

        if (foundStreams.isEmpty() && !fallbackTrailer.isNullOrEmpty()) {
            foundStreams.add(fallbackTrailer)
        }

        foundStreams.forEach { streamUrl ->
            val streamType = when {
                streamUrl.contains(".mpd", ignoreCase = true) -> ExtractorLinkType.DASH
                streamUrl.contains(".m3u8", ignoreCase = true) -> ExtractorLinkType.M3U8
                else -> ExtractorLinkType.VIDEO
            }
            val label = if (streamUrl == fallbackTrailer) "$name (Trailer)" else name

            callback.invoke(
                newExtractorLink(
                    source = name,
                    name = label,
                    url = streamUrl,
                    type = streamType
                ) {
                    this.referer = "$playerUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
        }

        foundSubs.forEach { subUrl ->
            subtitleCallback.invoke(
                newSubtitleFile(
                    lang = "English",
                    url = subUrl
                )
            )
        }

        return foundStreams.isNotEmpty()
    }
}
