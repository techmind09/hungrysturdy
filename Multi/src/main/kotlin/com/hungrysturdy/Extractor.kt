package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "TMBCloud"
    override val mainUrl = "https://tmbcloud.lol"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val response = app.get(url, referer = referer ?: mainUrl, allowRedirects = true)
            val document = response.document
            val finalUrl = response.url
            val uri = URI(finalUrl)
            val baseUrl = "${uri.scheme}://${uri.host}"

            // Page ID extraction from button or URL path (e.g. /download/413Y57 -> 413Y57)
            val pageId = document.selectFirst("button[data-page], a[data-page], div[data-page], input[name='page']")?.attr("data-page")
                ?: document.selectFirst("input[name='page']")?.attr("value")
                ?: uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "file" }

            var foundAny = false

            if (!pageId.isNullOrBlank()) {
                val dynamicTypes = document.select("button[data-type], a[data-type], input[name='type']").mapNotNull {
                    it.attr("data-type").ifBlank { it.attr("value") }.takeIf { type -> type.isNotBlank() }
                }
                val typesToTry = (dynamicTypes + listOf("fslv2new", "fastcdnv2", "r2", "telegram", "fsl", "pixel")).distinct()

                for (dataType in typesToTry) {
                    try {
                        val apiRes = app.post(
                            "$baseUrl/download/$pageId",
                            headers = mapOf(
                                "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                                "X-Requested-With" to "XMLHttpRequest",
                                "Origin" to baseUrl,
                                "Referer" to finalUrl
                            ),
                            data = mapOf("type" to dataType, "page" to pageId)
                        )

                        val text = apiRes.text.trim()
                        if (text.startsWith("{")) {
                            val json = JSONObject(text)
                            val downloadUrl = json.optString("url").takeIf { it.startsWith("http") }
                                ?: json.optString("download_url").takeIf { it.startsWith("http") }
                                ?: json.optString("file").takeIf { it.startsWith("http") }
                                ?: json.optString("link").takeIf { it.startsWith("http") }

                            if (!downloadUrl.isNullOrBlank()) {
                                foundAny = true
                                callback.invoke(
                                    newExtractorLink(
                                        source = name,
                                        name = "TMBCloud - ${dataType.uppercase()}",
                                        url = downloadUrl,
                                        type = if (downloadUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = getQualityFromName(document.title())
                                        this.referer = baseUrl
                                    }
                                )
                            }
                        }
                    } catch (_: Exception) {}
                }
            }

            // Fallback for direct links / buttons on the page
            if (!foundAny) {
                val directButtons = document.select("a.btn[href], a.download-btn[href], a.button[href], a[href]")
                for (btn in directButtons) {
                    val href = btn.attr("href").trim()
                    if (href.startsWith("http") && !href.contains(uri.host)) {
                        if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || 
                            href.contains("vikingfile") || href.contains("send.now") || href.contains("workers.dev") || 
                            href.contains("r2.dev") || href.contains(".mp4") || href.contains(".mkv")) {
                            callback.invoke(
                                newExtractorLink(
                                    source = name,
                                    name = "TMBCloud - Direct ${btn.text().ifBlank { "Stream" }}",
                                    url = href,
                                    type = ExtractorLinkType.VIDEO
                                ) {
                                    this.quality = getQualityFromName(document.title())
                                    this.referer = baseUrl
                                }
                            )
                        } else if (href.contains("tmbcloud")) {
                            loadExtractor(href, finalUrl, subtitleCallback, callback)
                        }
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
