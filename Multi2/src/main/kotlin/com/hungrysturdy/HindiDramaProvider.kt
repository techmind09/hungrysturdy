package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.newExtractorLink
import org.jsoup.nodes.Element
import java.net.URLEncoder

class HindiDramaProvider : MainAPI() {
    override var mainUrl = "https://hindidrama.net"
    override var name = "HindiDrama"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.TvSeries,
        TvType.AsianDrama,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Latest Added",
        "$mainUrl/trending" to "Trending",
        "$mainUrl/Korean" to "Korean Drama",
        "$mainUrl/Chinese" to "Chinese Drama",
        "$mainUrl/Japanese" to "Japanese Drama",
        "$mainUrl/Indian" to "Indian Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page > 1) {
            if (request.data.contains("?")) "${request.data}&page=$page" else "${request.data}?page=$page"
        } else {
            request.data
        }
        val document = app.get(url).document
        val home = mutableListOf<HomePageList>()

        if (page == 1 && request.data == "$mainUrl/") {
            val top10 = document.select("section.t10 .t10-item").mapNotNull { it.toTop10SearchResponse() }
            if (top10.isNotEmpty()) {
                home.add(HomePageList("Top 10 This Week", top10, isHorizontalImages = true))
            }
        }

        val gridItems = document.select(".grid .card").mapNotNull { it.toSearchResponse() }
        if (gridItems.isNotEmpty()) {
            home.add(HomePageList(request.name, gridItems, isHorizontalImages = false))
        }

        val hasNext = document.selectFirst(".pager a:contains(Next), .pager a:contains(»)") != null
        return newHomePageResponse(home, hasNext)
    }

    private fun Element.extractHref(): String? {
        val anchor = if (this.tagName() == "a") this else this.selectFirst("a")
        val rawHref = anchor?.attr("href")?.trim()
        return if (rawHref.isNullOrEmpty()) null else fixUrl(rawHref)
    }

    private fun Element.extractPoster(): String? {
        val img = this.selectFirst("img") ?: return null
        val src = img.attr("data-src").ifEmpty { img.attr("src") }
        return fixUrlNull(src)
    }

    private fun Element.toTop10SearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href = this.extractHref() ?: return null
        val posterUrl = this.extractPoster()
        val ratingText = this.selectFirst(".t10-rate")?.text()?.replace("★", "")?.trim()

        return newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            this.score = Score.from10(ratingText)
        }
    }

    private fun Element.toSearchResponse(): SearchResponse? {
        val title = this.selectFirst("h3")?.text()?.trim() ?: return null
        val href = this.extractHref() ?: return null
        val posterUrl = this.extractPoster()
        val pillType = this.selectFirst(".pill")?.text()?.trim()
        val isMovie = pillType.equals("Movie", ignoreCase = true)
        val ratingText = this.selectFirst(".rate")?.text()?.replace("★", "")?.trim()

        return if (isMovie) {
            newMovieSearchResponse(title, href, TvType.Movie) {
                this.posterUrl = posterUrl
                this.score = Score.from10(ratingText)
            }
        } else {
            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = posterUrl
                this.score = Score.from10(ratingText)
            }
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val encodedQuery = URLEncoder.encode(query.trim(), "UTF-8")
        val document = app.get("$mainUrl/search.php?q=$encodedQuery").document
        return document.select(".grid .card, .ac a.it").mapNotNull { el ->
            val anchor = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val title = anchor.selectFirst("h3, .t")?.text()?.trim()
                ?: anchor.attr("title").trim().ifEmpty { return@mapNotNull null }
            val rawHref = anchor.attr("href").trim()
            if (rawHref.isEmpty()) return@mapNotNull null
            val href = fixUrl(rawHref)
            val posterUrl = anchor.extractPoster()

            newTvSeriesSearchResponse(title, href, TvType.AsianDrama) {
                this.posterUrl = posterUrl
            }
        }
    }

    override suspend fun load(url: String): LoadResponse? {
        val document = app.get(url).document
        val title = document.selectFirst("h1, .entry-title")?.text()
            ?.substringBefore("– Episode")
            ?.trim()
            ?: document.selectFirst(".dcard h3 a, .dcard h3")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")?.substringBefore("|")?.trim()
            ?: return null

        // Fixed poster selection: prioritize sidebar card/poster image over generic og:image
        val poster = fixUrlNull(
            document.selectFirst(".dcard img, .poster img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".about p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val genreTags = document.select(".meta .chip:not(.star), .genres a")
            .map { it.text().trim() }
            .filter { it.isNotEmpty() && !it.contains("views") && it != "2026" && it != "1h" }

        val episodes = document.select(".eplist ul li a, .strip .ep, a[href*='/hindi-dubbed/watch-']").mapNotNull { el ->
            val anchor = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val rawHref = anchor.attr("href").trim()
            if (rawHref.isEmpty()) return@mapNotNull null
            val epHref = fixUrl(rawHref)

            val epBadge = el.selectFirst(".ep-badge")?.text()?.trim()
            val epTitle = anchor.text().trim().ifEmpty {
                anchor.attr("title").trim().ifEmpty { epBadge ?: "Episode" }
            }
            val epNum = Regex("""(?:Episode|Ep|E|e)\s*(\d+)""")
                .find(epBadge ?: epTitle)
                ?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epBadge ?: epTitle
                this.episode = epNum
                this.posterUrl = el.extractPoster()
            }
        }.distinctBy { it.data }

        return if (episodes.isNotEmpty()) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genreTags
            }
        } else {
            newMovieLoadResponse(title, url, TvType.Movie, url) {
                this.posterUrl = poster
                this.plot = description
                this.tags = genreTags
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val document = app.get(data).document
        var foundLinks = false

        // Find internal embed iframe URL
        val internalEmbedSrc = document.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
            ?: Regex("""var\s+current\s*=\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1)?.replace("\\/", "/")

        val playerDoc = if (!internalEmbedSrc.isNullOrEmpty()) {
            val fullEmbedUrl = fixUrl(internalEmbedSrc)
            app.get(fullEmbedUrl, referer = data).document
        } else {
            document
        }

        val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) fixUrl(internalEmbedSrc) else data

        // Direct server switcher buttons (.srv) iteration to fetch all servers (LuluStream, Abyss, UpBolt, Byse, Vidara, EarnVids)
        val serverButtons = playerDoc.select(".srv")
        if (serverButtons.isNotEmpty()) {
            for (btn in serverButtons) {
                val serverName = btn.text().trim().ifEmpty { "Server" }
                val rawSrc = btn.attr("data-src").ifEmpty { btn.attr("data-url") }.ifEmpty { btn.attr("href") }.trim()
                if (rawSrc.isNotEmpty()) {
                    val serverEmbedUrl = fixUrl(rawSrc)
                    try {
                        val serverHeaders = mapOf(
                            "User-Agent" to "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36",
                            "Referer" to playerPageUrl
                        )
                        val serverDocText = app.get(serverEmbedUrl, headers = serverHeaders).text

                        // Extract m3u8 or mp4 from server page
                        val streamMatch = Regex("""["'](https?://[^"']+\.m3u8[^"']*)["']""").find(serverDocText)
                            ?: Regex("""["'](https?://[^"']+\.mp4[^"']*)["']""").find(serverDocText)

                        val streamUrl = streamMatch?.groupValues?.get(1)?.replace("\\/", "/")

                        if (!streamUrl.isNullOrEmpty() && !streamUrl.contains("preview") && !streamUrl.contains("thumb")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = serverName,
                                    name = serverName,
                                    url = streamUrl
                                ) {
                                    this.referer = serverEmbedUrl
                                    this.quality = 0
                                    this.headers = serverHeaders
                                }
                            )
                            foundLinks = true
                        } else {
                            // Fallback to standard extractor if direct regex didn't match
                            if (loadExtractor(serverEmbedUrl, playerPageUrl, subtitleCallback, callback)) {
                                foundLinks = true
                            }
                        }
                    } catch (e: Exception) {
                        // Try standard extractor as backup
                        if (loadExtractor(serverEmbedUrl, playerPageUrl, subtitleCallback, callback)) {
                            foundLinks = true
                        }
                    }
                }
            }
        }

        // Fallback generic search if no .srv buttons matched
        if (!foundLinks) {
            val embedUrls = linkedSetOf<String>()
            playerDoc.select("iframe, video source").forEach { el ->
                val src = el.attr("data-src").ifEmpty { el.attr("src") }.trim()
                if (src.isNotEmpty() && !src.startsWith("about:")) {
                    embedUrls.add(fixUrl(src))
                }
            }
            for (embedUrl in embedUrls) {
                if (loadExtractor(embedUrl, playerPageUrl, subtitleCallback, callback)) {
                    foundLinks = true
                }
            }
        }

        return foundLinks
    }
}
