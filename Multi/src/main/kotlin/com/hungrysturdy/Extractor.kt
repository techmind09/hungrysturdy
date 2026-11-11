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

            val pageId = document.selectFirst("button[data-page], a[data-page], div[data-page]")?.attr("data-page")
                ?.takeIf { it.isNotBlank() }
                ?: uri.path.trimEnd('/').substringAfterLast('/').takeIf { it.isNotBlank() && it != "download" && it != "file" }

            val dynamicTypes = document.select("button[data-type], a[data-type]").mapNotNull {
                it.attr("data-type").takeIf { type -> type.isNotBlank() }
            }
            val typesToTry = (dynamicTypes + listOf("fslv2new", "fastcdnv2", "r2", "telegram", "fsl", "pixel")).distinct()

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
                                callback(
                                    ExtractorLink(
                                        source = "TMBCloud",
                                        name = "TMBCloud - ${dataType.uppercase()}",
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

            val directButtons = document.select("a.btn[href], a.download-btn[href], a.button[href]")
            for (btn in directButtons) {
                val href = btn.attr("href").trim()
                if (href.startsWith("http") && !href.contains(uri.host)) {
                    loadExtractor(href, response.url, subtitleCallback, callback)
                }
            }
        } catch (_: Exception) {}
    }
}
