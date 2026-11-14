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

    // Helper: Image element se lazy-load / src nikalkar high-res TMDB poster banana
    private fun Element?.extractPosterUrl(): String? {
        if (this == null) return null
        val rawUrl = this.attr("data-src").ifEmpty {
            this.attr("data-lazy-src").ifEmpty {
                this.attr("src")
            }
        }.trim()

        if (rawUrl.isEmpty() || rawUrl.startsWith("data:image") || rawUrl.contains("iconfinder.com")) {
            // Check srcset if src is placeholder
            val srcset = this.attr("srcset").substringBefore(" ").trim()
            if (srcset.startsWith("http")) return srcset
            return null
        }
        return fixUrlNull(rawUrl)
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
        val posterUrl = imgElement.extractPosterUrl()

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

        val imgElement = this.selectFirst("div.latest-poster img, img")
        val posterUrl = imgElement.extractPosterUrl()

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

        // Poster & Backdrop Fix:
        // 1. Post content ya sidebar poster se vertical poster dhoondhna
        val contentImg = document.select("div.post-thumbnail img, div.single-poster img, div.entry-content img, article img")
            .mapNotNull { it.extractPosterUrl() }
            .firstOrNull { !it.contains("iconfinder.com") && !it.contains("telegram") }

        val ogImage = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?.takeIf { !it.contains("iconfinder.com") }

        // Agar TMDB image hai toh vertical poster ke liye w500 aur background ke liye w780/original use karein
        val primaryImg = contentImg ?: ogImage
        val verticalPoster = primaryImg?.let {
            if (it.contains("image.tmdb.org/t/p/w780/")) it.replace("/w780/", "/w500/") else it
        }
        val backdropPoster = (ogImage ?: contentImg)?.let {
            if (it.contains("image.tmdb.org/t/p/w342/")) it.replace("/w342/", "/w780/") else it
        }

        val plot = document.selectFirst("div.entry-content p:not(:has(a))")?.text()?.trim()
            ?.takeIf { it.length > 20 }
            ?: document.selectFirst("meta[property=og:description]")?.attr("content")?.trim()

        val tags = document.select("div.tags a, a[rel=tag]").map { it.text().trim() }

        // Map: EpisodeNumber -> List of "Quality|URL"
        val episodeMap = mutableMapOf<Int, MutableList<String>>()

        // 1. Archive links (links.dramashindi.online/archives/...)
        val archiveLinks = document.select("a[href*='dramashindi.online'], div.entry-content a.wp-block-button__link, div.entry-content a")
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
                    parseArchiveEpisodes(archiveDoc, qualityLabel, episodeMap)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Fallback: Direct HubCloud/GDFlix/GDrive links on main post
        if (episodeMap.isEmpty()) {
            parseArchiveEpisodes(document, "HD", episodeMap)
        }

        val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, linkList) ->
            newEpisode(linkList.distinct().joinToString(";;;")) {
                this.name = "Episode $epNum"
                this.episode = epNum
                this.posterUrl = backdropPoster ?: verticalPoster
            }
        }

        val isMovie = rawTitle.contains("Movie", ignoreCase = true) && episodes.size <= 1

        return if (isMovie && episodes.isNotEmpty()) {
            newMovieLoadResponse(title, url, TvType.Movie, episodes.first().data) {
                this.posterUrl = verticalPoster
                this.backgroundPosterUrl = backdropPoster
                this.plot = plot
                this.tags = tags
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = verticalPoster
                this.backgroundPosterUrl = backdropPoster
                this.plot = plot
                this.tags = tags
            }
        }
    }

    // Sequential Top-to-Bottom Episode Parser for dramashindi.online / KatDrama
    private fun parseArchiveEpisodes(
        doc: org.jsoup.nodes.Document,
        qualityLabel: String,
        episodeMap: MutableMap<Int, MutableList<String>>
    ) {
        val contentRoot = doc.selectFirst("div.entry-content, article, main") ?: doc.body()
        var currentEp = 1
        val epRegex = Regex("""(?i)\b(?:Episode|Ep|E)\s*0*(\d+)\b""")

        for (el in contentRoot.select("*")) {
            val ownText = el.ownText().trim()
            if (ownText.isNotEmpty() && ownText.length < 80 && el.tagName() != "a") {
                val m = epRegex.find(ownText)
                if (m != null) {
                    val num = m.groupValues[1].toIntOrNull()
                    if (num != null && num in 1..500) {
                        currentEp = num
                    }
                }
            }

            if (el.tagName() == "a") {
                val href = el.attr("href").trim()
                if (href.contains("hubcloud", true) ||
                    href.contains("gdflix", true) ||
                    href.contains("drive.google.com", true) ||
                    href.contains("filebee", true)
                ) {
                    val contextText = "${el.text()} ${el.parent()?.ownText().orEmpty()} ${el.parent()?.previousElementSibling()?.text().orEmpty()}"
                    val epNum = epRegex.find(contextText)?.groupValues?.get(1)?.toIntOrNull() ?: currentEp
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add("$qualityLabel|$href")
                }
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
                url.contains("drive.google.com", ignoreCase = true) -> {
                    extractDirectGDrive(url, quality, callback)
                }
                else -> {
                    loadExtractor(url, mainUrl, subtitleCallback, callback)
                }
            }
        }
        return true
    }

    // =========================================================================
    // 1. GDFlix / GDrive Complete Extractor (Based on dom.html3.html & dom.html5.html)
    // =========================================================================
    private suspend fun extractGdFlix(
        url: String,
        defaultQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = customHeaders, interceptor = cfKiller, allowRedirects = true)
            val doc = response.document
            val htmlText = response.text

            val finalUrl = response.url.ifEmpty { url }
            val uri = URI(finalUrl)
            val baseOrigin = "${uri.scheme}://${uri.host}"

            // dom.html3.html: <title>GDFlix | The.Remarried.Empress.2026.S01E01.720p...</title>
            val pageTitle = doc.title()
            val detectedQuality = Regex("""(\d{3,4}p)""").find(pageTitle)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: defaultQuality
            val qLabel = if (detectedQuality != Qualities.Unknown.value) "${detectedQuality}p" else ""

            // A. Instant DL [10GBPS] -> https://instant.busycdn.xyz/... (dom.html3.html -> dom.html5.html)
            doc.selectFirst("a[href*='busycdn'], a[href*='instant.'], a.btn-danger:contains(Instant DL)")?.attr("href")?.let { instantHref ->
                val fullInstantUrl = if (instantHref.startsWith("http")) instantHref else "$baseOrigin$instantHref"
                extractInstantDlPage(fullInstantUrl, finalUrl, detectedQuality, qLabel, callback)
            }

            // B. FSL V2 DOWNLOAD [R2] -> https://pub-...r2.dev/... (dom.html3.html)
            doc.selectFirst("a[href*='.r2.dev'], a[href*='r2.cloudflarestorage'], a:contains(FSL V2), a:contains(FSL)")?.attr("href")?.let { r2Url ->
                if (r2Url.startsWith("http")) {
                    emitVideoLink(
                        source = "GDFlix",
                        name = "GDFlix [FSL V2 R2] $qLabel".trim(),
                        url = r2Url,
                        referer = finalUrl,
                        quality = detectedQuality,
                        callback = callback
                    )
                }
            }

            // C. FAST CLOUD / ZIPDISK -> /cloud/1794577040/kw4D77Hr8iR9Upa (dom.html3.html)
            doc.selectFirst("a[href^='/cloud/'], a[href^='/cflare/'], a:contains(FAST CLOUD)")?.attr("href")?.let { cloudPath ->
                val cloudUrl = if (cloudPath.startsWith("http")) cloudPath else "$baseOrigin$cloudPath"
                try {
                    val cloudRes = app.get(cloudUrl, headers = customHeaders, referer = finalUrl, allowRedirects = true)
                    val cloudDoc = cloudRes.document

                    // Check if it redirected to an Instant DL page with a#vd
                    val vdHref = cloudDoc.selectFirst("a#vd")?.attr("href")
                    if (!vdHref.isNullOrBlank() && vdHref.startsWith("http")) {
                        emitVideoLink("GDFlix", "GDFlix [Fast Cloud GDrive] $qLabel".trim(), vdHref, "", detectedQuality, callback)
                    } else {
                        cloudDoc.select("a.btn-success, a.btn-primary, a.btn-danger, a[href*='googleusercontent.com'], a[href*='r2.'], a[href*='busycdn'], a[href*='worker']").forEach { btn ->
                            val dlHref = btn.attr("href")
                            if (dlHref.startsWith("http") && !dlHref.contains("/login")) {
                                if (dlHref.contains("busycdn") || dlHref.contains("instant.")) {
                                    extractInstantDlPage(dlHref, cloudUrl, detectedQuality, qLabel, callback)
                                } else {
                                    emitVideoLink("GDFlix", "GDFlix [Fast Cloud] $qLabel".trim(), dlHref, cloudUrl, detectedQuality, callback)
                                }
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // D. DRIVEBOT [50MB/S] -> Even inside HTML comments in dom.html3.html!
            val driveBotUrl = doc.selectFirst("a[href*='drivebot']")?.attr("href")
                ?: Regex("""href=["'](https://drivebot\.[a-z]+/download\?id=[^"']+)["']""")
                    .find(htmlText)?.groupValues?.get(1)?.replace("&amp;", "&")

            if (!driveBotUrl.isNullOrBlank()) {
                extractDriveBot(driveBotUrl, finalUrl, detectedQuality, qLabel, callback)
            }

            // E. PixelDrain (if available on GDFlix)
            doc.selectFirst("a[href*='pixeldrain']")?.attr("href")?.let { pxl ->
                val id = pxl.substringAfterLast("/").substringBefore("?")
                if (id.isNotBlank()) {
                    emitVideoLink(
                        source = "GDFlix",
                        name = "GDFlix [PixelDrain] $qLabel".trim(),
                        url = "https://pixeldrain.dev/api/file/$id?download",
                        referer = "https://pixeldrain.dev/",
                        quality = detectedQuality,
                        callback = callback
                    )
                }
            }

            // F. GoFile / MultiUp Mirrors -> validate.multiup2.workers.dev (dom.html3.html & dom.html4.html)
            doc.selectFirst("a[href*='multiup'], a[href*='goflix'], a[href*='gofile'], a:contains(GoFile)")?.attr("href")?.let { mirrorUrl ->
                if (mirrorUrl.startsWith("http")) {
                    try {
                        val mirrorRes = app.get(mirrorUrl, headers = customHeaders, referer = finalUrl, allowRedirects = true)
                        val mirrorDoc = mirrorRes.document
                        mirrorDoc.select("a[href*='gofile.io'], a[href*='pixeldrain'], form[action*='gofile.io']").forEach { el ->
                            val targetLink = el.attr("href").ifEmpty { el.attr("action") }
                            if (targetLink.startsWith("http")) {
                                loadExtractor(targetLink, mirrorRes.url, subtitleCallback, callback)
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // Parses dom.html5.html ("Instant DL Page") -> extracts <a id="vd" href="https://video-downloads.googleusercontent.com/...">
    private suspend fun extractInstantDlPage(
        instantUrl: String,
        refererUrl: String,
        quality: Int,
        qLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            // 1. Check if ?url= query parameter already contains the direct link (as seen in dom.html5.html script)
            val urlParam = Regex("""[?&]url=([^&]+)""").find(instantUrl)?.groupValues?.get(1)
            if (!urlParam.isNullOrBlank()) {
                val decoded = java.net.URLDecoder.decode(urlParam, "UTF-8")
                if (decoded.startsWith("http")) {
                    emitVideoLink("GDrive", "GDFlix [Instant GDrive] $qLabel".trim(), decoded, "", quality, callback)
                    return
                }
            }

            // 2. Fetch the Instant DL Page (dom.html5.html)
            val instHeaders = customHeaders.toMutableMap().apply {
                put("Referer", refererUrl)
            }
            val res = app.get(instantUrl, headers = instHeaders, allowRedirects = true)
            val doc = res.document
            val html = res.text

            // Extract <a id="vd" href="https://video-downloads.googleusercontent.com/...">
            val vdLink = doc.selectFirst("a#vd, div.vd a, a[href*='googleusercontent.com']")?.attr("href")
                ?: Regex("""https://video-downloads\.googleusercontent\.com/[^\s"'<>]+""").find(html)?.value

            if (!vdLink.isNullOrBlank() && vdLink.startsWith("http")) {
                // Note: dom.html5.html has <meta name="referrer" content="no-referrer">, so referer must be empty!
                emitVideoLink(
                    source = "GDrive",
                    name = "GDFlix [GDrive Instant 10Gbps] $qLabel".trim(),
                    url = vdLink,
                    referer = "",
                    quality = quality,
                    callback = callback
                )
            } else if (res.url.contains("googleusercontent.com") || res.url.contains(".mkv") || res.url.contains(".mp4")) {
                emitVideoLink(
                    source = "GDrive",
                    name = "GDFlix [GDrive Direct] $qLabel".trim(),
                    url = res.url,
                    referer = "",
                    quality = quality,
                    callback = callback
                )
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // DriveBot Extractor
    private suspend fun extractDriveBot(
        driveBotUrl: String,
        refererUrl: String,
        quality: Int,
        qLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val dbRes = app.get(driveBotUrl, headers = customHeaders, referer = refererUrl, allowRedirects = true)
            val dbDoc = dbRes.document
            val dbHtml = dbRes.text

            // Look for direct download link or token POST inside DriveBot
            dbDoc.select("a.btn, a[href*='googleusercontent'], a[href*='r2.'], a[href*='worker'], a[href*='download']").forEach { a ->
                val h = a.attr("href")
                if (h.startsWith("http") && !h.contains("drivebot") && !h.contains("t.me")) {
                    emitVideoLink("GDFlix", "GDFlix [DriveBot] $qLabel".trim(), h, driveBotUrl, quality, callback)
                }
            }

            // Check if DriveBot uses form/ajax download link in script
            Regex("""window\.location\.href\s*=\s*["'](https?://[^"']+)["']""").findAll(dbHtml).forEach { m ->
                val link = m.groupValues[1]
                if (!link.contains("drivebot")) {
                    emitVideoLink("GDFlix", "GDFlix [DriveBot Direct] $qLabel".trim(), link, driveBotUrl, quality, callback)
                }
            }
        } catch (_: Exception) {}
    }

    // Direct drive.google.com/file/d/... Extractor
    private suspend fun extractDirectGDrive(
        url: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val fileId = Regex("""(?:file/d/|id=)([a-zA-Z0-9_-]+)""").find(url)?.groupValues?.get(1) ?: return
            val ucUrl = "https://drive.usercontent.google.com/download?id=$fileId&export=download&confirm=t"
            val ucResp = app.get(ucUrl, headers = customHeaders, referer = "https://drive.google.com/")
            val ucDoc = ucResp.document

            val formAction = ucDoc.selectFirst("form#download-form")?.attr("action")
            val finalDirectUrl = if (!formAction.isNullOrBlank()) {
                val params = ucDoc.select("form#download-form input[name]").mapNotNull { input ->
                    val name = input.attr("name")
                    val value = input.attr("value")
                    if (name.isNotBlank()) "$name=$value" else null
                }.joinToString("&")
                if (params.isNotBlank()) "$formAction?$params" else ucUrl
            } else {
                ucUrl
            }

            emitVideoLink("GDrive", "GDrive [Direct]", finalDirectUrl, "https://drive.google.com/", quality, callback)
        } catch (_: Exception) {}
    }

    // =========================================================================
    // 2. HubCloud Extractor (hubcloud.pw -> gamerxyt.com -> Direct Servers)
    // =========================================================================
    private suspend fun extractHubCloud(
        url: String,
        defaultQuality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = customHeaders, interceptor = cfKiller, allowRedirects = true)
            val doc = response.document
            val htmlText = response.text

            val fileHeader = doc.selectFirst("div.card-header")?.text()?.trim().orEmpty()
            val detectedQuality = Regex("""(\d{3,4}p)""").find(fileHeader)?.groupValues?.get(1)
                ?.let { getQualityFromName(it) } ?: defaultQuality
            val qLabel = if (detectedQuality != Qualities.Unknown.value) "${detectedQuality}p" else ""

            val gamerLink = doc.selectFirst("a#download")?.attr("href")
                ?: Regex("""var\s+url\s*=\s*['"](https://[^'"]*hubcloud\.php[^'"]*)['"]""")
                    .find(htmlText)?.groupValues?.get(1)
                ?: return

            val gamerDoc = app.get(gamerLink, headers = customHeaders, referer = response.url).document

            // 1. FSL Server
            gamerDoc.selectFirst("a#fsl, a:contains(FSL Server)")?.attr("href")?.let { fslUrl ->
                if (fslUrl.isNotBlank()) {
                    emitVideoLink("HubCloud", "HubCloud [FSL Server] $qLabel".trim(), fslUrl, gamerLink, detectedQuality, callback)
                }
            }

            // 2. HBPlay Direct Link (Base64 Decoded)
            gamerDoc.selectFirst("a[href*='hbplay.pages.dev']")?.attr("href")?.let { hbUrl ->
                val encodedU = hbUrl.substringAfter("u=", "").substringBefore("&")
                if (encodedU.isNotBlank()) {
                    try {
                        val decodedUrl = String(Base64.decode(encodedU, Base64.DEFAULT)).trim()
                        if (decodedUrl.startsWith("http")) {
                            emitVideoLink("HubCloud", "HubCloud [HBPlay Direct] $qLabel".trim(), decodedUrl, "https://hbplay.pages.dev/", detectedQuality, callback)
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. PixelDrain Server
            gamerDoc.selectFirst("a#pxl-1, a[href*='pixeldrain']")?.attr("href")?.let { pxlUrl ->
                val fileId = pxlUrl.substringAfterLast("/").substringBefore("?")
                if (fileId.isNotBlank()) {
                    emitVideoLink("HubCloud", "HubCloud [PixelServer] $qLabel".trim(), "https://pixeldrain.dev/api/file/$fileId?download", pxlUrl, detectedQuality, callback)
                }
            }

            // 4. 10Gbps Server (pixel.hubcloud.pw)
            gamerDoc.selectFirst("a[href*='pixel.hubcloud'], a:contains(10Gbps)")?.attr("href")?.let { tenGbpsUrl ->
                if (tenGbpsUrl.isNotBlank()) {
                    emitVideoLink("HubCloud", "HubCloud [10Gbps Server] $qLabel".trim(), tenGbpsUrl, gamerLink, detectedQuality, callback)
                }
            }

            // 5. Buzz Server (fuckingfast.net)
            gamerDoc.selectFirst("a[href*='fuckingfast.net'], a:contains(Buzz Server)")?.attr("href")?.let { buzzUrl ->
                if (buzzUrl.isNotBlank()) {
                    extractFuckingFast(buzzUrl, detectedQuality, qLabel, callback)
                }
            }

        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private suspend fun extractFuckingFast(
        url: String,
        quality: Int,
        qLabel: String,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val text = app.get(url, headers = customHeaders).text
            val directLink = Regex("""window\.open\(["'](https://fuckingfast\.net/dl/[^"']+)["']\)""")
                .find(text)?.groupValues?.get(1)
            if (!directLink.isNullOrBlank()) {
                emitVideoLink("HubCloud", "HubCloud [Buzz Server] $qLabel".trim(), directLink, url, quality, callback)
            }
        } catch (_: Exception) {}
    }

    // Safe newExtractorLink Builder (Compatible with latest CloudStream API)
    private suspend fun emitVideoLink(
        source: String,
        name: String,
        url: String,
        referer: String,
        quality: Int,
        callback: (ExtractorLink) -> Unit
    ) {
        callback.invoke(
            newExtractorLink(
                source = source,
                name = name,
                url = url,
                type = ExtractorLinkType.VIDEO
            ) {
                this.referer = referer
                this.quality = quality
            }
        )
    }
}
