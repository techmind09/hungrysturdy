package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import com.lagradost.cloudstream3.utils.AppUtils.parseJson

class KDHindiDubbed : MainAPI() {
    override var mainUrl = "https://dramacool.website"
    override var name = "KDHindiDubbed"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.Movie
    )

    private val cfKiller by lazy { CloudflareKiller() }

    private suspend fun fetchDoc(url: String, referer: String = mainUrl): Document {
        val res = app.get(url, referer = referer, allowRedirects = true)
        if (res.code in listOf(403, 503) || res.text.contains("Just a moment", true) || res.text.contains("cf-browser-verification", true)) {
            return app.get(url, referer = referer, interceptor = cfKiller, allowRedirects = true).document
        }
        return res.document
    }

    override val mainPage = mainPageOf(
        "$mainUrl/page/" to "Latest",
        "$mainUrl/category/action/page/" to "Action",
        "$mainUrl/category/adventure/page/" to "Adventure",
        "$mainUrl/category/arabic-subtitle/page/" to "Arabic Subtitle",
        "$mainUrl/category/business/page/" to "Business",
        "$mainUrl/category/chinese-drama/page/" to "Chinese Drama",
        "$mainUrl/category/chinese-language/page/" to "Chinese Language",
        "$mainUrl/category/comedy/page/" to "Comedy",
        "$mainUrl/category/crime/page/" to "Crime",
        "$mainUrl/category/drama/page/" to "Drama",
        "$mainUrl/category/english-dubbed/page/" to "English Dubbed",
        "$mainUrl/category/family/page/" to "Family",
        "$mainUrl/category/fantasy/page/" to "Fantasy",
        "$mainUrl/category/filipino-language/page/" to "Filipino Language",
        "$mainUrl/category/food/page/" to "Food",
        "$mainUrl/category/hindi-dubbed/page/" to "Hindi Dubbed",
        "$mainUrl/category/historical/page/" to "Historical",
        "$mainUrl/category/horror/page/" to "Horror",
        "$mainUrl/category/japanese-drama/page/" to "Japanese Drama",
        "$mainUrl/category/japanese-language/page/" to "Japanese Language",
        "$mainUrl/category/korean-drama/page/" to "Korean Drama",
        "$mainUrl/category/korean-language/page/" to "Korean Language",
        "$mainUrl/category/law/page/" to "Law",
        "$mainUrl/category/life/page/" to "Life",
        "$mainUrl/category/medical/page/" to "Medical",
        "$mainUrl/category/military/page/" to "Military",
        "$mainUrl/category/movie/page/" to "Movie",
        "$mainUrl/category/music/page/" to "Music",
        "$mainUrl/category/mystery/page/" to "Mystery",
        "$mainUrl/category/philippines-drama/page/" to "Philippines Drama",
        "$mainUrl/category/political/page/" to "Political",
        "$mainUrl/category/psychological/page/" to "Psychological",
        "$mainUrl/category/reality-show/page/" to "Reality Show",
        "$mainUrl/category/romance/page/" to "Romance",
        "$mainUrl/category/school/page/" to "School",
        "$mainUrl/category/sci-fi/page/" to "Sci-Fi",
        "$mainUrl/category/sports/page/" to "Sports",
        "$mainUrl/category/supernatural/page/" to "Supernatural",
        "$mainUrl/category/suspense/page/" to "Suspense",
        "$mainUrl/category/thai-language/page/" to "Thai Language",
        "$mainUrl/category/thailand-drama/page/" to "Thailand Drama",
        "$mainUrl/category/thriller/page/" to "Thriller",
        "$mainUrl/category/youth/page/" to "Youth"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val urlWithoutPage = request.data.substringBeforeLast("page/")
        val pageUrl = if (page == 1) urlWithoutPage else "${urlWithoutPage}page/$page/"

        val document = fetchDoc(pageUrl)
        val home = document.select("article.post, article.item, div.post-item, div.herald-lay-c, article.herald-lay-c").mapNotNull { it.toSearchResult() }
        
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h2 a, h3 a, a.title, h2.title a, .post-title a, .entry-title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href").ifBlank { titleElement.selectFirst("a")?.attr("href") ?: "" })
        
        if (title.isBlank() || href.isBlank()) return null

        var rawImg = ""
        val imgElements = this.select("img")
        for (img in imgElements) {
            rawImg = img.attr("data-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                ?: img.attr("data-cfsrc").takeIf { it.isNotBlank() }
                ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                ?: ""
            if (rawImg.isNotBlank()) break
        }

        if (rawImg.isBlank()) {
            val bgElement = this.selectFirst("[style*=background-image]")
            if (bgElement != null) {
                val style = bgElement.attr("style")
                val bgUrl = Regex("""url\(['"]?(.*?)['"]?\)""").find(style)?.groupValues?.get(1)
                if (!bgUrl.isNullOrBlank()) {
                    rawImg = bgUrl
                }
            }
        }

        val posterUrl = fixUrl(rawImg)
        val type = if (title.contains("Movie", true)) TvType.Movie else TvType.TvSeries
        
        return newMovieSearchResponse(title, href, type) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = fetchDoc("$mainUrl/?s=$encoded")
        
        return document.select("article.post, article.item, div.post-item, div.herald-lay-c, article.herald-lay-c")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)
        val title = document.selectFirst("h1.entry-title, h1, h2.title")?.text()?.trim() ?: "Drama"

        var poster = fixUrl(document.selectFirst("meta[property='og:image']")?.attr("content") ?: "")
        if (poster.isBlank()) {
             val imgElements = document.select("div.poster img, img.wp-post-image, .entry-content img, article img")
             for (img in imgElements) {
                 val rawSrc = img.attr("data-src").takeIf { it.isNotBlank() }
                     ?: img.attr("data-lazy-src").takeIf { it.isNotBlank() }
                     ?: img.attr("src").takeIf { it.isNotBlank() && !it.startsWith("data:") }
                 if (!rawSrc.isNullOrBlank()) {
                     poster = fixUrl(rawSrc)
                     break
                 }
             }
        }

        val description = document.selectFirst("div.storyline, .entry-content p:contains(follows), .entry-content p")?.text()?.trim() ?: "No Plot Found"
        val isMovie = title.contains("Movie", true)

        val episodeMap = LinkedHashMap<Int, MutableList<String>>()
        val allLinksForMovie = mutableListOf<String>()

        val subPageLinks = document.select(".entry-content a[href]").mapNotNull { el ->
            val href = el.attr("href").trim()
            val text = el.text().trim()
            
            if (href.isBlank() || href.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)) || href.contains("blogger.googleusercontent.com")) {
                return@mapNotNull null
            }
            
            if (href.startsWith("http") && (
                    text.contains("DOWNLOAD", true) ||
                    href.contains(mainUrl.substringAfter("https://")) || 
                    href.contains("dramacool")
                ) && !href.contains("/category/") && !href.contains("/tag/") && !href.contains("t.me")
            ) {
                href
            } else null
        }.distinct()

        // Scan main page first
        parseEpisodesFromDoc(document, episodeMap, allLinksForMovie)

        // If no direct links found on main page, scan subpages
        if (episodeMap.isEmpty() && allLinksForMovie.isEmpty()) {
            for (subLink in subPageLinks) {
                try {
                    val resolvedUrl = followRedirect(subLink, url)
                    if (isDirectServerHost(resolvedUrl)) {
                        allLinksForMovie.add(resolvedUrl)
                        episodeMap.getOrPut(1) { mutableListOf() }.add(resolvedUrl)
                    } else if (resolvedUrl.contains(mainUrl.substringAfter("https://"))) {
                        val subDoc = fetchDoc(resolvedUrl, url)
                        parseEpisodesFromDoc(subDoc, episodeMap, allLinksForMovie)
                    }
                } catch (_: Exception) {}
            }
        }

        // Final fallback: just get all raw links from main page
        if (episodeMap.isEmpty() && allLinksForMovie.isEmpty()) {
            document.select(".entry-content a[href]").forEach { el ->
                val h = el.attr("href").trim()
                if (h.startsWith("http") && !h.contains("/category/") && !h.contains("/tag/") && !h.contains("t.me") &&
                    !h.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)) && !h.contains("blogger.googleusercontent.com")) {
                    allLinksForMovie.add(h)
                    episodeMap.getOrPut(1) { mutableListOf() }.add(h)
                }
            }
        }

        return if (isMovie && episodeMap.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, allLinksForMovie.distinct().toJson()) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, links) ->
                newEpisode(links.distinct().toJson()) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(allLinksForMovie.distinct().toJson()) {
                        this.name = "Full Movie / Episode 1"
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

    private fun isDirectServerHost(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("momofile.shop") ||
               u.contains("xcloud") ||
               u.contains("tmbcloud") ||
               u.contains("pixeldrain") ||
               u.contains("theditch") ||
               u.contains("fileditch") ||
               u.contains("vik1ngfile") ||
               u.contains("vikingfile") ||
               u.contains("send.now") ||
               u.contains("susanexpectcare") ||
               u.contains("voe.sx")
    }

    private fun parseEpisodesFromDoc(
        doc: Document,
        episodeMap: LinkedHashMap<Int, MutableList<String>>,
        allLinksForMovie: MutableList<String>
    ) {
        val contentRoot = doc.selectFirst(".entry-content") ?: doc.body()
        val elements = contentRoot.select("h1, h2, h3, h4, h5, h6, p, strong, b, span, a[href], li")

        var currentEp = 1

        for (el in elements) {
            val ownText = el.ownText().ifBlank { el.text() }.trim()

            val epMatch = Regex("""(?i)Episode\s*[-–:]?\s*0*(\d{1,3})""").find(ownText)
                ?: Regex("""(?i)\bEp\s*[-–:]?\s*0*(\d{1,3})\b""").find(ownText)

            if (epMatch != null && el.tagName() != "a") {
                currentEp = epMatch.groupValues[1].toIntOrNull() ?: currentEp
            }

            if (el.tagName() == "a") {
                val href = el.attr("href").trim()
                val linkText = el.text().trim()

                if (href.isBlank() || !href.startsWith("http")) continue
                if (href.contains("/category/") || href.contains("/tag/") || href.contains("t.me") ||
                    href.contains("telegram.me") || href.contains("facebook.com") || href.contains("twitter.com") ||
                    href.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif)$", RegexOption.IGNORE_CASE)) || 
                    href.contains("blogger.googleusercontent.com")) continue

                val linkEp = Regex("""(?i)(?:Episode|Ep)\s*[-–:]?\s*0*(\d{1,3})""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                if (linkEp != null) {
                    currentEp = linkEp
                }

                if (isDirectServerHost(href) ||
                    href.contains("momofile") ||
                    href.contains("redir") ||
                    href.contains("fifa") ||
                    linkText.contains("MultipleLink", true) ||
                    linkText.contains("XCloud", true) ||
                    linkText.contains("Download", true) ||
                    linkText.contains("Watch", true) ||
                    linkText.contains("Server", true)
                ) {
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
        
        return try {
            val sources = parseJson<List<String>>(data)
            if (sources.isNotEmpty()) {
                sources.amap { sourceUrl ->
                    extractor.getUrl(sourceUrl, mainUrl, subtitleCallback, callback)
                }
                true
            } else {
                false
            }
        } catch (e: Exception) {
            // Fallback if data is just a single string URL
            if (data.startsWith("http")) {
                extractor.getUrl(data, mainUrl, subtitleCallback, callback)
                true
            } else {
                false
            }
        }
    }
}
