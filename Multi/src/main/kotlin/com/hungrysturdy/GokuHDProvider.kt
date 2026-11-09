package com.lagradost.hungrysturdy

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
    private val userAgent = "Mozilla/5.0 (Linux; Android 12; Pixel 6) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.6367.82 Mobile Safari/537.36"

    private val browserHeaders = mapOf(
        "User-Agent" to userAgent,
        "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
        "Accept-Language" to "en-US,en;q=0.9",
        "Cookie" to "_ga=GA1.1.1629448397.1794059276; domain-alert=1; fpestid=qKEJFcR_c3RLFa6Kn--eeRQW2dShUUkgk5lB1Hx76DaGCwYwnn5wDmCy7qWmMUlFPnD5Zw; _ga_7BWGJ9MXSS=GS2.1.s1794059275\$o1\$g1\$t1794059680\$j60\$l0\$h0"
    )

    private val cfMutexMap = ConcurrentHashMap<String, Mutex>()
    private val cfKillerMap = ConcurrentHashMap<String, CloudflareKiller>()

    private fun mutexFor(url: String): Mutex =
        cfMutexMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { Mutex() }

    private fun killerFor(url: String): CloudflareKiller =
        cfKillerMap.getOrPut(try { Uri.parse(url).host ?: mainUrl } catch (e: Exception) { mainUrl }) { CloudflareKiller() }

    private fun isCloudflarePage(response: NiceResponse): Boolean {
        return response.code in listOf(403, 503)
    }

    
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Latest",
        "$mainUrl/series/hindi-dubbed-series/" to "Hindi Dubbed Series",
        "$mainUrl/movies/hindi-dubbed-movies/" to "Hindi Dubbed Movies",
        "$mainUrl/ongoing-series/" to "Ongoing Series",
        "$mainUrl/trending/" to "Trending Anime"
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

        val document = app.get(url, headers = getHeaders(), cookies = cookies).document
        val home = document.select("article.post").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst(".entry-title a") ?: return null
        val title = titleEl.text()
        val href = fixUrl(titleEl.attr("href"))
        val posterUrl = fixUrl(this.selectFirst(".post-image img")?.attr("src") ?: "")

        val isDub = title.contains("Hindi", true) || title.contains("Dub", true)
        val isSub = title.contains("Sub", true)

        return newMovieSearchResponse(title, href, TvType.Anime) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, isSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = getHeaders(), cookies = cookies).document
        
        return document.select("article.post").mapNotNull { it.toSearchResult() }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url, headers = getHeaders(), cookies = cookies).document

        val title = document.selectFirst(".single-post-title")?.text() 
            ?: document.selectFirst("h1.entry-title")?.text() 
            ?: "Anime"
            
        val poster = fixUrl(
            document.selectFirst(".single-entry-body img")?.attr("src") 
                ?: document.selectFirst(".post-image img")?.attr("src") 
                ?: ""
        )
        
        val description = document.selectFirst(".xp-plot-box")?.text() 
            ?: document.selectFirst(".entry-content")?.text()

        val episodes = ArrayList<Episode>()

        document.select(".single-entry-body a, .xp-download-btn, a.btn-download").forEachIndexed { index, element ->
            val epName = element.text()
            val epLink = element.attr("href")
            if (epLink.startsWith("http") && !epLink.contains("telegram.dog") && !epLink.contains("whatsapp")) {
                episodes.add(
                    newEpisode(epLink) {
                        this.name = if (epName.isNotBlank()) epName else "Episode ${index + 1}"
                        this.episode = index + 1
                    }
                )
            }
        }

        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return if (tvType == TvType.AnimeMovie) {
            newMovieLoadResponse(title, url, TvType.AnimeMovie, episodes.firstOrNull()?.data ?: url) {
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
        isCapped: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data, headers = getHeaders(), cookies = cookies).document
        
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
