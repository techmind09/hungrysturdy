package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class KatDramaProvider : MainAPI() {
    override var mainUrl = "https://katdrama.org"
    override var name = "KatDrama"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true

    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    private val cfKiller = CloudflareKiller()

    private val cookieHeader = "_ga=GA1.1.878454062.1794569904; pp_main_566600af372321e7774c451466cdc753=1; vrk4n8fqhwc3jzy7pbsmgt6dx5lha2u9=01a1b98c-7aec-7761-b581-b09b7730a724_2; dom3ic8zudi28v8lr6fgphwffqoz0j6c=01a1b98c-7aec-7761-b581-b09b7730a724; pp_sub_566600af372321e7774c451466cdc753=2; _ga_99XT71GGK6=GS2.1.s1794569904\$o1\$g1\$t1794569925\$j39\$l0\$h0; xla=s4t"

    private val customHeaders = mapOf(
        "Cookie" to cookieHeader,
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36",
        "Referer" to "$mainUrl/"
    )

    override val mainPage = mainPageOf(
        "" to "Home",
        "category/korean-drama-hindi-dubbed" to "Korean Drama",
        "category/chinese-drama-hindi-dubbed" to "Chinese Drama",
        "category/movies" to "Movies",
        "category/japanese-drama" to "Japanese Drama",
        "category/japanese-drama-hindi-dubbed" to "Japanese Drama (Hindi)",
        "category/thai-drama-hindi-dubbed" to "Thai Drama",
        "category/turkish-drama-hindi-dubbed" to "Turkish Drama",
        "category/colombian-drama" to "Colombian Drama",
        "category/philippines-drama" to "Philippines Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (request.data.isEmpty()) {
            if (page == 1) mainUrl else "$mainUrl/page/$page/"
        } else {
            if (page == 1) "$mainUrl/${request.data}/" else "$mainUrl/${request.data}/page/$page/"
        }

        val document = app.get(url, headers = customHeaders).document
        val homePageLists = mutableListOf<HomePageList>()

        if (request.data.isEmpty() && page == 1) {
            val heroItems = document.select("div.hero-slide").mapNotNull { it.toHeroSearchResult() }
            if (heroItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Featured", heroItems, isHorizontalImages = true))
            }

            val trendingItems = document.select("div.trending-slide").mapNotNull { it.toTrendingSearchResult() }
            if (trendingItems.isNotEmpty()) {
                homePageLists.add(HomePageList("Top 10 Trending", trendingItems))
            }
        }

        val latestItems = document.select("article.latest-card").mapNotNull { it.toLatestSearchResult() }
        if (latestItems.isNotEmpty()) {
            val sectionTitle = if (request.data.isEmpty()) "Latest / Popular" else request.name
            homePageLists.add(HomePageList(sectionTitle, latestItems))
        }

        val hasNext = document.select("div.pagination a.next, a.next.page-numbers").isNotEmpty()
        return newHomePageResponse(homePageLists, hasNext)
    }

    private fun Element.toHeroSearchResult(): SearchResponse? {
        val title = this.selectFirst("h2.hero-title")?.text()?.trim() ?: return null
        val watchBtn = this.selectFirst("a.btn-watch, a.btn-primary") ?: return null
        val href = fixUrlNull(watchBtn.attr("href")) ?: return null

        val style = this.attr("style")
        val posterUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)

        val isMovie = href.contains("movie", ignoreCase = true)
        return newTvSeriesSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toTrendingSearchResult(): SearchResponse? {
        val linkElement = this.selectFirst("a.trending-poster-link") ?: return null
        val href = fixUrlNull(linkElement.attr("href")) ?: return null
        val imgElement = this.selectFirst("div.trending-poster img")
        val rawTitle = imgElement?.attr("alt")?.trim() ?: return null
        val title = cleanTitle(rawTitle)
        val posterUrl = fixUrlNull(imgElement.attr("src"))

        val isMovie = rawTitle.contains("Movie", ignoreCase = true) || href.contains("movie", ignoreCase = true)
        return newTvSeriesSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun Element.toLatestSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h3.latest-title a") ?: return null
        val rawTitle = titleElement.text().trim()
        val title = cleanTitle(rawTitle)
        val href = fixUrlNull(titleElement.attr("href")) ?: return null

        val imgElement = this.selectFirst("div.latest-poster img")
        val posterUrl = fixUrlNull(imgElement?.attr("src") ?: imgElement?.attr("data-src"))

        val isMovie = rawTitle.contains("Movie", ignoreCase = true) || href.contains("movie", ignoreCase = true)
        return newTvSeriesSearchResponse(title, href, if (isMovie) TvType.Movie else TvType.AsianDrama) {
            this.posterUrl = posterUrl
        }
    }

    private fun cleanTitle(title: String): String {
        return title.replace(Regex("""\[.*?\]"""), "")
            .replace(Regex("""\(.*?Dubbed.*?\)""", RegexOption.IGNORE_CASE), "")
            .replace("| Complete", "", ignoreCase = true)
            .replace("– All Episodes", "", ignoreCase = true)
            .trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val searchUrl = "$mainUrl/?s=$query"
        val document = app.get(searchUrl, headers = customHeaders).document
        return document.select("article.latest-card, article.post").mapNotNull {
            it.toLatestSearchResult()
        }
    }

    // Sequential Top-to-Bottom Scanner for links.dramashindi.online/archives/...
    private fun parseArchiveDocument(doc: org.jsoup.nodes.Document, fallbackQuality: String, episodeMap: MutableMap<Int, MutableList<String>>) {
        val contentRoot = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        var currentEp = 1
        var currentQuality = fallbackQuality

        val epRegex = Regex("""(?i)\b(?:episode|ep|e)\s*0*(\d+)\b""")
        val qualRegex = Regex("""(?i)\b(480p|720p|1080p|2160p)\b""")

        for (el in contentRoot.select("*")) {
            val ownText = el.ownText().trim()
            if (ownText.isNotEmpty() && ownText.length < 120 && el.tagName() != "a") {
                val epMatch = epRegex.find(ownText)
                if (epMatch != null) {
                    val parsedEp = epMatch.groupValues[1].toIntOrNull()
                    if (parsedEp != null && parsedEp in 1..500) {
                        currentEp = parsedEp
                    }
                }
                val qMatch = qualRegex.find(ownText)
                if (qMatch != null) {
                    currentQuality = qMatch.groupValues[1]
                }
            }

            if (el.tagName() == "a") {
                val href = el.attr("href").replace("&amp;", "&").trim()
                if (href.contains("hubcloud", true) ||
                    href.contains("gdflix", true) ||
                    href.contains("filebee", true) ||
                    href.contains("goflix", true) ||
                    href.contains("pixeldrain", true) ||
                    href.contains("drive.google.com", true)
                ) {
                    val linkText = el.text().trim()
                    val epFromLink = epRegex.find(linkText)?.groupValues?.get(1)?.toIntOrNull() ?: currentEp
                    val qualFromLink = qualRegex.find(linkText)?.groupValues?.get(1) ?: currentQuality

                    episodeMap.getOrPut(epFromLink) { mutableListOf() }.add("$qualFromLink|$href")
                }
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url, headers = customHeaders).document

        val rawTitle = document.selectFirst("h1.entry-title, h1.post-title, h1")?.text()?.trim() ?: return null
        val title = cleanTitle(rawTitle)

        val poster = fixUrlNull(
            document.selectFirst("meta[property=og:image]")?.attr("content")
                ?: document.selectFirst("div.post-thumbnail img, div.latest-poster img")?.attr("src")
        )
        val plot = document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()
            ?: document.selectFirst("div.entry-content p")?.text()?.trim()

        val tags = document.select("div.tags a, a[rel=tag]").map { it.text().trim() }

        val episodeMap = mutableMapOf<Int, MutableList<String>>()
        val visitedArchives = mutableSetOf<String>()

        val archiveLinks = document.select("a[href*='dramashindi.online'], a[href*='/archives/'], div.entry-content a.wp-block-button__link, div.entry-content a")
            .filter {
                val href = it.attr("href")
                href.contains("dramashindi.online") || href.contains("/archives/")
            }

        if (archiveLinks.isNotEmpty()) {
            archiveLinks.forEach { archiveBtn ->
                val archiveUrl = archiveBtn.attr("href").replace("&amp;", "&").trim()
                if (!visitedArchives.add(archiveUrl)) return@forEach

                val btnText = archiveBtn.text().trim()
                val qualityLabel = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(btnText)?.groupValues?.get(1) ?: "HD"

                try {
                    val archiveDoc = app.get(archiveUrl, headers = customHeaders, interceptor = cfKiller).document
                    parseArchiveDocument(archiveDoc, qualityLabel, episodeMap)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (episodeMap.isEmpty()) {
            parseArchiveDocument(document, "HD", episodeMap)
        }

        val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, linkList) ->
            val uniqueLinks = linkList.distinct()
            val qualities = uniqueLinks.map { it.substringBefore("|") }.distinct().joinToString(" • ")
            newEpisode(uniqueLinks.joinToString(";;;")) {
                this.name = "Episode $epNum [$qualities]"
                this.episode = epNum
            }
        }

        val isMovie = rawTitle.contains("Movie", ignoreCase = true) && episodes.size <= 1

        return if (isMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val items = data.split(";;;").filter { it.isNotBlank() }

        items.amap { item ->
            val parts = item.split("|", limit = 2)
            val qualityStr = if (parts.size == 2) parts[0] else ""
            val rawUrl = if (parts.size == 2) parts[1] else parts[0]
            val url = rawUrl.replace("&amp;", "&").trim()
            val quality = getQualityFromName(qualityStr)

            when {
                url.contains("hubcloud", ignoreCase = true) || url.contains("gamerxyt", ignoreCase = true) -> {
                    KatServerLogic.extractHubCloudServers(url, qualityStr, callback)
                }
                url.contains("gdflix", ignoreCase = true) -> {
                    KatServerLogic.extractGdFlixServers(url, qualityStr, subtitleCallback, callback)
                }
                url.contains("pixeldrain", ignoreCase = true) -> {
                    KatServerLogic.extractPixelDrain(url, quality, callback)
                }
                url.contains("hbplay.pages.dev", ignoreCase = true) -> {
                    KatServerLogic.extractHbPlay(url, quality, callback)
                }
                url.contains("fuckingfast.net", ignoreCase = true) -> {
                    KatServerLogic.extractFuckingFast(url, quality, callback)
                }
                url.contains("goflix.sbs", ignoreCase = true) -> {
                    KatServerLogic.extractGoFlixMirror(url, quality, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(url, qualityStr, subtitleCallback, callback)
                }
            }
        }
        return true
    }
}
