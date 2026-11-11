package com.starfilx

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.parseJson
import com.lagradost.cloudstream3.utils.AppUtils.toJson
import org.jsoup.nodes.Element

class StarfilxProvider : MainAPI() {
    override var mainUrl = "https://www.starfilx.in"
    override var name = "Starfilx"
    override val hasMainPage = true
    override var lang = "hi"
    override val supportedTypes = setOf(
        TvType.AsianDrama,
        TvType.TvSeries,
        TvType.Movie
    )

    override val mainPage = mainPageOf(
        "" to "Latest Updates",
        "hindi-dub-drama/" to "Hindi Dubbed",
        "k-drama/" to "K-Drama",
        "chinese-dramas/" to "Chinese Dramas",
        "ongoing/" to "Ongoing",
        "completed-dramas/" to "Completed Dramas",
        "english-dub-drama/" to "English Dubbed",
        "japanese-drama/" to "Japanese Drama",
        "thai-drama/" to "Thai Drama",
        "turkish-drama/" to "Turkish Drama",
        "korean-dub-movies/" to "Korean Dub Movies",
        "anime-movies-hindi-dubbed/" to "Anime Movies"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        val document = app.get(url).document
        val homeItems = mutableListOf<SearchResponse>()

        // 1. Parse standard WordPress Jannah post grid
        document.select("ul#posts-container li.post-item").mapNotNullTo(homeItems) {
            it.toSearchResult()
        }

        // 2. Fallback: If on homepage page 1, also grab unique items from top carousel (.ams-c)
        if (page == 1 && request.data.isEmpty()) {
            document.select("div.ams-tr div.ams-c:not(.clone)").forEach { card ->
                val item = card.toCarouselSearchResult()
                if (item != null && homeItems.none { it.url == item.url }) {
                    homeItems.add(item)
                }
            }
        }

        return newHomePageResponse(request.name, homeItems)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.post-title a") ?: return null
        val rawTitle = titleElement.text().trim()
        val cleanTitle = cleanTitle(rawTitle)
        val href = fixUrl(titleElement.attr("href"))

        val imgElement = this.selectFirst("a.post-thumb img")
        val posterUrl = imgElement?.let { img ->
            val srcset = img.attr("srcset")
            if (srcset.isNotBlank()) {
                srcset.split(",").lastOrNull()?.trim()?.split(" ")?.firstOrNull()
            } else {
                img.attr("data-src").ifBlank { img.attr("src") }
            }
        }

        return newAnimeSearchResponse(cleanTitle, href, TvType.AsianDrama) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = rawTitle.contains("HINDI", ignoreCase = true) ||
                           rawTitle.contains("DUB", ignoreCase = true),
                subExist = true
            )
        }
    }

    private fun Element.toCarouselSearchResult(): SearchResponse? {
        val onclick = this.attr("onclick")
        val href = Regex("""window\.location\s*=\s*['"]([^'"]+)['"]""")
            .find(onclick)?.groupValues?.get(1) ?: return null

        val rawTitle = this.selectFirst("h3.ams-h3")?.text()?.trim() ?: return null
        val cleanTitle = cleanTitle(rawTitle)
        val posterUrl = this.selectFirst("div.ams-img img")?.attr("src")

        return newAnimeSearchResponse(cleanTitle, fixUrl(href), TvType.AsianDrama) {
            this.posterUrl = posterUrl
            addDubStatus(
                dubExist = rawTitle.contains("HINDI", ignoreCase = true),
                subExist = true
            )
        }
    }

    private fun cleanTitle(title: String): String {
        return title
            .replace(Regex("\\|\\s*STARFILX.*", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\((official\\s*)?HINDI DUBBED\\)", RegexOption.IGNORE_CASE), "")
            .replace(Regex("\\(fixed\\)", RegexOption.IGNORE_CASE), "")
            .trim()
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val url = "$mainUrl/?s=${java.net.URLEncoder.encode(query, "UTF-8")}"
        val document = app.get(url).document

        return document.select("ul#posts-container li.post-item").mapNotNull {
            it.toSearchResult()
        }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document

        val rawTitle = document.selectFirst("h1.post-title, h1.entry-title")?.text()?.trim()
            ?: document.selectFirst("meta[property=og:title]")?.attr("content")
            ?: "Unknown Title"
        val title = cleanTitle(rawTitle)

        val poster = document.selectFirst("meta[property=og:image]")?.attr("content")
            ?: document.selectFirst("div.single-big-img img, figure.single-featured-image img")?.attr("src")

        val description = document.selectFirst("div.entry-content p")?.text()?.trim()
        val tags = document.select("span.post-cat-wrap a, div.post-bottom-meta a").map { it.text() }

        val contentArea = document.selectFirst("div.entry-content, article")

        // Map of Episode Number -> List of Server URLs (e.g. ep 1(terabox), ep 1(FileNext App))
        val episodeMap = linkedMapOf<Int, MutableList<String>>()
        val unnumberedLinks = mutableListOf<Pair<String, String>>()

        contentArea?.select("a.button, a.wp-block-button__link, a[href]")?.forEach { a ->
            val text = a.text().trim()
            val href = a.attr("href").trim()

            // Ignore Telegram links, empty anchors, or internal category/tag links
            if (href.isBlank() ||
                href.startsWith("#") ||
                href.contains("t.me/") ||
                href.contains("starfilx.in/")
            ) {
                return@forEach
            }

            // Match pattern like: "ep 1(terabox)" or "ep 1(FileNext App)" or "Episode 04"
            val epMatch = Regex("""(?:ep|episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add(href)
                    return@forEach
                }
            }

            // Fallback if button is a direct host link without an "ep X" label
            if (a.hasClass("button") ||
                href.contains("terabox") ||
                href.contains("filenext") ||
                href.contains("gofile") ||
                href.contains("hubcloud") ||
                href.contains("gdflix") ||
                href.contains("rpmstream")
            ) {
                unnumberedLinks.add(text to href)
            }
        }

        val episodes = mutableListOf<Episode>()

        if (episodeMap.isNotEmpty()) {
            episodeMap.entries.sortedBy { it.key }.forEach { (epNum, urls) ->
                episodes.add(
                    newEpisode(urls.distinct().toJson()) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    }
                )
            }
        } else if (unnumberedLinks.isNotEmpty()) {
            unnumberedLinks.forEachIndexed { index, (label, link) ->
                episodes.add(
                    newEpisode(listOf(link).toJson()) {
                        this.name = label.ifBlank { "Episode ${index + 1}" }
                        this.episode = index + 1
                    }
                )
            }
        }

        // Also check if there are embedded iframes directly inside the post
        if (episodes.isEmpty()) {
            contentArea?.select("iframe[src]")?.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (!src.contains("a-ads.com") && !src.contains("google")) {
                    episodes.add(
                        newEpisode(listOf(fixUrl(src)).toJson()) {
                            this.name = "Episode ${index + 1}"
                            this.episode = index + 1
                        }
                    )
                }
            }
        }

        return newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
            this.posterUrl = poster
            this.plot = description
            this.tags = tags
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        // Parse the JSON list of server links for the selected episode
        val urls: List<String> = try {
            parseJson<List<String>>(data)
        } catch (_: Exception) {
            listOf(data)
        }

        urls.amap { rawUrl ->
            var targetUrl = rawUrl

            // 1. Unwrap startfilx.com / arolinks.com Base64 shortener if present
            if (targetUrl.contains("?url=")) {
                val encodedPart = targetUrl.substringAfter("?url=").substringBefore("&")
                try {
                    targetUrl = base64Decode(encodedPart)
                } catch (_: Exception) {}
            }

            // 2. Normalize 1024terabox.com / teraboxapp.com / freeterabox.com for CloudStream's built-in Terabox extractor
            if (targetUrl.contains("1024terabox.com") ||
                targetUrl.contains("teraboxapp.com") ||
                targetUrl.contains("teraboxlink.com") ||
                targetUrl.contains("freeterabox.com")
            ) {
                targetUrl = targetUrl.replace(
                    Regex("https?://(www\\.)?(1024terabox|teraboxapp|teraboxlink|freeterabox)\\.com"),
                    "https://www.terabox.com"
                )
            }

            when {
                // 3. Handle FileNext links (filenext.in/s/...)
                targetUrl.contains("filenext.in") -> {
                    val doc = app.get(targetUrl, referer = mainUrl).document
                    // Check for direct video/download links or embedded player on FileNext page
                    doc.select("video source[src], a[href*=.mp4], a[href*=.mkv], a.download-button[href]").forEach { el ->
                        val mediaUrl = el.attr("src").ifBlank { el.attr("href") }
                        if (mediaUrl.startsWith("http")) {
                            callback.invoke(
                                ExtractorLink(
                                    source = this.name,
                                    name = "FileNext",
                                    url = mediaUrl,
                                    referer = targetUrl,
                                    quality = Qualities.Unknown.value,
                                    isM3u8 = mediaUrl.contains(".m3u8")
                                )
                            )
                        }
                    }
                    doc.select("iframe[src]").forEach { iframe ->
                        loadExtractor(fixUrl(iframe.attr("src")), targetUrl, subtitleCallback, callback)
                    }
                }

                // 4. Handle Starfilx RPMStream links
                targetUrl.contains("rpmstream.live") -> {
                    val doc = app.get(targetUrl, referer = mainUrl).document
                    doc.select("iframe[src]").forEach {
                        loadExtractor(fixUrl(it.attr("src")), targetUrl, subtitleCallback, callback)
                    }
                    val scriptData = doc.select("script").html()
                    Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(scriptData).forEach { match ->
                        M3u8Helper.generateM3u8(
                            "${this.name} RPMStream",
                            match.groupValues[1],
                            targetUrl
                        ).forEach(callback)
                    }
                }

                // 5. Pass Terabox, GoFile, GDFlix, HubCloud, Dailymotion, Google Drive to CloudStream extractors
                else -> {
                    loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }
}
