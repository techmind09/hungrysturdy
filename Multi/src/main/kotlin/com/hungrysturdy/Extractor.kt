package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.jsoup.Jsoup

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "KDHindiDubbed"
    override val mainUrl = "https://dramacool.website"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Fetch the page (could be a momofile page or direct watch page)
        val document = app.get(url).document

        // Select all stream/download buttons or links on the page (PixelDrain, Buzzheavier, FileDitch, xCloud, etc.)
        val links = document.select("a.btn, a.btn-success, a.button, a[href*='http']")

        for (i in 0 until links.size) {
            val link = links[i]
            val href = link.attr("href") ?: continue
            val title = link.text().trim().ifEmpty { "Direct Link" }

            if (href.startsWith("http")) {
                // If it points to external supported hosting/extractor sites, route via Cloudstream's loadExtractor
                if (href.contains("pixeldrain") || 
                    href.contains("buzzheavier") || 
                    href.contains("fileditch") || 
                    href.contains("xcloud") || 
                    href.contains("vikingfile") || 
                    href.contains("send.now")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                } 
                // If it's a direct video file link (.mp4 / .mkv)
                else if (href.endsWith(".mp4") || href.endsWith(".mkv")) {
                    callback(
                        newExtractorLink(
                            source = name,
                            name = "$name - $title",
                            url = href,
                            referer = url,
                            quality = getQualityFromName(title)
                        )
                    )
                } 
                // For other potential intermediate redirect/embed links, try loading them recursively
                else if (!href.contains("dramacool.website") && !href.contains("momofile.shop")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun getQualityFromName(title: String): Int {
        val lower = title.lowercase()
        return when {
            lower.contains("2160p") || lower.contains("4k") -> Qualities.P2160.value
            lower.contains("1080p") -> Qualities.P1080.value
            lower.contains("720p") -> Qualities.P720.value
            lower.contains("480p") -> Qualities.P480.value
            lower.contains("360p") -> Qualities.P360.value
            else -> Qualities.Unknown.value
        }
    }
}
