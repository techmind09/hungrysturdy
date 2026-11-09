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

        // Nexdrive ya redirect page par bane sabhi buttons aur direct links match honge
        document.select("a.wp-download-btn, a.btn-download, a.btn, div.download-links a, center a").forEach { element ->
            val href = element.attr("href")
            val text = element.text()

            if (href.isNotBlank() && href.startsWith("http")) {
                // Agar inner link Filepress, Hubcloud, GDrive, ya nexdrive redirect hai toh nested extractor parse hoga
                if (href.contains("hubcloud") || href.contains("filepress") || href.contains("drive.google") || href.contains("nexdrive")) {
                    loadExtractor(href, url, subtitleCallback, callback)
                } 
                // BuzzServer handler
                else if (text.contains("BuzzServer", true)) {
                    val dlink = app.get("$href/download", referer = href, allowRedirects = false).headers["hx-redirect"] ?: ""
                    if (dlink.isNotBlank()) {
                        val fullUrl = if (dlink.startsWith("http")) dlink else getBaseUrl(href) + dlink
                        callback.invoke(
                            ExtractorLink(
                                name,
                                "$name [BuzzServer]",
                                fullUrl,
                                referer ?: "",
                                Qualities.Unknown.value
                            )
                        )
                    }
                }
                // Direct Video Stream / Generic Download Links
                else {
                    callback.invoke(
                        ExtractorLink(
                            name,
                            if (text.isNotBlank()) "$name [$text]" else name,
                            href,
                            referer ?: "",
                            Qualities.Unknown.value
                        )
                    )
                }
            }
        }
    }
}
