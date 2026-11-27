package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class SkymoviesHD : MainAPI() {
    override var mainUrl = "https://new6.skymovieshd.clinic"
    override var name = "SkymoviesHD"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
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
        "$mainUrl/category/Hot-Short-Film.html" to "Hot Short Film",
        "$mainUrl/category/All-Web-Series.html" to "All Web Series",
        "$mainUrl/category/Regional-Movies.html" to "Regional Movies",
    )

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
        val home = ArrayList<SearchResponse>()

        if (request.name == "Latest Updated Movies" && page == 1) {
            val popular = document.select("div.Let").mapNotNull { it.toSearchResult() }
            home.addAll(popular)
        }

        val items = document.select("div.Fmvideo").mapNotNull { it.toSearchResult() }
        home.addAll(items)

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val anchor = this.selectFirst("a[href*='movie/']") ?: return null
        val href = fixUrl(anchor.attr("href"))
        if (href.endsWith("movie/.html")) return null

        val rawTitle = anchor.text().trim()
        if (rawTitle.isEmpty()) return null

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true) ||
                rawTitle.contains("Web Series", ignoreCase = true) ||
                Regex("""S\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle)

        val title = rawTitle.substringBefore(" 720p")
            .substringBefore(" 480p")
            .substringBefore(" 1080p")
            .substringBefore(" 2160p")
            .substringBefore(" HDRip")
            .substringBefore(" BluRay")
            .trim()

        val imgSrc = anchor.selectFirst("img")?.attr("src")
        val posterUrl = if (imgSrc != null && !imgSrc.contains("icon.png")) {
            fixUrl(imgSrc)
        } else {
            null
        }

        val quality = getQualityFromString(rawTitle)

        return if (isSeries) {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        } else {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.quality = quality
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/search.php?search=${query.replace(" ", "+")}&cat=All"
        val document = app.get(searchUrl).document

        return document.select("div.Fmvideo, div.Let").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("title")?.text()
            ?.substringBefore("||")
            ?.trim() ?: return null

        val year = Regex("""\((\d{4})\)""").find(rawTitle)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val cleanTitle = rawTitle.substringBefore(" 720p")
            .substringBefore(" 480p")
            .substringBefore(" 1080p")
            .substringBefore(" HDRip")
            .substringBefore(" BluRay")
            .trim()

        val poster = document.select("img").firstOrNull { img ->
            val src = img.attr("src")
            src.contains("tmdb") || src.contains("amazon") || src.contains("blogger") || src.contains("bmscdn")
        }?.attr("src")

        val plot = document.select("div.Let, div.Fmvideo, p").firstOrNull {
            val t = it.text()
            t.contains("Story", ignoreCase = true) || t.contains("Plot", ignoreCase = true) || t.length > 150
        }?.text()?.trim()

        val linkElements = document.select("a[href]").filter { a ->
            val href = a.attr("href")
            href.contains("howblogs") ||
            href.contains("drivehub") ||
            href.contains("hubdrive") ||
            href.contains("hubcloud")
        }

        val isSeries = rawTitle.contains("Season", ignoreCase = true) ||
                rawTitle.contains("Episode", ignoreCase = true) ||
                rawTitle.contains("Web Series", ignoreCase = true) ||
                Regex("""S\d{2}""", RegexOption.IGNORE_CASE).containsMatchIn(rawTitle) ||
                linkElements.any { Regex("""(Ep|Episode)\s*\d+""", RegexOption.IGNORE_CASE).containsMatchIn(it.text()) }

        return if (isSeries) {
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

            newTvSeriesLoadResponse(cleanTitle, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.year = year
                this.plot = plot
            }
        } else {
            val dataPayload = linkElements.distinctBy { it.attr("href") }
                .joinToString(",") { "${fixUrl(it.attr("href"))}|${it.text().trim()}" }

            newMovieLoadResponse(cleanTitle, url, TvType.Movie, dataPayload) {
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
                link.contains("howblogs") -> {
                    val doc = app.get(link).document
                    doc.select("a[href]").mapNotNull { it.attr("href") }
                        .filter {
                            it.contains("drivehub") ||
                            it.contains("hubdrive") ||
                            it.contains("hubcloud")
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
            url.contains("drivehub") || url.contains("hubdrive") -> {
                val doc = app.get(url, referer = referer).document
                val fileTitle = doc.selectFirst("h6.text-primary, title")?.text() ?: labelHint
                val hubCloudUrl = doc.selectFirst("a[href*='hubcloud']")?.attr("href")
                if (!hubCloudUrl.isNullOrBlank()) {
                    extractHubCloud(hubCloudUrl, url, fileTitle, subtitleCallback, callback)
                }
            }
            url.contains("hubcloud") -> {
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

        val detectedQuality: Int = getIndexQuality("$fileName $labelHint").takeIf { it != Qualities.Unknown.value }
            ?: Qualities.P1080.value

        val generatedUrl = doc1.selectFirst("a#download")?.attr("href") ?: return
        val doc2 = app.get(generatedUrl, referer = hubCloudUrl).document

        // 1. Direct Cloudflare R2 Storage link (FSL Server)
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

        // 2. Pixeldrain Server (/u/{id} -> /api/file/{id})
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

        // 3. 10Gbps Server & Buzz Server
        doc2.select("a[href*='pixel.hubcloud'], a[href*='fuckingfast']").forEach { a ->
            val serverUrl = a.attr("href")
            if (serverUrl.isNotBlank()) {
                loadExtractor(serverUrl, generatedUrl, subtitleCallback, callback)
            }
        }
    }
}

