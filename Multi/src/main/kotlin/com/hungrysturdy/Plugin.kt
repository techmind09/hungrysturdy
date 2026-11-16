package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Main Providers
        registerMainAPI(PrmoviesProvider())
        registerMainAPI(KatDramaProvider())
        registerMainAPI(DramaVerseProvider())
        registerMainAPI(GhostflixProvider())
        registerMainAPI(GokuHDProvider())
        registerMainAPI(ToonPlayProvider())

        // Extractors Registration
        registerExtractorAPI(GokuHDExtractor())

        // ToonPlay Extractors
        registerExtractorAPI(FlixCloudExtractor())
        registerExtractorAPI(AbyssPlayerExtractor())
        registerExtractorAPI(RavokExtractor())
    }
}
