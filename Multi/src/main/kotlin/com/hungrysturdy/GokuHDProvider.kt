package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import org.jsoup.nodes.Element

class GokuHDProvider : MainAPI() {
    override var mainUrl = "https://gokuhd.cc"
    override var name = "GokuHD"
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries
    )
    override var lang = "hi"
    override val hasMainPage = true

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36",
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9"
    )

    private val cookies = mapOf(
        "_ga" to "GA1.1.1334156482.1794195302",
        "_ga_076NE65BBV" to "GS2.1.s1794205955\$o2\$g0\$t1794205955\$j60\$l0\$h0"
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Latest",
        "$mainUrl/series/hindi-dubbed-series/" to "Series",
        "$mainUrl/movies/hindi-dubbed-movies/" to "Movies",
        "$mainUrl/ongoing-series/" to "Ongoing Series",
        "$mainUrl/trending/" to "Trending Anime",
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
        "$mainUrl/genres/school/" to "Genre: School"
    )

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            val trimmed = request.data.removeSuffix("/")
            "$trimmed/page/$page/"
        }

        val document = app.get(url, headers = headers, cookies = cookies).document
        val home = document.select("article.post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst(".entry-title a") ?: this.selectFirst("h2.entry-title a") ?: return null
        val title = titleEl.text()
        val href = fixUrl(titleEl.attr("href"))
        val posterUrl = fixUrl(this.selectFirst(".post-image img")?.attr("src") ?: "")

        val isDub = title.contains("Hindi", true) || title.contains("Dub", true)
        val isSub = title.contains("Sub", true)

        return newAnimeSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, isSub)
        }
    }

        override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = headers, cookies = cookies).document
        
        return document.select("article.post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = headers, cookies = cookies).document

        val title = document.selectFirst(".single-post-title")?.text() 
            ?: document.selectFirst("h1.entry-title")?.text() 
            ?: document.selectFirst("h2.entry-title")?.text() 
            ?: "Anime"
            
        // Fixed Poster Selector to check OpenGraph meta tag first (which has the correct poster URL)
        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst(".single-entry-body img")?.attr("src") 
                ?: document.selectFirst(".post-image img")?.attr("src") 
                ?: ""
        )
        
        val description = document.selectFirst(".xp-plot-box")?.text() 
            ?: document.selectFirst(".entry-content")?.text()
            ?: document.selectFirst(".entry-summary")?.text()

        val episodes = ArrayList<Episode>()

        // Improved selector to catch all download buttons and links inside the article body
        document.select("a.wp-download-btn, a.xp-download-btn, a.btn-download, .single-entry-body a").forEachIndexed { _, element ->
            val epName = element.text()
            val epLink = element.attr("href")
            if (epLink.startsWith("http") && 
                !epLink.contains("telegram.dog") && 
                !epLink.contains("whatsapp") && 
                !epLink.contains("facebook.com") && 
                !epLink.contains("twitter.com") &&
                !epLink.contains("imgbb.fit") // Ignore screenshot image links
            ) {
                if (episodes.none { it.data == epLink }) {
                    episodes.add(
                        newEpisode(epLink) {
                            this.name = if (epName.isNotBlank() && epName.length < 50) epName else "Episode ${episodes.size + 1}"
                            this.episode = episodes.size + 1
                        }
                    )
                }
            }
        }

        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes) {
                this.posterUrl = poster
                this.posterHeaders = headers
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCapped: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = headers, cookies = cookies).document
        
        val links = document.select("a.wp-download-btn, a.xp-download-btn, .single-entry-body a, a.btn-download")
        for (linkEl in links) {
            val href = linkEl.attr("href")
            if (href.startsWith("http")) {
                loadExtractor(href, data, subtitleCallback, callback)
            }
        }
        
        if (data.startsWith("http") && !data.contains("gokuhd.cc")) {
            loadExtractor(data, data, subtitleCallback, callback)
        }

        return true
    }
}
