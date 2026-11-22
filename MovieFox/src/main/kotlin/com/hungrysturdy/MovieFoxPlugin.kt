package com.hungrysturdy

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(MovieFoxProvider())

        // Drive, Cloud & Mirror Extractors
        registerExtractorAPI(GokuHDExtractor())
        registerExtractorAPI(VegaDriveExtractor())
        registerExtractorAPI(FilePressExtractor())
        registerExtractorAPI(InstantCloudExtractor())
        registerExtractorAPI(HubCloud())
        registerExtractorAPI(VCloud())
        registerExtractorAPI(GDFlix())
        registerExtractorAPI(GDLink())
        registerExtractorAPI(GDFlixApp())
        registerExtractorAPI(GdFlix1())
        registerExtractorAPI(GdFlix2())
        registerExtractorAPI(GDFlixNet())
        registerExtractorAPI(GDFlix15())
        registerExtractorAPI(FastDlExtractor())
        registerExtractorAPI(fastdlserver())
        registerExtractorAPI(Hubdrive())
        registerExtractorAPI(Driveleech())
        registerExtractorAPI(Driveseed())
        registerExtractorAPI(Hblinks())
        registerExtractorAPI(Linksmod())
        registerExtractorAPI(Howblogs())
        registerExtractorAPI(Gofile())
        registerExtractorAPI(PixeldrainExtractor())
        registerExtractorAPI(PixeldrainDevExtractor())

        // Stream, Anime & Embed Extractors
        registerExtractorAPI(ZephyrixExtractor())
        registerExtractorAPI(VoeExtractor())
        registerExtractorAPI(StreamTapeExtractor())
        registerExtractorAPI(NxStreamHubExtractor())
        registerExtractorAPI(NitroxExtractor())
        registerExtractorAPI(VibuxerExtractor())
        registerExtractorAPI(Videostr())
        registerExtractorAPI(Streameeeeee())
        registerExtractorAPI(Wootly())
        registerExtractorAPI(VibePlayer())
        registerExtractorAPI(Vivibebe())
        registerExtractorAPI(Bibiemb())
        registerExtractorAPI(MegaPlay())
        registerExtractorAPI(Vidwish())
        registerExtractorAPI(Vidtube())
        registerExtractorAPI(Modiplay())
        registerExtractorAPI(Iqsmartgames())
        registerExtractorAPI(HdStream4u())
        registerExtractorAPI(Hubstream())
        registerExtractorAPI(Playmogo())
        registerExtractorAPI(Otakuvid())
        registerExtractorAPI(Otakuhg())
        registerExtractorAPI(Minochinos())

        openSettings = { ctx ->
            try {
                val activeAct = CommonActivity.activity
                val targetCtx: Context = when {
                    ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed -> ctx
                    activeAct != null && !activeAct.isFinishing && !activeAct.isDestroyed -> activeAct
                    else -> context
                }
                MovieFoxSettingsDialog.show(targetCtx)
            } catch (_: Exception) {
            }
        }
    }
}
