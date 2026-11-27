package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SkymoviesHD : MainAPI() {

    override var mainUrl = "https://new6.skymovieshd.clinic"
    override var name = "SkymoviesHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = false

    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Updated Movies",
        "$mainUrl/category/Bollywood-Movies.html" to "Bollywood Movies",
        "$mainUrl/category/South-Indian-Hindi-Dubbed-Movies.html" to "South Indian Hindi Dubbed Movies",
        "$mainUrl/category/Bengali-Movies.html" to "Bengali Movies",
        "$mainUrl/category/Pakistani-Movies.html" to "Pakistani Movies",
        "$mainUrl/category/Hollywood-English-Movies.html" to "Hollywood English Movies",
        "$mainUrl/category/Hollywood-Hindi-Dubbed-Movies.html" to "Hollywood Hindi Dubbed Movies",
        "$mainUrl/category/Tamil-Movies.html" to "Tamil Movies",
        "$mainUrl/category/Telugu-Movies.html" to "Telugu Movies",
        "$mainUrl/category/Punjabi-Movies.html" to "Punjabi Movies",
        "$mainUrl/category/Bhojpuri-Movies.html" to "Bhojpuri Movies",
        "$mainUrl/category/Bangladeshi-Movies.html" to "Bangladeshi Movies",
        "$mainUrl/category/Marathi-Movies.html" to "Marathi Movies",
        "$mainUrl/category/Kannada-Movies.html" to "Kannada Movies",
        "$mainUrl/category/WWE-TV-Shows.html" to "WWE TV Shows",
        "$mainUrl/category/TV-Serial-Episodes.html" to "TV Serial Episodes",
        "$mainUrl/category/Gujrati-Movies-.html" to "Gujrati Movies",
        "$mainUrl/category/Malayalam-Movies.html" to "Malayalam Movies",
        "$mainUrl/category/Korean-and-China-Movies.html" to "Korean and China Movies",
        "$mainUrl/category/Movies-Trailer.html" to "Movies Trailer",
        "$mainUrl/category/Hot-Short-Film.html" to "Short Films",
        "$mainUrl/category/All-Web-Series.html" to "All Web Series",
        "$mainUrl/category/Regional-Movies.html" to "Regional Movies"
    )

    // ---------------------------------
    // TITLE CLEANING
    // ---------------------------------

    private fun cleanTitle(title: String): String {
        return title
            .substringBefore(" 2160p")
            .substringBefore(" 1080p")
            .substringBefore(" 720p")
            .substringBefore(" 480p")
            .substringBefore(" 360p")
            .substringBefore(" HDRip")
            .substringBefore(" BluRay")
            .substringBefore(" WEB-DL")
            .substringBefore(" WEBRip")
            .substringBefore(" DVDRip")
            .substringBefore("||")
            .trim()
    }

    // ---------------------------------
    // SERIES DETECTION
    // ---------------------------------

    private fun isSeries(title: String): Boolean {
        return title.contains("Season", true) ||
            title.contains("Episode", true) ||
            title.contains("Web Series", true) ||
            title.contains("TV Series", true) ||
            Regex("""S\d{1,2}""", RegexOption.IGNORE_CASE)
                .containsMatchIn(title)
    }

    // ---------------------------------
    // POSTER EXTRACTION
    // ---------------------------------

    private fun Element.getPoster(): String? {
        val img = selectFirst("img") ?: return null

        val image = listOf(
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull {
            it.isNotBlank() &&
                !it.contains("icon.png", true) &&
                !it.startsWith("data:image")
        }

        return image?.let { fixUrl(it) }
    }

    // ---------------------------------
    // SEARCH RESULT
    // ---------------------------------

    private fun Element.toSearchResult(): SearchResponse? {

        val anchor = selectFirst("a[href*='movie/']")
            ?: selectFirst("a[href]") ?: return null

        val href = fixUrl(anchor.attr("href"))

        if (href.isBlank()) return null

        val rawTitle = anchor.text().trim()

        if (rawTitle.isBlank()) return null

        val title = cleanTitle(rawTitle)

        if (title.isBlank()) return null

        val poster = getPoster()

        // IMPORTANT:
        // SearchQuality? is not Int.
        // Use getQualityFromString directly.

        val quality = getQualityFromString(rawTitle)

        return if (isSeries(rawTitle)) {

            newTvSeriesSearchResponse(
                title,
                href,
                TvType.TvSeries
            ) {
                this.posterUrl = poster
                this.quality = quality
            }

        } else {

            newMovieSearchResponse(
                title,
                href,
                TvType.Movie
            ) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    // ---------------------------------
    // MAIN PAGE
    // ---------------------------------

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {

        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith(".html")) {
                request.data.replace(
                    ".html",
                    "/$page.html"
                )
            } else {
                "${request.data}?page=$page"
            }
        }

        val document = app.get(url).document

        val home = ArrayList<SearchResponse>()

        if (request.name == "Latest Updated Movies" && page == 1) {

            document.select("div.Let")
                .mapNotNull { it.toSearchResult() }
                .let { home.addAll(it) }
        }

        document.select("div.Fmvideo")
            .mapNotNull { it.toSearchResult() }
            .let { home.addAll(it) }

        val uniqueResults = home.distinctBy { it.url }

        return newHomePageResponse(
            request.name,
            uniqueResults
        )
    }

    // ---------------------------------
    // SEARCH
    // ---------------------------------

    override suspend fun search(
        query: String
    ): List<SearchResponse> {

        val encodedQuery = java.net.URLEncoder.encode(
            query,
            "UTF-8"
        )

        val searchUrl =
            "$mainUrl/search.php?search=$encodedQuery&cat=All"

        val document = app.get(searchUrl).document

        return document.select(
            "div.Fmvideo, div.Let"
        )
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    // ---------------------------------
    // LOAD DETAILS
    // ---------------------------------

    override suspend fun load(
        url: String
    ): LoadResponse? {

        val document = app.get(url).document

        val rawTitle = document.selectFirst("title")
            ?.text()
            ?.substringBefore("||")
            ?.trim()
            ?: return null

        val title = cleanTitle(rawTitle)

        val year = Regex("""\((\d{4})\)""")
            .find(rawTitle)
            ?.groupValues
            ?.getOrNull(1)
            ?.toIntOrNull()

        val poster = document.select("img")
            .mapNotNull { img ->

                val src = listOf(
                    img.attr("data-src"),
                    img.attr("data-original"),
                    img.attr("src")
                ).firstOrNull {
                    it.isNotBlank() &&
                        !it.startsWith("data:image") &&
                        (
                            it.contains("tmdb", true) ||
                            it.contains("amazon", true) ||
                            it.contains("blogger", true) ||
                            it.contains("bmscdn", true)
                        )
                }

                src?.let { fixUrl(it) }
            }
            .firstOrNull()

        val plot = document.select(
            "div.Let, div.Fmvideo, .description, .story, p"
        )
            .map { it.text().trim() }
            .firstOrNull {
                it.length > 100 &&
                    (
                        it.contains("Story", true) ||
                        it.contains("Plot", true) ||
                        it.length > 150
                    )
            }

        val series = isSeries(rawTitle)

        return if (series) {

            newTvSeriesLoadResponse(
                title,
                url,
                TvType.TvSeries,
                emptyList()
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }

        } else {

            newMovieLoadResponse(
                title,
                url,
                TvType.Movie,
                url
            ) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        }
    }

    // ---------------------------------
    // LINK LOADING
    // ---------------------------------

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {

        // Add only authorized video-source
        // integrations here.

        return false
    }
}

