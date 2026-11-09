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
        val document = Jsoup.connect(mainUrl).get()
        val homePageList = mutableListOf<HomePageList>()

        val scriptData = document.select("script").html()
        
        // 1. Featured / Trending Items
        val heroItems = mutableListOf<SearchResponse>()
        val movieRegex = """"id":(\d+),"media_type":"(movie|tv)","title":"([^"]+)","poster_path":"([^"]+)"""".toRegex()
        
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

        // 2. Studios / Platforms List (Netflix, Prime, Apple TV, Crunchyroll, etc.)
        val studioList = listOf(
            Pair("Netflix", "$mainUrl/movies?provider=8"),
            Pair("Amazon Prime Video", "$mainUrl/movies?provider=9"),
            Pair("Apple TV", "$mainUrl/movies?provider=350"),
            Pair("Crunchyroll", "$mainUrl/movies?provider=283"),
            Pair("Disney Plus", "$mainUrl/movies?provider=337"),
            Pair("Hulu", "$mainUrl/movies?provider=15"),
            Pair("HBO Max", "$mainUrl/movies?provider=1899"),
            Pair("MGM Plus", "$mainUrl/movies?provider=34"),
            Pair("Paramount Plus", "$mainUrl/movies?provider=2303"),
            Pair("Peacock", "$mainUrl/movies?provider=387"),
            Pair("Shudder", "$mainUrl/movies?provider=99"),
            Pair("Discovery+", "$mainUrl/movies?provider=531")
        )

        val studioSearchResponses = studioList.map { (name, link) ->
            newMovieSearchResponse(name, link, TvType.Movie) {
                this.posterUrl = "https://via.placeholder.com/300x180/1a1a1a/ffffff?text=$name"
            }
        }
        homePageList.add(HomePageList("Studios & Platforms", studioSearchResponses))

        // 3. Popular Genres List
        val genresList = listOf(
            Pair("Comedy", "$mainUrl/movies?genre=35"),
            Pair("Action", "$mainUrl/movies?genre=28"),
            Pair("Drama", "$mainUrl/movies?genre=18"),
            Pair("Horror", "$mainUrl/movies?genre=27"),
            Pair("Romance", "$mainUrl/movies?genre=10749"),
            Pair("Adventure", "$mainUrl/movies?genre=12"),
            Pair("Science Fiction", "$mainUrl/movies?genre=878"),
            Pair("Thriller", "$mainUrl/movies?genre=53"),
            Pair("Animation", "$mainUrl/movies?genre=16"),
            Pair("Crime", "$mainUrl/movies?genre=80"),
            Pair("Fantasy", "$mainUrl/movies?genre=14"),
            Pair("Mystery", "$mainUrl/movies?genre=9648"),
            Pair("Documentary", "$mainUrl/movies?genre=99"),
            Pair("Family", "$mainUrl/movies?genre=10751"),
            Pair("History", "$mainUrl/movies?genre=36"),
            Pair("Music", "$mainUrl/movies?genre=10402"),
            Pair("War", "$mainUrl/movies?genre=10752"),
            Pair("Western", "$mainUrl/movies?genre=37")
        )

        val genreSearchResponses = genresList.map { (genreName, link) ->
            newMovieSearchResponse(genreName, link, TvType.Movie) {
                this.posterUrl = "https://via.placeholder.com/300x180/222222/ffffff?text=$genreName"
            }
        }
        homePageList.add(HomePageList("Popular Genres", genreSearchResponses))

        return newHomePageResponse(homePageList)
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = Jsoup.connect(searchUrl).get()
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
        val document = Jsoup.connect(url).get()
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
        val document = Jsoup.connect(data).get()
        val iframeSrc = document.select("iframe").attr("src")

        if (iframeSrc.isNotEmpty()) {
            val fullEmbedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            loadExtractor(fullEmbedUrl, data, subtitleCallback, callback)
            return true
        }
        
        return false
    }
}
