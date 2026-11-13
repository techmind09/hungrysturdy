package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KDHindiDubbed : MainAPI() {
    // FIXED: Exact domain from your HTML source so categories don't redirect to Homepage!
    override var mainUrl = "https://kdhindidubbed.cfd"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie,
        TvType.AsianDrama
    )

    private val cfKiller by lazy { CloudflareKiller() }

    private suspend fun fetchDoc(url: String, referer: String = mainUrl): Document {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Linux; Android 13; SM-S918B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
        )
        headers.putAll(cfKiller.getCookieHeaders(url).toMap())
        val res = app.get(url, referer = referer, headers = headers, allowRedirects = true)
        if (res.code in listOf(403, 503, 429) || res.text.contains("Just a moment", true) || res.text.contains("cf-browser-verification", true)) {
            return app.get(url, referer = referer, interceptor = cfKiller, allowRedirects = true).document
        }
        return res.document
    }

    // FIXED: Clean category URLs matching the exact WordPress menu from your HTML
    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/arabic-subtitle/" to "Arabic Subtitle",
        "$mainUrl/category/business/" to "Business",
        "$mainUrl/category/chinese-drama/" to "Chinese Drama",
        "$mainUrl/category/chinese-language/" to "Chinese Language",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/english-dubbed/" to "English Dubbed",
        "$mainUrl/category/family/" to "Family",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/filipino-language/" to "Filipino Language",
        "$mainUrl/category/food/" to "Food",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/historical/" to "Historical",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/japanese-drama/" to "Japanese Drama",
        "$mainUrl/category/japanese-language/" to "Japanese Language",
        "$mainUrl/category/korean-drama/" to "Korean Drama",
        "$mainUrl/category/korean-language/" to "Korean Language",
        "$mainUrl/category/law/" to "Law",
        "$mainUrl/category/life/" to "Life",
        "$mainUrl/category/medical/" to "Medical",
        "$mainUrl/category/military/" to "Military",
        "$mainUrl/category/movie/" to "Movie",
        "$mainUrl/category/music/" to "Music",
        "$mainUrl/category/mystery/" to "Mystery",
        "$mainUrl/category/philippines-drama/" to "Philippines Drama",
        "$mainUrl/category/political/" to "Political",
        "$mainUrl/category/psychological/" to "Psychological",
        "$mainUrl/category/reality-show/" to "Reality Show",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/school/" to "School",
        "$mainUrl/category/sci-fi/" to "Sci-Fi",
        "$mainUrl/category/sports/" to "Sports",
        "$mainUrl/category/supernatural/" to "Supernatural",
        "$mainUrl/category/suspense/" to "Suspense",
        "$mainUrl/category/thai-language/" to "Thai Language",
        "$mainUrl/category/thailand-drama/" to "Thailand Drama",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/youth/" to "Youth"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) {
            "$baseUrl/"
        } else {
            "$baseUrl/page/$page/"
        }

        val document = fetchDoc(pageUrl)
        val home = document.select("article.herald-lay-c, article.post, article.type-post")
            .mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, .entry-title a, h2 a, h3 a") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))

        if (title.isBlank() || href.isBlank()) return null

        val imgElement = this.selectFirst(".herald-post-thumbnail img, img.wp-post-image, img")
        val rawImg = imgElement?.attr("data-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
            ?: imgElement?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: ""

        // Upgrade Blogger w640-h360 thumbnail to higher quality if available, or keep exact URL
        val posterUrl = fixUrl(rawImg)

        val isMovie = title.contains("Movie", true) || this.hasClass("category-movie")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = fetchDoc("$mainUrl/?s=$encoded")

        return document.select("article.herald-lay-c, article.post, article.type-post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"

        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".herald-post-thumbnail img, .entry-content img, article img")?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: document.selectFirst(".herald-post-thumbnail img, .entry-content img, article img")?.attr("data-src").orEmpty()
        )

        // FIXED: Proper plot extraction from Herald theme's .entry-content/.herald-entry-content
        val description = document.select(".entry-content p, .herald-entry-content p")
            .map { it.text().trim() }
            .firstOrNull { p ->
                p.length > 40 &&
                !p.contains("Download", true) &&
                !p.contains("Telegram", true) &&
                !p.contains("Episode", true)
            } ?: document.selectFirst("meta[property='og:description'], meta[name='description']")?.attr("content")?.trim()
            ?: "Watch $title online."

        val isMovie = title.contains("Movie", true)

        val episodeMap = LinkedHashMap<Int, MutableList<String>>()
        val allLinksForMovie = mutableListOf<String>()

        // 1. Scan the main drama page first
        parseEpisodesFromDoc(document, episodeMap, allLinksForMovie)

        // 2. Find sub-page links (like "DOWNLOAD LINKS 720p", "DOWNLOAD LINKS 1080p", or sub-posts)
        val contentRoot = document.selectFirst(".entry-content, .herald-entry-content") ?: document.body()
        val subPageLinks = contentRoot.select("a[href]").mapNotNull { el ->
            val href = fixUrl(el.attr("href").trim())
            val text = el.text().trim()

            if (!isValidCandidateUrl(href)) return@mapNotNull null

            val isSubPage = text.contains("DOWNLOAD", true) ||
                text.contains("720p", true) ||
                text.contains("1080p", true) ||
                text.contains("480p", true) ||
                text.contains("Click Here", true) ||
                text.contains("Episode", true) ||
                ((href.contains("kdhindidubbed") || href.contains("dramacool")) && href != url)

            if (isSubPage && !isDirectServerHost(href)) href else null
        }.distinct()

        for (subLink in subPageLinks) {
            try {
                val resolvedUrl = followRedirect(subLink, url)
                if (isDirectServerHost(resolvedUrl)) {
                    allLinksForMovie.add(resolvedUrl)
                    episodeMap.getOrPut(1) { mutableListOf() }.add(resolvedUrl)
                } else {
                    val subDoc = fetchDoc(resolvedUrl, url)
                    parseEpisodesFromDoc(subDoc, episodeMap, allLinksForMovie)
                }
            } catch (_: Exception) {}
        }

        // 3. Final fallback: grab any remaining valid external links inside content
        if (episodeMap.isEmpty() && allLinksForMovie.isEmpty()) {
            contentRoot.select("a[href]").forEach { el ->
                val h = fixUrl(el.attr("href").trim())
                if (isValidCandidateUrl(h) && !h.contains("kdhindidubbed.cfd")) {
                    allLinksForMovie.add(h)
                    episodeMap.getOrPut(1) { mutableListOf() }.add(h)
                }
            }
        }

        return if (isMovie && episodeMap.size <= 1) {
            val movieData = allLinksForMovie.distinct().ifEmpty { listOf(url) }.joinToString("|")
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.entries.sortedBy { it.key }.mapNotNull { (epNum, links) ->
                val distinctLinks = links.distinct()
                if (distinctLinks.isEmpty()) return@mapNotNull null
                // Pass links joined by "|" so there is ZERO JSON parsing failure!
                newEpisode(distinctLinks.joinToString("|")) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                val fallbackData = allLinksForMovie.distinct().ifEmpty { listOf(url) }.joinToString("|")
                listOf(
                    newEpisode(fallbackData) {
                        this.name = "Episode 1"
                        this.episode = 1
                    }
                )
            }

            newTvSeriesLoadResponse(title, url, TvType.TvSeries, episodes) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    private fun isValidCandidateUrl(href: String): Boolean {
        if (href.isBlank() || !href.startsWith("http")) return false
        val lower = href.lowercase()
        if (lower.contains("/category/") ||
            lower.contains("/tag/") ||
            lower.contains("/page/") ||
            lower.contains("/upcoming-series-list") ||
            lower.contains("/dmca") ||
            lower.contains("/about-us") ||
            lower.contains("wp-content") ||
            lower.contains("t.me") ||
            lower.contains("telegram.me") ||
            lower.contains("facebook.com") ||
            lower.contains("twitter.com") ||
            lower.contains("instagram.com") ||
            lower.contains("blogger.googleusercontent.com") ||
            lower.contains("wp.com/blogger") ||
            lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|svg)(\\?.*)?$"))
        ) {
            return false
        }
        return true
    }

    private fun isDirectServerHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("momofile.shop") ||
               u.contains("pixeldrain.dev") ||
               u.contains("pixeldrain.com") ||
               u.contains("send.now") ||
               u.contains("sendnow") ||
               u.contains("vik1ngfile.site") ||
               u.contains("vikingfile") ||
               u.contains("fileditchvids.st") ||
               u.contains("fileditch") ||
               u.contains("theditch") ||
               u.contains("tmbcloud.lol") ||
               u.contains("susanexpectcare") ||
               u.contains("voe.sx") ||
               u.contains("xcloud") ||
               u.contains("buzzheavier")
    }

    private fun parseEpisodesFromDoc(
        doc: Document,
        episodeMap: LinkedHashMap<Int, MutableList<String>>,
        allLinksForMovie: MutableList<String>
    ) {
        val contentRoot = doc.selectFirst(".entry-content, .herald-entry-content") ?: doc.body()
        val elements = contentRoot.select("h1, h2, h3, h4, h5, h6, p, div, li, strong, b, span, a[href]")

        var currentEp = 1

        for (el in elements) {
            if (el.tagName() != "a") {
                val ownText = el.ownText().trim()
                val epMatch = Regex("""(?i)(?:Episode|Ep)\s*[-–:=\s]*0*(\d{1,3})\b""").find(ownText)
                if (epMatch != null) {
                    currentEp = epMatch.groupValues[1].toIntOrNull() ?: currentEp
                }
            } else {
                val href = fixUrl(el.attr("href").trim())
                val linkText = el.text().trim()

                if (!isValidCandidateUrl(href)) continue

                // Check if the link text itself or its parent paragraph has the Episode number
                val linkEp = Regex("""(?i)(?:Episode|Ep)\s*[-–:=\s]*0*(\d{1,3})\b""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                    ?: Regex("""(?i)(?:Episode|Ep)\s*[-–:=\s]*0*(\d{1,3})\b""").find(el.parent()?.text().orEmpty())?.groupValues?.get(1)?.toIntOrNull()

                if (linkEp != null) {
                    currentEp = linkEp
                }

                val isServerLink = isDirectServerHost(href) ||
                    href.contains("redir") ||
                    href.contains("fifa") ||
                    href.contains("?url=") ||
                    href.contains("goto") ||
                    linkText.contains("MultipleLink", true) ||
                    linkText.contains("Momofile", true) ||
                    linkText.contains("Pixeldrain", true) ||
                    linkText.contains("Tmbcloud", true) ||
                    linkText.contains("XCloud", true) ||
                    linkText.contains("Watch", true) ||
                    linkText.contains("Server", true) ||
                    (linkText.contains("Download", true) && !linkText.contains("DOWNLOAD LINKS", true))

                if (isServerLink) {
                    episodeMap.getOrPut(currentEp) { mutableListOf() }.add(href)
                    allLinksForMovie.add(href)
                }
            }
        }
    }

    private suspend fun followRedirect(url: String, referer: String): String {
        var targetUrl = url
        try {
            if (targetUrl.contains("redir") || targetUrl.contains("fifa") || targetUrl.contains("?url=") || targetUrl.contains("goto")) {
                var res = app.get(targetUrl, referer = referer, allowRedirects = true)
                if (res.code in listOf(403, 503) || res.text.contains("Just a moment", true)) {
                    res = app.get(targetUrl, referer = referer, interceptor = cfKiller, allowRedirects = true)
                }
                targetUrl = res.url
                val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
                if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                    targetUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
                } else {
                    val scriptRedirect = Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""").find(res.text)?.groupValues?.get(1)
                    if (!scriptRedirect.isNullOrBlank()) targetUrl = scriptRedirect
                }
            }
        } catch (_: Exception) {}
        return targetUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val extractor = KDHindiDubbedExtractor()

        // Split our pipe-separated links (completely avoids JSON parsing bugs!)
        val rawUrls = data.split("|")
            .map { it.trim() }
            .filter { it.startsWith("http") }
            .distinct()

        if (rawUrls.isEmpty()) return false

        for (sourceUrl in rawUrls) {
            try {
                extractor.getUrl(sourceUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
        return true
    }
}

