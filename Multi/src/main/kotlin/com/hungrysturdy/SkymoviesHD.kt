package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class SkymoviesHD : MainAPI() {

    override var mainUrl = "https://new6.skymovieshd.clinic"
    override var name = "SkymoviesHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

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

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("""\s*\(\d{4}\).*"""), { m ->
                val yearPart = m.value.substringBefore(")") + ")"
                yearPart
            })
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

    private fun isSeries(title: String): Boolean {
        return title.contains("Season", true) ||
            title.contains("Episode", true) ||
            title.contains("Web Series", true) ||
            title.contains("TV Series", true) ||
            Regex("""S\d{1,2}""", RegexOption.IGNORE_CASE).containsMatchIn(title)
    }

    private fun isJunkImage(src: String): Boolean {
        val s = src.lowercase()
        return s.isBlank() ||
            s.startsWith("data:image") ||
            s.contains("icon.png") ||
            s.contains("arw.gif") ||
            s.contains("logo") ||
            s.contains("favicon") ||
            s.contains("snvhost") ||
            s.contains("banner") ||
            s.contains("ad") && (s.contains("adangle") || s.contains("vitalickitten") || s.contains("doubleclick"))
    }

    private fun Element.getPoster(): String? {
        val img = selectFirst("img") ?: return null
        val image = listOf(
            img.attr("data-src"),
            img.attr("data-original"),
            img.attr("data-lazy-src"),
            img.attr("src")
        ).firstOrNull { !isJunkImage(it) }
        return image?.let { fixUrl(it) }
    }

    private fun extractDetailPoster(document: Document): String? {
        val candidates = document.select("img").mapNotNull { img ->
            val src = listOf(
                img.attr("data-src"),
                img.attr("data-original"),
                img.attr("data-lazy-src"),
                img.attr("src")
            ).firstOrNull { !isJunkImage(it) }
            src?.let { fixUrl(it) }
        }

        // Prefer known movie poster hosts first, then any remaining valid image
        return candidates.firstOrNull { u ->
            u.contains("tmdb", true) ||
                u.contains("imdb", true) ||
                u.contains("amazon", true) ||
                u.contains("blogger", true) ||
                u.contains("googleusercontent", true) ||
                u.contains("bmscdn", true) ||
                u.contains("catimages", true) ||
                u.contains("postimg", true) ||
                u.contains("imgbb", true)
        } ?: candidates.firstOrNull()
    }

    private suspend fun fetchPosterFromDetail(movieUrl: String): String? {
        return try {
            val doc = app.get(movieUrl, timeout = 10).document
            extractDetailPoster(doc)
        } catch (_: Exception) {
            null
        }
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = selectFirst("a[href*='/movie/'], a[href^='movie/']") ?: return null
        val rawHref = anchor.attr("href").trim()

        if (rawHref.isBlank() ||
            rawHref.endsWith("movie/.html") ||
            rawHref.contains("skybap", true)
        ) return null

        val href = fixUrl(rawHref)
        val rawTitle = anchor.text().trim()
        if (rawTitle.isBlank() || rawTitle.contains("BookMark", true)) return null

        val title = cleanTitle(rawTitle)
        if (title.isBlank()) return null

        val poster = getPoster()
        val quality = getQualityFromString(rawTitle)

        return if (isSeries(rawTitle)) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = poster
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = poster
                this.quality = quality
            }
        }
    }

    override suspend fun getMainPage(
        page: Int,
        request: MainPageRequest
    ): HomePageResponse {
        val url = if (page == 1) {
            request.data
        } else {
            if (request.data.endsWith(".html")) {
                request.data.replace(".html", "/$page.html")
            } else {
                "${request.data}?page=$page"
            }
        }

        val document = app.get(url).document
        val rawList = ArrayList<SearchResponse>()

        document.select("div.Let, div.Fmvideo, div.Bolly").forEach { element ->
            element.toSearchResult()?.let { rawList.add(it) }
        }

        if (rawList.isEmpty()) {
            document.select("a[href*='/movie/'], a[href^='movie/']").forEach { a ->
                a.parent()?.toSearchResult()?.let { rawList.add(it) }
            }
        }

        // Remove duplicate quality cards for the same title
        val uniqueResults = rawList.distinctBy { it.name.lowercase() }

        // Fetch posters for items missing a poster (like category pages)
        val withPosters = uniqueResults.amap { item ->
            if (item.posterUrl == null) {
                val poster = fetchPosterFromDetail(item.url)
                when (item) {
                    is MovieSearchResponse -> item.copy(posterUrl = poster)
                    is TvSeriesSearchResponse -> item.copy(posterUrl = poster)
                    else -> item
                }
            } else {
                item
            }
        }

        return newHomePageResponse(
            request.name,
            withPosters
        )
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = java.net.URLEncoder.encode(query, "UTF-8")
        val searchUrl = "$mainUrl/search.php?search=$encodedQuery&cat=All"
        val document = app.get(searchUrl).document

        val uniqueResults = document.select("div.Fmvideo, div.Let, div.Bolly")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.name.lowercase() }

        return uniqueResults.amap { item ->
            if (item.posterUrl == null) {
                val poster = fetchPosterFromDetail(item.url)
                when (item) {
                    is MovieSearchResponse -> item.copy(posterUrl = poster)
                    is TvSeriesSearchResponse -> item.copy(posterUrl = poster)
                    else -> item
                }
            } else {
                item
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
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

        val poster = extractDetailPoster(document)

        // Ignore category list / navigation / copyright blocks in plot
        val plot = document.select("div.Let, div.Fmvideo, .description, .story, p")
            .filter { el -> el.select("select, option, form").isEmpty() }
            .map { it.text().trim() }
            .firstOrNull { text ->
                text.length > 60 &&
                    !text.contains("BookMark", true) &&
                    !text.contains("Bollywood Movies South Indian", true) &&
                    !text.contains("WWE TV Shows TV Serial", true) &&
                    !text.contains("Pc HD 1080p 720p 480p Movies Download", true) &&
                    !text.contains("All Rights Reserved", true)
            }

        val linkElements = document.select("a[href]").filter { a ->
            val href = a.attr("href")
            href.contains("howblogs", true) ||
                href.contains("drivehub", true) ||
                href.contains("hubdrive", true) ||
                href.contains("hubcloud", true)
        }

        val series = isSeries(rawTitle) ||
            linkElements.any { Regex("""(Ep|Episode)\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it.text()) }

        return if (series) {
            val episodesMap = LinkedHashMap<Int, MutableList<Pair<String, String>>>()
            linkElements.forEachIndexed { index, a ->
                val text = a.text().trim()
                val epNum = Regex("""(?:Ep|Episode|E)\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(text)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: (index + 1)
                val href = fixUrl(a.attr("href"))
                episodesMap.getOrPut(epNum) { mutableListOf() }.add(text to href)
            }

            val episodes = episodesMap.map { (epNum, epLinks) ->
                val epName = epLinks.firstOrNull()?.first?.takeIf { it.isNotBlank() } ?: "Episode $epNum"
                val dataPayload = epLinks.joinToString(",") { "${it.second}|${it.first}" }
                newEpisode(dataPayload) {
                    this.name = epName
                    this.episode = epNum
                }
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            val dataPayload = linkElements.distinctBy { it.attr("href") }
                .joinToString(",") { "${fixUrl(it.attr("href"))}|${it.text().trim()}" }

            newMovieLoadResponse(title, url, TvType.Movie, dataPayload) {
                this.posterUrl = poster
                this.year = year
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
        val entries = data.split(",").filter { it.isNotBlank() }

        entries.forEach { entry ->
            val parts = entry.split("|", limit = 2)
            val link = parts[0].trim()
            val labelHint = parts.getOrElse(1) { "" }

            when {
                link.contains("howblogs", true) -> {
                    val doc = app.get(link).document
                    doc.select("a[href]").mapNotNull { it.attr("href") }
                        .filter {
                            it.contains("drivehub", true) ||
                                it.contains("hubdrive", true) ||
                                it.contains("hubcloud", true)
                        }
                        .distinct()
                        .forEach { targetUrl ->
                            resolveIntermediateHost(targetUrl, link, labelHint, subtitleCallback, callback)
                        }
                }
                else -> {
                    resolveIntermediateHost(link, mainUrl, labelHint, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private suspend fun resolveIntermediateHost(
        url: String,
        referer: String,
        labelHint: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        when {
            url.contains("drivehub", true) || url.contains("hubdrive", true) -> {
                val doc = app.get(url, referer = referer).document
                val fileTitle = doc.selectFirst("h6.text-primary, title")?.text() ?: labelHint
                val hubCloudUrl = doc.selectFirst("a[href*='hubcloud']")?.attr("href")
                if (!hubCloudUrl.isNullOrBlank()) {
                    extractHubCloud(hubCloudUrl, url, fileTitle, subtitleCallback, callback)
                }
            }
            url.contains("hubcloud", true) -> {
                extractHubCloud(url, referer, labelHint, subtitleCallback, callback)
            }
            else -> {
                loadExtractor(url, referer, subtitleCallback, callback)
            }
        }
    }

    private suspend fun extractHubCloud(
        hubCloudUrl: String,
        referer: String,
        labelHint: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val doc1 = app.get(hubCloudUrl, referer = referer).document
        val fileName = doc1.selectFirst("div.card-header, title")?.text()?.trim() ?: labelHint
        val detectedQuality = getQualityFromName("$fileName $labelHint")

        val generatedUrl = doc1.selectFirst("a#download")?.attr("href") ?: return
        val doc2 = app.get(generatedUrl, referer = hubCloudUrl).document

        doc2.selectFirst("a#fsl")?.attr("href")?.let { fslLink ->
            if (fslLink.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - FSL Server",
                        url = fslLink,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = generatedUrl
                        this.quality = detectedQuality
                    }
                )
            }
        }

        doc2.selectFirst("a#pxl-1, a[href*='pixeldrain']")?.attr("href")?.let { pxlUrl ->
            val fileId = pxlUrl.substringAfterLast("/").substringBefore("?")
            if (fileId.isNotBlank()) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - PixelDrain",
                        url = "https://pixeldrain.com/api/file/$fileId",
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.referer = pxlUrl
                        this.quality = detectedQuality
                    }
                )
            }
        }

        doc2.select("a[href*='pixel.hubcloud'], a[href*='fuckingfast']").forEach { a ->
            val serverUrl = a.attr("href")
            if (serverUrl.isNotBlank()) {
                loadExtractor(serverUrl, generatedUrl, subtitleCallback, callback)
            }
        }
    }

    private fun getQualityFromName(str: String): Int {
        return when {
            str.contains("2160p", true) || str.contains("4k", true) -> Qualities.P2160.value
            str.contains("1440p", true) || str.contains("2k", true) -> Qualities.P1440.value
            str.contains("1080p", true) -> Qualities.P1080.value
            str.contains("720p", true) -> Qualities.P720.value
            str.contains("480p", true) -> Qualities.P480.value
            str.contains("360p", true) -> Qualities.P360.value
            else -> Qualities.P1080.value
        }
    }
}

