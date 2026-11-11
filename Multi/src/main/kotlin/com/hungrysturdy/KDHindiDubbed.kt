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

        val contentLinks = document.select(".entry-content a[href], div.episodest a[href], ul.episodelist li a[href], .eplister a[href]")

        val episodes = contentLinks.mapNotNull { element ->
            val link = fixUrl(element.attr("href").trim())
            val epName = element.text().trim()

            if (link.isBlank() || link.contains("/category/") || link.contains("/tag/") || link.contains("telegram.me") || link.contains("t.me")) {
                return@mapNotNull null
            }

            if (epName.contains("Episode", true) || epName.contains("Ep ", true) || link.contains("episode", true)) {
                val epNum = Regex("""(?:Episode|Ep)\s*(\d+)""", RegexOption.IGNORE_CASE).find(epName)?.groupValues?.get(1)?.toIntOrNull()
                    ?: epName.filter { it.isDigit() }.toIntOrNull()
                    ?: 1

                newEpisode(link) {
                    name = epName.ifBlank { "Episode $epNum" }
                    episode = epNum
                }
            } else null
        }.distinctBy { it.data }.ifEmpty {
            listOf(
                newEpisode(url) {
                    name = "Full Movie / All Episodes"
                    episode = 1
                }
            )
        }

        return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
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
        if (!data.contains(mainUrl.substringAfter("://"))) {
            processExternalLink(data, mainUrl, subtitleCallback, callback)
            return true
        }

        val document = app.get(data).document
        val links = document.select(".entry-content a[href], a.btn[href], a.button[href], a.download-btn[href], a.watch-btn[href], iframe[src]")

        for (element in links) {
            val rawHref = element.attr("href").ifBlank { element.attr("src") }
            val link = fixUrl(rawHref.trim())

            if (!link.startsWith("http")) continue
            if (link.contains("/category/") || link.contains("/tag/") || link.contains("wp-login") || link.contains("facebook.com") || link.contains("twitter.com")) continue

            processExternalLink(link, data, subtitleCallback, callback)
        }
        return true
    }

    private suspend fun processExternalLink(
        initialLink: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            var link = initialLink
            if (link.contains("krakenfiles") || link.contains("statcounter") || link.contains("histats")) return

            if (link.contains("redir") || link.contains("fifa") || link.contains("?url=") || link.contains("goto")) {
                val res = app.get(link, referer = referer, allowRedirects = true)
                link = res.url

                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    link = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                } else {
                    val scriptRedirect = Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""").find(res.text)?.groupValues?.get(1)
                    if (!scriptRedirect.isNullOrBlank()) {
                        link = scriptRedirect
                    }
                }
            }

            if (link.contains("momofile") || link.contains("xcloud") || (link.contains("dramacool.website") && !link.contains("tmbcloud"))) {
                val subDoc = app.get(link, referer = referer).document
                val subLinks = subDoc.select(".entry-content a[href], a.btn[href], a.button[href], a.download-btn[href], a[href*='tmbcloud'], a[href*='hubcloud'], a[href*='gdflix'], a[href*='filepress']")
                for (subEl in subLinks) {
                    val subHref = subEl.attr("href").trim()
                    if (subHref.startsWith("http") && !subHref.contains("dramacool.website") && !subHref.contains("krakenfiles")) {
                        routeToExtractor(subHref, link, subtitleCallback, callback)
                    }
                }
            } else {
                routeToExtractor(link, referer, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    private suspend fun routeToExtractor(
        url: String,
        referer: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        if (url.contains("tmbcloud") || url.contains("/download/")) {
            KDHindiDubbedExtractor().getUrl(url, referer, subtitleCallback, callback)
        } else {
            loadExtractor(url, referer, subtitleCallback, callback)
        }
    }
}
