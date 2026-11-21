package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieFoxProvider())
        registerExtractorAPI(GokuHDExtractor())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(PixeldrainDevExtractor())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(NxStreamHubExtractor())
        registerExtractorAPI(NitroxExtractor())
        registerExtractorAPI(VibuxerExtractor())
        openSettings = { ctx ->
            try {
                MovieFoxSettingsDialog.show(ctx)
            } catch (_: Exception) {}
        }
    }
}

