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
        val home = document.select("div.item, div.post-item, article").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2 a, h3 a, a.title")?.text() ?: return null
        val href = fixUrl(this.selectFirst("h2 a, h3 a, a.title")?.attr("href") ?: return null)
        val posterUrl = fixUrl(this.selectFirst("img")?.attr("data-src") ?: this.selectFirst("img")?.attr("src"))
        
        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("div.item, div.post-item, article").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, h2.title")?.text()?.trim() ?: "Drama"
        val poster = fixUrl(document.selectFirst("div.poster img, img.wp-post-image")?.attr("src"))
        val description = document.selectFirst("div.storyline, div.content, .entry-content")?.text()?.trim()
        
        val episodes = document.select("div.episodest, ul.episodelist li, a.btn-eps").mapNotNull { element ->
            val link = fixUrl(element.selectFirst("a")?.attr("href") ?: element.attr("href"))
            val epName = element.text().trim()
            val epNum = epName.filter { it.isDigit() }.toIntOrNull() ?: 1
            Episode(link, name = epName, episode = epNum)
        }.ifEmpty {
            // If single page movie or direct watch link
            listOf(Episode(url, name = title, episode = 1))
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
        
        // Find all download / watch links (e.g., pointing to momofile.shop or direct players)
        val links = document.select("a[href*=\"momofile\"], a.btn, a.button, a[href*=\"xcloud\"]")
        
        for (element in links) {
            val link = element.attr("href") ?: continue
            if (link.startsWith("http")) {
                loadExtractor(link, data, subtitleCallback, callback)
            }
        }
        return true
    }
}
