package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

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
            
            // 1. Trending / Featured Items from Next.js Script or DOM
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

            if (heroItems.isEmpty()) {
                document.select("a[href*=\"/watch/play/\"]").forEach { element ->
                    parseCard(element, heroItems)
                }
            }

            if (heroItems.isNotEmpty()) {
                homePageList.add(HomePageList("Trending Right Now", heroItems.distinctBy { it.url }))
            }

            // 2. Platforms / Studios Rows
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
                Pair("Peacock", "$mainUrl/platforms/387/movie")
            )

            for ((studioName, studioUrl) in studioList) {
                try {
                    val studioDoc = app.get(studioUrl).document
                    val studioItems = mutableListOf<SearchResponse>()

                    studioDoc.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"], a[href*=\"/watch/\"], a[href*=\"/platforms/\"]").forEach { element ->
                        parseCard(element, studioItems)
                    }

                    if (studioItems.isNotEmpty()) {
                        homePageList.add(HomePageList(studioName, studioItems.distinctBy { it.url }))
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            // 3. Popular Genres Rows
            val genresList = listOf(
                Pair("Comedy", "$mainUrl/movies?genre=35"),
                Pair("Action", "$mainUrl/movies?genre=28"),
                Pair("Horror", "$mainUrl/movies?genre=27"),
                Pair("Romance", "$mainUrl/movies?genre=10749"),
                Pair("Adventure", "$mainUrl/movies?genre=12"),
                Pair("Animation", "$mainUrl/movies?genre=16")
            )

            for ((genreName, genreUrl) in genresList) {
                try {
                    val genreDoc = app.get(genreUrl).document
                    val genreItems = mutableListOf<SearchResponse>()

                    genreDoc.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"], a[href*=\"/watch/\"]").forEach { element ->
                        parseCard(element, genreItems)
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
            document.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"], a[href*=\"/watch/\"]").forEach { element ->
                parseCard(element, items)
            }
            return newHomePageResponse(HomePageList(request.name, items.distinctBy { it.url }))
        }
    }

    private fun parseCard(element: Element, list: MutableList<SearchResponse>) {
        val href = element.attr("href")
        if (href.isNullOrBlank() || href == "/" || href.startsWith("#")) return

        val title = element.select("h3, h4, p, span").text()
            .ifEmpty { element.attr("title").ifEmpty { element.select("img").attr("alt") } }
        
        val poster = element.select("img").attr("src").let {
            if (it.startsWith("//")) "https:$it" else if (it.startsWith("/")) "https://pantyflix.com$it" else it
        }

        if (title.isNotBlank() && (href.contains("movie") || href.contains("tv") || href.contains("watch"))) {
            val isMovie = href.contains("movie") || href.contains("watch/play/movie")
            val mediaType = if (isMovie) TvType.Movie else TvType.TvSeries
            val fullUrl = if (href.startsWith("http")) href else "https://pantyflix.com$href"

            if (isMovie) {
                list.add(newMovieSearchResponse(title, fullUrl, mediaType) {
                    this.posterUrl = poster
                })
            } else {
                list.add(newTvSeriesSearchResponse(title, fullUrl, mediaType) {
                    this.posterUrl = poster
                })
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search?q=$query"
        val document = app.get(searchUrl).document
        val results = mutableListOf<SearchResponse>()

        document.select("a[href*=\"/movie/\"], a[href*=\"/tv/\"], a[href*=\"/watch/\"]").forEach { element ->
            parseCard(element, results)
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.select("meta[property=og:title]").attr("content")
            .ifEmpty { document.title() }
        val poster = document.select("meta[property=og:image]").attr("content")
            .ifEmpty { document.select("img").first()?.attr("src") ?: "" }
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

        if (!iframeSrc.isNullOrEmpty()) {
            val fullEmbedUrl = if (iframeSrc.startsWith("//")) "https:$iframeSrc" else iframeSrc
            loadExtractor(fullEmbedUrl, data, subtitleCallback, callback)
            return true
        }

        document.select("source, video").forEach { srcEl ->
            val src = srcEl.attr("src")
            if (src.isNotBlank()) {
                callback(
                    ExtractorLink(
                        name,
                        name,
                        src,
                        data,
                        Qualities.Unknown.value,
                        isM3u8 = src.contains(".m3u8")
                    )
                )
            }
        }
        
        return false
    }
}
