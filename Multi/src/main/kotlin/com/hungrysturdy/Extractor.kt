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
            val typesToTry = listOf("fslv2new", "fastcdnv2", "r2", "telegram")

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
                                "fslv2new" -> "TMBCloud - Instant Download FSLV2 [10GBPS]"
                                "fastcdnv2" -> "TMBCloud - FastCDN V2 [10GBPS]"
                                "r2" -> "TMBCloud - FastCloud R2 [10GBPS]"
                                "telegram" -> "TMBCloud - Telegram File"
                                else -> "TMBCloud - Server"
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
