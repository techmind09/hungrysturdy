package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

open class GDFlixExtractor : ExtractorApi() {
    override val name = "GDFlix"
    override val mainUrl = "https://gdflix.vip"
    override val requiresReferer = true

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val pageUrl = resolveUrl(url, mainUrl)
        val response = app.get(pageUrl, referer = referer ?: mainUrl)
        val basePageUrl = response.url.ifBlank { pageUrl }
        val doc = response.document

        val fileTitle = doc.selectFirst("ul.list-group li")
            ?.text()
            ?.substringAfter(":", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringAfter("|", "").trim().ifBlank { name }

        val baseQuality = detectQualityFromText(fileTitle)

        val buttons = doc.select(".card-body a.btn[href], .card-body a[href]")
            .mapNotNull { el ->
                val rawHref = el.attr("href").trim()
                if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:")) {
                    return@mapNotNull null
                }
                val resolved = resolveUrl(rawHref, basePageUrl)
                val label = el.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "Mirror" }
                label to resolved
            }
            .distinctBy { it.second }

        for ((label, targetUrl) in buttons) {
            if (targetUrl.contains("/login", ignoreCase = true) ||
                targetUrl.contains("t.me/", ignoreCase = true) ||
                targetUrl.contains("filesgram.xyz", ignoreCase = true) ||
                targetUrl.contains("movies4u.foo", ignoreCase = true)
            ) {
                continue
            }

            when {
                label.contains("Instant DL", ignoreCase = true) ||
                    label.contains("DIRECT SERVER", ignoreCase = true) ||
                    targetUrl.contains(".mkv", ignoreCase = true) ||
                    targetUrl.contains(".mp4", ignoreCase = true) ||
                    targetUrl.contains(".m3u8", ignoreCase = true) -> {
                    val streamType = if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                        ExtractorLinkType.M3U8
                    } else {
                        ExtractorLinkType.VIDEO
                    }
                    callback.invoke(
                        newExtractorLink(
                            source = name,
                            name = "$name - $label",
                            url = targetUrl,
                            type = streamType
                        ) {
                            this.referer = basePageUrl
                            this.quality = baseQuality
                        }
                    )
                }

                targetUrl.contains("/cloud/", ignoreCase = true) ||
                    label.contains("FAST CLOUD", ignoreCase = true) ||
                    label.contains("ZIPDISK", ignoreCase = true) -> {
                    extractFromCloudPage(targetUrl, basePageUrl, baseQuality, subtitleCallback, callback)
                }

                else -> {
                    loadExtractor(targetUrl, basePageUrl, subtitleCallback, callback)
                }
            }
        }
    }

    private suspend fun extractFromCloudPage(
        cloudUrl: String,
        refererUrl: String,
        fallbackQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        val cloudDoc = try {
            app.get(cloudUrl, referer = refererUrl).document
        } catch (_: Exception) {
            return
        }

        val cloudLinks = cloudDoc.select("a[href], source[src], video[src]")
            .mapNotNull { el ->
                val raw = if (el.tagName() == "a") el.attr("href") else el.attr("src")
                val cleaned = raw.trim()
                if (cleaned.isBlank() || cleaned.startsWith("#") || cleaned.startsWith("javascript:")) {
                    null
                } else {
                    resolveUrl(cleaned, cloudUrl)
                }
            }
            .distinct()

        for (link in cloudLinks) {
            if (link.contains(".mp4", ignoreCase = true) ||
                link.contains(".mkv", ignoreCase = true) ||
                link.contains(".m3u8", ignoreCase = true)
            ) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = "$name - Fast Cloud",
                        url = link,
                        type = if (link.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                    ) {
                        this.referer = cloudUrl
                        this.quality = detectQualityFromText(link).let {
                            if (it != Qualities.Unknown.value) it else fallbackQuality
                        }
                    }
                )
            } else if (!link.contains("gdflix", ignoreCase = true)) {
                loadExtractor(link, cloudUrl, subtitleCallback, callback)
            }
        }
    }

    private fun resolveUrl(rawUrl: String, baseUrl: String): String {
        val trimmed = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> {
                val origin = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.getOrNull(1)
                    ?: mainUrl
                "$origin/${trimmed.trimStart('/')}"
            }
        }
    }

    private fun detectQualityFromText(text: String): Int = when {
        text.contains("2160p", ignoreCase = true) || text.contains("4k", ignoreCase = true) -> Qualities.P2160.value
        text.contains("1080p", ignoreCase = true) -> Qualities.P1080.value
        text.contains("720p", ignoreCase = true) -> Qualities.P720.value
        text.contains("480p", ignoreCase = true) -> Qualities.P480.value
        else -> Qualities.Unknown.value
    }
}
