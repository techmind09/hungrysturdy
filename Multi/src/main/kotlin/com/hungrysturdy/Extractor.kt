package com.lagradost.cloudstream3.extractors

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.getApp
import com.lagradost.cloudstream3.utils.Qualities

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
        
        // Fixed quotes and selector syntax
        val links = document.select("a.btn, a.btn-success, a[href*='http'], div.dropdown-menu a")
        
        for (link in links) {
            val href = link.attr("href") ?: continue
            val title = link.text() ?: "MomoFile Link"
            
            if (href.startsWith("http")) {
                if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || href.contains("xcloud")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                } else if (href.endsWith(".mp4") || href.endsWith(".mkv")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $title",
                            url = href,
                            referer = url,
                            quality = Qualities.Unknown.value
                        )
                    )
                } else {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (e: Exception) {
                        callback(
                            newExtractorLink(
                                source = name,
                                name = "$name - $title",
                                url = href,
                                referer = url,
                                quality = Qualities.Unknown.value
                            )
                        )
                    }
                }
            }
        }
    }
}
