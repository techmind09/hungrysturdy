package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
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
        val titleEl = this.selectFirst("p.poster-title") 
            ?: this.selectFirst(".entry-title a") 
            ?: this.selectFirst("h2.entry-title a") 
            ?: this.selectFirst("a[href]")
            ?: return null
            
        val title = titleEl.text().trim()
        if (title.isBlank()) return null
        
        val hrefTag = if (this.tagName() == "a") this else this.selectFirst("a[href]")
        val href = fixUrl(hrefTag?.attr("href") ?: titleEl.attr("href"))
        if (href.isBlank()) return null
        
        val posterUrl = fixUrl(
            this.selectFirst("img")?.attr("src") 
                ?: this.selectFirst("img")?.attr("data-src") 
                ?: ""
        )

        val isDub = title.contains("Hindi", true) || title.contains("Dub", true)
        val isSub = title.contains("Sub", true)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, isSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val searchUrl = "$mainUrl/?s=$encodedQuery"
        
        val html = VegaNet.fetchWithCf(searchUrl, referer = mainUrl)?.text ?: return emptyList()
        val doc = Jsoup.parse(html)
        
        return doc.select("a:has(p.poster-title), article.post, div.post, div.item, div.result-item")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
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

        val episodes = mutableListOf<Episode>()

        doc.select("a.wp-download-btn, a.xp-download-btn, a.btn-download, a.btn, .single-entry-body a, .entry-content a, div.btn-danger, button.btn").forEachIndexed { index, element ->
            val epLink = element.attr("href").takeIf { it.isNotBlank() } 
                ?: element.attr("onclick").let { onclick ->
                    Regex("""openInNewTab\(['"]([^'"]+)['"]\)""").find(onclick)?.groupValues?.get(1)
                } ?: ""

            val epName = element.text().trim()

            if (epLink.startsWith("http") && GokuHDSources.isServerLink(epLink)) {
                val epNum = Regex("""(?i)ep(?:isode)?\.?\s*0*(\d{1,3})""").find(epName)?.groupValues?.get(1)?.toIntOrNull() 
                    ?: (index + 1)

                val episodeDataList = listOf(EpisodeLink(epLink, epNum))
                val jsonEpisodeData = mapper.writeValueAsString(episodeDataList)

                if (episodes.none { it.data == jsonEpisodeData }) {
                    episodes.add(
                        newEpisode(jsonEpisodeData) {
                            this.name = if (epName.isNotBlank() && epName.length < 40) epName else "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.AnimeMovie) {
            val movieData = mapper.writeValueAsString(listOf(EpisodeLink(url, null)))
            newMovieLoadResponse(title, url, TvType.AnimeMovie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
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
        try {
            val sources = tryParseJson<ArrayList<EpisodeLink>>(data)
            if (!sources.isNullOrEmpty()) {
                var found = false
                sources.amap { sourceObj ->
                    val servers = GokuHDSources.resolve(sourceObj.source, sourceObj.episode)
                    for (server in servers) {
                        if (GokuHDSources.alive(server.url, server.headers)) {
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
                }
                return found
            }

            if (data.startsWith("http")) {
                val servers = GokuHDSources.resolve(data, null)
                var found = false
                for (server in servers) {
                    if (GokuHDSources.alive(server.url, server.headers)) {
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
            return false
        } catch (e: Exception) {
            return false
        }
    }

    data class EpisodeLink(
        val source: String,
        val episode: Int? = null
    )
}
