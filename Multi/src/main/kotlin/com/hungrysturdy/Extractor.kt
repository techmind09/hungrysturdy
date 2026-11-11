package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "TMBCloud"
    override val mainUrl = "https://tmbcloud.dev"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer ?: mainUrl)
            val document = response.document

            val uri = URI(response.url)
            val baseUrl = "${uri.scheme}://${uri.host}"

            // 1. Page ID button se ya URL path se extract karein
            val pageId = document.selectFirst("button[data-page], a[data-page], div[data-page]")?.attr("data-page")
                ?.takeIf { it.isNotBlank() }
                ?: uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "file" }

            // 2. Types dynamic nikalo ya default list use karo
            val dynamicTypes = document.select("button[data-type], a[data-type]").mapNotNull {
                it.attr("data-type").takeIf { type -> type.isNotBlank() }
            }
            val typesToTry = (dynamicTypes + listOf("fslv2new", "fastcdnv2", "r2", "telegram", "fsl", "pixel")).distinct()

            var foundAnyLink = false

            if (!pageId.isNullOrBlank()) {
                for (dataType in typesToTry) {
                    try {
                        val apiRes = app.post(
                            "$baseUrl/download/$pageId",
                            headers = mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to baseUrl,
                                "Referer" to response.url
                            ),
                            data = mapOf("type" to dataType, "page" to pageId)
                        )

                        if (apiRes.text.trim().startsWith("{")) {
                            val json = JSONObject(apiRes.text)
                            val downloadUrl = json.optString("url").takeIf { it.startsWith("http") }
                                ?: json.optString("download_url").takeIf { it.startsWith("http") }
                                ?: json.optString("file").takeIf { it.startsWith("http") }
                                ?: json.optString("link").takeIf { it.startsWith("http") }

                            if (!downloadUrl.isNullOrBlank()) {
                                foundAnyLink = true
                                val serverName = when (dataType) {
                                    "fslv2new", "fsl" -> "TMBCloud - Instant FSL [10GBPS]"
                                    "fastcdnv2" -> "TMBCloud - FastCDN V2"
                                    "r2" -> "TMBCloud - FastCloud R2"
                                    "telegram" -> "TMBCloud - Telegram"
                                    else -> "TMBCloud - ${dataType.uppercase()}"
                                }

                                callback(
                                    ExtractorLink(
                                        source = "TMBCloud",
                                        name = serverName,
                                        url = downloadUrl,
                                        referer = baseUrl,
                                        quality = getQualityFromName(document.title()),
                                        type = if (downloadUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    )
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // 3. Fallback: Agar TMBCloud page par direct <a href> download buttons hain (non-AJAX)
            if (!foundAnyLink) {
                val directButtons = document.select("a.btn[href], a.download-btn[href], a.button[href], a[href*='.mp4'], a[href*='.mkv'], a[href*='workers.dev'], a[href*='r2.dev'], a[href*='hubcloud'], a[href*='gdflix']")
                for (btn in directButtons) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && !href.contains(uri.host)) {
                        if (href.contains(".mkv") || href.contains(".mp4") || href.contains("workers.dev") || href.contains("r2.dev") || href.contains("pixeldrain")) {
                            callback(
                                ExtractorLink(
                                    source = "TMBCloud",
                                    name = "TMBCloud - Direct ${btn.text().trim()}",
                                    url = href,
                                    referer = baseUrl,
                                    quality = getQualityFromName(document.title()),
                                    type = ExtractorLinkType.VIDEO
                                )
                            )
                        } else {
                            loadExtractor(href, response.url, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
