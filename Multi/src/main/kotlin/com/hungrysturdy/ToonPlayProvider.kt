package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson

class ToonPlayProvider : MainAPI() {
    override var mainUrl = "https://toonplay.in"
    override var name = "ToonPlay"
    override val hasMainPage = true
    override var lang = "hi"
    override val hasDownloadSupport = true
    override val supportedTypes = setOf(
        TvType.Anime,
        TvType.AnimeMovie,
        TvType.OVA,
        TvType.Movie,
        TvType.TvSeries,
        TvType.Cartoon
    )

    override val mainPage = mainPageOf(
        "$mainUrl/" to "Home / Latest",
        "$mainUrl/category/anime/" to "Anime",
        "$mainUrl/category/cartoons/" to "Cartoons",
        "$mainUrl/category/movies/" to "Movies",
        "$mainUrl/category/series/" to "Series",
        // Languages & Audio
        "$mainUrl/category/hindi-dubbed/" to "Language: Hindi",
        "$mainUrl/category/telugu-dubbed/" to "Language: Telugu",
        "$mainUrl/category/tamil-dubbed/" to "Language: Tamil",
        "$mainUrl/category/malayalam-dubbed/" to "Language: Malayalam",
        "$mainUrl/category/kannada-dubbed/" to "Language: Kannada",
        "$mainUrl/category/bengali-dubbed/" to "Language: Bengali",
        "$mainUrl/category/english-dubbed/" to "Language: English",
        "$mainUrl/category/japanese/" to "Language: Japanese",
        "$mainUrl/category/korean/" to "Language: Korean"
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val pageUrl = if (page <= 1) {
            request.data
        } else {
            val trimmed = request.data.removeSuffix("/")
            "$trimmed/page/$page/"
        }

        val html = app.get(pageUrl).text
        val doc = Jsoup.parse(html)
        
        val home = doc.select("article, div.post, div.item, .grid-item, a:has(img)").mapNotNull { it.toSearchResult() }

        return newHomePageResponse(request.name, home, hasNext = home.isNotEmpty())
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val titleEl = this.selectFirst("a[title], h2 a, h3 a, .poster-title, .entry-title a")
            ?: (if (this.tagName() == "a") this else this.selectFirst("a[href]"))
            ?: return null

        val rawTitle = titleEl.attr("title").takeIf { it.isNotBlank() }
            ?: titleEl.text().trim()
            .ifBlank { this.selectFirst("img")?.attr("alt")?.trim().orEmpty() }

        val title = rawTitle.replace("Permalink to ", "", ignoreCase = true).trim()
        if (title.isBlank()) return null

        val hrefTag = if (this.tagName() == "a") this else (titleEl.takeIf { it.tagName() == "a" } ?: this.selectFirst("a[href]"))
        val href = fixUrl(hrefTag?.attr("href").orEmpty())
        if (href.isBlank() || href == mainUrl || href == "$mainUrl/") return null

        val img = this.selectFirst("img")
        val posterUrl = fixUrl(
            img?.attr("data-lazy-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("data-src")?.takeIf { it.isNotBlank() }
                ?: img?.attr("srcset")?.substringBefore(" ")?.takeIf { it.isNotBlank() }
                ?: img?.attr("src").orEmpty()
        )

        val isDub = title.contains("Hindi", true) || title.contains("Dub", true) || title.contains("Tamil", true) || title.contains("Telugu", true) || title.contains("Malayalam", true) || title.contains("Kannada", true) || title.contains("Bengali", true)
        val isSub = title.contains("Sub", true)
        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        return newAnimeSearchResponse(title, href, tvType) {
            this.posterUrl = posterUrl
            this.addDubStatus(isDub, isSub)
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val cleanQuery = query.trim()
        if (cleanQuery.isBlank()) return emptyList()
        val encodedQuery = java.net.URLEncoder.encode(cleanQuery, "UTF-8")

        val searchUrl = "$mainUrl/?s=$encodedQuery"
        val html = app.get(searchUrl).text
        if (html.isBlank()) return emptyList()

        val doc = Jsoup.parse(html)
        val results = doc.select("article, div.post, div.item, .grid-item, a:has(img)")
            .mapNotNull { it.toSearchResult() }
            .filter { !it.url.contains("/category/") && !it.url.contains("/tag/") && !it.url.contains("/page/") }
            .distinctBy { it.url }

        return results
    }

    override suspend fun load(url: String): LoadResponse {
        val html = app.get(url).text
        val doc = Jsoup.parse(html)

        val title = doc.selectFirst("h1.entry-title, h1.title, .single-post-title")?.text()?.trim() 
            ?: doc.selectFirst("meta[property='og:title']")?.attr("content")?.substringBefore(" - ")
            ?: "ToonPlay Content"
            
        val poster = fixUrl(
            doc.selectFirst("meta[property='og:image']")?.attr("content")
                ?: doc.selectFirst(".single-entry-body img, .entry-content img, .post-image img")?.attr("src")
                ?: ""
        )
        
        val description = doc.selectFirst(".entry-content p, .xp-plot-box, .description")?.text()?.trim()
            ?: doc.selectFirst("meta[property='og:description']")?.attr("content")
            ?: "No Plot Found"

        val tvType = if (title.contains("Movie", true)) TvType.AnimeMovie else TvType.Anime

        val episodes = mutableListOf<Episode>()
        val movieLinks = mutableListOf<String>()

        doc.select("a.wp-download-btn, a.btn, a.btn-download, .entry-content a[href]").forEach { element ->
            val epLink = element.attr("href")
            val epName = element.text().trim()

            if (epLink.isNotBlank() && (epLink.startsWith("http") || epLink.startsWith("/"))) {
                val absoluteLink = fixUrl(epLink)
                if (tvType == TvType.AnimeMovie) {
                    movieLinks.add(absoluteLink)
                } else {
                    val epNum = Regex("""(?i)ep(?:isode)?\.?\s*0*(\d{1,3})""").find(epName)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (episodes.size + 1)
                    
                    episodes.add(
                        newEpisode(absoluteLink) {
                            this.name = epName.ifBlank { "Episode $epNum" }
                            this.episode = epNum
                        }
                    )
                }
            }
        }

        return if (tvType == TvType.AnimeMovie || episodes.isEmpty()) {
            val data = mapper.writeValueAsString(movieLinks.ifEmpty { listOf(url) })
            newMovieLoadResponse(title, url, TvType.AnimeMovie, data) {
                this.posterUrl = poster
                this.plot = description
            }
        } else {
            newTvSeriesLoadResponse(title, url, TvType.Anime, episodes.distinctBy { it.data }) {
                this.posterUrl = poster
                this.plot = description
            }
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCasting: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        return try {
            val links = tryParseJson<List<String>>(data) ?: listOf(data)
            var found = false

            for (link in links) {
                if (link.contains("filemoon") || link.contains("streamwish") || link.contains("vidhide") || link.contains("streamtape") || link.contains("voe") || link.contains("abyss")) {
                    loadExtractor(link, "$mainUrl/", subtitleCallback, callback)
                    found = true
                } else {
                    try {
                        val doc = app.get(link).document
                        doc.select("iframe").forEach { iframe ->
                            val src = iframe.attr("src").takeIf { it.isNotBlank() } ?: iframe.attr("data-src")
                            if (!src.isNullOrBlank()) {
                                loadExtractor(fixUrl(src), link, subtitleCallback, callback)
                                found = true
                            }
                        }
                        doc.select("a[href]").forEach { a ->
                            val href = a.attr("href")
                            if (href.contains("filemoon") || href.contains("streamwish") || href.contains("vidhide") || href.contains("streamtape") || href.contains("voe") || href.contains("abyss")) {
                                loadExtractor(href, link, subtitleCallback, callback)
                                found = true
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
            found
        } catch (e: Exception) {
            false
        }
    }
}
