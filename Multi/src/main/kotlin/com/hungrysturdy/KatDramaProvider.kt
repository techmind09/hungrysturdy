package com.hungrysturdy

import android.util.Base64
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import java.net.URI

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
        "User-Agent" to "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36",
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

        val archiveLinks = document.select("a[href*='links.dramashindi.online'], div.entry-content a.wp-block-button__link, div.entry-content a")
            .filter {
                val href = it.attr("href")
                href.contains("dramashindi.online") || href.contains("/archives/")
            }

        if (archiveLinks.isNotEmpty()) {
            archiveLinks.forEach { archiveBtn ->
                val archiveUrl = archiveBtn.attr("href")
                val btnText = archiveBtn.text().trim()
                val qualityLabel = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(btnText)?.groupValues?.get(1) ?: "HD"

                try {
                    val archiveDoc = app.get(archiveUrl, headers = customHeaders, interceptor = cfKiller).document

                    archiveDoc.select("a[href*='hubcloud'], a[href*='gdflix'], a[href*='filebee']").forEach { hostLink ->
                        val hostHref = hostLink.attr("href")
                        val prevHeading = hostLink.parent()?.previousElementSibling()?.text().orEmpty()
                        val contextText = "$prevHeading ${hostLink.parent()?.text().orEmpty()} ${hostLink.parent()?.parent()?.text().orEmpty()} ${hostLink.text()}"
                        val epNum = Regex("""(?:Episode|Ep|E)\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                            .find(contextText)?.groupValues?.get(1)?.toIntOrNull() ?: 1

                        episodeMap.getOrPut(epNum) { mutableListOf() }.add("$qualityLabel|$hostHref")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        if (episodeMap.isEmpty()) {
            document.select("a[href*='hubcloud'], a[href*='gdflix']").forEach { link ->
                val href = link.attr("href")
                val contextText = "${link.parent()?.text().orEmpty()} ${link.text()}"
                val epNum = Regex("""(?:Episode|Ep|E)\s*0*(\d+)""", RegexOption.IGNORE_CASE)
                    .find(contextText)?.groupValues?.get(1)?.toIntOrNull() ?: 1
                val qualityLabel = Regex("""(\d{3,4}p)""", RegexOption.IGNORE_CASE).find(contextText)?.groupValues?.get(1) ?: "HD"

                episodeMap.getOrPut(epNum) { mutableListOf() }.add("$qualityLabel|$href")
            }
        }

        val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, linkList) ->
            newEpisode(linkList.distinct().joinToString(";;;")) {
                this.name = "Episode $epNum"
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
            val url = if (parts.size == 2) parts[1] else parts[0]
            val quality = getQualityFromName(qualityStr)

            when {
                url.contains("hubcloud", ignoreCase = true) -> {
                    extractHubCloud(url, quality, callback)
                }
                url.contains("gdflix", ignoreCase = true) -> {
                    extractGdFlix(url, quality, subtitleCallback, callback)
                }
                else -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    private suspend fun extractHubCloud(
        url: String,
        defaultQuality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = customHeaders, interceptor = cfKiller)
            val doc = response.document
            val htmlText = response.text

            val fileHeader = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
            val detectedQuality = Regex("""(\d{3,4}p)""").find(fileHeader)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: defaultQuality

            val gamerLink = doc.selectFirst("a#download")?.attr("href")
                ?: Regex("""var\s+url\s*=\s*['"](https://[^'"]*hubcloud\.php[^'"]*)['"]""")
                    .find(htmlText)?.groupValues?.get(1)
                ?: return

            val gamerDoc = app.get(gamerLink, headers = customHeaders, referer = url).document

            // 1. FSL Server
            gamerDoc.selectFirst("a#fsl, a:contains(FSL Server)")?.attr("href")?.let { fslUrl ->
                if (fslUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "HubCloud [FSL Server]",
                            fslUrl,
                            gamerLink,
                            detectedQuality
                        )
                    )
                }
            }

            // 2. HBPlay Direct Link
            gamerDoc.selectFirst("a[href*='hbplay.pages.dev']")?.attr("href")?.let { hbUrl ->
                val encodedU = hbUrl.substringAfter("u=", "").substringBefore("&")
                if (encodedU.isNotBlank()) {
                    try {
                        val decodedUrl = String(Base64.decode(encodedU, Base64.DEFAULT))
                        if (decodedUrl.startsWith("http")) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "HubCloud [HBPlay Direct]",
                                    decodedUrl,
                                    "https://hbplay.pages.dev/",
                                    detectedQuality
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. PixelDrain Server
            gamerDoc.selectFirst("a#pxl-1, a[href*='pixeldrain']")?.attr("href")?.let { pxlUrl ->
                val fileId = pxlUrl.substringAfterLast("/")
                if (fileId.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "HubCloud [PixelServer]",
                            "https://pixeldrain.dev/api/file/$fileId?download",
                            pxlUrl,
                            detectedQuality
                        )
                    )
                }
            }

            // 4. 10Gbps Server
            gamerDoc.selectFirst("a[href*='pixel.hubcloud'], a:contains(10Gbps)")?.attr("href")?.let { tenGbpsUrl ->
                if (tenGbpsUrl.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "HubCloud [10Gbps Server]",
                            tenGbpsUrl,
                            gamerLink,
                            detectedQuality
                        )
                    )
                }
            }

            // 5. Buzz Server
            gamerDoc.selectFirst("a[href*='fuckingfast.net'], a:contains(Buzz Server)")?.attr("href")?.let { buzzUrl ->
                if (buzzUrl.isNotBlank()) {
                    extractFuckingFast(buzzUrl, detectedQuality, callback)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractFuckingFast(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val text = app.get(url, headers = customHeaders).text
            val directLink = Regex("""window\.open\(["'](https://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(text)?.groupValues?.get(1)
            if (directLink != null) {
                callback.invoke(
                    newExtractorLink(
                        this.name,
                        "HubCloud [Buzz Server]",
                        directLink,
                        url,
                        quality
                    )
                )
            }
        } catch (_: Exception) {}
    }

    private suspend fun extractGdFlix(
        url: String,
        defaultQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = customHeaders, interceptor = cfKiller)
            val doc = response.document
            val htmlText = response.text

            val finalUrl = response.url
            val uri = URI(finalUrl)
            val baseOrigin = "${uri.scheme}://${uri.host}"

            val pageTitle = doc.title()
            val detectedQuality = Regex("""(\d{3,4}p)""").find(pageTitle)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: defaultQuality

            // 1. Instant DL [10GBPS]
            doc.selectFirst("a[href*='busycdn'], a[href*='instant.'], a.btn-danger:contains(Instant DL)")?.attr("href")?.let { instantUrl ->
                if (instantUrl.startsWith("http")) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "GDFlix [Instant DL 10Gbps]",
                            instantUrl,
                            finalUrl,
                            detectedQuality
                        )
                    )
                }
            }

            // 2. FAST CLOUD / ZIPDISK
            doc.selectFirst("a[href^='/cloud/'], a:contains(FAST CLOUD)")?.attr("href")?.let { cloudPath ->
                val cloudUrl = if (cloudPath.startsWith("http")) cloudPath else "$baseOrigin$cloudPath"
                try {
                    val cloudRes = app.get(cloudUrl, headers = customHeaders, referer = finalUrl)
                    val cloudDoc = cloudRes.document
                    cloudDoc.select("a.btn-success, a.btn-primary, a.btn-danger, a[href*='r2.cloudflarestorage'], a[href*='busycdn'], a[href*='worker']").forEach { btn ->
                        val dlHref = btn.attr("href")
                        if (dlHref.startsWith("http") && !dlHref.contains("/login")) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "GDFlix [Fast Cloud]",
                                    dlHref,
                                    cloudUrl,
                                    detectedQuality
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. DRIVEBOT [50MB/S]
            val driveBotUrl = doc.selectFirst("a[href*='drivebot']")?.attr("href")
                ?: Regex("""href=["'](https://drivebot\.[a-z]+/download\?id=[^"']+)["']""")
                    .find(htmlText)?.groupValues?.get(1)?.replace("&amp;", "&")

            if (!driveBotUrl.isNullOrBlank()) {
                try {
                    val dbRes = app.get(driveBotUrl, headers = customHeaders, referer = finalUrl)
                    val dbDoc = dbRes.document
                    dbDoc.select("a.btn, a[href*='download']").forEach { a ->
                        val h = a.attr("href")
                        if (h.startsWith("http") && !h.contains("drivebot")) {
                            callback.invoke(
                                newExtractorLink(
                                    this.name,
                                    "GDFlix [DriveBot]",
                                    h,
                                    driveBotUrl,
                                    detectedQuality
                                )
                            )
                        }
                    }
                } catch (_: Exception) {}
            }

            // 4. PixelDrain
            doc.selectFirst("a[href*='pixeldrain']")?.attr("href")?.let { pxl ->
                val id = pxl.substringAfterLast("/")
                if (id.isNotBlank()) {
                    callback.invoke(
                        newExtractorLink(
                            this.name,
                            "GDFlix [PixelDrain]",
                            "https://pixeldrain.dev/api/file/$id?download",
                            finalUrl,
                            detectedQuality
                        )
                    )
                }
            }

            // 5. GoFile [Multiup]
            doc.selectFirst("a[href*='goflix'], a[href*='gofile'], a:contains(GoFile)")?.attr("href")?.let { goUrl ->
                if (goUrl.startsWith("http")) {
                    loadExtractor(goUrl, finalUrl, subtitleCallback, callback)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
