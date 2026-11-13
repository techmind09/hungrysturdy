package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(PrmoviesProvider())
        registerMainAPI(KatDramaProvider())
        registerMainAPI(DramaVerseProvider())
        registerMainAPI(GhostflixProvider())
        registerMainAPI(GokuHDProvider())

        // Extractors Registration
        registerExtractorAPI(GokuHDExtractor())
        registerExtractorAPI(KatHubCloudExtractor())
        registerExtractorAPI(HubCloudDrive())
        registerExtractorAPI(HubCloudClub())
        registerExtractorAPI(GamerXYTExtractor())
        registerExtractorAPI(KatGDFlixExtractor())
        registerExtractorAPI(GDFlixCfd())
        registerExtractorAPI(GDFlixdad())
        registerExtractorAPI(KatPixelDrainExtractor())
        registerExtractorAPI(KatHBPlayExtractor())
        registerExtractorAPI(KatFuckingFastExtractor())
        // Universal & Custom Video Extractors (Vidhide clones, Luluvid, Filemoon)
        registerExtractorAPI(Filemoon())
    }
}
