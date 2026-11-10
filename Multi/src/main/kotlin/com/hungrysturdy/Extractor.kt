package com.hungrysturdy

import com.lagradost.cloudstream3.ExtractorApi
import com.lagradost.cloudstream3.SubtitleFile
import com.lagradost.cloudstream3.utils.ExtractorLink
import com.lagradost.cloudstream3.utils.loadExtractor
import com.lagradost.cloudstream3.utils.app
import com.lagradost.cloudstream3.utils.Qualities
import com.lagradost.cloudstream3.newExtractorLink

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
        val document = app.get(url).document
        
        val links = document.select("a.btn, a.btn-success, a[href*='http'], div.dropdown-menu a")
        
        // Explicitly casting or iterating safely to avoid ambiguity
        for (i in 0 until links.size) {
            val link = links[i]
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
