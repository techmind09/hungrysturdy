Package com.hungrysturdy.Aryavidya

import com.fasterxml.jackson.annotation.JsonProperty
import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.nodes.Element

class AryaVidyaProvider : MainAPI() {
    override var mainUrl = "https://stream.aryavidya.cc"
    override var name = "AryaVidya"
    override val supportedTypes = setOf(TvType.Documentary, TvType.Live, TvType.Others)
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

        // 1. Live Classes Shortcut Option
        batchCards.add(
            newAnimeSearchResponse("🔴 Live Classes & Ongoing Sessions", "$mainUrl/#/live-classes", TvType.Live) {
                this.posterUrl = "https://static.pw.live/images/live-icon.png"
            }
        )

        // 2. PDF Bank Shortcut Option
        batchCards.add(
            newAnimeSearchResponse("📚 PDF Bank (Short Notes, Mindmaps, Formula Sheets)", "$mainUrl/#/pdf-bank", TvType.Documentary) {
                this.posterUrl = "https://static.pw.live/"
            }
        )

        // 3. JSON API se Batches fetch karna
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
            homePages.add(HomePageList("Live Classes, Study Material & Batches", distinctCards))
        }

        return newHomePageResponse(homePages)
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

        val episodes = mutableListOf<Episode>()

        // Target selectors for Class Videos, Live Classes, Subjects, Topics & PDFs
        val elements = doc.select("div.lecture-item, .class-row, li.episode-item, .episode, a[href*='watch'], a[href*='video'], .lesson-item, .subject-card, .chapter-item, div.card, a[href*='topic'], a[href*='subject'], a[href*='pdf'], a[href*='live'], a[href*='deeplink']")
        
        if (elements.isNotEmpty()) {
            elements.mapIndexedNotNull { index, element ->
                val epName = element.selectFirst(".name, span, h4, p, h3")?.text() ?: "Class / Item ${index + 1}"
                val aTag = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
                val rawHref = aTag.attr("href")
                if (rawHref.isBlank()) return@mapIndexedNotNull null
                val epUrl = fixUrlNull(if (rawHref.startsWith("#")) "$mainUrl/$rawHref" else rawHref) ?: return@mapIndexedNotNull null
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
            }
        }

        // Fallback selector for anchor links matching video/live/class patterns
        if (episodes.isEmpty()) {
            doc.select("a[href]").mapIndexedNotNull { index, element ->
                val href = element.attr("href")
                if (href.contains("watch") || href.contains("video") || href.contains("lecture") || href.contains("class") || href.contains("live") || href.contains("topic") || href.contains("subject") || href.contains("pdf")) {
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
        
        // 1. Direct Video / Live stream source extraction
        val videoUrl = doc.selectFirst("video source")?.attr("src") 
            ?: doc.selectFirst("video")?.attr("src")
            ?: doc.selectFirst("iframe")?.attr("src")
            ?: doc.selectFirst("source")?.attr("src")

        if (!videoUrl.isNullOrEmpty()) {
            val fixedVideoUrl = fixUrl(videoUrl)
            if (fixedVideoUrl.contains(".m3u8") || fixedVideoUrl.contains("m3u8")) {
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

        // 2. PDF Document Link Extraction
        val pdfUrl = doc.selectFirst("a[href$='.pdf']")?.attr("href") ?: doc.selectFirst("a[href*='pdf']")?.attr("href")
        if (!pdfUrl.isNullOrEmpty()) {
            callback(
                newExtractorLink(
                    source = name,
                    name = "Document PDF / Notes",
                    url = fixUrl(pdfUrl)
                ) {
                    this.referer = "$mainUrl/"
                    this.quality = Qualities.Unknown.value
                }
            )
            return true
        }

        // 3. Script-based video / stream / m3u8 regex extraction
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
