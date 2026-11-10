package com.hungrysturdy

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.VideoExtractor
import com.lagradost.cloudstream3.utils.AppUtils.tryParseJson
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getApp

class MomoFileExtractor : ExtractorApi() {
    override val name = "MomoFile"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = getApp(url).document
        
        // Find all download or streaming links on momofile page
        val links = document.select("a.btn, a.btn-success, a[href*="], div.dropdown-menu a")
        
        for (link in links) {
            val href = link.attr("href") ?: continue
            val title = link.text() ?: "MomoFile Link"
            
            if (href.startsWith("http")) {
                // If the link points directly to supported hosts or video files
                if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || href.contains("xcloud")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv")) {
                    callback(
                        ExtractorLink(
                            source = name,
                            name = "$name - $title",
                            url = href,
                            referer = url,
                            quality = getQuality(title),
                            isM3u8 = false
                        )
                    )
                } else {
                    // Try recursive extraction or direct resolution if it's an intermediary redirect
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        // Fallback generic link
                        callback(
                            ExtractorLink(
                                source = name,
                                name = "$name - $title",
                                url = href,
                                referer = url,
                                quality = ExtractorLink.Quality.Unknown.value,
                                isM3u8 = false
                            )
                        )
                    }
                }
            }
        }
    }
}
