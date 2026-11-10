package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "TMBCloud"
    override val mainUrl = "https://tmbcloud.dev"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url, referer = referer).document
        
        if (url.contains("tmbcloud")) {
            val pageId = document.selectFirst("button[data-page]")?.attr("data-page")
            val dataType = document.selectFirst("button[data-page]")?.attr("data-type") ?: "r2"
            
            if (!pageId.isNullOrBlank()) {
                try {
                    val apiRes = app.post(
                        "https://tmbcloud.dev/download/$pageId",
                        headers = mapOf(
                            "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                            "X-Requested-With" to "XMLHttpRequest",
                            "Referer" to url
                        ),
                        data = mapOf("type" to dataType, "page" to pageId)
                    )
                    
                    val json = JSONObject(apiRes.text)
                    val downloadUrl = json.optString("url").takeIf { it.startsWith("http") } 
                        ?: json.optString("download_url").takeIf { it.startsWith("http") }
                        ?: json.optString("file").takeIf { it.startsWith("http") }

                    if (!downloadUrl.isNullOrBlank()) {
                        callback(
                            ExtractorLink(
                                source = "FastCDN",
                                name = "FastCDN (10Gbps)",
                                url = downloadUrl,
                                referer = url,
                                quality = Qualities.P1080.value,
                                type = ExtractorLinkType.VIDEO
                            )
                        )
                    }
                } catch (_: Exception) {}
            }
        } else {
            // General external hosts (PixelDrain, Voe, Buzzheavier, FileDitch, etc.)
            val links = document.select("a.btn, a.download-btn, a[href*='http']")
            for (element in links) {
                val href = element.attr("href") ?: continue
                val text = element.text().lowercase()
                if (href.startsWith("http") && !href.contains("dramacool.website")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
