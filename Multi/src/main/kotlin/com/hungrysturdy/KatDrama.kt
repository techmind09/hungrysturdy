package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URLEncoder

class KatDrama : MainAPI() {
    override var mainUrl = "https://k-drama.in"
    override var name = "KatDrama"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home",
        "$mainUrl/explore?type=kdrama&page=" to "K-Drama",
        "$mainUrl/explore?type=cdrama&page=" to "C-Drama",
        "$mainUrl/explore?type=jdrama&page=" to "J-Drama",
        "$mainUrl/explore?type=movie&page=" to "Movies",
        "$mainUrl/explore?provider=hindi&page=" to "Hindi Dubbed",
        "$mainUrl/ranking?page=" to "Top 10"
    )

    private val headers = mapOf(
        "User-Agent" to "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
        "Cookie" to "_ga=GA1.1.583239637.1794483658; _ga_0TNV9GL2DB=GS2.1.s1794492289\$o2\$g1\$t1794492315\$j34\$l0\$h0",
        "Referer" to "$mainUrl/"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data == "$mainUrl/") mainUrl else "${request.data}$page"
        val document = app.get(url, headers = headers).document
        val homePageList = mutableListOf<HomePageList>()
        val rows = document.select(".cineapse-row-wrapper")

        if (request.data == "$mainUrl/" && rows.isNotEmpty()) {
            rows.forEach { row ->
                val title = row.selectFirst("h3.cineapse-row-title")?.text()
                    ?.replace("Explore all →", "")?.trim() ?: return@forEach
                val elements = row.select(".cineapse-row-track .cineapse-card")
                val searchResponses = elements.mapNotNull { it.toSearchResult() }

                if (searchResponses.isNotEmpty()) {
                    homePageList.add(HomePageList(title, searchResponses))
                }
            }
        } else {
            val elements = document.select(".cineapse-card, a[href*='/detail/']")
            val searchResponses = elements.mapNotNull { it.toSearchResult() }

            if (searchResponses.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, searchResponses))
            }
        }

        return newHomePageResponse(homePageList)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val onClickAttr = this.attr("onclick")
        val urlPath = if (onClickAttr.contains("href='")) {
            onClickAttr.substringAfter("href='").substringBefore("'")
        } else {
            this.attr("href")
        }

        if (urlPath.isBlank() || !urlPath.contains("/detail/")) return null

        val title = this.selectFirst(".cineapse-card-title, h4")?.text()?.trim() ?: return null
        if (title.equals("x", ignoreCase = true)) return null

        val posterUrl = fixUrlNull(this.selectFirst("img.cineapse-poster-img, img")?.attr("src"))
        val year = this.selectFirst(".cineapse-card-meta span")?.text()?.trim()?.toIntOrNull()
        val fullUrl = fixUrl(urlPath)

        val isMovie = fullUrl.contains("/detail/movie/") ||
                this.select(".cineapse-card-meta span").any { it.text().equals("Movie", true) }

        return if (isMovie) {
            newMovieSearchResponse(title, fullUrl, TvType.Movie) {
                this.posterUrl = posterUrl
                this.year = year
            }
        } else {
            newTvSeriesSearchResponse(title, fullUrl, TvType.AsianDrama) {
                this.posterUrl = posterUrl
                this.year = year
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        val apiUrl = "$mainUrl/api.php?action=search&q=$encodedQuery"
        val response = app.get(apiUrl, headers = headers).parsedSafe<SearchApiResponse>() ?: return emptyList()

        return response.results.mapNotNull { item ->
            val title = item.name ?: item.title ?: return@mapNotNull null
            val id = item.id ?: return@mapNotNull null
            val isMovie = item.media_type == "movie" || (!item.title.isNullOrBlank() && item.name.isNullOrBlank())

            val slugBase = title.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')
            val slug = "$slugBase-$id"

            val url = if (isMovie) "$mainUrl/detail/movie/$slug" else "$mainUrl/detail/tv/$slug"
            val poster = item.poster_path?.let { "https://image.tmdb.org/t/p/w500$it" }
            val year = (item.first_air_date ?: item.release_date)?.take(4)?.toIntOrNull()

            if (isMovie) {
                newMovieSearchResponse(title, url, TvType.Movie) {
                    this.posterUrl = poster
                    this.year = year
                }
            } else {
                newTvSeriesSearchResponse(title, url, TvType.AsianDrama) {
                    this.posterUrl = poster
                    this.year = year
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
        val poster_path: String?,
        val first_air_date: String?,
        val release_date: String?
    )

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = headers).document

        val title = document.selectFirst("h1, .cineapse-title, .detail-title")?.text()?.trim() ?: return null
        val poster = fixUrlNull(
            document.selectFirst("img.poster, img.detail-poster")?.attr("src")
                ?: document.selectFirst("meta[property='og:image']")?.attr("content")
        )
        val plot = document.selectFirst(".plot, .synopsis, p.overview, p.description")?.text()?.trim()
            ?: document.selectFirst("meta[name='description']")?.attr("content")

        val isMovie = url.contains("/detail/movie/") || url.contains("/movie/")
        val slug = url.substringAfterLast("/")

        return if (isMovie) {
            val watchUrl = "$mainUrl/watch/movie-$slug"
            newMovieLoadResponse(title, url, TvType.Movie, watchUrl) {
                this.posterUrl = poster
                this.plot = plot
            }
        } else {
            val episodes = mutableListOf<Episode>()
            val episodeElements = document.select(".episode-btn, .ep-list button, a[href*='-episode-'], a[href*='/watch/']")

            if (episodeElements.isNotEmpty()) {
                episodeElements.forEachIndexed { index, epElement ->
                    val href = epElement.attr("href")
                    val epNum = Regex("episode-(\\d+)").find(href)?.groupValues?.get(1)?.toIntOrNull()
                        ?: epElement.text().filter { it.isDigit() }.toIntOrNull()
                        ?: (index + 1)

                    val epUrl = if (href.isNotBlank() && href.contains("/watch/")) {
                        fixUrl(href)
                    } else {
                        "$mainUrl/watch/$slug-episode-$epNum"
                    }

                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
                        }
                    )
                }
            } else {
                val totalEpsText = document.selectFirst(".total-episodes")?.text() ?: "1"
                val totalEps = totalEpsText.filter { it.isDigit() }.toIntOrNull() ?: 1

                for (i in 1..totalEps) {
                    episodes.add(
                        newEpisode("$mainUrl/watch/$slug-episode-$i") {
                            this.name = "Episode $i"
                            this.episode = i
                        }
                    )
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes.distinctBy { it.episode }) {
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

        // FIX: Replaced .forEach with a standard Kotlin for loop
        for (serverBtn in document.select(".server-btn, button[data-server]")) {
            val serverUrl = serverBtn.attr("data-url")
            if (serverUrl.isNotBlank()) {
                UniversalExtractors.invoke(serverUrl, data, subtitleCallback, callback)
            }
        }

        // FIX: Replaced .forEach with a standard Kotlin for loop
        for (iframe in document.select("iframe")) {
            val src = iframe.attr("src")
            if (src.isNotBlank()) {
                UniversalExtractors.invoke(src, data, subtitleCallback, callback)
            }
        }

        return true
    }
