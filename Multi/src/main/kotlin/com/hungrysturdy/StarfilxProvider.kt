package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
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

        // 2. If on homepage page 1, also grab unique items from top carousel (.ams-c)
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

        val description = document.select("div.entry-content p")
            .map { it.text().trim() }
            .firstOrNull { it.length > 30 && !it.contains("Telegram", ignoreCase = true) }

        val tags = document.select("span.post-cat-wrap a, div.post-bottom-meta a").map { it.text() }

        val contentArea = document.selectFirst("div.entry-content, article")

        val episodeMap = linkedMapOf<Int, MutableList<String>>()
        val unnumberedLinks = mutableListOf<Pair<String, String>>()

        contentArea?.select("a.button, a.wp-block-button__link, a[href]")?.forEach { a ->
            val text = a.text().trim()
            val href = a.attr("href").trim()

            if (href.isBlank() ||
                href.startsWith("#") ||
                href.contains("t.me/") ||
                href.contains("starfilx.in/")
            ) {
                return@forEach
            }

            val epMatch = Regex("""(?:ep|episode)\s*(\d+)""", RegexOption.IGNORE_CASE).find(text)
            if (epMatch != null) {
                val epNum = epMatch.groupValues[1].toIntOrNull()
                if (epNum != null) {
                    episodeMap.getOrPut(epNum) { mutableListOf() }.add(href)
                    return@forEach
                }
            }

            if (a.hasClass("button") ||
                href.contains("terabox", ignoreCase = true) ||
                href.contains("filenext", ignoreCase = true) ||
                href.contains("gofile", ignoreCase = true) ||
                href.contains("hubcloud", ignoreCase = true) ||
                href.contains("gdflix", ignoreCase = true) ||
                href.contains("rpmstream", ignoreCase = true) ||
                href.contains("dailymotion", ignoreCase = true) ||
                href.contains("dai.ly", ignoreCase = true)
            ) {
                unnumberedLinks.add(text to href)
            }
        }

        val episodes = mutableListOf<Episode>()

        if (episodeMap.isNotEmpty()) {
            episodeMap.entries.sortedBy { it.key }.forEach { (epNum, urls) ->
                episodes.add(
                    newEpisode(urls.distinct().joinToString("|")) {
                        this.name = "Episode $epNum"
                        this.episode = epNum
                    }
                )
            }
        } else if (unnumberedLinks.isNotEmpty()) {
            unnumberedLinks.forEachIndexed { index, (label, link) ->
                episodes.add(
                    newEpisode(link) {
                        this.name = label.ifBlank { "Episode ${index + 1}" }
                        this.episode = index + 1
                    }
                )
            }
        }

        contentArea?.select("iframe[src]")?.forEachIndexed { index, iframe ->
            val src = iframe.attr("src")
            if (!src.contains("a-ads.com") && !src.contains("google")) {
                val epNum = index + 1
                if (episodeMap.containsKey(epNum)) {
                    episodeMap[epNum]?.add(fixUrl(src))
                } else if (episodes.isEmpty()) {
                    episodes.add(
                        newEpisode(fixUrl(src)) {
                            this.name = "Episode $epNum"
                            this.episode = epNum
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
        val urls = if (data.startsWith("[")) {
            tryParseJson<List<String>>(data) ?: listOf(data)
        } else {
            data.split("|").map { it.trim() }.filter { it.isNotBlank() }
        }

        urls.amap { rawUrl ->
            var targetUrl = rawUrl

            if (targetUrl.contains("?url=")) {
                val encodedPart = targetUrl.substringAfter("?url=").substringBefore("&")
                try {
                    targetUrl = base64Decode(encodedPart)
                } catch (_: Exception) {}
            }

            when {
                targetUrl.contains("terabox", ignoreCase = true) -> {
                    extractTerabox(targetUrl, subtitleCallback, callback)
                }
                targetUrl.contains("filenext", ignoreCase = true) -> {
                    extractFileNext(targetUrl, subtitleCallback, callback)
                }
                targetUrl.contains("rpmstream.live", ignoreCase = true) -> {
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
                else -> {
                    loadExtractor(targetUrl, mainUrl, subtitleCallback, callback)
                }
            }
        }

        return true
    }

    private suspend fun extractTerabox(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val shortId = Regex("""/s/1?([A-Za-z0-9_-]+)""").find(url)?.groupValues?.get(1)
        if (!shortId.isNullOrBlank()) {
            try {
                val infoUrl = "https://terabox.hnn.workers.dev/api/get-info?shorturl=$shortId&pwd="
                val infoRes = app.get(infoUrl).parsedSafe<TeraboxInfoResponse>()

                if (infoRes?.ok == true && !infoRes.list.isNullOrEmpty()) {
                    val file = infoRes.list.first()
                    val postBody = mapOf(
                        "shareid" to (infoRes.shareid ?: 0).toString(),
                        "uk" to (infoRes.uk ?: 0).toString(),
                        "sign" to (infoRes.sign ?: ""),
                        "timestamp" to (infoRes.timestamp ?: 0).toString(),
                        "fs_id" to (file.fs_id ?: "").toString()
                    )

                    val dlRes = app.post(
                        "https://terabox.hnn.workers.dev/api/get-download",
                        json = postBody
                    ).parsedSafe<TeraboxDownloadResponse>()

                    val streamUrl = dlRes?.downloadLink
                    if (!streamUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = this.name,
                                name = "Terabox Direct",
                                url = streamUrl,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.referer = "https://www.terabox.com/"
                                this.quality = Qualities.P720.value
                            }
                        )
                        return
                    }
                }
            } catch (_: Exception) {}
        }

        val normalizedUrl = url.replace(
            Regex("https?://(www\\.)?(1024terabox|teraboxapp|teraboxlink|freeterabox)\\.com"),
            "https://www.terabox.com"
        )
        loadExtractor(normalizedUrl, mainUrl, subtitleCallback, callback)
    }

    private suspend fun extractFileNext(
        url: String,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, headers = mapOf("Referer" to mainUrl))
            val html = response.text
            val doc = response.document

            doc.select("video source[src], video[src], a[href*=.mp4], a[href*=.mkv], a[href*=.m3u8], a[href*=download]").forEach { el ->
                val mediaUrl = el.attr("src").ifBlank { el.attr("href") }
                if (mediaUrl.startsWith("http") && !mediaUrl.contains("filenext.in/s/")) {
                    callback.invoke(
                        newExtractorLink(
                            source = this.name,
                            name = "FileNext",
                            url = fixUrl(mediaUrl),
                            type = if (mediaUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                        ) {
                            this.referer = url
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }

            val mediaRegex = Regex("""https?://[^\s"'<>\\]+?\.(?:mp4|m3u8|mkv)(?:\?[^\s"'<>\\]*)?""", RegexOption.IGNORE_CASE)
            mediaRegex.findAll(html).map { it.value.replace("\\/", "/") }.distinct().forEach { streamUrl ->
                callback.invoke(
                    newExtractorLink(
                        source = this.name,
                        name = "FileNext Stream",
                        url = streamUrl,
                        type = if (streamUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                    ) {
                        this.referer = url
                        this.quality = Qualities.Unknown.value
                    }
                )
            }

            doc.select("iframe[src]").forEach { iframe ->
                loadExtractor(fixUrl(iframe.attr("src")), url, subtitleCallback, callback)
            }
        } catch (_: Exception) {}
    }

    data class TeraboxFile(val fs_id: Any? = null, val filename: String? = null)
    data class TeraboxInfoResponse(
        val ok: Boolean? = null,
        val shareid: Long? = null,
        val uk: Long? = null,
        val sign: String? = null,
        val timestamp: Long? = null,
        val list: List<TeraboxFile>? = null
    )
    data class TeraboxDownloadResponse(val ok: Boolean? = null, val downloadLink: String? = null)
}
