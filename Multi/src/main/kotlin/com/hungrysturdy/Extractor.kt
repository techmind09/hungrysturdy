package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import org.json.JSONObject
import java.net.URI

class KDHindiDubbedExtractor : ExtractorApi() {
    override val name = "MomoFile"
    override val mainUrl = "https://momofile.shop"
    override val requiresReferer = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        try {
            val targetUrl = url
            val uri = URI(targetUrl)
            val baseUrl = "${uri.scheme}://${uri.host}"

            // 1. TMBCloud link handler
            if (targetUrl.contains("tmbcloud")) {
                val response = app.get(targetUrl, referer = referer ?: "https://tmbcloud.lol")
                val doc = response.document
                val pageId = doc.selectFirst("button[data-page], a[data-page]")?.attr("data-page")
                    ?: targetUrl.trimEnd('/').substringAfterLast('/')
                
                val apiRes = app.post(
                    "https://tmbcloud.lol/download/$pageId",
                    headers = mapOf(
                        "Content-Type" to "application/x-www-form-urlencoded; charset=UTF-8",
                        "X-Requested-With" to "XMLHttpRequest",
                        "Referer" to targetUrl
                    ),
                    data = mapOf("type" to "fslv2new", "page" to (pageId ?: ""))
                )
                if (apiRes.text.trim().startsWith("{")) {
                    val json = JSONObject(apiRes.text)
                    val dlUrl = json.optString("url").takeIf { it.startsWith("http") }
                        ?: json.optString("download_url").takeIf { it.startsWith("http") }
                    if (!dlUrl.isNullOrBlank()) {
                        callback.invoke(
                            newExtractorLink(
                                source = "TMBCloud",
                                name = "TMBCloud - Server",
                                url = dlUrl,
                                type = if (dlUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = "https://tmbcloud.lol"
                            }
                        )
                    }
                }
                return
            }

            // 2. MomoFile PHP JSON endpoints handler (Concurrent / Parallel fetching for speed)
            val fileId = uri.query?.substringAfter("id=")?.takeIf { it.isNotBlank() }
                ?: targetUrl.trimEnd('/').substringAfterLast('/')

            val services = listOf("pixeldrain", "buzzheavier", "fileditch", "vikingfile", "sendnow")

            services.amap { service ->
                try {
                    val endpoint = "$baseUrl/$service.php?id=$fileId"
                    val res = app.get(endpoint, referer = targetUrl)
                    val text = res.text.trim()

                    if (text.startsWith("{")) {
                        val json = JSONObject(text)
                        val status = json.optString("status")
                        if (status == "ready" || json.optBoolean("ok")) {
                            val videoUrl = json.optString("url").takeIf { it.startsWith("http") }
                            if (!videoUrl.isNullOrBlank()) {
                                callback.invoke(
                                    newExtractorLink(
                                        source = "MomoFile - ${service.replaceFirstChar { it.uppercase() }}",
                                        name = "Stream (${service.uppercase()})",
                                        url = videoUrl,
                                        type = if (videoUrl.contains(".m3u8")) ExtractorLinkType.M3U8 else ExtractorLinkType.VIDEO
                                    ) {
                                        this.quality = Qualities.P1080.value
                                        this.referer = baseUrl
                                    }
                                )
                            }
                        }
                    }
                } catch (_: Exception) {}
            }

            // 3. Fallback anchors parser
            val document = app.get(targetUrl, referer = referer ?: baseUrl).document
            val links = document.select("a[href]")
            for (element in links) {
                val href = element.attr("href").trim()
                if (href.startsWith("http") && !href.contains("momofile.shop")) {
                    if (href.contains("pixeldrain") || href.contains("buzzheavier") || href.contains("fileditch") || href.contains("workers.dev") || href.contains("r2.dev")) {
                        callback.invoke(
                            newExtractorLink(
                                source = "MomoFile",
                                name = "Direct - ${element.text().ifBlank { "Server" }}",
                                url = href,
                                type = ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.P1080.value
                                this.referer = baseUrl
                            }
                        )
                    }
                }
            }
        } catch (_: Exception) {}
    }
}
