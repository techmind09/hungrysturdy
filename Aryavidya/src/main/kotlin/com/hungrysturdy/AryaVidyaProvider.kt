package com.hungrysturdy.Aryavidya

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AryaVidyaProvider : MainAPI() {
    override var mainUrl = "https://stream.aryavidya.cc"
    override var name = "AryaVidya"
    override val supportedTypes = setOf(TvType.Documentary, TvType.Others)
    override val hasMainPage = true

    data class ApiResponse(
        @JsonProperty("batches") val batches: List<BatchItem?>?
    )

    data class BatchItem(
        @JsonProperty("batch_id") val batchId: String?,
        @JsonProperty("byName") val byName: String?,
        @JsonProperty("name") val name: String?,
        @JsonProperty("photo") val photo: String?,
        @JsonProperty("exam") val exam: String?
    )

    override suspend fun getMainPage(page: Int, request: MainPageRequest): HomePageResponse {
        val homePages = mutableListOf<HomePageList>()
        val batchCards = mutableListOf<SearchResponse>()

        // 1. PDF Bank Shortcut Option
        batchCards.add(
            newAnimeSearchResponse("📚 PDF Bank (Short Notes, Mindmaps, Formula Sheets)", "$mainUrl/#/pdf-bank", TvType.Documentary) {
                this.posterUrl = "https://static.pw.live/"
            }
        )

        // 2. JSON API se Batches fetch karna
        try {
            val jsonUrl = "$mainUrl/assets/static/json/xylem-courses.json"
            val jsonResponse = app.get(jsonUrl).parsedSafe<ApiResponse>()
            
            jsonResponse?.batches?.mapNotNull { item ->
                if (item == null) return@mapNotNull null
                val title = item.byName ?: item.name ?: item.exam ?: return@mapNotNull null
                val id = item.batchId ?: return@mapNotNull null
                val href = "$mainUrl/#/batch/$id"
                val poster = fixUrlNull(item.photo)

                newAnimeSearchResponse(title, href, TvType.Documentary) {
                    this.posterUrl = poster
                }
            }?.let { batchCards.addAll(it) }
        } catch (_: Exception) {}

        val distinctCards = batchCards.distinctBy { it.url }
        if (distinctCards.isNotEmpty()) {
            homePages.add(HomePageList("Study Material & Batches", distinctCards))
        }

        return newHomePageResponse(homePages)
    }

    private fun Element.toSearchResult(): SearchResponse? {
        val aTag = if (this.tagName() == "a") this else this.selectFirst("a") ?: return null
        val href = fixUrlNull(aTag.attr("href")) ?: return null
        val title = this.selectFirst("h3, .title, .batch-name, p, span")?.text() ?: aTag.text()
        if (title.isBlank()) return null
        val poster = fixUrlNull(this.selectFirst("img")?.attr("src") ?: aTag.selectFirst("img")?.attr("src"))

        return newAnimeSearchResponse(title, href, TvType.Documentary) {
            this.posterUrl = poster
        }
    }

    override suspend fun search(query: String): List<SearchResponse> {
        val results = mutableListOf<SearchResponse>()
        try {
            val jsonUrl = "$mainUrl/assets/static/json/xylem-courses.json"
            val jsonResponse = app.get(jsonUrl).parsedSafe<ApiResponse>()
            
            jsonResponse?.batches?.mapNotNull { item ->
                if (item == null) return@mapNotNull null
                val title = item.byName ?: item.name ?: item.exam ?: return@mapNotNull null
                if (!title.contains(query, ignoreCase = true)) return@mapNotNull null
                val id = item.batchId ?: return@mapNotNull null
                val href = "$mainUrl/#/batch/$id"
                val poster = fixUrlNull(item.photo)

                newAnimeSearchResponse(title, href, TvType.Documentary) {
                    this.posterUrl = poster
                }
            }?.let { results.addAll(it) }
        } catch (_: Exception) {}

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val targetUrl = if (url.contains("/#")) url.replace("/#", "") else url
        val doc = app.get(targetUrl).document
        val title = doc.selectFirst("h1, .batch-title, .title, h3")?.text() ?: "Study Content"
        val poster = fixUrlNull(doc.selectFirst("img.poster, img.banner, img")?.attr("src"))

        // Batches, Subjects, Topics, aur PDF Bank elements ko target karna
        val elements = doc.select("div.lecture-item, .class-row, li.episode-item, .episode, a[href*='watch'], a[href*='video'], .lesson-item, .subject-card, .chapter-item, div.card, a[href*='topic'], a[href*='subject'], a[href*='pdf'], a[href*='deeplink']")
        
        val episodes = mutableListOf<Episode>()
        if (elements.isNotEmpty()) {
            elements.mapIndexedNotNull { index, element ->
                val epName = element.selectFirst(".name, span, h4, p, h3")?.text() ?: "Item ${index + 1}"
                val aTag = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
                val rawHref = aTag.attr("href")
                val epUrl = fixUrlNull(if (rawHref.startsWith("#")) "$mainUrl/$rawHref" else rawHref) ?: return@mapIndexedNotNull null
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        if (episodes.isEmpty()) {
            doc.select("a[href]").mapIndexedNotNull { index, element ->
                val href = element.attr("href")
                if (href.contains("watch") || href.contains("video") || href.contains("lecture") || href.contains("class") || href.contains("topic") || href.contains("subject") || href.contains("pdf")) {
                    val epName = element.text().ifBlank { "Content ${index + 1}" }
                    val epUrl = fixUrlNull(if (href.startsWith("#")) "$mainUrl/$href" else href) ?: return@mapIndexedNotNull null
                    episodes.add(
                        newEpisode(epUrl) {
                            this.name = epName
                            this.episode = index + 1
                        }
                    )
                } else null
            }
        }

        val finalEpisodes = if (episodes.isEmpty()) {
            listOf(newEpisode(url) {
                this.name = title
                this.episode = 1
            })
        } else {
            episodes.distinctBy { it.data }
        }

        return newTvSeriesLoadResponse(title, url, TvType.Documentary, finalEpisodes) {
            this.posterUrl = poster
        }
    }

    override suspend fun loadLinks(
        data: String,
        isCtype: Boolean,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ): Boolean {
        val targetData = if (data.contains("/#")) data.replace("/#", "") else data
        val doc = app.get(targetData).document
        
        val videoUrl = doc.selectFirst("video source")?.attr("src") 
            ?: doc.selectFirst("video")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: doc.selectFirst("source")?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            val fixedVideoUrl = fixUrl(videoUrl)
            if (fixedVideoUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(
                    name,
                    fixedVideoUrl,
                    referer = "$mainUrl/"
                ).forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = fixedVideoUrl
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        // Agar PDF ya document link ho toh use as direct extractor link pass karein
        val pdfUrl = doc.selectFirst("a[href$='.pdf']")?.attr("href")
        if (!pdfUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Document PDF",
                    url = fixUrl(pdfUrl)
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        val scriptContent = doc.select("script").html()
        val urlRegex = "(https?://[^\\s\"']+\\.(?:m3u8|mp4|pdf)[^\\s\"']*)".toRegex()
        val match = urlRegex.find(scriptContent)
        if (match != null) {
            val foundUrl = match.value
            if (foundUrl.contains(".m3u8")) {
                M3u8Helper.generateM3u8(name, foundUrl, referer = "$mainUrl/").forEach(callback)
            } else {
                callback(
                    newExtractorLink(
                        source = name,
                        name = name,
                        url = foundUrl
                    ) {
                        this.referer = "$mainUrl/"
                        this.quality = Qualities.Unknown.value
                    }
                )
            }
            return true
        }

        return false
    }
}
