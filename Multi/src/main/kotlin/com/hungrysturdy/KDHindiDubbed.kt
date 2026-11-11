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
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/movie/page/" to "Movie"
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

        // Post page ke saare download/watch links ko target karo
        val linksElements = document.select(".entry-content a[href], div.episodest a[href], ul.episodelist li a[href], .eplister a[href], a.btn[href], a.button[href]")

        for (element in linksElements) {
            val href = fixUrl(element.attr("href").trim())
            val name = element.text().trim()

            if (href.isBlank() || href.contains("/category/") || href.contains("/tag/") || href.contains("t.me") || href.contains("facebook.com")) continue

            if (href.startsWith("http")) {
                allLinksForMovie.add(EpisodeLink(href, null))

                // Episode number extract karna
                val epNum = Regex("""(?i)(?:episode|ep)\.?\s*0*(\d{1,3})""").find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)(?:episode|ep)\.?\s*0*(\d{1,3})""").find(href)?.groupValues?.get(1)?.toIntOrNull()
                    ?: 1

                episodeMap.getOrPut(epNum) { mutableListOf() }.add(EpisodeLink(href, epNum))
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
                sources.amap { sourceObj ->
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

            // Bypassing shorteners/redirects
            if (targetUrl.contains("redir") || targetUrl.contains("fifa") || targetUrl.contains("?url=") || targetUrl.contains("goto")) {
                val res = app.get(targetUrl, referer = referer, allowRedirects = true)
                targetUrl = res.url
                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    targetUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                }
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
                    loadExtractor(href, targetUrl, subtitleCallback, callback)
                } else if (href.contains("momofile") || href.contains("xcloud") || href.contains("tmbcloud")) {
                    hasDirect = true
                    onFound()
                    loadExtractor(href, targetUrl, subtitleCallback, callback)
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
