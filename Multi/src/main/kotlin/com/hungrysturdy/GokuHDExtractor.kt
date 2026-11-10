package com.hungrysturdy

import com.lagradost.cloudstream3.*
import com.lagradost.cloudstream3.utils.*

class GokuHDExtractor : ExtractorApi() {
    override val name: String = "GokuHD"
    override val mainUrl: String = "https://nexdrive.beer"
    override val requiresReferer: Boolean = false

    override suspend fun getUrl(
        url: String,
        referer: String?,
        subtitleCallback: (SubtitleFile) -> Unit,
        callback: (ExtractorLink) -> Unit
    ) {
        // Ensure GokuHDSource is properly accessed
        val servers = GokuHDSource.resolve(url)

        for (server in servers) {
            if (GokuHDSource.alive(server.url, server.headers)) {
                callback.invoke(
                    newExtractorLink(
                        source = name,
                        name = server.name,
                        url = server.url,
                        type = ExtractorLinkType.VIDEO
                    ) {
                        this.quality = Qualities.Unknown.value
                        this.headers = server.headers
                        this.referer = referer ?: mainUrl
                    }
                )
            }
        }
    }
}
