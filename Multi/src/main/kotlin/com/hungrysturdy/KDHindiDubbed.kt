package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.network.CloudflareKiller
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class KDHindiDubbed : MainAPI() {
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

    companion object {
        const val TMDB_API_KEY = "e76d52f346700d0edf06fb2e938f98ad"
        const val TMDB_ACCESS_TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJhdWQiOiJlNzZkNTJmMzQ2NzAwZDBlZGYwNmZiMmU5MzhmOThhZCIsIm5iZiI6MTc3NzkwNzk4NS4xNiwic3ViIjoiNjlmOGI5MTEzODdlMWExNWY3MzI3MWQxIiwic2NvcGVzIjpbImFwaV9yZWFkIl0sInZlcnNpb24iOjF9.xKkhmPxRCBvBPok8br1xxjIMEHfkAO_jQZQ5nBkX2Cc"
    }

    private val cfKiller by lazy { CloudflareKiller() }

    private suspend fun fetchDoc(url: String, referer: String = mainUrl): Document {
        val headers = mutableMapOf(
            "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Safari/537.36"
        )
        headers.putAll(cfKiller.getCookieHeaders(url).toMap())
        val res = app.get(url, referer = referer, headers = headers, allowRedirects = true)
        if (res.code in listOf(403, 503, 429) || res.text.contains("Just a moment", true) || res.text.contains("cf-browser-verification", true)) {
            return app.get(url, referer = referer, interceptor = cfKiller, allowRedirects = true).document
        }
        return res.document
    }

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest",
        "$mainUrl/category/action/" to "Action",
        "$mainUrl/category/adventure/" to "Adventure",
        "$mainUrl/category/arabic-subtitle/" to "Arabic Subtitle",
        "$mainUrl/category/business/" to "Business",
        "$mainUrl/category/chinese-drama/" to "Chinese Drama",
        "$mainUrl/category/comedy/" to "Comedy",
        "$mainUrl/category/crime/" to "Crime",
        "$mainUrl/category/drama/" to "Drama",
        "$mainUrl/category/english-dubbed/" to "English Dubbed",
        "$mainUrl/category/fantasy/" to "Fantasy",
        "$mainUrl/category/hindi-dubbed/" to "Hindi Dubbed",
        "$mainUrl/category/horror/" to "Horror",
        "$mainUrl/category/korean-drama/" to "Korean Drama",
        "$mainUrl/category/romance/" to "Romance",
        "$mainUrl/category/school/" to "School",
        "$mainUrl/category/thriller/" to "Thriller",
        "$mainUrl/category/movie/" to "Movie"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val baseUrl = request.data.trimEnd('/')
        val pageUrl = if (page <= 1) "$baseUrl/" else "$baseUrl/page/$page/"
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
        val posterUrl = fixUrl(rawImg)

        val isMovie = title.contains("Movie", true) || this.hasClass("category-movie")
        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) { this.posterUrl = posterUrl }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.TvSeries) { this.posterUrl = posterUrl }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encoded = java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val document = fetchDoc("$mainUrl/?s=$encoded")
        return document.select("article.herald-lay-c, article.post, article.type-post")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    private fun isValidCandidateUrl(href: String): Boolean {
        if (href.isBlank() || !href.startsWith("http")) return false
        val lower = href.lowercase()
        return !lower.contains("/category/") &&
               !lower.contains("/tag/") &&
               !lower.contains("/page/") &&
               !lower.contains("t.me") &&
               !lower.contains("telegram.me") &&
               !lower.contains("blogger.googleusercontent.com") &&
               !lower.matches(Regex(".*\\.(jpg|jpeg|png|webp|gif|svg)(\\?.*)?$"))
    }

    private fun isServerOrRedirectUrl(url: String): Boolean {
        val u = url.lowercase()
        return u.contains("momofile") ||
               u.contains("pixeldrain") ||
               u.contains("send.now") ||
               u.contains("sendnow") ||
               u.contains("vik1ngfile") ||
               u.contains("vikingfile") ||
               u.contains("fileditch") ||
               u.contains("theditch") ||
               u.contains("tmbcloud") ||
               u.contains("susanexpectcare") ||
               u.contains("voe.sx") ||
               u.contains("voe.") ||
               u.contains("xcloud") ||
               u.contains("buzzheavier") ||
               u.contains("redir") ||
               u.contains("fifa") ||
               u.contains("?url=") ||
               u.contains("goto") ||
               u.contains("dl.") ||
               u.contains("embed")
    }

    private fun extractEpNum(text: String, href: String, fallback: Int): Int {
        val combined = "$text$href"
        val match = Regex("""(?i)(?:episode|ep|e)\s*[-–:=]?\s*0*(\d{1,3})\b""").find(combined)
        return match?.groupValues?.get(1)?.toIntOrNull() ?: fallback
    }

    override suspend fun load(url: String): LoadResponse {
        val document = fetchDoc(url)
        val title = document.selectFirst("h1.entry-title, h1")?.text()?.trim() ?: "Drama"

        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")?.takeIf { it.isNotBlank() }
                ?: document.selectFirst(".herald-post-thumbnail img, .entry-content img, article img")?.attr("src").orEmpty()
        )

        val description = document.select(".entry-content p, .herald-entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 40 && !it.contains("Download", true) && !it.contains("Episode", true) }
            ?: "Watch $title online."

        val isMovie = title.contains("Movie", true)
        val episodeMap = LinkedHashMap<Int, MutableList<String>>()
        val allLinksForMovie = mutableListOf<String>()

        val contentRoot = document.selectFirst(".entry-content, .herald-entry-content") ?: document.body()
        val links = contentRoot.select("a[href]")
        var fallbackEp = 1

        for (a in links) {
            val href = fixUrl(a.attr("href").trim())
            val text = a.text().trim()
            val parentText = a.parent()?.text().orEmpty()

            if (!isValidCandidateUrl(href)) continue

            val epNum = extractEpNum("$text$parentText", href, fallbackEp)

            if (isServerOrRedirectUrl(href)) {
                episodeMap.getOrPut(epNum) { mutableListOf() }.add(href)
                allLinksForMovie.add(href)
                if (epNum >= fallbackEp) fallbackEp = epNum + 1
            } else {
                try {
                    val resolvedUrl = followRedirect(href, url)
                    if (isServerOrRedirectUrl(resolvedUrl)) {
                        episodeMap.getOrPut(epNum) { mutableListOf() }.add(resolvedUrl)
                        allLinksForMovie.add(resolvedUrl)
                    } else {
                        val subDoc = fetchDoc(resolvedUrl, url)
                        val subLinks = subDoc.select(".entry-content a[href], body a[href]")
                        var subEp = epNum
                        for (subA in subLinks) {
                            val subHref = fixUrl(subA.attr("href").trim())
                            val subText = subA.text().trim()
                            if (isValidCandidateUrl(subHref) && isServerOrRedirectUrl(subHref)) {
                                val finalEp = extractEpNum("$subText${subA.parent()?.text()}", subHref, subEp)
                                episodeMap.getOrPut(finalEp) { mutableListOf() }.add(subHref)
                                allLinksForMovie.add(subHref)
                                if (finalEp >= subEp) subEp = finalEp + 1
                            }
                        }
                    }
                } catch (_: Exception) {}
                if (epNum >= fallbackEp) fallbackEp = epNum + 1
            }
        }

        if (episodeMap.isEmpty() && allLinksForMovie.isEmpty()) {
            links.forEach { el ->
                val h = fixUrl(el.attr("href").trim())
                if (isValidCandidateUrl(h) && !h.contains("kdhindidubbed.cfd")) {
                    allLinksForMovie.add(h)
                    episodeMap.getOrPut(1) { mutableListOf() }.add(h)
                }
            }
        }

        return if (isMovie && episodeMap.size <= 1) {
            newMovieLoadResponse(title, url, TvType.Movie, allLinksForMovie.distinct().joinToString("|")) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, linksList) ->
                newEpisode(linksList.distinct().joinToString("|")) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(allLinksForMovie.distinct().joinToString("|")) {
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
        val rawUrls = data.split("|").map { it.trim() }.filter { it.startsWith("http") }.distinct()
        if (rawUrls.isEmpty()) return false

        for (sourceUrl in rawUrls) {
            try {
                extractor.getUrl(sourceUrl, mainUrl, subtitleCallback, callback)
            } catch (_: Exception) {}
        }
        return true
    }
}
