package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.json.JSONObject
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class GokuHDProvider : MainAPI() {
    override var mainUrl = "https://gokuhd.nl"
    override var name = "GokuHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Latest",
        "$mainUrl/series/" to "Series",
        "$mainUrl/series/hindi-dubbed-series/" to "Hindi Dubbed Series",
        "$mainUrl/series/english-dubbed-subbed-series/" to "English Dubbed & Subbed",
        "$mainUrl/series/hindi-subbed-series/" to "Hindi Subbed Series",
        "$mainUrl/movies/" to "Movies",
        "$mainUrl/movies/hindi-dubbed-movies/" to "Hindi Dubbed Movies",
        "$mainUrl/movies/english-dubbed-subbed-movies/" to "English Dubbed & Subbed Movies",
        "$mainUrl/movies/hindi-subbed-movies/" to "Hindi Subbed Movies",
        "$mainUrl/ongoing-series/" to "Ongoing Series",
        "$mainUrl/trending/" to "Trending",
        // Languages
        "$mainUrl/language/hindi/" to "Language: Hindi",
        "$mainUrl/language/english/" to "Language: English",
        "$mainUrl/language/japanese/" to "Language: Japanese",
        // Genres
        "$mainUrl/genres/action/" to "Genre: Action",
        "$mainUrl/genres/adventure/" to "Genre: Adventure",
        "$mainUrl/genres/comedy/" to "Genre: Comedy",
        "$mainUrl/genres/dark-fantasy/" to "Genre: Dark Fantasy",
        "$mainUrl/genres/drama/" to "Genre: Drama",
        "$mainUrl/genres/ecchi/" to "Genre: Ecchi",
        "$mainUrl/genres/fantasy/" to "Genre: Fantasy",
        "$mainUrl/genres/harem/" to "Genre: Harem",
        "$mainUrl/genres/historical/" to "Genre: Historical",
        "$mainUrl/genres/horror/" to "Genre: Horror",
        "$mainUrl/genres/isekai/" to "Genre: Isekai",
        "$mainUrl/genres/magic/" to "Genre: Magic",
        "$mainUrl/genres/mystery/" to "Genre: Mystery",
        "$mainUrl/genres/psychological/" to "Genre: Psychological",
        "$mainUrl/genres/romance/" to "Genre: Romance",
        "$mainUrl/genres/school/" to "Genre: School",
        "$mainUrl/genres/sci-fi/" to "Genre: Sci-Fi"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            val trimmed = request.data.removeSuffix("/")
            "$trimmed/page/$page/"
        }

        val html = VegaNet.fetchWithCf(pageUrl, referer = mainUrl)?.text 
            ?: return newHomePageResponse(emptyList(), hasNext = false)
        val doc = Jsoup.parse(html)
        
        val home = doc.select("a:has(p.poster-title), article, div.post, div.item").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("p.poster-title, .poster-title, .entry-title a, h2.entry-title a, h3.entry-title a, h2 a, h3 a, .post-title a, a[title]")
            ?: (if (this.tagName() == "a") this else this.selectFirst("a[href]"))
            ?: return null

        val rawTitle = titleEl.attr("title").takeIf { it.isNotBlank() }
            ?: titleEl.text().trim()
            .ifBlank { this.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        val title = rawTitle.replace("Permalink to ", "", ignoreCase = true).trim()
        if (title.isBlank()) return null

        val hrefTag = if (this.tagName() == "a") this else (titleEl.takeIf { it.tagName() == "a" } ?: this.selectFirst("a[href]"))
        val href = fixUrl(hrefTag?.attr("href").orEmpty())
        if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrl(
            img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src").orEmpty()
        )

        val isDub = title.contains("Hindi", true) || title.contains("Dub", true)
        val isSub = title.contains("Sub", true)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, isSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")
        val searchUrl = "$mainUrl/search.php?q=$encodedQuery&page=1"

        val text = VegaNet.retry(attempts = 3, gapMs = 800L) {
            try {
                val res = VegaNet.fetchWithCf(searchUrl, referer = "$mainUrl/")
                if (res?.code == 200) res.text else null
            } catch (_: Exception) {
                null
            }
        } ?: return emptyList()

        val hits = try {
            JSONObject(text).optJSONArray("hits") ?: return emptyList()
        } catch (_: Exception) {
            return emptyList()
        }

        val out = mutableListOf<SearchResponse>()
        for (i in 0 until hits.length()) {
            val d = hits.optJSONObject(i)?.optJSONObject("document") ?: continue
            val title = d.optString("post_title")
            val permalink = d.optString("permalink")
            val poster = d.optString("post_thumbnail").takeIf { it.startsWith("http") }
                ?: d.optString("poster").takeIf { it.startsWith("http") }
                ?: ""

            if (title.isBlank() || permalink.isBlank()) continue

            val absUrl = fixUrl(permalink)
            val isDub = title.contains("Hindi", true) || title.contains("Dub", true)
            val isSub = title.contains("Sub", true)

            out.add(
                newAnimeSearchResponse(title, absUrl, TvType.Anime) {
                    this.posterUrl = fixUrl(poster)
                    this.addDubStatus(isDub, isSub)
                }
            )
        }
        return out.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val html = VegaNet.fetchWithCf(url, referer = mainUrl)?.text 
            ?: throw ErrorLoadingException("Failed to load page")
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst(".single-post-title")?.text() 
            ?: doc.selectFirst("h1.entry-title")?.text() 
            ?: doc.selectFirst("h2.entry-title")?.text() 
            ?: "Anime"
            
        val poster = fixUrl(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".single-entry-body img")?.attr("src") 
                ?: doc.selectFirst(".post-image img")?.attr("src") 
                ?: doc.selectFirst("div.post-thumbnail img")?.attr("src")
                ?: ""
        )
        
        val description = doc.selectFirst(".xp-plot-box")?.text() 
            ?: doc.selectFirst(".entry-content")?.text()
            ?: doc.selectFirst("div.entry-summary")?.text()
            ?: "No Plot Found"

        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        val episodeMap = LinkedHashMap<Int, MutableList<EpisodeLink>>()
        val allLinksForMovie = mutableListOf<EpisodeLink>()

        doc.select("a.wp-download-btn, a.xp-download-btn, a.btn-download, a.btn, .single-entry-body a, .entry-content a, div.btn-danger, button.btn").forEach { element ->
            val epLink = element.attr("href").takeIf { it.isNotBlank() } 
                ?: element.attr("onclick").let { onclick ->
                    Regex("""openInNewTab\(['"]([^'"]+)['"]\)""").find(onclick)?.groupValues?.get(1)
                } ?: ""

            val epName = element.text().trim()
            val parentText = element.parent()?.previousElementSibling()?.text().orEmpty()

            if (epLink.startsWith("http") && GokuHDSources.isServerLink(epLink)) {
                allLinksForMovie.add(EpisodeLink(epLink, null))

                val epNum = Regex("""(?i)ep(?:isode)?\.?\s*0*(\d{1,3})""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)ep(?:isode)?\.?\s*0*(\d{1,3})""").find(parentText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                episodeMap.getOrPut(epNum) { mutableListOf() }.add(EpisodeLink(epLink, epNum))
            }
        }

        return if (tvType == TvType.AnimeMovie) {
            val movieData = mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = if (episodeMap.size <= 1 && episodeMap[1]?.any { it.source.contains("nexdrive", true) } == true) {
                val nexLinks = episodeMap[1].orEmpty()
                val detectedEps = mutableSetOf<Int>()
                nexLinks.forEach { linkObj ->
                    GokuHDSources.fetchDrivePage(linkObj.source)?.episodes?.keys?.let { detectedEps.addAll(it) }
                }
                if (detectedEps.isNotEmpty()) {
                    detectedEps.sorted().map { epNum ->
                        val epLinks = nexLinks.map { EpisodeLink(it.source, epNum) }
                        newEpisode(mapper.writeValueAsString(epLinks)) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    }
                } else {
                    listOf(
                        newEpisode(mapper.writeValueAsString(nexLinks)) {
                            this.name = "Episode 1"
                            this.episode = 1
                        }
                    )
                }
            } else {
                episodeMap.map { (epNum, links) ->
                    val distinctLinks = links.distinctBy { it.source }
                    newEpisode(mapper.writeValueAsString(distinctLinks)) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    }
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val sources = tryParseJson<ArrayList<EpisodeLink>>(data)
            var found = false

            if (!sources.isNullOrEmpty()) {
                sources.amap { sourceObj ->
                    val servers = GokuHDSources.resolve(sourceObj.source, sourceObj.episode)
                    for (server in servers) {
                        found = true
                        callback.invoke(
                            newExtractorLink(
                                source = name,
                                name = server.name,
                                url = server.url,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = VegaNet.qualityOf(server.name)
                                this.headers = server.headers
                                this.referer = mainUrl
                            }
                        )
                    }
                }
                return found
            }

            if (data.startsWith("http")) {
                val servers = GokuHDSources.resolve(data, null)
                for (server in servers) {
                    found = true
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = server.name,
                            url = server.url,
                            type = ExtractorLinkType.VIDEO
                        ) {
                            this.quality = VegaNet.qualityOf(server.name)
                            this.headers = server.headers
                            this.referer = mainUrl
                        }
                    )
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    data class EpisodeLink(
        val source: String,
        val episode: Int? = null
    )
}
