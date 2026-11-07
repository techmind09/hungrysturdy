package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AnimeDubIndiaProvider : MainAPI() {
    override var mainUrl = "https://www.animehubindia.com"
    override var name = "Anime Dub India"
    override var lang = "hi"
    override val hasMainPage = true
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA
    )

    private val cookies = mapOf(
        "_ga_CPLDHBTQGX" to "GS2.1.s1794065397\$o1\$g0\$t1794065397\$j60\$l0\$h0",
        "_ga" to "GA1.1.389048499.1794065398"
    )

    private fun getHeaders(): Map<String, String> {
        return mapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36",
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
            "Referer" to "$mainUrl/"
        )
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Newly Released",
        "$mainUrl/#shounen-section" to "Shounen Anime",
        "$mainUrl/#isekai-section" to "Isekai Anime",
        "$mainUrl/#supernatural-section" to "Supernatural Anime"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val doc = app.get(
            mainUrl,
            headers = getHeaders(),
            cookies = cookies
        ).document

        val homeList = mutableListOf<HomePageList>()

        val newlyReleased = doc.select("#newly-released-section a, .anime-card, div.grid a").mapNotNull {
            it.toSearchResult()
        }.distinctBy { it.url }

        if (newlyReleased.isNotEmpty()) {
            homeList.add(HomePageList("Newly Released Anime", newlyReleased))
        }

        return newHomePageResponse(homeList)
    }

    private fun Element.toSearchResult(): AnimeSearchResponse? {
        val href = this.attr("href")
        if (href.isBlank() || href.contains("#")) return null
        val url = fixUrl(href)
        
        val title = this.selectFirst("h3")?.text()?.trim() 
            ?: this.selectFirst(".title")?.text()?.trim()
            ?: this.attr("data-title")
            ?: if (this.text().isNotBlank()) this.text().trim() else return null

        val posterUrl = fixUrlNull(this.selectFirst("img")?.attr("src") ?: this.selectFirst("img")?.attr("data-src"))

        return newAnimeSearchResponse(title, url, TvType.Anime) {
            this.posterUrl = posterUrl
            this.dubStatus = mutableSetOf(DubStatus.Dubbed)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val doc = app.get(
            mainUrl,
            headers = getHeaders(),
            cookies = cookies
        ).document

        return doc.select("a.anime-card, div.grid a, .search-result-item").mapNotNull { card ->
            val title = card.selectFirst("h3")?.text()?.trim() 
                ?: card.selectFirst(".title")?.text()?.trim() 
                ?: card.text()

            if (title.contains(query, ignoreCase = true)) {
                card.toSearchResult()
            } else {
                null
            }
        }.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val response = app.get(
            url,
            headers = getHeaders(),
            cookies = cookies
        )
        val doc = response.document

        val title = doc.selectFirst("h1")?.text()?.trim() 
            ?: doc.selectFirst("meta[property=og:title]")?.attr("content") 
            ?: "Anime Dub India"

        val poster = fixUrlNull(
            doc.selectFirst("meta[property=og:image]")?.attr("content") 
                ?: doc.selectFirst("img.object-cover")?.attr("src")
        )

        val description = doc.selectFirst("meta[name=description]")?.attr("content") 
            ?: doc.selectFirst(".synopsis, .description")?.text()

        val genreList = doc.select(".genre-tag, .tags a").map { it.text() }

        val episodes = mutableListOf<Episode>()
        
        doc.select("a[href*='drive'], a[href*='mega'], a[href*='embed'], .episodelist a, .ep-card").forEachIndexed { index, el ->
            val epUrl = el.attr("href")
            if (epUrl.isNotBlank() && !epUrl.startsWith("#")) {
                val epName = el.text().ifBlank { "Episode ${index + 1}" }
                episodes.add(
                    newEpisode(fixUrl(epUrl)) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            episodes.add(
                newEpisode(url) {
                    this.name = "Full Episode / Stream"
                    this.episode = 1
                }
            )
        }

        return newAnimeLoadResponse(title, url, TvType.Anime) {
            this.posterUrl = poster
            this.plot = description
            this.tags = genreList
            addEpisodes(DubStatus.Dubbed, episodes)
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        if (data.startsWith("http")) {
            loadExtractor(data, subtitleCallback, callback)
        }
        return true
    }
}
