package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.JsUnpacker
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

        val title = document.selectFirst("h1")?.text()
            ?.substringBefore("– Episode")
            ?.substringBefore("- Episode")
            ?.trim()
            ?: document.selectFirst(".dcard h3")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
                ?.removePrefix("Watch ")
                ?.substringBefore(" Episode ")
                ?.substringBefore(" Hindi Dubbed")
                ?.substringBefore("|")
                ?.trim()
            ?: return null

        val poster = fixUrlNull(
            document.selectFirst("img[src*='themoviedb.org'], .dcard img, .poster img")?.let {
                it.attr("data-src").ifEmpty { it.attr("src") }
            } ?: document.selectFirst("meta[property=og:image]")?.attr("content")
        )

        val description = document.selectFirst(".about p")?.text()?.trim()
            ?: document.selectFirst("meta[name=description]")?.attr("content")?.trim()

        val genreTags = document.select(".meta .chip:not(.star), .genres a")
            .map { it.text().trim() }
            .filter {
                it.isNotEmpty() &&
                    !it.contains("views", ignoreCase = true) &&
                    !it.matches(Regex("""^\d{4}$""")) &&
                    !it.matches(Regex("""^\d+h.*"""))
            }

        val episodes = document.select(".eplist ul li a, a[href*='/hindi-dubbed/watch-']").mapNotNull { el ->
            val anchor = if (el.tagName() == "a") el else el.selectFirst("a") ?: return@mapNotNull null
            val rawHref = anchor.attr("href").trim()
            if (rawHref.isEmpty() || rawHref.contains("/hindi-dubbed-online/")) return@mapNotNull null
            val epHref = fixUrl(rawHref)

            val epTitle = anchor.text().trim().ifEmpty { "Episode" }
            val epNum = Regex("""(?:Episode|Ep|E|e)\s*(\d+)""")
                .find(epTitle)
                ?.groupValues?.get(1)?.toIntOrNull()
                ?: Regex("""-e(\d+)""").find(epHref)?.groupValues?.get(1)?.toIntOrNull()

            newEpisode(epHref) {
                this.name = epTitle
                this.episode = epNum
                this.posterUrl = poster
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

        val internalEmbedSrc = document.selectFirst("#player iframe, iframe[src*='/embed/']")?.attr("src")?.trim()
            ?: Regex("""var\s+current\s*=\s*["']([^"']+)["']""").find(document.html())?.groupValues?.get(1)?.replace("\\/", "/")

        val playerPageUrl = if (!internalEmbedSrc.isNullOrEmpty()) fixUrl(internalEmbedSrc) else data
        val playerHtml = if (!internalEmbedSrc.isNullOrEmpty()) {
            app.get(playerPageUrl, referer = data).text
        } else {
            document.html()
        }

        val playerDoc = org.jsoup.Jsoup.parse(playerHtml, playerPageUrl)
        val serverList = linkedMapOf<String, String>()

        playerDoc.select(".srv, [data-src], [data-url], [data-embed]").forEach { el ->
            val sName = el.text().trim().ifEmpty { "Server" }
            val rawUrl = listOf("data-src", "data-url", "data-embed")
                .firstNotNullOfOrNull { attr -> el.attr(attr).trim().takeIf { it.isNotEmpty() } }
            if (rawUrl != null && (rawUrl.startsWith("http") || rawUrl.startsWith("//") || rawUrl.startsWith("/"))) {
                serverList[fixUrl(rawUrl)] = sName
            }
        }

        playerDoc.select("iframe").forEach { el ->
            val src = el.attr("data-src").ifEmpty { el.attr("src") }.trim()
            if (src.isNotEmpty() && !src.startsWith("about:")) {
                serverList.putIfAbsent(fixUrl(src), "Embed")
            }
        }

        val urlRegex = Regex("""https?:\\?/\\?/[a-zA-Z0-9.-]+\.[a-zA-Z]{2,}(?:\\?/[^"'\s<>\+)""")
        urlRegex.findAll(playerHtml).forEach { match ->
            val cleanUrl = match.value.replace("\\/", "/")
            if (!cleanUrl.contains("hindidrama.net") &&
                !cleanUrl.contains("googletagmanager") &&
                !cleanUrl.contains("cloudflare") &&
                !cleanUrl.contains("bancadeltempoidea") &&
                !cleanUrl.contains("themoviedb") &&
                !cleanUrl.contains("googleapis") &&
                !cleanUrl.contains("gstatic")
            ) {
                serverList.putIfAbsent(cleanUrl, cleanUrl.substringAfter("://").substringBefore("/"))
            }
        }

        val userAgent = "Mozilla/5.0 (Linux; Android 15; Infinix X6851) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Mobile Safari/537.36"

        for ((embedUrl, serverLabel) in serverList) {
            val builtInLoaded = loadExtractor(embedUrl, playerPageUrl, subtitleCallback, callback) ||
                loadExtractor(embedUrl, "$mainUrl/", subtitleCallback, callback)

            if (builtInLoaded) {
                foundLinks = true
                continue
            }

            try {
                val headers = mapOf(
                    "User-Agent" to userAgent,
                    "Referer" to playerPageUrl
                )
                val embedHtml = app.get(embedUrl, referer = playerPageUrl, headers = headers).text
                val unpacked = JsUnpacker(embedHtml).unpack() ?: embedHtml
                val combined = "$embedHtml\n$unpacked"

                val streamRegex = Regex(
                    """["'](https?://[^"'\s<>]+?\.(?:m3u8|mp4)(?:\?[^"'\s<>]*)?)["']""",
                    RegexOption.IGNORE_CASE
                )
                val streamUrl = streamRegex.findAll(combined) // Fixed variable name to streamUrl
                    .map { it.groupValues[1].replace("\\/", "/") }
                    .firstOrNull { !it.contains("preview") && !it.contains("thumb") }

                if (!streamUrl.isNullOrEmpty()) {
                    callback.invoke(
                        newExtractorLink(
                            source = serverLabel,
                            name = serverLabel,
                            url = streamUrl
                        ) {
                            this.referer = embedUrl
                            this.quality = 0
                            this.headers = mapOf(
                                "User-Agent" to userAgent,
                                "Referer" to embedUrl
                            )
                        }
                    )
                    foundLinks = true
                }
            } catch (_: Exception) {
            }
        }

        return foundLinks
    }
}
