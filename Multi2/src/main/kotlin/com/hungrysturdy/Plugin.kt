package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Main Providers
        registerMainAPI(SflixProvider())
        registerMainAPI(HindiDramaProvider())

        // Custom Server Extractors
        registerExtractorAPI(LuluVidExtractor())
        registerExtractorAPI(LuluStreamAltExtractor())
        registerExtractorAPI(EarnVidsExtractor())
        registerExtractorAPI(EarnVidsProExtractor())
        registerExtractorAPI(VidaraExtractor())
        registerExtractorAPI(VidaraAltExtractor())
        registerExtractorAPI(ByseExtractor())
        registerExtractorAPI(ByseAltExtractor())
        registerExtractorAPI(UpBoltExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(ShortIcuExtractor())
        registerExtractorAPI(LPlayGoExtractor()) // Added here
    }
}
