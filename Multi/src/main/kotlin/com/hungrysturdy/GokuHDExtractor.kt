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
        // Agar URL direct koi file/storage link hai toh use seedha loadExtractor mein bhejeyin
        if (url.contains("gofile.io") || 
            url.contains("filepress") || 
            url.contains("hubcloud") || 
            url.contains("drive.google") || 
            url.contains("1fichier.com") ||
            url.contains("vikingfile") ||
            url.contains("buzzheavier")
        ) {
            loadExtractor(url, referer ?: mainUrl, subtitleCallback, callback)
            return
        }

        // FastDL / 10Gbps direct download page handling
        if (url.contains("fastdl.beer") || url.contains("vglist")) {
            val doc = app.get(url).document
            val directLink = doc.selectFirst("a.btn-download, a#download, a:contains(Download Now)")?.attr("href")
                ?: doc.selectFirst("a[href*='googleusercontent']")?.attr("href")

            if (!directLink.isNullOrBlank()) {
                callback.invoke(
                    newExtractorLink(
                        name,
                        "$name [10Gbps FastDL]",
                        directLink,
                        ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.referer = url
                    }
                )
            }
            return
        }

        // General Redirection Pages (Vegadrive, Nexdrive, VCloud, Dotflix, Transferit)
        val document = app.get(url).document

        // Sabhi download buttons aur links ko target karna
        document.select("a.wp-download-btn, a.btn-download, a.btn, div.download-links a, center a, .btn-success").forEach { element ->
            var href = element.attr("href")
            val text = element.text()

            if (href.isNotBlank() && href.startsWith("http")) {
                if (!href.startsWith("http")) {
                    href = getBaseUrl(url) + href
                }

                // Nested Hubs / Extractors
                if (href.contains("hubcloud") || 
                    href.contains("filepress") || 
                    href.contains("drive.google") || 
                    href.contains("nexdrive") || 
                    href.contains("vegadrive") ||
                    href.contains("vcloud") ||
                    href.contains("dotflix") ||
                    href.contains("gofile")
                ) {
                    loadExtractor(href, url, subtitleCallback, callback)
                } 
                // FastDL / Direct links inside page
                else if (href.contains("fastdl.beer") || href.contains("googleusercontent")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [Direct]",
                            href,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                            this.referer = url
                        }
                    )
                }
                // Transferit or other mirrors
                else if (href.contains("transfer.it")) {
                    callback.invoke(
                        newExtractorLink(
                            name,
                            "$name [Transferit]",
                            href,
                            ExtractorLinkType.VIDEO
                        ) {
                            this.quality = Qualities.Unknown.value
                        }
                    )
                }
                // BuzzServer handler
                else if (text.contains("BuzzServer", true) || text.contains("Buzzheavier", true)) {
                    val dlink = app.get("$href/download", referer = href, allowRedirects = false).headers["hx-redirect"] ?: ""
                    val fullUrl = if (dlink.isNotBlank()) {
                        if (dlink.startsWith("http")) dlink else getBaseUrl(href) + dlink
                    } else href

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
                // Default fallback for other buttons
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
