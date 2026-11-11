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
        val document = app.get("${request.data}$page").document
        val home = document.select("div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 a, h3 a, a.title, .entry-title a")?.text() ?: return null
        val href = fixUrl(this.selectFirst("h2 a, h3 a, a.title, .entry-title a")?.attr("href") ?: return null)
        
        val imgElement = this.selectFirst("img")
        val rawImg = imgElement?.attr("src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: ""
        val posterUrl = fixUrl(rawImg)
        
        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, h2.title")?.text()?.trim() ?: "Drama"
        
        val posterImg = document.selectFirst("div.poster img, img.wp-post-image, .entry-content img")
        val rawPoster = posterImg?.attr("src")?.takeIf { it.isNotBlank() } ?: posterImg?.attr("data-src") ?: ""
        val poster = fixUrl(rawPoster)
        
        val description = document.selectFirst("div.storyline, div.content, .entry-content p")?.text()?.trim()
        
        val episodes = document.select("a[href*='episode'], div.episodest a, ul.episodelist li a, a.btn-eps, .eplister a").mapNotNull { element ->
            val link = fixUrl(element.attr("href") ?: "")
            val epName = element.text().trim()
            val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 1
            if (link.isNotBlank() && (epName.contains("Episode", true) || epName.contains("Ep", true) || link.contains("episode"))) {
                newEpisode(link) {
                    name = epName
                    episode = epNum
                }
            } else null
        }.distinctBy { it.data }.ifEmpty {
            listOf(
                newEpisode(url) {
                    name = title
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
        val document = app.get(data).document
        val links = document.select("a[href], a.btn, a.button, a.watch-btn, a.download-btn")

        for (element in links) {
            val link = element.attr("href")?.let { fixUrl(it) } ?: continue
            if (link.startsWith("http")) {
                try {
                    // Ignore unwanted/dead domains that trigger wrong extractors like krakenfiles
                    if (link.contains("krakenfiles") || link.contains("statcounter") || link.contains("histats")) continue

                    // 1. Handle intermediate pages like momofile.shop, xcloud.asia, or dramacool redirects
                    if (link.contains("momofile") || link.contains("xcloud") || (link.contains("dramacool.website") && !link.contains("tmbcloud"))) {
                        val subDoc = app.get(link).document
                        val subLinks = subDoc.select("a[href], a.btn, a.download-btn")
                        for (subEl in subLinks) {
                            val subHref = subEl.attr("href") ?: continue
                            if (subHref.startsWith("http") && !subHref.contains("dramacool.website") && !subHref.contains("krakenfiles")) {
                                loadExtractor(subHref, link, subtitleCallback, callback)
                            }
                        }
                    } 
                    // 2. Handle TMBCloud links directly using our custom extractor
                    else if (link.contains("tmbcloud")) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    } 
                    // 3. Handle valid direct external hosting links only
                    else if (!link.contains("dramacool.website")) {
                        loadExtractor(link, data, subtitleCallback, callback)
                    }
                } catch (_: Exception) {}
            }
        }
        return true
    }
}
