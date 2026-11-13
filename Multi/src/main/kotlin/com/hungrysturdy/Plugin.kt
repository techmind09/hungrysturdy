package com.hungrysturdy

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // All providers should be added in this manner. Please don't edit the providers list directly.
        registerMainAPI(PrmoviesProvider())
        registerMainAPI(KatDrama())
        registerMainAPI(GhostflixProvider())
        registerMainAPI(KDHindiDubbed())
        registerMainAPI(GokuHDProvider())

        // Extractors Registration
        registerExtractorAPI(KDHindiDubbedExtractor())
        registerExtractorAPI(GokuHDExtractor())

        // Universal & Custom Video Extractors (Vidhide clones, Luluvid, Filemoon)
        registerExtractorAPI(Vidsp())
        registerExtractorAPI(VidHideplus())
        registerExtractorAPI(VidHidedht())
        registerExtractorAPI(Vidhidehub())
        registerExtractorAPI(Vidhidetoul())
        registerExtractorAPI(Xtapes())
        registerExtractorAPI(Luluvid())
        registerExtractorAPI(Filemoon())
    }
}
