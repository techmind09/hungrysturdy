package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

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
        val pageUrl = "${request.data}$page"
        val document = app.get(pageUrl).document
        val home = document.select("article.herald-lay-c, div.item, div.post-item, article, div.box").mapNotNull { it.toSearchResult() }
        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleElement = this.selectFirst("h2.entry-title a, h2 a, h3 a, a.title") ?: return null
        val title = titleElement.text().trim()
        val href = fixUrl(titleElement.attr("href"))
        if (title.isBlank() || href.isBlank()) return null

        val imgElement = this.selectFirst("img")
        val rawImg = imgElement?.attr("src")?.takeIf { it.isNotBlank() && !it.startsWith("data:") }
            ?: imgElement?.attr("data-src")?.takeIf { it.isNotBlank() } ?: ""
        val posterUrl = fixUrl(rawImg)

        return newMovieSearchResponse(title, href, TvType.TvSeries) {
            this.posterUrl = posterUrl
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val document = app.get("$mainUrl/?s=$query").document
        return document.select("article.herald-lay-c, div.item, div.post-item, article, div.box")
            .mapNotNull { it.toSearchResult() }
            .distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val document = app.get(url).document
        val title = document.selectFirst("h1.entry-title, h1, h2.title")?.text()?.trim() ?: "Drama"

        val poster = fixUrl(
            document.selectFirst("meta[property='og:image']")?.attr("content")
                ?: document.selectFirst("div.poster img, img.wp-post-image, .entry-content img, article img")?.attr("src")
                ?: ""
        )

        val description = document.selectFirst("div.storyline, .entry-content p:contains(follows), .entry-content p")?.text()?.trim() ?: "No Plot Found"
        val tvType = if (title.contains("Movie", true)) TvType.Movie else TvType.TvSeries

        val episodeMap = LinkedHashMap<Int, MutableList<EpisodeLink>>()
        val allLinksForMovie = mutableListOf<EpisodeLink>()

        // Step 1: Check karo agar main page par "DOWNLOAD LINKS" (kdhindidubbed.cfd etc.) ke intermediate pages hain
        val pagesToScan = mutableListOf<org.jsoup.nodes.Document>()
        pagesToScan.add(document)

        val subPageLinks = document.select(".entry-content a[href]").mapNotNull { el ->
            val href = el.attr("href").trim()
            val text = el.text().trim()
            if (href.startsWith("http") && (text.contains("DOWNLOAD LINKS", true) || href.contains("kdhindidubbed") || (href.contains("dramacool") && href != url))) {
                href
            } else null
        }.distinct()

        for (subLink in subPageLinks) {
            try {
                val res = followRedirect(subLink, url)
                pagesToScan.add(app.get(res, referer = url).document)
            } catch (_: Exception) {}
        }

        // Step 2: Har page ke andar Episode - 01, Episode - 02 aur uske niche ke MultipleLink / XCloud links collect karo
        for (doc in pagesToScan) {
            parseEpisodesFromDoc(doc, episodeMap, allLinksForMovie)
        }

        return if (tvType == TvType.Movie && episodeMap.size <= 1) {
            val movieData = mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })
            newMovieLoadResponse(title, url, TvType.Movie, movieData) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            val episodes = episodeMap.entries.sortedBy { it.key }.map { (epNum, links) ->
                val distinctLinks = links.distinctBy { it.source }
                newEpisode(mapper.writeValueAsString(distinctLinks)) {
                    this.name = "Episode $epNum"
                    this.episode = epNum
                }
            }.ifEmpty {
                listOf(
                    newEpisode(mapper.writeValueAsString(allLinksForMovie.distinctBy { it.source })) {
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

    private fun parseEpisodesFromDoc(
        doc: org.jsoup.nodes.Document,
        episodeMap: LinkedHashMap<Int, MutableList<EpisodeLink>>,
        allLinksForMovie: MutableList<EpisodeLink>
    ) {
        val contentRoot = doc.selectFirst(".entry-content") ?: doc.body()
        // Saare text elements aur links ko top-to-bottom order mein traverse karo
        val elements = contentRoot.select("h1, h2, h3, h4, h5, h6, p, strong, b, span, a[href], li")

        var currentEp = 1
        var hasSeenEpisodeHeading = false

        for (el in elements) {
            val ownText = el.ownText().ifBlank { el.text() }.trim()
            val epMatch = Regex("""(?i)Episode\s*[-–:]?\s*0*(\d{1,3})""").find(ownText)
                ?: Regex("""(?i)\bEp\s*[-–:]?\s*0*(\d{1,3})\b""").find(ownText)

            if (epMatch != null && el.tagName() != "a") {
                currentEp = epMatch.groupValues[1].toIntOrNull() ?: currentEp
                hasSeenEpisodeHeading = true
            }

            if (el.tagName() == "a") {
                val href = el.attr("href").trim()
                val linkText = el.text().trim()

                if (href.isBlank() || !href.startsWith("http")) continue
                if (href.contains("/category/") || href.contains("/tag/") || href.contains("t.me") ||
                    href.contains("telegram.me") || href.contains("facebook.com") || href.contains("twitter.com") ||
                    linkText.contains("DOWNLOAD LINKS", true) || linkText.contains("Telegram", true)) continue

                // Agar link ke khud ke text mein Episode number hai
                val linkEp = Regex("""(?i)(?:Episode|Ep)\s*[-–:]?\s*0*(\d{1,3})""").find(linkText)?.groupValues?.get(1)?.toIntOrNull()
                if (linkEp != null) {
                    currentEp = linkEp
                    hasSeenEpisodeHeading = true
                }

                // Check karo ki ye server link hai (MultipleLink / momofile, XCloud, TMBCloud, redir, etc.)
                if (href.contains("momofile") || href.contains("xcloud") || href.contains("tmbcloud") ||
                    href.contains("redir") || href.contains("fifa") || href.contains("pixeldrain") ||
                    linkText.contains("MultipleLink", true) || linkText.contains("XCloud", true) ||
                    linkText.contains("Download", true) || linkText.contains("Watch", true) || hasSeenEpisodeHeading) {

                    episodeMap.getOrPut(currentEp) { mutableListOf() }.add(EpisodeLink(href, currentEp))
                    allLinksForMovie.add(EpisodeLink(href, null))
                }
            }
        }
    }

    private suspend fun followRedirect(url: String, referer: String): String {
        var targetUrl = url
        if (targetUrl.contains("redir") || targetUrl.contains("fifa") || targetUrl.contains("?url=") || targetUrl.contains("goto")) {
            val res = app.get(targetUrl, referer = referer, allowRedirects = true)
            targetUrl = res.url
            val metaRefresh = res.document.selectFirst("meta[http-equiv=refresh]")?.attr("content")
            if (!metaRefresh.isNullOrBlank() && metaRefresh.contains("url=", true)) {
                targetUrl = metaRefresh.substringAfter("url=").substringAfter("URL=").trim().removeSurrounding("'").removeSurrounding("\"")
            } else {
                val scriptRedirect = Regex("""window\.location(?:\.href)?\s*=\s*["']([^"']+)["']""").find(res.text)?.groupValues?.get(1)
                if (!scriptRedirect.isNullOrBlank()) targetUrl = scriptRedirect
            }
        }
        return targetUrl
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val sources = tryParseJson<ArrayList<EpisodeLink>>(data)
        if (!sources.isNullOrEmpty()) {
            sources.amap { sourceObj ->
                KDHindiDubbedExtractor().getUrl(sourceObj.source, mainUrl, subtitleCallback, callback)
            }
            return true
        }

        if (data.startsWith("http")) {
            KDHindiDubbedExtractor().getUrl(data, mainUrl, subtitleCallback, callback)
            return true
        }
        return false
    }

    data class EpisodeLink(
        val source: String,
        val episode: Int? = null
    )
}
