package com.hungrysturdy

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {

    init {
        openSettings = { ctx: Context ->
            MovieFoxSettingsDialog.show(ctx)
        }
    }

    override fun load(context: Context) {
        MovieFoxSettingsDialog.bindContext(context)
        openSettings = { ctx: Context ->
            MovieFoxSettingsDialog.show(ctx)
        }

        // Main MovieFox Provider (All Core + CineStream + Nuvio Manifest Servers)
        registerMainAPI(MovieFoxProvider())

        // Base & Google Drive / Gofile / Driveleech Extractors
        registerExtractorAPI(BaseJWPExtractor())
        registerExtractorAPI(GDriveExtractor())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())

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

        // HubCloud / VCloud / GDFlix / Buzz / HBPlay / GoFlix Extractors
        registerExtractorAPI(KatFastDlServer())
        registerExtractorAPI(KatHubCloudExtractor())
        registerExtractorAPI(VCloud())
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

        // CineStream Extractors
        registerExtractorAPI(Videostr())
        registerExtractorAPI(Streameeeeee())
        registerExtractorAPI(Wootly())
        registerExtractorAPI(Linksmod())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Otakuvid())
        registerExtractorAPI(Otakuhg())
        registerExtractorAPI(Minochinos())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(FlixCloud())
        registerExtractorAPI(Asianload())
        registerExtractorAPI(Embedload())
        registerExtractorAPI(VibePlayer())
        registerExtractorAPI(Vivibebe())
        registerExtractorAPI(Bibiemb())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(PpzjYoutube())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(Modiplay())
        registerExtractorAPI(Iqsmartgames())
    }
}


