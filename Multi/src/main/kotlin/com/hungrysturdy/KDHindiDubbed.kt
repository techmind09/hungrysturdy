package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KDHindiDubbed : MainAPI() {
    override var mainUrl = "https://dramacool.website"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie)

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/english-dubbed/page/" to "English Dubbed",
        "$mainUrl/category/family/page/" to "Family",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/historical/page/" to "Historical",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/japanese-drama/page/" to "Japanese Drama",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/movie/page/" to "Movie",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/school/page/" to "School",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/supernatural/page/" to "Supernatural",
        "$mainUrl/category/thailand-drama/page/" to "Thailand Drama",
        "$mainUrl/category/thriller/page/" to "Thriller",
        "$mainUrl/category/youth/page/" to "Youth"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get("${request.data}$page").document
        val home = document.select("article.herald-lay-c, div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h2 a, h3 a, a.title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))

        val imgElement = this.selectFirst("img")
        val rawImg = imgElement?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } ?: ""
        val posterUrl = fixUrl(rawImg)

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.herald-lay-c, div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1, h2.title")?.text()?.trim() ?: "Drama"

        val posterImg = document.selectFirst("div.poster img, img.wp-post-image, .entry-content img, article img")
        val rawPoster = posterImg?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: posterImg?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: posterImg?.attr("data-lazy-src")?.takeIf { it.isNotBlank() } ?: ""
        val poster = fixUrl(rawPoster)

        val description = document.selectFirst("div.storyline, div.content, .entry-content p")?.text()?.trim()

        val episodes = mutableListOf<Episode>()
        
        // Post page ke andar jitne bhi links hain unhein scan karo
        val allLinks = document.select(".entry-content a[href], div.episodest a[href], ul.episodelist li a[href], .eplister a[href], .entry-content p a[href]")
        
        var epCounter = 1
        for (element in allLinks) {
            val link = fixUrl(element.attr("href").trim())
            val name = element.text().trim()

            if (link.isBlank() || link.contains("/category/") || link.contains("/tag/") || link.contains("t.me") || link.contains("facebook.com")) continue

            if (name.contains("Episode", true) || name.contains("Ep", true) || name.contains("MultipleLink", true) || name.contains("XCloud", true) || link.contains("episode", true) || link.contains("momofile") || link.contains("xcloud")) {
                val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(name)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epCounter

                episodes.add(
                    newEpisode(link) {
                        this.name = if (name.isNotBlank()) name else "Episode $epNum"
                        this.episode = epNum
                    }
                )
                epCounter++
            }
        }

        val finalEpisodes = episodes.distinctBy { it.data }.ifEmpty {
            listOf(
                newEpisode(url) {
                    name = title
                    episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, finalEpisodes) {
            this.posterUrl = poster
            this.plot = description
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        resolveLinksRecursive(data, data, subtitleCallback, callback, 0)
        return true
    }

    private suspend fun resolveLinksRecursive(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit,
        depth: Int
    ) {
        if (depth > 3 || url.isBlank()) return
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

            val doc = app.get(targetUrl, referer = referer).document
            val links = doc.select("a[href], a.btn, a.button, .entry-content a, iframe[src]")

            var foundValid = false
            for (element in links) {
                val href = fixUrl(element.attr("href").ifBlank { element.attr("src") }.trim())
                val text = element.text().lowercase()

                if (!href.startsWith("http") || href.contains("dramacool.website")) continue

                if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || 
                    href.contains("vikingfile") || href.contains("send.now") || href.contains("workers.dev") || 
                    href.contains("r2.dev") || href.contains(".mp4") || href.contains(".mkv")) {
                    foundValid = true
                    loadExtractor(href, targetUrl, subtitleCallback, callback)
                } else if (text.contains("download") || text.contains("watch") || text.contains("link") || 
                           text.contains("xcloud") || text.contains("server") || text.contains("multiplelink") || href.contains("momofile") || href.contains("xcloud")) {
                    foundValid = true
                    resolveLinksRecursive(href, targetUrl, subtitleCallback, callback, depth + 1)
                }
            }

            if (!foundValid) {
                loadExtractor(targetUrl, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }
}
