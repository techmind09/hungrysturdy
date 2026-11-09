package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class PantyflixProvider : MainAPI() {
    override var mainUrl = "https://pantyflix.com"
    override var name = "Pantyflix"
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Anime
    )
    override var lang = "en"
    override val hasMainPage = true

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = request.data.ifEmpty { mainUrl }
        val document = app.get(url).document

        if (request.data.isEmpty()) {
            val homePageList = mutableListOf<HomePageList>()
            val scriptData = document.select("script").html()
            
            // 1. Trending / Featured Items
            val heroItems = mutableListOf<SearchResponse>()
            val movieRegex = """"id":\s*(\d+),\s*"media_type":\s*"(movie|tv)",\s*"title":\s*"([^"]+)",\s*"poster_path":\s*"([^"]+)"""".toRegex()
            
            movieRegex.findAll(scriptData).forEach { matchResult ->
                val (id, type, title, posterPath) = matchResult.destructured
                val fullPoster = "https://image.tmdb.org/t/p/w500$posterPath"
                
                if (type == "movie") {
                    heroItems.add(newMovieSearchResponse(title, "$mainUrl/watch/play/movie/$id", TvType.Movie) {
                        this.posterUrl = fullPoster
                    })
                } else {
                    heroItems.add(newTvSeriesSearchResponse(title, "$mainUrl/watch/play/tv/$id", TvType.TvSeries) {
                        this.posterUrl = fullPoster
                    })
                }
            }

            if (heroItems.isNotEmpty()) {
                homePageList.add(HomePageList("Trending Right Now", heroItems.distinctBy { it.url }))
            }

            // 2. Platforms / Studios List with their individual rows
            val studioList = listOf(
                Pair("Netflix", "$mainUrl/platforms/8/movie"),
                Pair("Amazon Prime Video", "$mainUrl/platforms/9/movie"),
                Pair("Apple TV", "$mainUrl/platforms/350/movie"),
                Pair("Crunchyroll", "$mainUrl/platforms/283/movie"),
                Pair("Disney Plus", "$mainUrl/platforms/337/movie"),
                Pair("Hulu", "$mainUrl/platforms/15/movie"),
                Pair("HBO Max", "$mainUrl/platforms/1899/movie"),
                Pair("MGM Plus", "$mainUrl/platforms/34/movie"),
                Pair("Paramount Plus", "$mainUrl/platforms/2303/movie"),
                Pair("Peacock", "$mainUrl/platforms/387/movie"),
                Pair("Shudder", "$mainUrl/platforms/99/movie"),
                Pair("Discovery+", "$mainUrl/platforms/531/movie")
            )

            // Har platform ke liye alag row fetch karna
            for ((studioName, studioUrl) in studioList) {
                try {
                    val studioDoc = app.get(studioUrl).document
                    val studioItems = mutableListOf<SearchResponse>()

                    studioDoc.select("a[href*=/movie/], a[href*=/tv/], a[href*=/watch/]").forEach { element ->
                        val href = element.attr("href")
                        val title = element.text().ifEmpty { element.select("span, p, img").attr("alt") }
                        val poster = element.select("img").attr("src")

                        if (title.isNotBlank()) {
                            val isMovie = href.contains("movie")
                            val mediaType = if (isMovie) TvType.Movie else TvType.TvSeries
                            val fullUrl = if (href.startsWith("http")) href else mainUrl + href
                            
                            studioItems.add(
                                if (isMovie) {
                                    newMovieSearchResponse(title, fullUrl, mediaType) {
                                        this.posterUrl = poster
                                    }
                                } else {
                                    newTvSeriesSearchResponse(title, fullUrl, mediaType) {
                                        this.posterUrl = poster
                                    }
                                }
                            )
                        }
                    }

                    if (studioItems.isNotEmpty()) {
                        homePageList.add(HomePageList(studioName, studioItems.distinctBy { it.url }))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Popular Genres List
            val genresList = listOf(
                Pair("Comedy", "$mainUrl/movies?genre=35"),
                Pair("Action", "$mainUrl/movies?genre=28"),
                Pair("Drama", "$mainUrl/movies?genre=18"),
                Pair("Horror", "$mainUrl/movies?genre=27"),
                Pair("Romance", "$mainUrl/movies?genre=10749"),
                Pair("Adventure", "$mainUrl/movies?genre=12")
            )

            for ((genreName, genreUrl) in genresList) {
                try {
                    val genreDoc = app.get(genreUrl).document
                    val genreItems = mutableListOf<SearchResponse>()

                    genreDoc.select("a[href*=/movie/], a[href*=/tv/], a[href*=/watch/]").forEach { element ->
                        val href = element.attr("href")
                        val title = element.text().ifEmpty { element.select("span, p, img").attr("alt") }
                        val poster = element.select("img").attr("src")

                        if (title.isNotBlank()) {
                            val isMovie = href.contains("movie")
                            val mediaType = if (isMovie) TvType.Movie else TvType.TvSeries
                            val fullUrl = if (href.startsWith("http")) href else mainUrl + href
                            
                            genreItems.add(
                                if (isMovie) {
                                    newMovieSearchResponse(title, fullUrl, mediaType) {
                                        this.posterUrl = poster
                                    }
                                } else {
                                    newTvSeriesSearchResponse(title, fullUrl, mediaType) {
                                        this.posterUrl = poster
                                    }
                                }
                            )
                        }
                    }

                    if (genreItems.isNotEmpty()) {
                        homePageList.add(HomePageList("Genre: $genreName", genreItems.distinctBy { it.url }))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            return newHomePageResponse(homePageList)
        } else {
            val items = mutableListOf<SearchResponse>()
            
            document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/watch/]").forEach { element ->
                val href = element.attr("href")
                val title = element.text().ifEmpty { element.select("span, p, img").attr("alt") }
                val poster = element.select("img").attr("src")

                if (title.isNotBlank()) {
                    val isMovie = href.contains("movie")
                    val mediaType = if (isMovie) TvType.Movie else TvType.TvSeries
                    val fullUrl = if (href.startsWith("http")) href else mainUrl + href
                    
                    items.add(
                        if (isMovie) {
                            newMovieSearchResponse(title, fullUrl, mediaType) {
                                this.posterUrl = poster
                            }
                        } else {
                            newTvSeriesSearchResponse(title, fullUrl, mediaType) {
                                this.posterUrl = poster
                            }
                        }
                    )
                }
            }

            return newHomePageResponse(HomePageList(request.name, items.distinctBy { it.url }))
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        val results = mutableListOf<SearchResponse>()

        document.select("a[href*=/movie/], a[href*=/tv/], a[href*=/watch/]").forEach { element ->
            val href = element.attr("href")
            val title = element.text().ifEmpty { element.select("span, p, img").attr("alt") }
            val poster = element.select("img").attr("src")

            if (title.isNotBlank()) {
                val isMovie = href.contains("movie")
                val mediaType = if (isMovie) TvType.Movie else TvType.TvSeries
                val fullUrl = if (href.startsWith("http")) href else mainUrl + href
                
                results.add(
                    if (isMovie) {
                        newMovieSearchResponse(title, fullUrl, mediaType) {
                            this.posterUrl = poster
                        }
                    } else {
                        newTvSeriesSearchResponse(title, fullUrl, mediaType) {
                            this.posterUrl = poster
                        }
                    }
                )
            }
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("meta[property=og:title]").attr("content")
            .ifEmpty { document.title() }
        val poster = document.select("meta[property=og:image]").attr("content")
        val description = document.select("meta[name=description]").attr("content")

        val isMovie = url.contains("movie")

        return if (isMovie) {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.TvSeries, emptyList()) {
                this.posterUrl = poster
                this.plot = description
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
        val iframeSrc = document.select("iframe").attr("src")

        if (iframeSrc.isNotEmpty()) {
            val fullEmbedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            loadExtractor(fullEmbedUrl, data, subtitleCallback, callback)
            return true
        }
        
        return false
    }
}
