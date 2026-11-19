package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        // Sirf MovieFox Main Provider
        registerMainAPI(MovieFoxProvider())

        // Voe Extractors
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(VoeToExtractor())
        registerExtractorAPI(VoeCoExtractor())

        // Pixeldrain & GDrive Extractors
        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(PixeldrainDevExtractor())
        registerExtractorAPI(GDriveExtractor())

        // VidPrime, Nitrox, NxStreamHub & Vibuxer Extractors
        registerExtractorAPI(VidPrimeExtractor())
        registerExtractorAPI(NitroxExtractor())
        registerExtractorAPI(NxStreamHubExtractor())
        registerExtractorAPI(VibuxerExtractor())

        // SFlix / Rabbitstream / UpCloud / MegaCloud Extractors
        registerExtractorAPI(RabbitstreamExtractor())
        registerExtractorAPI(UpCloudExtractor())
        registerExtractorAPI(MegaCloudExtractor())
        registerExtractorAPI(MegaCloudClubExtractor())

        // StreamWish & Filemoon Extractors
        registerExtractorAPI(StreamWishExtractor())
        registerExtractorAPI(FilemoonExtractor())

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

        // StreamTape Extractors
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(StreamTapeToExtractor())
        registerExtractorAPI(StreamTapeNetExtractor())
        registerExtractorAPI(StreamTapeXyzExtractor())
        registerExtractorAPI(ShaveTapeExtractor())
        registerExtractorAPI(StrCloudExtractor())
        registerExtractorAPI(StrTapeTechExtractor())
        registerExtractorAPI(StapeFunExtractor())

        // Sirf MovieFox Plugin par Settings UI
        openSettings = { ctx ->
            MovieFoxSettingsDialog.show(ctx)
        }
    }
}
