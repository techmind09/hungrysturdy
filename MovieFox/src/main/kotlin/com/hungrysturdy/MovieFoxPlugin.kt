package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieFoxProvider())

        registerExtractorAPI(VCloudExtractor())
        registerExtractorAPI(VCloudZipExtractor())
        registerExtractorAPI(HubCloudExtractor())
        registerExtractorAPI(HubCloudDadExtractor())
        registerExtractorAPI(HubCloudInkExtractor())
        registerExtractorAPI(VegaDriveExtractor())
        registerExtractorAPI(FastDLExtractor())
        registerExtractorAPI(FilepressExtractor())
        registerExtractorAPI(FilebeeExtractor())
        registerExtractorAPI(FpGoExtractor())
        registerExtractorAPI(GoFileExtractor())
        registerExtractorAPI(GpdlExtractor())
        registerExtractorAPI(GDShineExtractor())

        openSettings = { ctx ->
            PluginDonation.open(
                context = ctx,
                pluginName = "MovieFox"
            )
        }
    }
}
