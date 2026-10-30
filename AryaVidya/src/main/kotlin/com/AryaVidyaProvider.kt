package com.AryaVidya

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AryaVidyaProvider : MainAPI() {
    override var mainUrl = "https://stream.aryavidya.cc"
    override var name = "AryaVidya"
    override val supportedTypes = setOf(TvType.Documentary, TvType.Others)
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val doc = app.get(mainUrl).document
        val homePages = mutableListOf<HomePageList>()

        // Batches aur Courses ke cards ko select karna
        val batchCards = doc.select("div.batch-card, div.random-batch-item, div.group").mapNotNull { 
            it.toSearchResult()
        }

        if (batchCards.isNotEmpty()) {
            homePages.add(HomePageList("Batches & Study Material", batchCards))
        }

        return newHomePageResponse(homePages)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val title = this.selectFirst("h3, .title, .batch-name")?.text() ?: return null
        val href = fixUrlNull(this.selectFirst("a")?.attr("href")) ?: return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get("$mainUrl/search?q=$query").document
        return doc.select("div.batch-card, div.search-item").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .batch-title")?.text() ?: "Study Content"
        val poster = fixUrlNull(doc.selectFirst("img.poster, img.banner")?.attr("src"))

        // Lectures ya Classes ki list ko extract karna
        val episodes = doc.select("div.lecture-item, .class-row, li.episode-item").mapIndexed { index, element ->
            val epName = element.selectFirst(".name, span, h4")?.text() ?: "Lecture ${index + 1}"
            val epUrl = fixUrlNull(element.selectFirst("a")?.attr("href") ?: url)!!
            
            newEpisode(epUrl) {
                this.name = epName
                this.episode = index + 1
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, episodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCtype: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val doc = app.get(data).document
        
        // Video player tags ya iframe stream ko target karna
        val videoUrl = doc.selectFirst("video source")?.attr("src") 
            ?: doc.selectFirst("iframe")?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            if (videoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    videoUrl,
                    referer = "$mainUrl/"
                ).forEach(callback)
            } else {
                callback(
                    ExtractorLink(
                        source = name,
                        name = name,
                        url = videoUrl,
                        referer = "$mainUrl/",
                        quality = Qualities.Unknown.value
                    )
                )
            }
            return true
        }
        return false
    }
}
