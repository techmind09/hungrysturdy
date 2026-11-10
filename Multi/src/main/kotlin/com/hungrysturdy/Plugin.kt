package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Initialize Cloudflare bypass support for AnimeWorld
        initAnimeWorldCFBypass()
        
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(PrmoviesProvider())
        registerMainAPI(NineAnimeProvider())
        registerExtractorAPI(NineAnimeMegaPlay())
        registerExtractorAPI(NineAnimeVidmoly())
        registerExtractorAPI(NineAnimeMoon())
        registerMainAPI(AnimeWorldProvider())
    }
}
