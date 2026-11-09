package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*
import java.net.URI

class GokuHDExtractor : ExtractorApi() {
    override val name: String = "GokuHD Extractor"
    override val mainUrl: String = "https://nexdrive.beer"
    override val requiresReferer: Boolean = false

    private fun getBaseUrl(url: String): String {
        return try {
            val uri = URI(url)
            "${uri.scheme}://${uri.host}"
        } catch (e: Exception) {
            url
        }
    }

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val document = app.get(url).document

        document.select("a.wp-download-btn, a.btn-download, a.btn, div.download-links a, center a").forEach { element ->
            val href = element.attr("href")
            val text = element.text()

            if (href.isNotBlank() && href.startsWith("http")) {
                if (href.contains("hubcloud") || href.contains("filepress") || href.contains("drive.google") || href.contains("nexdrive")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                } 
                else if (text.contains("BuzzServer", true)) {
                    val dlink = app.get("$href/download", referer = href, allowRedirects = false).headers["hx-redirect"] ?: ""
                    if (dlink.isNotBlank()) {
                        val fullUrl = if (dlink.startsWith("http")) dlink else getBaseUrl(href) + dlink
                        callback.invoke(
                            newExtractorLink(
                                name,
                                "$name [BuzzServer]",
                                fullUrl,
                                ExtractorLinkType.VIDEO
                            ) {
                                this.quality = Qualities.Unknown.value
                            }
                        )
                    }
                }
                else {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            if (text.isNotBlank()) "$name [$text]" else name,
                            href,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
            }
        }
    }
}
