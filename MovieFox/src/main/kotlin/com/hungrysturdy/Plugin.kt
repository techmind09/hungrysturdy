package com.hungrysturdy

import com.lagradost.cloudstream3.CloudStreamApp
import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class Plugin : BasePlugin() {

    override fun load() {
        CloudStreamApp.context?.let { MovieFoxSettingsDialog.bindContext(it) }

        // Main MovieFox Provider (includes Prmovies, HindiDrama & Sflix servers)
        registerMainAPI(MovieFoxProvider())

        // Base & Google Drive Extractors
        registerExtractorAPI(BaseJWPExtractor())
        registerExtractorAPI(GDriveExtractor())

        // StreamTape & Mirrors
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(StreamTapeToExtractor())
        registerExtractorAPI(StreamTapeNetExtractor())
        registerExtractorAPI(StreamTapeXyzExtractor())
        registerExtractorAPI(ShaveTapeExtractor())
        registerExtractorAPI(StrCloudExtractor())
        registerExtractorAPI(StrTapeTechExtractor())
        registerExtractorAPI(StapeFunExtractor())

        // Voe & Mirrors
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(VoeToExtractor())
        registerExtractorAPI(VoeCoExtractor())

        // Pixeldrain
        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(PixeldrainDevExtractor())
        registerExtractorAPI(KatPixelDrainExtractor())

        // JWP / Worker / Embed Extractors
        registerExtractorAPI(VidPrimeExtractor())
        registerExtractorAPI(NitroxExtractor())
        registerExtractorAPI(NxStreamHubExtractor())
        registerExtractorAPI(VibuxerExtractor())
        registerExtractorAPI(LuluVidExtractor())
        registerExtractorAPI(LuluStreamAltExtractor())
        registerExtractorAPI(LuluVdoExtractor())
        registerExtractorAPI(VidaraExtractor())
        registerExtractorAPI(VidaraAltExtractor())
        registerExtractorAPI(RabbitstreamExtractor())
        registerExtractorAPI(UpCloudExtractor())
        registerExtractorAPI(MegaCloudExtractor())
        registerExtractorAPI(MegaCloudClubExtractor())
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(FilemoonExtractor())
        registerExtractorAPI(EarnVidsExtractor())
        registerExtractorAPI(EarnVidsProExtractor())
        registerExtractorAPI(UpBoltExtractor())
        registerExtractorAPI(UpBoltTvExtractor())
        registerExtractorAPI(AbyssExtractor())
        registerExtractorAPI(AbyssCdnExtractor())
        registerExtractorAPI(ShortIcuExtractor())
        registerExtractorAPI(LPlayGoExtractor())
        registerExtractorAPI(VidMolyExtractor())
        registerExtractorAPI(SwVidMolyExtractor())
        registerExtractorAPI(VidMolyToExtractor())

        // HubCloud / GDFlix / Buzz / HBPlay / GoFlix Extractors
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
        registerExtractorAPI(KatHBPlayExtractor())
        registerExtractorAPI(KatFuckingFastExtractor())
        registerExtractorAPI(KatGoFlixExtractor())
    }
}

