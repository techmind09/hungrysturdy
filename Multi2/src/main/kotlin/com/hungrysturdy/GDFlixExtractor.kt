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
        val links = collectLinks(url, referer, subtitleCallback)
        for (link in links) {
            callback.invoke(link)
        }
    }

    internal suspend fun collectLinks(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val results = mutableListOf<ExtractorLink>()
        val seenUrls = linkedSetOf<String>()

        val pageUrl: String = resolveUrl(url, mainUrl)
        val response = app.get(pageUrl, referer = referer ?: mainUrl)
        val basePageUrl: String = response.url.ifBlank { pageUrl }
        val doc = response.document

        val fileTitle: String = doc.selectFirst("ul.list-group li")
            ?.text()
            ?.substringAfter(":", "")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?: doc.title().substringAfter("|", "").trim().ifBlank { name }

        val baseQuality: Int = detectQualityFromText(fileTitle)

        val buttons = doc.select(".card-body a.btn[href], .card-body a[href]")
            .mapNotNull { el ->
                val rawHref: String = el.attr("href").trim()
                if (rawHref.isBlank() || rawHref.startsWith("#") || rawHref.startsWith("javascript:")) {
                    return@mapNotNull null
                }
                val resolved: String = resolveUrl(rawHref, basePageUrl)
                val label: String = el.text().replace(Regex("""\s+"""), " ").trim().ifBlank { "Mirror" }
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
                    if (seenUrls.add(targetUrl)) {
                        val streamType = if (targetUrl.contains(".m3u8", ignoreCase = true)) {
                            ExtractorLinkType.M3U8
                        } else {
                            ExtractorLinkType.VIDEO
                        }
                        results.add(
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
                }

                targetUrl.contains("/cloud/", ignoreCase = true) ||
                    label.contains("FAST CLOUD", ignoreCase = true) ||
                    label.contains("ZIPDISK", ignoreCase = true) -> {
                    val cloudLinks = extractFromCloudPage(targetUrl, basePageUrl, baseQuality, subtitleCallback)
                    for (cl in cloudLinks) {
                        if (seenUrls.add(cl.url)) {
                            results.add(cl)
                        }
                    }
                }

                else -> {
                    if (seenUrls.add(targetUrl)) {
                        loadExtractor(targetUrl, basePageUrl, subtitleCallback) { extLink ->
                            results.add(extLink)
                        }
                    }
                }
            }
        }

        return results
    }

    private suspend fun extractFromCloudPage(
        cloudUrl: String,
        refererUrl: String,
        fallbackQuality: Int,
        subtitleCallback: (SubtitleFile) -> Unit
    ): List<ExtractorLink> {
        val out = mutableListOf<ExtractorLink>()
        val cloudDoc = try {
            app.get(cloudUrl, referer = refererUrl).document
        } catch (_: Exception) {
            return out
        }

        val cloudLinks = cloudDoc.select("a[href], source[src], video[src]")
            .mapNotNull { el ->
                val raw: String = if (el.tagName().equals("a", ignoreCase = true)) {
                    el.attr("href").trim()
                } else {
                    el.attr("src").trim()
                }
                if (raw.isBlank() || raw.startsWith("#") || raw.startsWith("javascript:")) {
                    null
                } else {
                    resolveUrl(raw, cloudUrl)
                }
            }
            .distinct()

        for (link in cloudLinks) {
            if (link.contains(".mp4", ignoreCase = true) ||
                link.contains(".mkv", ignoreCase = true) ||
                link.contains(".m3u8", ignoreCase = true)
            ) {
                out.add(
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
                loadExtractor(link, cloudUrl, subtitleCallback) { extLink ->
                    out.add(extLink)
                }
            }
        }

        return out
    }

    private fun resolveUrl(rawUrl: String, baseUrl: String): String {
        val trimmed: String = rawUrl.trim()
        return when {
            trimmed.startsWith("http://", ignoreCase = true) ||
                trimmed.startsWith("https://", ignoreCase = true) -> trimmed
            trimmed.startsWith("//") -> "https:$trimmed"
            else -> {
                val origin: String = Regex("""^(https?://[^/]+)""").find(baseUrl)?.groupValues?.getOrNull(1)
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

