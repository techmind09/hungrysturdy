package com.hungrysturdy

import android.app.Activity
import android.content.Context
import com.lagradost.cloudstream3.CommonActivity
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class MovieFoxPlugin : Plugin() {
    override fun load(context: Context) {
        MovieFoxSettingsDialog.bindContext(context)
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

        // CineStream Extractors
        registerExtractorAPI(CsVideostr())
        registerExtractorAPI(CsStreameeeeee())
        registerExtractorAPI(CsGofile())
        registerExtractorAPI(CsWootly())
        registerExtractorAPI(CsVibePlayer())
        registerExtractorAPI(CsVivibebe())
        registerExtractorAPI(CsBibiemb())
        registerExtractorAPI(CsPlaymogo())
        registerExtractorAPI(CsOtakuvid())
        registerExtractorAPI(CsOtakuhg())
        registerExtractorAPI(CsMinochinos())
        registerExtractorAPI(CsHdStream4u())
        registerExtractorAPI(CsHubstream())
        registerExtractorAPI(CsIqsmartgames())
        registerExtractorAPI(CsVidwish())
        registerExtractorAPI(CsVidtube())
        registerExtractorAPI(CsMegaPlay())
        registerExtractorAPI(CsAsianload())
        registerExtractorAPI(CsEmbedload())
        registerExtractorAPI(CsFlixCloud())
        registerExtractorAPI(CsPpzjYoutube())

        openSettings = { ctx ->
            try {
                val activeAct = CommonActivity.activity
                val resolvedActivity: Activity? = when {
                    ctx is Activity && !ctx.isFinishing && !ctx.isDestroyed -> ctx
                    activeAct != null && !activeAct.isFinishing && !activeAct.isDestroyed -> activeAct
                    context is Activity && !context.isFinishing && !context.isDestroyed -> context
                    else -> null
                }
                if (resolvedActivity != null) {
                    MovieFoxSettingsDialog.show(resolvedActivity)
                }
            } catch (_: Exception) {
            }
        }
    }
}

