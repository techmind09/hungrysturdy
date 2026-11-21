package com.hungrysturdy

import android.content.Context
import com.hungrysturdy.donation.DonationDialogFragment
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class Plugin : Plugin() {
    override fun load(context: Context) {
        // Main Providers
        registerMainAPI(PrmoviesProvider())
        registerMainAPI(KatDramaProvider())
        registerMainAPI(DramaVerseProvider())
        registerMainAPI(GokuHDProvider())
        registerMainAPI(ToonPlayProvider())

        // Extractors Registration
        registerExtractorAPI(GokuHDExtractor())
        registerExtractorAPI(KatFastDlServer())
        registerExtractorAPI(KatHubCloudExtractor())
        registerExtractorAPI(HubCloudDrive())
        registerExtractorAPI(HubCloudClub())
        registerExtractorAPI(HubCloudOne())
        registerExtractorAPI(GamerXYTExtractor())
        registerExtractorAPI(KatGDFlixExtractor())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlixCfd())
        registerExtractorAPI(GDFlixdad())
        registerExtractorAPI(GDFlixNew9())
        registerExtractorAPI(GDFlixNew10())
        registerExtractorAPI(KatPixelDrainExtractor())
        registerExtractorAPI(KatHBPlayExtractor())
        registerExtractorAPI(KatFuckingFastExtractor())
        registerExtractorAPI(KatGoFlixExtractor())
        registerExtractorAPI(Filemoon())

        // ToonPlay Extractors
        registerExtractorAPI(FlixCloudExtractor())
        registerExtractorAPI(AbyssPlayerExtractor())
        registerExtractorAPI(RavokExtractor())

        // Ek hi jagah se poore plugin ke liye Donation Dialog
        openSettings = { ctx ->
            DonationDialogFragment.show(
                context = ctx,
                pluginName = "Hungrysturdy Plugins"
            )
        }
    }
}
