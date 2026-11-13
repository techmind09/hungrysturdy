package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import org.jsoup.nodes.Element
import android.util.Base64

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
            for (row in rows) {
                val title = row.selectFirst("h3.cineapse-row-title")?.text()
                    ?.replace(" Explore all →", "")?.trim() ?: continue
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
        val html = document.html()

        val title = document.selectFirst("h1, .cineapse-title, h2.text-2xl, .text-3xl")?.text() 
            ?: document.title().substringBefore("-").trim() 
            ?: "Unknown"

        var poster = document.selectFirst("img.poster, .detail-poster, img[alt*='poster' i]")?.attr("src")
        if (poster.isNullOrBlank()) {
            poster = document.select("img").mapNotNull { it.attr("src") }.firstOrNull { it.contains("tmdb.org") }
        }
        if (poster.isNullOrBlank()) {
            poster = Regex("""https?://image\.tmdb\.org/t/p/[^"'\s\\]+""").find(html)?.value?.replace("\\", "")
        }

        var plot = document.selectFirst(".plot, .synopsis, p.description, .text-gray-300, .text-sm")?.text()
        if (plot.isNullOrBlank() || plot.length < 20) {
            plot = document.select("p").maxByOrNull { it.text().length }?.text()
        }

        val isMovie = url.contains("/movie/")
        val slug = url.substringAfterLast("/")

        if (isMovie) {
            val watchUrl = "$mainUrl/watch/movie/$slug"
            return newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val epMap = mutableMapOf<Int, String>()

            val episodeLinks = document.select("a[href*='-episode-'], a[href*='/watch/tv/']")
            for (link in episodeLinks) {
                val epHref = link.attr("href")
                val epNum = Regex("""episode-(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()
                if (epNum != null) {
                    epMap[epNum] = fixUrl(epHref)
                }
            }

            if (epMap.isEmpty()) {
                val matches = Regex("""/watch/tv/([^"'\s\\]+-episode-(\d+))""").findAll(html)
                for (match in matches) {
                    val fullPath = match.groupValues[1]
                    val epNum = match.groupValues[2].toIntOrNull()
                    if (epNum != null) {
                        epMap[epNum] = "$mainUrl/watch/tv/$fullPath"
                    }
                }
            }

            if (epMap.isEmpty()) {
                val maxEpFromRegex = Regex("""episode-(\d+)""").findAll(html).mapNotNull { it.groupValues[1].toIntOrNull() }.maxOrNull()
                val totalEpsText = document.selectFirst(".total-episodes")?.text()
                val totalEpsFromText = totalEpsText?.filter { it.isDigit() }?.toIntOrNull()
                
                val totalEps = maxEpFromRegex ?: totalEpsFromText ?: 1

                for (i in 1..totalEps) {
                    epMap[i] = "$mainUrl/watch/tv/$slug-episode-$i"
                }
            }

            epMap.toSortedMap().forEach { (epNum, epUrl) ->
                episodes.add(newEpisode(epUrl) { 
                    this.episode = epNum 
                    this.name = "Episode $epNum"
                })
            }

            return newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        val html = document.html()
        var found = false

        // FIX: Deep scan embedded JSON script tags (Next.js / Nuxt hydration payloads) where KatDrama hides true stream URLs
        val scriptTags = document.select("script#__NEXT_DATA__, script[type='application/json']")
        for (script in scriptTags) {
            val jsonText = script.text()
            val extractedUrls = Regex("""https?://[^"'\s\\]+(?:hakunaymatata|devcorp\.me|modiplay\.xyz|rozgarlelo|fvibuxer|vidmoly|staticmoly|xcloud1\.lol|tmbcloud)[^"'\s\\]*""").findAll(jsonText)
            for (match in extractedUrls) {
                val cleanUrl = match.value.replace("\\", "")
                UniversalExtractors.invoke(cleanUrl, data, subtitleCallback, callback)
                found = true
            }
        }

        // 1. Check standard server buttons
        for (serverBtn in document.select("[data-url], [data-link], [data-src], [data-server], .server-btn, .server-item")) {
            var serverUrl = serverBtn.attr("data-url").ifBlank { serverBtn.attr("data-link") }.ifBlank { serverBtn.attr("data-src") }
            if (serverUrl.isNotBlank()) {
                if (!serverUrl.startsWith("http") && !serverUrl.startsWith("//")) {
                    try { serverUrl = String(Base64.decode(serverUrl, Base64.DEFAULT)) } catch (_: Exception) {}
                }
                if (serverUrl.startsWith("//")) serverUrl = "https:$serverUrl"
                if (serverUrl.startsWith("http")) {
                    UniversalExtractors.invoke(serverUrl, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 2. Check iframes directly
        for (iframe in document.select("iframe")) {
            var src = iframe.attr("src").ifBlank { iframe.attr("data-src") }
            if (src.isNotBlank()) {
                if (src.startsWith("//")) src = "https:$src"
                if (src.startsWith("http")) {
                    UniversalExtractors.invoke(src, data, subtitleCallback, callback)
                    found = true
                }
            }
        }

        // 3. Fallback: Raw HTML Regex Scan
        val serverRegex = Regex("""(https?://[^"'\s\\]+(?:hakunaymatata|devcorp\.me|modiplay\.xyz|rozgarlelo|fvibuxer|vidmoly|staticmoly|xcloud1\.lol|tmbcloud)[^"'\s\\]*)""")
        for (match in serverRegex.findAll(html)) {
            val cleanUrl = match.value.replace("\\", "")
            UniversalExtractors.invoke(cleanUrl, data, subtitleCallback, callback)
            found = true
        }

        return found
    }
}
