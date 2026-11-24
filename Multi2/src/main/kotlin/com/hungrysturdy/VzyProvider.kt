package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class VzyProvider : MainAPI() {
    override var mainUrl = "https://www.vzy.one"
    override var name = "Vzy"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Movie,
        TvType.TvSeries,
        TvType.Live
    )

    override val mainPage = mainPageOf(
        "$mainUrl/en" to "Home & Featured",
        "$mainUrl/en/new-releases/list/365803?widgetType=KTM&wid=6a7da2196d174833334f3e72&playlistId=365803&railname=New+Releases&ratio=2x3&railCardSize=LARGE&railItems=30&partnerCapValue=0" to "New Releases",
        "$mainUrl/en/live-matches/list/345378?widgetType=KTM&wid=6905f904bcb3af89e09a3479&playlistId=345378&railname=%F0%9F%94%B4+Live+Matches&ratio=16x9&railCardSize=LARGE&railItems=10&partnerCapValue=0&metaDemographic=Language" to "Live Matches",
        "$mainUrl/en/free" to "Free Content",
        "$mainUrl/en/livetv" to "Live TV"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val separator = if (request.data.contains("?")) "&" else "?"
        val url = if (page == 1) request.data else "${request.data}${separator}page=$page"
        val document = app.get(url, referer = "$mainUrl/").document
        val homePageList = mutableListOf<HomePageList>()

        if (request.data == "$mainUrl/en" || request.data == "$mainUrl/en/livetv") {
            val heroItems = document.select("div.ssr-hero-carousel__slide a.hero-image-link").mapNotNull {
                it.toSearchResult()
            }
            if (heroItems.isNotEmpty()) {
                homePageList.add(HomePageList("Featured", heroItems, isHorizontalImages = false))
            }

            document.select("div.sliderMainRow").forEach { row ->
                val rowTitle = row.selectFirst("a.row-header-title, h2.row-header-title, .row-header")?.text()?.trim()
                    ?: return@forEach
                if (rowTitle.contains("Browse By OTT", ignoreCase = true)) return@forEach

                val isHorizontal = row.selectFirst("div.ratio-16x9") != null
                val items = row.select("div.slider-item a.rail-card-main-wrapper, div.slider-item a[href*='/movies/'], div.slider-item a[href*='/series/'], div.slider-item a[href*='/live/']")
                    .distinctBy { it.attr("href") }
                    .mapNotNull { it.toSearchResult() }

                if (items.isNotEmpty()) {
                    homePageList.add(HomePageList(rowTitle, items, isHorizontalImages = isHorizontal))
                }
            }
        } else {
            val items = document.select("a.rail-card-main-wrapper, a.hero-image-link, a[href*='/movies/'], a[href*='/series/'], a[href*='/live/']")
                .distinctBy { it.attr("href") }
                .mapNotNull { it.toSearchResult() }
            if (items.isNotEmpty()) {
                homePageList.add(HomePageList(request.name, items))
            }
        }

        return newHomePageResponse(homePageList, hasNext = false)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val rawHref = this.attr("href").trim()
        if (rawHref.isBlank() || rawHref.contains("/ott/")) return null
        val href = fixUrl(rawHref)

        val img = this.selectFirst("img.img-display, img.boxart-image, img")
        val title = this.selectFirst("h4.rail-card-title, .rail-card-title")?.text()?.trim()
            ?: this.attr("aria-label").removePrefix("View ").trim().takeIf { it.isNotBlank() }
            ?: img?.attr("alt")?.substringBefore(" Hindi")?.substringBefore(" Tamil")?.trim().takeIf { !it.isNullOrBlank() }
            ?: return null

        val posterUrl = fixUrlNull(img?.attr("src"))

        val type = when {
            href.contains("/live/") -> TvType.Live
            href.contains("/series/") -> TvType.TvSeries
            else -> TvType.Movie
        }

        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/en/search?q=${query.replace(" ", "%20")}"
        val document = app.get(searchUrl, referer = "$mainUrl/").document

        return document.select("a.rail-card-main-wrapper, a[href*='/movies/'], a[href*='/series/'], a[href*='/live/']")
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }
    }

    private fun parseTagValues(tagsObj: JSONObject?, key: String): List<String> {
        val arr = tagsObj?.optJSONObject(key)?.optJSONArray("objects") ?: return emptyList()
        return buildList {
            for (i in 0 until arr.length()) {
                val v = arr.optJSONObject(i)?.optString("value")?.trim().orEmpty()
                if (v.isNotEmpty() && v != "ㅤㅤ") add(v)
            }
        }
    }

    private fun extractAssetIdFromUrl(url: String): String? {
        return Regex("""/(\d{5,})(?:[/?#]|$)""").find(url)?.groupValues?.getOrNull(1)
    }

    private fun humanizeSlugTitle(url: String): String? {
        val segment = url.substringBefore("?").substringBefore("#").trimEnd('/')
            .substringAfterLast("/")
        if (segment.isBlank() || segment.all { it.isDigit() }) {
            val parts = url.substringBefore("?").trimEnd('/').split("/")
            val slug = parts.getOrNull(parts.size - 2) ?: return null
            if (slug.all { it.isDigit() }) return null
            return slug.split("-").joinToString(" ") { word ->
                word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
            }
        }
        return segment.split("-").joinToString(" ") { word ->
            word.replaceFirstChar { if (it.isLowerCase()) it.titlecase() else it.toString() }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val normalizedUrl = fixUrl(url)
        val pageText = app.get(normalizedUrl, referer = "$mainUrl/").text
        val document = Jsoup.parse(pageText)
        val assetId = extractAssetIdFromUrl(normalizedUrl)
        val assetJson = extractKalturaAssetJson(pageText, assetId)

        val title = assetJson?.optString("name")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("h1, meta[property=og:title]")?.let {
                if (it.tagName() == "meta") it.attr("content") else it.text()
            }?.trim()?.takeIf { it.isNotBlank() }
            ?: humanizeSlugTitle(normalizedUrl)
            ?: return null

        val metas = assetJson?.optJSONObject("metas")
        val tags = assetJson?.optJSONObject("tags")

        val poster = assetJson?.optJSONArray("images")?.let { imagesArr ->
            var fallbackPoster: String? = null
            for (i in 0 until imagesArr.length()) {
                val imgObj = imagesArr.optJSONObject(i) ?: continue
                val ratio = imgObj.optString("ratio")
                val imgUrl = imgObj.optString("url")
                if (imgUrl.isNotBlank()) {
                    if (ratio == "2x3") return@let imgUrl
                    if (fallbackPoster == null) fallbackPoster = imgUrl
                }
            }
            fallbackPoster
        } ?: fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("img.img-display, img.boxart-image")?.attr("src")
        )

        val plot = metas?.optJSONObject("LongSummary")?.optString("value")?.takeIf { it.isNotBlank() }
            ?: metas?.optJSONObject("ShortSummary")?.optString("value")?.takeIf { it.isNotBlank() }
            ?: assetJson?.optString("description")?.takeIf { it.isNotBlank() }
            ?: document.selectFirst("meta[name=description], meta[property=og:description]")
                ?.attr("content")?.trim()

        val year = metas?.optJSONObject("Year")?.optInt("value")?.takeIf { it > 0 }
        val genres = parseTagValues(tags, "Genre")

        val recommendations = document.select("a.rail-card-main-wrapper, a[href*='/movies/'], a[href*='/series/']")
            .filter { fixUrl(it.attr("href")) != normalizedUrl }
            .distinctBy { it.attr("href") }
            .mapNotNull { it.toSearchResult() }

        val isSeries = normalizedUrl.contains("/series/") || metas?.has("SeriesID") == true
        val isLive = normalizedUrl.contains("/live/")

        if (isSeries) {
            val episodes = document.select("a[href*='/episode/'], div.episode-card a").mapIndexedNotNull { index, ep ->
                val epHref = fixUrl(ep.attr("href"))
                val epName = ep.text().trim().ifEmpty { "Episode ${index + 1}" }
                newEpisode(epHref) {
                    this.name = epName
                    this.episode = index + 1
                }
            }

            return newTvSeriesLoadResponse(title, normalizedUrl, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.year = year
                this.tags = genres
                this.recommendations = recommendations
            }
        }

        val type = if (isLive) TvType.Live else TvType.Movie
        return newMovieLoadResponse(title, normalizedUrl, type, normalizedUrl) {
            this.posterUrl = poster
            this.plot = plot
            this.year = year
            this.tags = genres
            this.recommendations = recommendations
        }
    }

    private fun extractKalturaAssetJson(text: String, preferredAssetId: String? = null): JSONObject? {
        val marker = "\"objectType\":\"KalturaMediaAsset\""
        var searchFrom = 0
        var firstMatch: JSONObject? = null

        while (true) {
            val idx = text.indexOf(marker, searchFrom)
            if (idx == -1) break

            var start = idx
            while (start >= 0 && text[start] != '{') start--
            if (start < 0) {
                searchFrom = idx + marker.length
                continue
            }

            var depth = 0
            var inString = false
            var escaped = false
            var endIdx = -1

            for (i in start until text.length) {
                val c = text[i]
                if (escaped) {
                    escaped = false
                    continue
                }
                if (c == '\\' && inString) {
                    escaped = true
                    continue
                }
                if (c == '"') {
                    inString = !inString
                    continue
                }
                if (!inString) {
                    if (c == '{') depth++
                    else if (c == '}') {
                        depth--
                        if (depth == 0) {
                            endIdx = i + 1
                            break
                        }
                    }
                }
            }

            if (endIdx != -1) {
                val obj = runCatching { JSONObject(text.substring(start, endIdx)) }.getOrNull()
                if (obj != null) {
                    if (firstMatch == null) firstMatch = obj
                    if (preferredAssetId != null && obj.optString("id") == preferredAssetId) {
                        return obj
                    }
                }
                searchFrom = endIdx
            } else {
                break
            }
        }

        return firstMatch
    }

    private fun guessQuality(url: String): Int {
        return when {
            url.contains("2160", ignoreCase = true) || url.contains("4k", ignoreCase = true) -> Qualities.P2160.value
            url.contains("1080", ignoreCase = true) -> Qualities.P1080.value
            url.contains("720", ignoreCase = true) -> Qualities.P720.value
            url.contains("480", ignoreCase = true) -> Qualities.P480.value
            url.contains("360", ignoreCase = true) -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetUrl = fixUrl(data)
        val responseText = app.get(targetUrl, referer = "$mainUrl/").text
        var emitted = false

        val doc = Jsoup.parse(responseText)
        val watchBtnAttrUrls = doc.select("button.watch-now-btn, [class*='watch-now']").flatMap { el ->
            el.attributes().mapNotNull { attr ->
                val v = attr.value
                if (v.startsWith("http") && (v.contains(".m3u8") || v.contains(".mpd") || v.contains("/watch") || v.contains("/play"))) v else null
            }
        }

        val cleanedText = (responseText + "\n" + watchBtnAttrUrls.joinToString("\n")).replace("\\/", "/")
        val streamRegex = Regex("""(https?://[^\s"'<>\\]+\.(?:m3u8|mpd)(?:\?[^\s"'<>\\]*)?)""")

        val foundLinks = streamRegex.findAll(cleanedText)
            .map { it.groupValues[1] }
            .filterNot { it.contains("hbomax.com", ignoreCase = true) }
            .distinct()
            .toList()

        foundLinks.forEach { streamUrl ->
            val isM3u8 = streamUrl.contains(".m3u8", ignoreCase = true)
            val labelSuffix = if (streamUrl.contains("/trailer/", ignoreCase = true)) "Trailer" else "Stream"
            callback.invoke(
                newExtractorLink(
                    source = this.name,
                    name = "${this.name} $labelSuffix",
                    url = streamUrl,
                    type = if (isM3u8) ExtractorLinkType.M3U8 else ExtractorLinkType.DASH
                ) {
                    this.quality = guessQuality(streamUrl)
                    this.referer = "$mainUrl/"
                    this.headers = mapOf(
                        "Referer" to "$mainUrl/",
                        "Origin" to mainUrl
                    )
                }
            )
            emitted = true
        }

        return emitted
    }
}
