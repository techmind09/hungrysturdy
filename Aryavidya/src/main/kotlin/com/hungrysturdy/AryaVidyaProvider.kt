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

    // Xylem/AryaVidya JSON Structure ke anusaar data classes
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

        try {
            // Sahi JSON endpoint jo aapke network logs mein mila hai
            val jsonUrl = "$mainUrl/assets/static/json/xylem-courses.json"
            val jsonResponse = app.get(jsonUrl).parsedSafe<ApiResponse>()
            
            jsonResponse?.batches?.mapNotNull { item ->
                if (item == null) return@mapNotNull null
                val title = item.byName ?: item.name ?: item.exam ?: return@mapNotNull null
                val id = item.batchId ?: return@mapNotNull null
                val href = "$mainUrl/batch/$id"
                val poster = fixUrlNull(item.photo)

                newAnimeSearchResponse(title, href, TvType.Documentary) {
                    this.posterUrl = poster
                }
            }?.let { batchCards.addAll(it) }
        } catch (_: Exception) {}

        // Fallback agar API se na mile toh HTML scrape karein
        if (batchCards.isEmpty()) {
            try {
                val doc = app.get(mainUrl).document
                doc.select("div.batch-card, div.random-batch-item, div.group, .card, a[href*='batch'], div[class*='batch']").mapNotNull { 
                    it.toSearchResult()
                }.let { batchCards.addAll(it) }
            } catch (_: Exception) {}
        }

        val distinctCards = batchCards.distinctBy { it.url }
        if (distinctCards.isNotEmpty()) {
            homePages.add(HomePageList("Batches & Study Material", distinctCards))
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
                val href = "$mainUrl/batch/$id"
                val poster = fixUrlNull(item.photo)

                newAnimeSearchResponse(title, href, TvType.Documentary) {
                    this.posterUrl = poster
                }
            }?.let { results.addAll(it) }
        } catch (_: Exception) {}

        if (results.isEmpty()) {
            try {
                val doc = app.get("$mainUrl/search?q=$query").document
                doc.select("div.batch-card, div.search-item, .card, a[href*='batch']").mapNotNull { it.toSearchResult() }.let { results.addAll(it) }
            } catch (_: Exception) {}
        }

        return results.distinctBy { it.url }
    }

    override suspend fun load(url: String): LoadResponse {
        val doc = app.get(url).document
        val title = doc.selectFirst("h1, .batch-title, .title")?.text() ?: "Study Content"
        val poster = fixUrlNull(doc.selectFirst("img.poster, img.banner, img")?.attr("src"))

        val elements = doc.select("div.lecture-item, .class-row, li.episode-item, .episode, a[href*='watch'], a[href*='video'], .lesson-item, .subject-card, .chapter-item, div.card")
        
        val episodes = mutableListOf<Episode>()
        if (elements.isNotEmpty()) {
            elements.mapIndexedNotNull { index, element ->
                val epName = element.selectFirst(".name, span, h4, p, h3")?.text() ?: "Lecture ${index + 1}"
                val aTag = if (element.tagName() == "a") element else element.selectFirst("a") ?: element
                val epUrl = fixUrlNull(aTag.attr("href")) ?: return@mapIndexedNotNull null
                
                episodes.add(
                    newEpisode(epUrl) {
                        this.name = epName
                        this.episode = index + 1
                    }
                )
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
        val doc = app.get(data).document
        
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

        val scriptContent = doc.select("script").html()
        val urlRegex = "(https?://[^\\s\"']+\\.(?:m3u8|mp4)[^\\s\"']*)".toRegex()
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
