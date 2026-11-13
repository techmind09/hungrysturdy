package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class KDHindiDubbed : MainAPI() {
    override var mainUrl = "https://dramacool.website"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/arabic-subtitle/page/" to "Arabic Subtitle",
        "$mainUrl/category/business/page/" to "Business",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/chinese-language/page/" to "Chinese Language",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/english-dubbed/page/" to "English Dubbed",
        "$mainUrl/category/family/page/" to "Family",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/filipino-language/page/" to "Filipino Language",
        "$mainUrl/category/food/page/" to "Food",
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/historical/page/" to "Historical",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/japanese-drama/page/" to "Japanese Drama",
        "$mainUrl/category/japanese-language/page/" to "Japanese Language",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/korean-language/page/" to "Korean Language",
        "$mainUrl/category/law/page/" to "Law",
        "$mainUrl/category/life/page/" to "Life",
        "$mainUrl/category/medical/page/" to "Medical",
        "$mainUrl/category/military/page/" to "Military",
        "$mainUrl/category/movie/page/" to "Movie",
        "$mainUrl/category/music/page/" to "Music",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/philippines-drama/page/" to "Philippines Drama",
        "$mainUrl/category/political/page/" to "Political",
        "$mainUrl/category/psychological/page/" to "Psychological",
        "$mainUrl/category/reality-show/page/" to "Reality Show",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/school/page/" to "School",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/sports/page/" to "Sports",
        "$mainUrl/category/supernatural/page/" to "Supernatural",
        "$mainUrl/category/suspense/page/" to "Suspense",
        "$mainUrl/category/thai-language/page/" to "Thai Language",
        "$mainUrl/category/thailand-drama/page/" to "Thailand Drama",
        "$mainUrl/category/thriller/page/" to "Thriller",
        "$mainUrl/category/youth/page/" to "Youth"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = "${request.data}$page"
        val document = app.get(pageUrl).document
        val home = document.select("article.herald-lay-c, div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h2 a, h3 a, a.title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        if (title.isBlank() || href.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val rawImg = imgElement?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: ""
        val posterUrl = fixUrl(rawImg)

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.herald-lay-c, div.item, div.post-item, article, div.box")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1, h2.title")?.text()?.trim() ?: "Drama"
        
        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img, img.wp-post-image, .entry-content img, article img")?.attr("src") 
                ?: ""
        )
        
        val description = document.selectFirst("div.storyline, div.content, .entry-content p")?.text()?.trim() ?: "No Plot Found"
        val tvType = if (title.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        val episodeMap = LinkedHashMap<Int, MutableList<EpisodeLink>>()
        val allLinksForMovie = mutableListOf<EpisodeLink>()

        val episodeContainers = document.select(".entry-content p, .entry-content div, div.episodest, ul.episodelist li, .eplister")

        if (episodeContainers.isNotEmpty()) {
            var epIndex = 1
            for (container in episodeContainers) {
                val links = container.select("a[href]")
                if (links.isEmpty()) continue

                val containerText = container.text()
                val explicitEpNum = Regex("""(?i)(?:episode|ep)\.?\s*0*(\d{1,3})""").find(containerText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epIndex

                val linksList = mutableListOf<EpisodeLink>()
                for (linkEl in links) {
                    val href = fixUrl(linkEl.attr("href").trim())
                    if (href.isBlank() || href.contains("/category/") || href.contains("/tag/") || href.contains("t.me")) continue
                    if (href.startsWith("http")) {
                        linksList.add(EpisodeLink(href, explicitEpNum))
                        allLinksForMovie.add(EpisodeLink(href, null))
                    }
                }

                if (linksList.isNotEmpty()) {
                    episodeMap.getOrPut(explicitEpNum) { mutableListOf() }.addAll(linksList)
                    epIndex++
                }
            }
        }

        if (episodeMap.isEmpty()) {
            val allLinks = document.select(".entry-content a[href], a.btn[href], a.button[href]")
            var fallbackEp = 1
            for (element in allLinks) {
                val href = fixUrl(element.attr("href").trim())
                val name = element.text().trim()
                if (href.isBlank() || href.contains("/category/") || href.contains("/tag/") || href.contains("t.me")) continue

                if (href.startsWith("http")) {
                    val foundEp = Regex("""(?i)(?:episode|ep)\.?\s*0*(\d{1,3})""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                        ?: fallbackEp

                    episodeMap.getOrPut(foundEp) { mutableListOf() }.add(EpisodeLink(href, foundEp))
                    allLinksForMovie.add(EpisodeLink(href, null))
                    fallbackEp++
                }
            }
        }

        return if (tvType == TvType.Movie) {
            val movieData = mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.map { (epNum, links) ->
                val distinctLinks = links.distinctBy { it.source }
                newEpisode(mapper.writeValueAsString(distinctLinks)) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })) {
                        this.name = title
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
                for (sourceObj in sources) {
                    resolveAndCallback(sourceObj.source, data, subtitleCallback, callback) {
                        found = true
                    }
                }
                return found
            }

            if (data.startsWith("http")) {
                resolveAndCallback(data, data, subtitleCallback, callback) {
                    found = true
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }

    private suspend fun resolveAndCallback(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        onFound: () -> Unit
    ) {
        try {
            var targetUrl = url
            if (targetUrl.contains("krakenfiles") || targetUrl.contains("statcounter") || targetUrl.contains("histats")) return

            if (targetUrl.contains("redir") || targetUrl.contains("fifa") || targetUrl.contains("?url=") || targetUrl.contains("goto")) {
                val res = app.get(targetUrl, referer = referer, allowRedirects = true)
                targetUrl = res.url
                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    targetUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                }
            }

            if (targetUrl.contains("tmbcloud") || targetUrl.contains("/download/")) {
                onFound()
                KDHindiDubbedExtractor().getUrl(targetUrl, referer, subtitleCallback, callback)
                return
            }

            val doc = app.get(targetUrl, referer = referer).document
            val actionLinks = doc.select("a[href], a.btn, a.button, .entry-content a")

            var hasDirect = false
            for (element in actionLinks) {
                val href = fixUrl(element.attr("href").trim())
                if (!href.startsWith("http") || href.contains("dramacool.website")) continue

                if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || 
                    href.contains("vikingfile") || href.contains("send.now") || href.contains("workers.dev") || 
                    href.contains("r2.dev") || href.contains(".mp4") || href.contains(".mkv")) {
                    hasDirect = true
                    onFound()
                    callback.invoke(
                        ExtractorLink(
                            source = name,
                            name = "Direct - ${element.text().ifBlank { "Stream" }}",
                            url = href,
                            referer = targetUrl,
                            quality = Qualities.P1080.value,
                            type = ExtractorLinkType.VIDEO
                        )
                    )
                } else if (href.contains("tmbcloud") || href.contains("momofile") || href.contains("xcloud")) {
                    hasDirect = true
                    onFound()
                    KDHindiDubbedExtractor().getUrl(href, targetUrl, subtitleCallback, callback)
                }
            }

            if (!hasDirect) {
                onFound()
                loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    data class EpisodeLink(
        val source: String,
        val episode: Int? = null
    )
}
