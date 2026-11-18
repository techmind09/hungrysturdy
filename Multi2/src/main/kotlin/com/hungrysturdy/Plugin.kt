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
        // LuluStream Extractors
        registerExtractorAPI(LuluVidExtractor())
        registerExtractorAPI(LuluStreamAltExtractor())
        registerExtractorAPI(LuluVdoExtractor())

        // EarnVids Extractors
        registerExtractorAPI(EarnVidsExtractor())
        registerExtractorAPI(EarnVidsProExtractor())

        // Vidara Extractors
        registerExtractorAPI(VidaraExtractor())
        registerExtractorAPI(VidaraAltExtractor())

        // UpBolt Extractors
        registerExtractorAPI(UpBoltExtractor())
        registerExtractorAPI(UpBoltTvExtractor())

        // Abyss & Short.icu Extractors
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssCdnExtractor())
        registerExtractorAPI(ShortIcuExtractor())

        // LPlayGo & VidMoly Extractors
        registerExtractorAPI(LPlayGoExtractor())
        registerExtractorAPI(VidMolyExtractor())
        registerExtractorAPI(SwVidMolyExtractor())
        registerExtractorAPI(VidMolyToExtractor())

        // StreamTape Extractors (All Domains)
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(StreamTapeToExtractor())
        registerExtractorAPI(StreamTapeNetExtractor())
        registerExtractorAPI(StreamTapeXyzExtractor())
        registerExtractorAPI(ShaveTapeExtractor())
        registerExtractorAPI(StrCloudExtractor())
        registerExtractorAPI(StrTapeTechExtractor())
        registerExtractorAPI(StapeFunExtractor())
    }
}
