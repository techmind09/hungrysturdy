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
            
            // Find all available download buttons on the TMBCloud page
            val buttons = document.select("button[data-type], button[class*='btn']")
            val typesToTry = mutableSetOf("fslv2new", "fastcdnv2", "r2", "telegram")
            
            for (btn in buttons) {
                val dtype = btn.attr("data-type")
                if (!dtype.isNullOrBlank()) {
                    typesToTry.add(dtype)
                }
            }

            if (!pageId.isNullOrBlank()) {
                for (dataType in typesToTry) {
                    try {
                        val apiRes = app.post(
                            "https://tmbcloud.lol/download/$pageId",
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
                            val serverName = when(dataType) {
                                "fslv2new" -> "TMBCloud - FSLV2 [10GBPS]"
                                "fastcdnv2" -> "TMBCloud - FastCDN V2 [10GBPS]"
                                "r2" -> "TMBCloud - FastCloud R2 [10GBPS]"
                                "telegram" -> "TMBCloud - Telegram File"
                                else -> "TMBCloud - Server ($dataType)"
                            }

                            callback(
                                ExtractorLink(
                                    source = "TMBCloud",
                                    name = serverName,
                                    url = downloadUrl,
                                    referer = url,
                                    quality = Qualities.P1080.value,
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        }
                    } catch (_: Exception) {}
                }
            }
            
            // Fallback direct input
            val directInput = document.selectFirst("input[value*='http']")?.attr("value")
            if (!directInput.isNullOrBlank()) {
                callback(
                    ExtractorLink(
                        source = "TMBCloud",
                        name = "TMBCloud - Direct Link",
                        url = directInput,
                        referer = url,
                        quality = Qualities.P1080.value,
                        type = ExtractorLinkType.VIDEO
                    )
                )
            }
        } else {
            val links = document.select("a.btn, a.download-btn, a[href*='http']")
            for (element in links) {
                val href = element.attr("href") ?: continue
                if (href.startsWith("http") && !href.contains("dramacool.website")) {
                    try {
                        loadExtractor(href, url, subtitleCallback, callback)
                    } catch (_: Exception) {}
                }
            }
        }
    }
}
