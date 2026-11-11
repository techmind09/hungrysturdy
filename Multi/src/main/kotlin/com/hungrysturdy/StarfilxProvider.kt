package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
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
        "k-drama/" to "K-Drama",
        "chinese-dramas/" to "Chinese Dramas",
        "hindi-dub-drama/" to "Hindi Dubbed",
        "english-dub-drama/" to "English Dubbed",
        "ongoing/" to "Ongoing",
        "completed-dramas/" to "Completed Dramas",
        "japanese-drama/" to "Japanese Drama",
        "thai-drama/" to "Thai Drama"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val url = if (page == 1) {
            "$mainUrl/${request.data}"
        } else {
            "$mainUrl/${request.data}page/$page/"
        }

        val document = app.get(url).document
        val home = document.select("ul#posts-container li.post-item").mapNotNull {
            it.toSearchResult()
        }

        return newHomePageResponse(request.name, home)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.post-title a") ?: return null
        val rawTitle = titleElement.text().trim()
        val cleanTitle = cleanTitle(rawTitle)
        val href = fixUrl(titleElement.attr("href"))

        val imgElement = this.selectFirst("a.post-thumb img")
        val posterUrl = imgElement?.let { img ->
            // Extract the highest resolution image from srcset if available, fallback to src
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
                           rawTitle.contains("DUBBED", ignoreCase = true),
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
        val url = "$mainUrl/?s=$query"
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

        val episodes = mutableListOf<Episode>()
        val contentArea = document.selectFirst("div.entry-content, article")

        // 1. Parse Episode Links / Download Buttons inside the post
        contentArea?.select("a[href]")?.forEach { a ->
            val linkText = a.text().trim()
            val href = a.attr("href")

            // Skip Telegram, social, or internal navigation links
            if (href.contains("t.me") || href.contains("starfilx.in/category") || href.startsWith("#")) {
                return@forEach
            }

            if (linkText.contains(Regex("Ep(isode)?\\s*\\d+|Watch|Download|Server|Link", RegexOption.IGNORE_CASE)) ||
                a.hasClass("button") || a.hasClass("wp-block-button__link")
            ) {
                val epNum = Regex("(\\d+)").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                episodes.add(
                    newEpisode(href) {
                        this.name = linkText.ifBlank { "Episode ${epNum ?: (episodes.size + 1)}" }
                        this.episode = epNum
                    }
                )
            }
        }

        // 2. Parse direct embedded iframes if episodes aren't listed as buttons
        if (episodes.isEmpty()) {
            contentArea?.select("iframe[src]")?.forEachIndexed { index, iframe ->
                val src = iframe.attr("src")
                if (!src.contains("a-ads.com") && !src.contains("google")) {
                    episodes.add(
                        newEpisode(fixUrl(src)) {
                            this.name = "Episode ${index + 1}"
                            this.episode = index + 1
                        }
                    )
                }
            }
        }

        return if (episodes.size > 1 || rawTitle.contains(Regex("ep\\s*\\d+|complete|season", RegexOption.IGNORE_CASE))) {
            newTvSeriesLoadResponse(title, url, TvType.AsianDrama, episodes) {
                this.posterUrl = poster
                this.plot = description
                this.tags = tags
            }
        } else {
            val movieData = episodes.firstOrNull()?.data ?: url
            newMovieLoadResponse(title, url, TvType.AsianDrama, movieData) {
                this.posterUrl = poster
                this.plot = description
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
        var targetUrl = data

        // Unwrap startfilx.com / full-page-script Base64 shortener if present
        if (targetUrl.contains("?url=")) {
            val encodedPart = targetUrl.substringAfter("?url=").substringBefore("&")
            try {
                targetUrl = base64Decode(encodedPart)
            } catch (_: Exception) {}
        }

        // Handle custom streaming/host domains configured in the site's script
        when {
            targetUrl.contains("rpmstream.live") -> {
                val doc = app.get(targetUrl, referer = mainUrl).document
                doc.select("iframe[src]").forEach {
                    loadExtractor(fixUrl(it.attr("src")), targetUrl, subtitleCallback, callback)
                }
                // Check for direct m3u8 in script tags
                val scriptData = doc.select("script").html()
                Regex("""file\s*:\s*["']([^"']+\.m3u8[^"']*)["']""").findAll(scriptData).forEach { match ->
                    M3u8Helper.generateM3u8(
                        this.name,
                        match.groupValues[1],
                        targetUrl
                    ).forEach(callback)
                }
            }
            else -> {
                loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)
            }
        }

        return true
    }
}
