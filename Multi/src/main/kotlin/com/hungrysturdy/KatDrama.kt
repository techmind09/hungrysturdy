package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element

class KatDrama : MainAPI() {
    override var mainUrl = "https://k-drama.in"
    override var name = "KatDrama"
    override val hasMainPage = true
    override var lang = "en"
    override val supportedTypes = setOf(TvType.TvSeries, TvType.Movie, TvType.AsianDrama)

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/explore?type=kdrama" to "K-Drama",
        "$mainUrl/explore?type=cdrama" to "C-Drama",
        "$mainUrl/explore?type=jdrama" to "J-Drama",
        "$mainUrl/explore?type=movie" to "Movies",
        "$mainUrl/ranking" to "Top 10"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val document = app.get(request.data).document
        val homePageList = mutableListOf<HomePageList>()
        val rows = document.select(".cineapse-row-wrapper")
        
        if (rows.isNotEmpty()) {
            rows.forEach { row ->
                val title = row.selectFirst("h3.cineapse-row-title")?.text()?.replace(" Explore all →", "")?.trim() ?: return@forEach
                val elements = row.select(".cineapse-row-track .cineapse-card")
                val searchResponses = elements.mapNotNull { toSearchResult(it) }

                if (searchResponses.isNotEmpty()) {
                    homePageList.add(HomePageList(title, searchResponses))
                }
            }
        } else {
            val elements = document.select(".cineapse-card")
            val searchResponses = elements.mapNotNull { toSearchResult(it) }
            
            if (searchResponses.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, searchResponses))
            }
        }

        // FIX 1: Use direct newHomePageResponse method with list
        return newHomePageResponse(homePageList)
    }

    private fun toSearchResult(element: Element): SearchResponse? {
        val onClickAttr = element.attr("onclick")
        val urlPath = onClickAttr.substringAfter("href='").substringBefore("'")
        if (urlPath.isEmpty() || urlPath == onClickAttr) return null

        val title = element.selectFirst(".cineapse-card-title")?.text() ?: return null
        val posterUrl = element.selectFirst("img.cineapse-poster-img")?.attr("src")
        val fullUrl = fixUrl(urlPath)

        return if (fullUrl.contains("/movie/")) {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, fullUrl, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val apiUrl = "$mainUrl/api.php?action=search&q=$query"
        val response = app.get(apiUrl).parsedSafe<SearchApiResponse>() ?: return emptyList()

        return response.results.mapNotNull { item ->
            val title = item.name ?: item.title ?: return@mapNotNull null
            val isMovie = item.media_type == "movie" || (item.title != null && item.name == null)
            val id = item.id
            
            val slugBase = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val slug = "$slugBase-$id"
            
            val url = if (isMovie) "$mainUrl/detail/movie/$slug" else "$mainUrl/detail/tv/$slug"
            val poster = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }

            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.TvSeries) {
                    this.posterUrl = poster
                }
            }
        }
    }

    data class SearchApiResponse(val results: List<SearchApiResult> = emptyList())
    data class SearchApiResult(
        val id: Int?,
        val title: String?,
        val name: String?,
        val media_type: String?,
        val poster_path: String?
    )

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        
        val title = document.selectFirst("h1, .cineapse-title")?.text() ?: "Unknown"
        val poster = document.selectFirst("img.poster, .detail-poster")?.attr("src")
        val plot = document.selectFirst(".plot, .synopsis, p.description")?.text()
        
        val isMovie = url.contains("/movie/")

        if (isMovie) {
            val slug = url.substringAfterLast("/")
            val watchUrl = "$mainUrl/watch/movie-$slug"
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val slug = url.substringAfterLast("/")
            
            val episodeElements = document.select(".episode-btn, .ep-list button, a[href*='-episode-']")
            
            if (episodeElements.isNotEmpty()) {
                episodeElements.forEach { epElement ->
                    val epNumText = epElement.text()
                    val epNum = epNumText.filter { it.isDigit() }.toIntOrNull()
                    val epUrl = "$mainUrl/watch/$slug-episode-$epNum"
                    
                    if (epNum != null) {
                        // FIX 2: newEpisode method
                        episodes.add(newEpisode(epUrl) { this.episode = epNum })
                    }
                }
            } else {
                val totalEpsText = document.selectFirst(".total-episodes")?.text() ?: "10"
                val totalEps = totalEpsText.filter { it.isDigit() }.toIntOrNull() ?: 1
                
                for (i in 1..totalEps) {
                    // FIX 2: newEpisode method
                    episodes.add(newEpisode("$mainUrl/watch/$slug-episode-$i") { this.episode = i })
                }
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    // FIX 3: Corrected parameter order (subtitleCallback comes BEFORE callback)
    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        
        // FIX 4: UniversalExtractors will work now provided Extractor.kt shares the exact same package name.
        document.select(".server-btn, button[data-server]").forEach { serverBtn ->
            val serverUrl = serverBtn.attr("data-url")
            if (serverUrl.isNotBlank()) {
                UniversalExtractors.invoke(serverUrl, data, subtitleCallback, callback)
            }
        }

        document.select("iframe").forEach { iframe ->
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                UniversalExtractors.invoke(src, data, subtitleCallback, callback)
            }
        }
        
        return true
    }
}
