package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.WebViewResolver
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import java.net.URLDecoder

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

    override val mainPage = mainPageOf(
        "trending" to "Trending Now",
        "anime" to "Anime",
        "latest" to "Latest Episodes & Releases",
        "series" to "Most Popular TV Series",
        "movies" to "Blockbuster Anime Movies",
        "cartoons" to "Cartoons",
        "lang:hin" to "Language: Hindi",
        "lang:tel" to "Language: Telugu",
        "lang:tam" to "Language: Tamil",
        "lang:mal" to "Language: Malayalam",
        "lang:kan" to "Language: Kannada",
        "lang:ben" to "Language: Bengali",
        "lang:eng" to "Language: English",
        "lang:jpn" to "Language: Japanese",
        "lang:kor" to "Language: Korean"
    )

    private fun createSlug(title: String): String {
        return title.lowercase()
            .replace(Regex("[^a-z0-9\\s-]"), "")
            .trim()
            .replace(Regex("\\s+"), "-")
    }

    private suspend fun fetchRenderedDom(url: String): Document {
        return try {
            val response = app.get(
                url,
                interceptor = WebViewResolver(
                    interceptUrl = Regex("""(api|graphql|supabase|firebase|json|watch|assets)"""),
                    additionalUrls = listOf(Regex(""".*""")),
                    script = """
                        new Promise((resolve) => {
                            let attempts = 0;
                            const check = setInterval(() => {
                                attempts++;
                                if (document.querySelector('#trending, #watchpage-active-iframe, h3[title]') || attempts > 15) {
                                    clearInterval(check);
                                    resolve();
                                }
                            }, 400);
                        });
                    """.trimIndent(),
                    timeout = 8000L
                )
            )
            Jsoup.parse(response.text)
        } catch (e: Exception) {
            app.get(url).document
        }
    }

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        if (page > 1) return newHomePageResponse(request.name, emptyList(), hasNext = false)

        val sectionId = request.data
        val results = mutableListOf<SearchResponse>()

        if (!sectionId.startsWith("lang:")) {
            val doc = fetchRenderedDom(mainUrl)
            val sectionSelector = when (sectionId) {
                "trending" -> "section#trending .group"
                "latest" -> "section#latest .group"
                "series" -> "section#series .group"
                "movies" -> "section#movies .group"
                else -> "section .group"
            }

            for (card in doc.select(sectionSelector)) {
                card.toToonPlayCard()?.let { results.add(it) }
            }
        }

        if (results.isEmpty()) {
            val anilistItems = fetchCatalogForToonPlay(sectionId, page)
            results.addAll(anilistItems)
        }

        return newHomePageResponse(request.name, results.distinctBy { it.url }, hasNext = false)
    }

    private fun Element.toToonPlayCard(): SearchResponse? {
        val titleEl = this.selectFirst("h3[title], h3") ?: return null
        val title = titleEl.attr("title").ifBlank { titleEl.text() }.trim()
        if (title.isBlank()) return null

        val img = this.select("img").firstOrNull {
            val src = it.attr("src")
            src.isNotBlank() && !src.contains("placeholder.webp")
        }
        val poster = img?.attr("src")?.trim() ?: ""

        val badgeText = this.selectFirst("span:contains(MOVIE), span:contains(SERIES), span:contains(ONA), span:contains(TV_SHORT)")?.text().orEmpty()
        val isMovie = badgeText.contains("MOVIE", true) || title.contains("Movie", true)

        val slug = createSlug(title)
        val prefix = if (isMovie) "movie" else "series"

        val anilistId = Regex("""/bx?(\d+)-""").find(poster)?.groupValues?.get(1) ?: ""
        val watchUrl = if (anilistId.isNotBlank()) {
            "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-1&lang=hin&anilistid=$anilistId"
        } else {
            "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-1&lang=hin"
        }

        val epSubText = this.selectFirst("div.font-mono.text-indigo-300")?.text().orEmpty()
        val epCount = Regex("""EP:(\d+)""").find(epSubText)?.groupValues?.get(1)?.toIntOrNull()

        return newAnimeSearchResponse(title, watchUrl, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
            this.posterUrl = fixUrl(poster)
            this.addDubStatus(DubStatus.Dubbed, epCount)
            this.addDubStatus(DubStatus.Subbed, epCount)
        }
    }

    private suspend fun fetchCatalogForToonPlay(section: String, page: Int): List<SearchResponse> {
        val sort = when (section) {
            "trending" -> "TRENDING_DESC"
            "latest" -> "UPDATED_AT_DESC"
            "movies" -> "POPULARITY_DESC"
            else -> "POPULARITY_DESC"
        }
        val format = if (section == "movies") "MOVIE" else "TV"

        val query = """
            query {
              Page(page: $page, perPage: 18) {
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

            val root = org.json.JSONObject(res)
            val mediaArray = root.optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()
            val list = mutableListOf<SearchResponse>()

            for (i in 0 until mediaArray.length()) {
                val item = mediaArray.optJSONObject(i) ?: continue
                val id = item.optInt("id")
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: titleObj?.optString("romaji").orEmpty()
                if (title.isBlank()) continue

                val poster = item.optJSONObject("coverImage")?.let {
                    it.optString("extraLarge").ifBlank { it.optString("large") }
                }.orEmpty()

                val isMovie = item.optString("format") == "MOVIE"
                val slug = createSlug(title)
                val prefix = if (isMovie) "movie" else "series"
                val langCode = if (section.startsWith("lang:")) section.substringAfter("lang:") else "hin"

                val url = "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-1&lang=$langCode&anilistid=$id"

                list.add(
                    newAnimeSearchResponse(title, url, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                        this.posterUrl = poster
                        this.addDubStatus(true, true)
                    }
                )
            }
            list
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim().replace("\\", "\\\\").replace("\"", "\\\"")
        if (cleanQuery.isBlank()) return emptyList()

        val gql = """
            query {
              Page(page: 1, perPage: 20) {
                media(search: "$cleanQuery", type: ANIME, sort: [POPULARITY_DESC]) {
                  id
                  title { english romaji }
                  coverImage { extraLarge large }
                  format
                }
              }
            }
        """.trimIndent()

        return try {
            val res = app.post(
                "https://graphql.anilist.co",
                headers = mapOf("Content-Type" to "application/json"),
                json = mapOf("query" to gql)
            ).text

            val mediaArray = org.json.JSONObject(res)
                .optJSONObject("data")?.optJSONObject("Page")?.optJSONArray("media") ?: return emptyList()

            val results = mutableListOf<SearchResponse>()
            for (i in 0 until mediaArray.length()) {
                val item = mediaArray.optJSONObject(i) ?: continue
                val id = item.optInt("id")
                val titleObj = item.optJSONObject("title")
                val title = titleObj?.optString("english")?.takeIf { it.isNotBlank() && it != "null" }
                    ?: titleObj?.optString("romaji").orEmpty()
                if (title.isBlank()) continue

                val poster = item.optJSONObject("coverImage")?.let {
                    it.optString("extraLarge").ifBlank { it.optString("large") }
                }.orEmpty()

                val isMovie = item.optString("format") == "MOVIE"
                val slug = createSlug(title)
                val prefix = if (isMovie) "movie" else "series"
                val watchUrl = "$mainUrl/watch/$prefix-$slug?season=1&ep=1&server=options-1&lang=hin&anilistid=$id"

                results.add(
                    newAnimeSearchResponse(title, watchUrl, if (isMovie) TvType.AnimeMovie else TvType.Anime) {
                        this.posterUrl = poster
                        this.addDubStatus(true, true)
                    }
                )
            }
            results
        } catch (e: Exception) {
            emptyList()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = fetchRenderedDom(url)

        val fallbackSlugTitle = url.substringAfter("/watch/")
            .substringBefore("?")
            .removePrefix("series-")
            .removePrefix("movie-")
            .replace("-", " ")
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }

        var title = doc.selectFirst("h1.text-xl, h1")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ToonPlay")?.trim()?.takeIf { it.isNotBlank() }
            ?: fallbackSlugTitle

        var poster = doc.selectFirst("div.aspect-\\[3\\/4\\] img")?.attr("src")?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: ""

        var plot = doc.selectFirst("p.line-clamp-3, p.text-slate-300")?.text()?.trim()?.takeIf { it.isNotBlank() }
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")?.takeIf { it.isNotBlank() }
            ?: "Watch $title online in HD with multi-audio on ToonPlay."

        val tags = doc.select("div.pt-1.flex.flex-wrap span").map { it.text().trim() }.filter { it.isNotBlank() }

        val anilistId = Regex("""anilistid=(\d+)""").find(url)?.groupValues?.get(1)?.toIntOrNull()
        var totalEpCount = 24

        if (anilistId != null) {
            try {
                val gql = """query { Media(id: $anilistId) { title { english romaji } description coverImage { extraLarge } episodes } }"""
                val res = app.post("https://graphql.anilist.co", json = mapOf("query" to gql)).text
                val media = org.json.JSONObject(res).optJSONObject("data")?.optJSONObject("Media")
                if (media != null) {
                    if (title.equals("ToonPlay", true) || title.isBlank()) {
                        val engTitle = media.optJSONObject("title")?.optString("english").orEmpty()
                        val romajiTitle = media.optJSONObject("title")?.optString("romaji").orEmpty()
                        title = engTitle.takeIf { it.isNotBlank() && it != "null" } ?: romajiTitle.ifBlank { title }
                    }
                    if (poster.isBlank() || poster.contains("og-image.jpg")) {
                        poster = media.optJSONObject("coverImage")?.optString("extraLarge").orEmpty()
                    }
                    val desc = media.optString("description")
                    if (desc.isNotBlank() && desc != "null") {
                        plot = Jsoup.parse(desc).text()
                    }
                    val eps = media.optInt("episodes", 0)
                    if (eps > 0) totalEpCount = eps
                }
            } catch (_: Exception) {}
        }

        val isMovie = url.contains("/watch/movie-")
        val baseWatchUrl = url.substringBefore("?")
        val anilistParam = if (anilistId != null) "&anilistid=$anilistId" else ""

        val domEpButtons = doc.select("aside button[title]:has(span:contains(EP))")
        val episodes = mutableListOf<Episode>()

        if (domEpButtons.isNotEmpty()) {
            for (btn in domEpButtons) {
                val epTitle = btn.attr("title").trim()
                val epNum = btn.selectFirst("span.tabular-nums")?.text()?.trim()?.toIntOrNull() ?: (episodes.size + 1)
                val epData = EpisodeData(
                    watchUrl = "$baseWatchUrl?season=1&ep=$epNum&server=options-1&lang=hin$anilistParam",
                    season = 1,
                    episode = epNum,
                    anilistId = anilistId
                ).toJson()

                episodes.add(
                    newEpisode(epData) {
                        this.name = epTitle.ifBlank { "Episode $epNum" }
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        } else if (!isMovie) {
            for (epNum in 1..totalEpCount) {
                val epData = EpisodeData(
                    watchUrl = "$baseWatchUrl?season=1&ep=$epNum&server=options-1&lang=hin$anilistParam",
                    season = 1,
                    episode = epNum,
                    anilistId = anilistId
                ).toJson()

                episodes.add(
                    newEpisode(epData) {
                        this.name = "Episode $epNum"
                        this.season = 1
                        this.episode = epNum
                    }
                )
            }
        }

        return if (isMovie) {
            val movieData = EpisodeData(
                watchUrl = url,
                season = 1,
                episode = 1,
                anilistId = anilistId
            ).toJson()

            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = fixUrl(poster)
                this.plot = plot
                this.tags = tags
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
        val targetUrl = epData?.watchUrl ?: data
        var foundLinks = false

        val serverUrls = listOf("options-1", "options-0", "options-2").map { serverOpt ->
            if (targetUrl.contains("server=")) {
                targetUrl.replace(Regex("server=[^&]+"), "server=$serverOpt")
            } else {
                "$targetUrl${if (targetUrl.contains("?")) "&" else "?"}server=$serverOpt"
            }
        }.distinct()

        for (sUrl in serverUrls) {
            val doc = fetchRenderedDom(sUrl)
            val iframeSrc = doc.selectFirst("iframe#watchpage-active-iframe, iframe")?.attr("src")?.trim().orEmpty()

            if (iframeSrc.isNotBlank()) {
                if (iframeSrc.contains("data=")) {
                    val decodedUrl = URLDecoder.decode(iframeSrc, "UTF-8")
                    val rawBase64 = decodedUrl.substringAfter("data=").substringBefore("&")
                        .replace(" ", "+")
                    try {
                        val jsonStr = String(Base64.decode(rawBase64, Base64.DEFAULT))
                        val jsonArray = org.json.JSONArray(jsonStr)
                        for (i in 0 until jsonArray.length()) {
                            val obj = jsonArray.optJSONObject(i) ?: continue
                            val langName = obj.optString("language", "Multi")
                            val playerLink = obj.optString("link").replace("\\/", "/").trim()
                            if (playerLink.isNotBlank()) {
                                val success = loadExtractor(playerLink, "$mainUrl/", subtitleCallback) { link ->
                                    callback.invoke(
                                        newExtractorLink(
                                            source = name,
                                            name = "${link.name} - $langName",
                                            url = link.url,
                                            type = link.type
                                        ) {
                                            this.referer = link.referer
                                            this.quality = link.quality
                                            this.headers = link.headers
                                        }
                                    )
                                }
                                if (success) foundLinks = true
                            }
                        }
                    } catch (_: Exception) {}
                }

                val success = loadExtractor(fixUrl(iframeSrc), "$mainUrl/", subtitleCallback, callback)
                if (success) foundLinks = true
            }
        }

        return foundLinks
    }

    data class EpisodeData(
        val watchUrl: String,
        val season: Int = 1,
        val episode: Int = 1,
        val anilistId: Int? = null
    )
}
